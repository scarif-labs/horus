package com.scariflabs.horus.terminal

import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.util.zip.GZIPInputStream

/**
 * Streaming tar.gz extraction that never lets an archive entry escape the
 * destination root:
 *  - entry names are rejected when absolute, containing "..", or empty;
 *  - every written path's parent is resolved and must stay under the root, so
 *    a symlink planted earlier in the same archive cannot redirect a later
 *    write outside the root;
 *  - hardlink targets must resolve under the root;
 *  - entry count, per-file size, and total size are bounded;
 *  - device/fifo entries are skipped and recorded (a distro rootfs does not
 *    need them under PRoot).
 *
 * Supported formats: ustar (with prefix), GNU long names ('L'), and pax
 * extended headers ('x'/'g') carrying a `path=` override. Sizes must be plain
 * octal; GNU base-256 sizes are rejected.
 */
class SafeTarGzExtractor(
  private val maxEntries: Int = 100_000,
  private val maxFileBytes: Long = 256L * 1024 * 1024,
  private val maxTotalBytes: Long = 512L * 1024 * 1024,
) : DistroStoreCore.RootfsExtractor {
  data class Summary(
    val entryCount: Int,
    val fileCount: Int,
    val directoryCount: Int,
    val symlinkCount: Int,
    val hardlinkCount: Int,
    val skippedEntries: List<String>,
    val totalBytes: Long,
  )

  class UnsafeArchiveException(message: String) : IOException(message)

  override fun extract(archive: File, destinationRoot: File): Summary {
    val rootCanonical = destinationRoot.canonicalFile
    if (!rootCanonical.isDirectory) throw IOException("destination root is not a directory")
    var entries = 0
    var files = 0
    var dirs = 0
    var symlinks = 0
    var hardlinks = 0
    var total = 0L
    val skipped = mutableListOf<String>()
    var nextPathOverride: String? = null
    var globalPathOverride: String? = null

    FileInputStream(archive).use { fileInput ->
      GZIPInputStream(fileInput, 1 shl 16).use { input ->
        val header = ByteArray(512)
        while (true) {
          if (!readFully(input, header)) break
          if (header.all { it == 0.toByte() }) {
            if (!readFully(input, header)) throw UnsafeArchiveException("truncated end-of-archive")
            if (header.all { it == 0.toByte() }) break
            throw UnsafeArchiveException("single zero block in tar stream")
          }
          if (++entries > maxEntries) throw UnsafeArchiveException("archive exceeds $maxEntries entries")
          val size = parseOctal(header, 124, 12)
          if (size < 0 || size > maxFileBytes) throw UnsafeArchiveException("unreasonable entry size $size")
          val typeFlag = header[156]
          val isPaxHeader = typeFlag == 'x'.code.toByte() || typeFlag == 'g'.code.toByte()
          val isLongNameHeader = typeFlag == 'L'.code.toByte()
          val name = if (isPaxHeader || isLongNameHeader) {
            fixedString(header, 0, 100).takeIf { it.isNotEmpty() }
          } else {
            entryName(header, globalPathOverride, nextPathOverride)
          }
            ?: throw UnsafeArchiveException("missing entry name")
          val paddedSize = (size + 511) / 512 * 512

          when (typeFlag) {
            'L'.code.toByte() -> {
              nextPathOverride = readInlineString(input, size).trimEnd('\u0000', '\n')
              skipRemaining(input, paddedSize - size)
              continue
            }
            'x'.code.toByte() -> {
              val records = readInlineBytes(input, size)
              nextPathOverride = parsePaxPath(records)
              skipRemaining(input, paddedSize - size)
              continue
            }
            'g'.code.toByte() -> {
              val records = readInlineBytes(input, size)
              parsePaxPath(records)?.let { globalPathOverride = it }
              skipRemaining(input, paddedSize - size)
              continue
            }
          }

          val safeRelative = sanitizeRelativeName(name)
          if (safeRelative == null) {
            // Alpine's official minirootfs starts with a root-directory entry
            // named "./". It has no payload and must not be treated as a
            // filesystem path outside the extraction root.
            if ((name == "." || name == "./") && typeFlag == '5'.code.toByte()) {
              skipRemaining(input, paddedSize)
              nextPathOverride = null
              continue
            }
            throw UnsafeArchiveException("unsafe entry name \"$name\"")
          }
          val target = File(rootCanonical, safeRelative)
          ensureUnderRoot(rootCanonical, target)

          when (typeFlag) {
            '5'.code.toByte() -> {
              if (!target.isDirectory && !target.mkdirs() && !target.isDirectory) {
                throw IOException("cannot create directory ${target.relativeToOrNull(rootCanonical)}")
              }
              dirs++
              applyMode(target, header, isDirectory = true)
              skipRemaining(input, paddedSize)
            }
            '2'.code.toByte() -> {
              val linkTarget = fixedString(header, 157, 100)
              if (linkTarget.isEmpty()) throw UnsafeArchiveException("symlink without target")
              ensureParentDirectory(target, rootCanonical)
              removeEntry(target)
              Files.createSymbolicLink(
                target.toPath(),
                java.nio.file.Paths.get(linkTarget),
              )
              symlinks++
              skipRemaining(input, paddedSize)
            }
            '1'.code.toByte() -> {
              val linkTarget = sanitizeRelativeName(fixedString(header, 157, 100))
                ?: throw UnsafeArchiveException("unsafe hardlink target")
              val source = File(rootCanonical, linkTarget)
              ensureUnderRoot(rootCanonical, source)
              ensureCanonicalUnderRoot(rootCanonical, source)
              if (!source.isFile) throw UnsafeArchiveException("hardlink target is not a regular file")
              ensureParentDirectory(target, rootCanonical)
              removeEntry(target)
              source.copyTo(target, overwrite = true)
              hardlinks++
              skipRemaining(input, paddedSize)
            }
            '0'.code.toByte(), 0.toByte(), '7'.code.toByte() -> {
              total += size
              if (total > maxTotalBytes) throw UnsafeArchiveException("archive exceeds total byte budget")
              // writeRegularFile consumes exactly `size` bytes from the stream.
              writeRegularFile(input, target, size, rootCanonical)
              skipRemaining(input, paddedSize - size)
              applyMode(target, header, isDirectory = false)
              files++
            }
            '3'.code.toByte(), '4'.code.toByte(), '6'.code.toByte() -> {
              skipped.add("$safeRelative (type ${typeFlag.toInt().toChar()})")
              skipRemaining(input, paddedSize)
            }
            else -> throw UnsafeArchiveException("unsupported tar entry type ${typeFlag.toInt().toChar()}")
          }
          nextPathOverride = null
        }
      }
    }
    return Summary(entries, files, dirs, symlinks, hardlinks, skipped.take(32), total)
  }

  private fun entryName(header: ByteArray, globalPathOverride: String?, nextPathOverride: String?): String? {
    val override = nextPathOverride ?: globalPathOverride
    if (override != null && override.isNotEmpty()) return override
    val name = fixedString(header, 0, 100)
    if (name.isEmpty()) return null
    val magic = fixedString(header, 257, 6)
    if (magic.startsWith("ustar")) {
      val prefix = fixedString(header, 345, 155)
      if (prefix.isNotEmpty()) return "$prefix/$name"
    }
    return name
  }

  /** Rejects absolute paths, empty names, and any "." or ".." component. */
  fun sanitizeRelativeName(rawName: String): String? {
    if (rawName.isEmpty()) return null
    // Tar writers commonly prefix every path with "./". Strip exactly that
    // leading marker; dot components anywhere else remain invalid.
    val relativeName = if (rawName.startsWith("./")) rawName.substring(2) else rawName
    val withoutTrailingSlash = relativeName.trimEnd('/')
    if (withoutTrailingSlash.isEmpty()) return null
    if (relativeName.startsWith("/") || relativeName.contains("\\")) return null
    if (withoutTrailingSlash.split('/').any { it.isEmpty() || it == "." || it == ".." }) return null
    if (withoutTrailingSlash.length > 4096) return null
    return withoutTrailingSlash
  }

  private fun ensureUnderRoot(rootCanonical: File, target: File) {
    val parent = target.parentFile ?: throw UnsafeArchiveException("entry has no parent directory")
    ensurePathWithinRoot(rootCanonical, parent, "entry escapes the extraction root")
  }

  private fun writeRegularFile(input: GZIPInputStream, target: File, size: Long, root: File) {
    ensureParentDirectory(target, root)
    val effectiveParent = target.parentFile?.canonicalFile
    if (effectiveParent != null && effectiveParent != root.canonicalFile &&
      !effectiveParent.path.startsWith(root.canonicalFile.path + File.separator)
    ) {
      throw UnsafeArchiveException("resolved parent escapes the extraction root")
    }
    removeEntry(target)
    FileOutputStream(target, false).use { output ->
      val buffer = ByteArray(1 shl 16)
      var remaining = size
      while (remaining > 0) {
        val chunk = minOf(buffer.size.toLong(), remaining).toInt()
        val read = input.read(buffer, 0, chunk)
        if (read < 0) throw EOFException("archive ended mid-file")
        output.write(buffer, 0, read)
        remaining -= read
      }
    }
  }

  private fun applyMode(target: File, header: ByteArray, isDirectory: Boolean) {
    val mode = parseOctal(header, 100, 8).toInt()
    val executable = isDirectory || (mode and 0b001_001_001) != 0
    target.setReadable(true, false)
    target.setWritable(true, true)
    target.setExecutable(executable, false)
  }

  private fun readInlineBytes(input: GZIPInputStream, size: Long): ByteArray {
    if (size <= 0 || size > 1L * 1024 * 1024) throw UnsafeArchiveException("unreasonable inline record size")
    val bytes = ByteArray(size.toInt())
    if (!readFully(input, bytes)) throw EOFException("archive ended inside an inline record")
    return bytes
  }

  private fun readInlineString(input: GZIPInputStream, size: Long): String =
    readInlineBytes(input, size).toString(Charsets.UTF_8)

  private fun skipRemaining(input: GZIPInputStream, size: Long) {
    var remaining = size
    val buffer = ByteArray(1 shl 16)
    while (remaining > 0) {
      val chunk = minOf(buffer.size.toLong(), remaining).toInt()
      val read = input.read(buffer, 0, chunk)
      if (read < 0) throw EOFException("archive ended while skipping entry data")
      remaining -= read
    }
  }

  private fun readFully(input: GZIPInputStream, buffer: ByteArray): Boolean {
    var offset = 0
    while (offset < buffer.size) {
      val read = input.read(buffer, offset, buffer.size - offset)
      if (read < 0) return offset == 0
      offset += read
    }
    return true
  }

  private fun fixedString(header: ByteArray, offset: Int, length: Int): String {
    var end = offset
    val limit = minOf(offset + length, header.size)
    while (end < limit && header[end] != 0.toByte()) end++
    return String(header, offset, end - offset, Charsets.UTF_8)
  }

  private fun parseOctal(header: ByteArray, offset: Int, length: Int): Long {
    var value = 0L
    var seen = false
    for (i in offset until offset + length) {
      val byte = header[i]
      if (byte == 0.toByte() || byte == ' '.code.toByte()) {
        if (seen) break
        continue
      }
      if (byte < '0'.code.toByte() || byte > '7'.code.toByte()) {
        if (byte.toInt() and 0x80 != 0) throw UnsafeArchiveException("base-256 tar sizes are not supported")
        throw UnsafeArchiveException("non-octal byte in tar numeric field")
      }
      seen = true
      value = value * 8 + (byte - '0'.code.toByte())
    }
    return value
  }

  private fun parsePaxPath(records: ByteArray): String? {
    var offset = 0
    var path: String? = null
    while (offset < records.size) {
      val space = records.indexOfByte(' '.code.toByte(), offset)
      if (space <= offset) throw UnsafeArchiveException("malformed pax record")
      val recordLength = records.copyOfRange(offset, space).toString(Charsets.US_ASCII).toIntOrNull()
        ?: throw UnsafeArchiveException("malformed pax record length")
      if (recordLength <= space - offset + 2 || offset + recordLength > records.size) {
        throw UnsafeArchiveException("pax record exceeds its header size")
      }
      if (records[offset + recordLength - 1] != '\n'.code.toByte()) {
        throw UnsafeArchiveException("pax record is not newline terminated")
      }
      val payloadStart = space + 1
      val payloadEnd = offset + recordLength - 1
      val payload = records.copyOfRange(payloadStart, payloadEnd).toString(Charsets.UTF_8)
      if (payload.startsWith("path=")) path = payload.substringAfter("path=")
      offset += recordLength
    }
    return path
  }

  private fun ByteArray.indexOfByte(value: Byte, start: Int): Int {
    for (index in start until size) if (this[index] == value) return index
    return -1
  }

  private fun ensureParentDirectory(target: File, root: File) {
    val parent = target.parentFile
    if (parent == null) return

    // Resolve before creating anything. If an earlier archive entry planted a
    // symlink in this path, mkdirs() can fail with a generic IOException or
    // appear to succeed while redirecting the write outside the root. The
    // canonical check must be the first operation on the parent path.
    val canonicalRoot = root.canonicalFile
    ensurePathWithinRoot(canonicalRoot, parent, "resolved parent escapes the extraction root")

    if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
      throw IOException("cannot create parent for ${target.relativeToOrNull(root)}")
    }

    // Re-check after mkdirs() in case the filesystem changed between the two
    // operations, or a symlink appeared in a concurrently modified tree.
    ensurePathWithinRoot(canonicalRoot, parent, "resolved parent escapes the extraction root")
  }

  /**
   * Checks the lexical path and every symlink component. File.canonicalFile
   * does not resolve dangling symlinks on all supported filesystems, so the
   * link target is resolved explicitly before mkdirs() is allowed to run.
   */
  private fun ensurePathWithinRoot(root: File, path: File, message: String) {
    val rootPath = root.toPath().toAbsolutePath().normalize()
    val candidate = path.toPath().toAbsolutePath().normalize()
    if (!candidate.startsWith(rootPath)) throw UnsafeArchiveException(message)

    var cursor = rootPath
    for (component in rootPath.relativize(candidate)) {
      cursor = cursor.resolve(component)
      if (!Files.isSymbolicLink(cursor)) continue
      val linkTarget = Files.readSymbolicLink(cursor)
      val resolved = (if (linkTarget.isAbsolute) linkTarget else cursor.parent.resolve(linkTarget))
        .toAbsolutePath()
        .normalize()
      if (!resolved.startsWith(rootPath)) throw UnsafeArchiveException(message)
    }
  }

  private fun ensureCanonicalUnderRoot(root: File, target: File) {
    val canonical = target.canonicalFile
    if (canonical != root.canonicalFile && !canonical.path.startsWith(root.canonicalFile.path + File.separator)) {
      throw UnsafeArchiveException("resolved link target escapes the extraction root")
    }
  }

  private fun removeEntry(target: File) {
    if (!target.exists() && !Files.isSymbolicLink(target.toPath())) return
    if (Files.isSymbolicLink(target.toPath())) {
      Files.delete(target.toPath())
    } else if (!target.deleteRecursively()) {
      throw IOException("cannot replace extraction entry ${target.path}")
    }
  }
}
