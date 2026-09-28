package com.scariflabs.horus.terminal

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * Reads the guest's home and workspace directly from app-private storage for
 * the file explorer, instead of starting a PRoot shell per query. It mirrors
 * the bounded shell queries in src/files/fileExplorer.ts: the same path
 * validation, statuses, list cap, and preview limit.
 *
 * Every lookup uses lstat semantics. A symlinked root is not_found, a
 * symlinked path component is invalid_path, and nothing ever follows a link
 * out of the selected root. Pure java.nio over two host directories so JVM
 * tests can exercise it with temporary folders.
 */
class GuestFileBrowser(
  private val homeRoot: File,
  private val workspaceRoot: File,
) {
  enum class EntryKind(val wireName: String) {
    DIRECTORY("directory"),
    FILE("file"),
    SYMLINK("symlink"),
    OTHER("other"),
  }

  data class Entry(val name: String, val kind: EntryKind, val sizeBytes: Long)

  sealed interface ListOutcome {
    data class Success(
      val entries: List<Entry>,
      val truncated: Boolean,
      val hiddenInvalidNameCount: Int,
    ) : ListOutcome

    data class Failure(val errorCode: String) : ListOutcome
  }

  sealed interface ReadOutcome {
    /** [bytes] holds at most PREVIEW_LIMIT_BYTES + 1 bytes; [sizeBytes] is the lstat size. */
    class Success(val bytes: ByteArray, val sizeBytes: Long) : ReadOutcome

    data class Failure(val errorCode: String) : ReadOutcome
  }

  /** Receives each exported regular file; directories are implied by [relativePath]. */
  fun interface ExportSink {
    /** [relativePath] is the folder components below the export, then the file name. */
    fun writeFile(relativePath: List<String>, source: InputStream)
  }

  sealed interface ExportOutcome {
    data class Success(val fileCount: Int, val byteCount: Long, val skippedCount: Int) : ExportOutcome
    data class Failure(val errorCode: String) : ExportOutcome
  }

  private class ExportFile(val relativePath: List<String>, val path: Path, val sizeBytes: Long)

  private sealed interface Resolved {
    data class Found(val root: Path, val target: Path) : Resolved
    data class Failure(val errorCode: String) : Resolved
  }

  fun list(root: String, path: List<String>): ListOutcome {
    if (!isValidPath(path)) return ListOutcome.Failure(INVALID_PATH)
    val rootDir = rootFor(root) ?: return ListOutcome.Failure(INVALID_PATH)
    val directory = when (val resolved = resolveDirectory(rootDir, path)) {
      is Resolved.Failure -> return ListOutcome.Failure(resolved.errorCode)
      is Resolved.Found -> resolved.target
    }
    val entries = ArrayList<Entry>()
    var hidden = 0
    var count = 0
    var truncated = false
    // The shell query ran `find ... 2>/dev/null`: an unreadable directory
    // listed as empty, and a failing iteration kept what it had printed.
    val stream: DirectoryStream<Path> = try {
      Files.newDirectoryStream(directory)
    } catch (_: IOException) {
      return ListOutcome.Success(emptyList(), truncated = false, hiddenInvalidNameCount = 0)
    } catch (_: SecurityException) {
      return ListOutcome.Success(emptyList(), truncated = false, hiddenInvalidNameCount = 0)
    }
    try {
      val iterator = stream.iterator()
      while (true) {
        val entryPath = try {
          if (!iterator.hasNext()) break
          iterator.next()
        } catch (_: RuntimeException) {
          // DirectoryIteratorException or a concurrent close: stop like find.
          break
        }
        // Undecodable names still count toward the cap, as in the shell.
        if (count >= LIST_LIMIT) {
          truncated = true
          break
        }
        count += 1
        val name = decodedName(directory, entryPath)
        if (name == null) {
          hidden += 1
          continue
        }
        entries += entryFor(name, entryPath)
      }
    } finally {
      try {
        stream.close()
      } catch (_: IOException) {
        // Nothing useful to report; the listing is already complete.
      }
    }
    return ListOutcome.Success(entries, truncated, hidden)
  }

  fun read(root: String, path: List<String>): ReadOutcome {
    if (path.isEmpty() || !isValidPath(path)) return ReadOutcome.Failure(INVALID_PATH)
    val rootDir = rootFor(root) ?: return ReadOutcome.Failure(INVALID_PATH)
    val parent = when (val resolved = resolveDirectory(rootDir, path.dropLast(1))) {
      is Resolved.Failure -> return ReadOutcome.Failure(resolved.errorCode)
      is Resolved.Found -> resolved
    }
    val file = parent.target.resolve(path.last())
    val attributes = lstat(file) ?: return ReadOutcome.Failure(NOT_FOUND)
    if (attributes.isSymbolicLink) return ReadOutcome.Failure(INVALID_PATH)
    if (!attributes.isRegularFile) return ReadOutcome.Failure(NOT_FOUND)
    if (!staysUnderRoot(parent.root, file)) return ReadOutcome.Failure(INVALID_PATH)
    if (!Files.isReadable(file)) return ReadOutcome.Failure(COMMAND_FAILED)
    val size = attributes.size()
    if (size < 0) return ReadOutcome.Failure(COMMAND_FAILED)
    if (size > PREVIEW_LIMIT_BYTES) return ReadOutcome.Failure(TOO_LARGE)
    val bytes = try {
      readBounded(file)
    } catch (_: IOException) {
      return ReadOutcome.Failure(COMMAND_FAILED)
    } catch (_: SecurityException) {
      return ReadOutcome.Failure(COMMAND_FAILED)
    }
    return ReadOutcome.Success(bytes, size)
  }

  /**
   * Copies every regular file below a guest directory into [sink]. The whole
   * tree is scanned first so an oversized export fails before writing
   * anything. Symlinks, special files, undecodable names, and anything past
   * EXPORT_MAX_DEPTH are skipped and counted; a file that fails to copy is
   * skipped too, so one unreadable file does not abort the rest.
   */
  fun export(root: String, path: List<String>, sink: ExportSink): ExportOutcome {
    if (!isValidPath(path)) return ExportOutcome.Failure(INVALID_PATH)
    val rootDir = rootFor(root) ?: return ExportOutcome.Failure(INVALID_PATH)
    val resolved = when (val result = resolveDirectory(rootDir, path)) {
      is Resolved.Failure -> return ExportOutcome.Failure(result.errorCode)
      is Resolved.Found -> result
    }
    val files = ArrayList<ExportFile>()
    var skipped = 0
    var totalBytes = 0L
    val pending = ArrayDeque<Pair<Path, List<String>>>()
    pending.addLast(resolved.target to emptyList())
    while (pending.isNotEmpty()) {
      val (directory, relative) = pending.removeFirst()
      val stream: DirectoryStream<Path> = try {
        Files.newDirectoryStream(directory)
      } catch (_: IOException) {
        skipped += 1
        continue
      } catch (_: SecurityException) {
        skipped += 1
        continue
      }
      try {
        for (entry in stream) {
          val name = decodedName(directory, entry)
          val attributes = lstat(entry)
          if (name == null || attributes == null || attributes.isSymbolicLink) {
            skipped += 1
            continue
          }
          val entryRelative = relative + name
          when {
            attributes.isDirectory -> {
              if (entryRelative.size >= EXPORT_MAX_DEPTH) skipped += 1 else pending.addLast(entry to entryRelative)
            }
            attributes.isRegularFile -> {
              val size = attributes.size().coerceAtLeast(0)
              totalBytes += size
              if (files.size >= EXPORT_MAX_FILES || totalBytes > EXPORT_MAX_BYTES) {
                return ExportOutcome.Failure(TOO_LARGE)
              }
              files += ExportFile(entryRelative, entry, size)
            }
            else -> skipped += 1
          }
        }
      } catch (_: RuntimeException) {
        // DirectoryIteratorException: keep what was listed, as list() does.
      } finally {
        try {
          stream.close()
        } catch (_: IOException) {
          // Nothing useful to report.
        }
      }
    }
    var copied = 0
    var copiedBytes = 0L
    for (file in files) {
      // The tree may have changed since the scan; never follow a new link out.
      if (!staysUnderRoot(resolved.root, file.path)) {
        skipped += 1
        continue
      }
      try {
        Files.newInputStream(file.path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
          sink.writeFile(file.relativePath, input)
        }
        copied += 1
        copiedBytes += file.sizeBytes
      } catch (_: IOException) {
        skipped += 1
      } catch (_: SecurityException) {
        skipped += 1
      }
    }
    return ExportOutcome.Success(copied, copiedBytes, skipped)
  }

  private fun rootFor(root: String): File? = when (root) {
    ROOT_HOME -> homeRoot
    ROOT_WORKSPACE -> workspaceRoot
    else -> null
  }

  private fun resolveDirectory(rootDir: File, components: List<String>): Resolved {
    val root = rootDir.toPath()
    val rootAttributes = lstat(root)
    if (rootAttributes == null || rootAttributes.isSymbolicLink || !rootAttributes.isDirectory) {
      return Resolved.Failure(NOT_FOUND)
    }
    var current = root
    for (component in components) {
      current = current.resolve(component)
      val attributes = lstat(current) ?: return Resolved.Failure(NOT_FOUND)
      if (attributes.isSymbolicLink) return Resolved.Failure(INVALID_PATH)
      if (!attributes.isDirectory) return Resolved.Failure(NOT_FOUND)
    }
    if (!staysUnderRoot(root, current)) return Resolved.Failure(INVALID_PATH)
    return Resolved.Found(root, current)
  }

  private fun staysUnderRoot(root: Path, target: Path): Boolean = try {
    val canonicalRoot = root.toRealPath()
    // Every component below the root was already lstat-checked, so following
    // links here only resolves the root's own ancestors (e.g. /data/user/0).
    val canonicalTarget = target.toRealPath()
    canonicalTarget == canonicalRoot || canonicalTarget.startsWith(canonicalRoot)
  } catch (_: IOException) {
    false
  } catch (_: SecurityException) {
    false
  }

  private fun readBounded(file: Path): ByteArray {
    Files.newByteChannel(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
      val buffer = ByteBuffer.allocate(PREVIEW_LIMIT_BYTES + 1)
      while (buffer.hasRemaining()) {
        if (channel.read(buffer) < 0) break
      }
      return buffer.array().copyOf(buffer.position())
    }
  }

  private fun entryFor(name: String, entry: Path): Entry {
    // The shell used `[ -L ]`, `[ -d ]`, `[ -f ]`; an entry that vanished or
    // cannot be examined fails every test and falls through to `other`.
    val attributes = lstat(entry) ?: return Entry(name, EntryKind.OTHER, 0)
    return when {
      attributes.isSymbolicLink -> Entry(name, EntryKind.SYMLINK, 0)
      attributes.isDirectory -> Entry(name, EntryKind.DIRECTORY, 0)
      attributes.isRegularFile -> Entry(name, EntryKind.FILE, attributes.size().coerceAtLeast(0))
      else -> Entry(name, EntryKind.OTHER, 0)
    }
  }

  companion object {
    private const val ROOT_HOME = "home"
    private const val ROOT_WORKSPACE = "workspace"
    private const val INVALID_PATH = "invalid_path"
    private const val NOT_FOUND = "not_found"
    private const val TOO_LARGE = "too_large"
    private const val COMMAND_FAILED = "command_failed"
    private const val MAX_PATH_COMPONENTS = 16
    private const val MAX_PATH_COMPONENT_BYTES = 255
    private const val MAX_QUOTED_PATH_LENGTH = 2048

    /** Mirrors GUEST_FILE_LIST_LIMIT in src/files/fileExplorer.ts. */
    const val LIST_LIMIT = 200

    /** Mirrors GUEST_FILE_PREVIEW_LIMIT_BYTES in src/files/fileExplorer.ts. */
    const val PREVIEW_LIMIT_BYTES = 65_536

    /** Mirrors GUEST_FILE_EXPORT_MAX_FILES in src/files/fileExplorer.ts. */
    const val EXPORT_MAX_FILES = 20_000

    /** Mirrors GUEST_FILE_EXPORT_MAX_BYTES in src/files/fileExplorer.ts. */
    const val EXPORT_MAX_BYTES = 2L * 1024 * 1024 * 1024

    const val EXPORT_MAX_DEPTH = 32

    /**
     * Makes one path component safe for shared storage, which is often
     * FAT-like: reserved characters and control characters become '_'.
     */
    fun exportSafeName(name: String): String {
      val cleaned = buildString(name.length) {
        for (char in name) append(if (char < ' ' || char == '\u007f' || char in "\"*/:<>?\\|") '_' else char)
      }.trimEnd(' ', '.')
      return cleaned.ifEmpty { "_" }
    }

    /**
     * Names the export's top-level folder after the exported directory (or
     * root) plus a timestamp, so repeated exports never merge or overwrite.
     */
    fun exportFolderName(root: String, path: List<String>, timestamp: String): String =
      "${exportSafeName(path.lastOrNull() ?: root)}-$timestamp"

    /**
     * Mirrors isValidGuestFilePath in src/files/fileExplorer.ts, including
     * the cumulative shell-quoted length the PTY command enforced.
     */
    fun isValidPath(path: List<String>): Boolean {
      if (path.size > MAX_PATH_COMPONENTS) return false
      var quotedLength = 0
      for (component in path) {
        if (component.isEmpty() || component == "." || component == ".." ||
          component.contains('/') || component.contains('\u0000') ||
          !hasWellFormedSurrogates(component) ||
          component.toByteArray(StandardCharsets.UTF_8).size > MAX_PATH_COMPONENT_BYTES
        ) {
          return false
        }
        // shellQuote wraps in single quotes and turns each ' into '\''.
        quotedLength += component.length + 2 + 3 * component.count { it == '\'' }
        if (quotedLength > MAX_QUOTED_PATH_LENGTH) return false
      }
      return true
    }

    private fun hasWellFormedSurrogates(value: String): Boolean {
      var index = 0
      while (index < value.length) {
        val char = value[index]
        if (Character.isHighSurrogate(char)) {
          if (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1])) return false
          index += 2
          continue
        }
        if (Character.isLowSurrogate(char)) return false
        index += 1
      }
      return true
    }

    private fun lstat(path: Path): BasicFileAttributes? = try {
      Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (_: IOException) {
      null
    } catch (_: SecurityException) {
      null
    }

    /**
     * Returns the entry's name, or null when its on-disk bytes are not valid
     * UTF-8. The platform decodes names lossily, so a name that does not
     * resolve back to the same path bytes was not valid UTF-8; the shell
     * query hid exactly those names behind hiddenInvalidNameCount.
     */
    private fun decodedName(directory: Path, entry: Path): String? {
      val name = entry.fileName?.toString() ?: return null
      if (name.isEmpty() || name == "." || name == ".." || name.contains('/') || name.contains('\u0000')) {
        return null
      }
      val roundTrip = try {
        directory.resolve(name)
      } catch (_: RuntimeException) {
        return null
      }
      return if (roundTrip == entry) name else null
    }
  }
}
