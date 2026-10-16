package com.scariflabs.horus.terminal

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import org.json.JSONObject

/**
 * Rootfs acquisition state machine. Owns the storage
 * transitions; the network, extraction, and guest probe are injected so unit
 * tests and connected tests can drive every failure path deterministically.
 *
 * Invariants:
 *  - the active rootfs directory is never written into directly; extraction
 *    happens in versions/<id>.tmp and is renamed into place only after the
 *    guest probe passes;
 *  - active.json and manifest.json are written atomically (temp + rename);
 *  - a failed or interrupted install leaves the previous active version and
 *    every user directory (home, workspaces) untouched;
 *  - reset removes only what its explicit scope names; home deletion is a
 *    separate destructive request, never a side effect.
 */
class DistroStoreCore(
  private val paths: DistroStorePaths,
  private val downloader: ArchiveDownloader,
  private val extractor: RootfsExtractor,
  private val prober: GuestProber,
  private val clock: () -> Long = System::currentTimeMillis,
) {
  interface ArchiveDownloader {
    /** Streams the archive to `destination`; returns the verified byte count. */
    @Throws(IOException::class)
    fun download(url: String, expectedBytes: Long, destination: File, timeoutMs: Long): Long
  }

  interface RootfsExtractor {
    @Throws(IOException::class)
    fun extract(archive: File, destinationRoot: File, format: ArchiveFormat): SafeTarExtractor.Summary
  }

  sealed interface InstallOutcome {
    data class Success(
      val rootfsId: String,
      val archiveBytes: Long,
      val archiveSha256: String,
      val extraction: SafeTarExtractor.Summary,
      val probe: GuestProbeResult,
      val reusedCachedArchive: Boolean,
      val durationMs: Long,
    ) : InstallOutcome

    data class Failure(val rootfsId: String, val stage: String, val reasonCode: String, val detail: String) : InstallOutcome
  }

  /**
   * The installed rootfs a record points at. Alpine records keep the v1 shape
   * (`alpineRelease`, no `distro`) byte for byte, so an older Horus can still
   * read them after a downgrade; other distributions use v2.
   */
  data class ActiveRecord(
    val schemaVersion: Int,
    val distro: String,
    val rootfsId: String,
    val release: String,
    val rootfsSha256: String,
    val installedAtIso: String,
    val probeMarkers: List<String>,
  )

  val storage: DistroStorePaths get() = paths

  private val sha256Pattern = Regex("^[a-f0-9]{64}$")

  fun ensureLayout() {
    for (directory in paths.allDirectories()) {
      var ancestor: File? = directory
      while (ancestor != null && ancestor != paths.base.parentFile) {
        if (Files.isSymbolicLink(ancestor.toPath())) {
          throw IOException("storage path is a symbolic link: ${ancestor.name}")
        }
        ancestor = ancestor.parentFile
      }
      if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
        throw IOException("cannot create ${directory.relativeToOrNull(paths.base)}")
      }
    }
    pruneProotTemps(paths.guestTmp)
    pruneProotTemps(File(paths.sessions, ".proot-scratch"))
    // Reclaim leftovers from releases that staged files in the rootfs /tmp.
    paths.versions.listFiles()?.filter { AlpineRootfsCatalog.isValidRootfsId(it.name) }?.forEach {
      val rootfs = paths.versionRootfs(it.name)
      if (isOwnedDirectory(rootfs)) pruneProotTemps(File(rootfs, "tmp"))
    }
  }

  @Synchronized
  fun install(
    entry: RootfsCatalogEntry = RootfsCatalog.DEFAULT,
    rootfsId: String = entry.rootfsId,
    url: String = entry.url,
    expectedSha256: String = entry.sha256,
    expectedBytes: Long = entry.sizeBytes,
    downloadTimeoutMs: Long = 120_000,
    source: ArchiveDownloader = downloader,
    /** False installs the version beside the active one, e.g. a second user's distribution, without switching to it. */
    makeActive: Boolean = true,
  ): InstallOutcome {
    if (!AlpineRootfsCatalog.isValidRootfsId(rootfsId)) {
      return InstallOutcome.Failure(rootfsId, "validate", "invalid_rootfs_id", "id does not match the catalog pattern")
    }
    if (!sha256Pattern.matches(expectedSha256)) {
      return InstallOutcome.Failure(rootfsId, "validate", "invalid_digest", "expected digest is not sha-256")
    }
    if (expectedBytes <= 0L || downloadTimeoutMs <= 0L) {
      return InstallOutcome.Failure(rootfsId, "validate", "invalid_request", "size and timeout must be positive")
    }
    val startedAt = clock()
    try {
      ensureLayout()
    } catch (error: IOException) {
      return InstallOutcome.Failure(rootfsId, "layout", "storage_unavailable", error.message ?: "layout failure")
    }

    // A verified archive in the cache may be reused; downloads (and archives
    // the user imports by hand) always land in downloads/incoming first.
    val cachedArchive = paths.archiveInCache(rootfsId)
    val incoming = paths.archiveInIncoming(rootfsId)
    val archiveToUse: File
    val reusedCache: Boolean
    try {
      if (cachedArchive.isFile && sha256OfFile(cachedArchive) == expectedSha256 && cachedArchive.length() == expectedBytes) {
        archiveToUse = cachedArchive
        reusedCache = true
      } else {
        reusedCache = false
        incoming.delete()
        val downloaded = try {
          source.download(url, expectedBytes, incoming, downloadTimeoutMs)
        } catch (error: IOException) {
          incoming.delete()
          return InstallOutcome.Failure(rootfsId, "download", "download_failed", error.message ?: "download failure")
        }
        if (downloaded != expectedBytes) {
          incoming.delete()
          return InstallOutcome.Failure(rootfsId, "download", "size_mismatch", "downloaded $downloaded bytes, expected $expectedBytes")
        }
        val digest = sha256OfFile(incoming)
        if (digest != expectedSha256) {
          incoming.delete()
          return InstallOutcome.Failure(rootfsId, "download", "digest_mismatch", "sha-256 $digest != $expectedSha256")
        }
        cachedArchive.parentFile?.mkdirs()
        deleteTreeIfPresent(cachedArchive)
        if (!incoming.renameTo(cachedArchive)) {
          incoming.delete()
          return InstallOutcome.Failure(rootfsId, "download", "promote_archive_failed", "cannot move verified archive into the cache")
        }
        archiveToUse = cachedArchive
      }
    } catch (error: IOException) {
      return InstallOutcome.Failure(rootfsId, "download", "download_io_error", error.message ?: "io failure")
    }

    // Extract into a staging directory; never into the active rootfs path.
    val staging = paths.versionStaging(rootfsId)
    try {
      deleteTreeIfPresent(staging)
    } catch (error: IOException) {
      return InstallOutcome.Failure(rootfsId, "extract", "staging_cleanup_failed", error.message ?: "cannot clear staging")
    }
    val stagingRootfs = File(staging, "rootfs")
    val summary = try {
      if (!stagingRootfs.mkdirs()) throw IOException("cannot create staging rootfs")
      extractor.extract(archiveToUse, stagingRootfs, entry.archiveFormat)
    } catch (error: Exception) {
      deleteQuietly(staging)
      val reason = when (error) {
        is SafeTarExtractor.UnsafeArchiveException -> "unsafe_archive"
        else -> "extraction_failed"
      }
      return InstallOutcome.Failure(rootfsId, "extract", reason, error.message ?: "extraction failure")
    }

    // The guest must prove itself before promotion.
    // The markers to require come from the catalog entry, never from the
    // prober's result, so another distribution's probe output cannot pass.
    val probe = try {
      val probeHome = paths.homeFor(entry.distro)
      if (!probeHome.isDirectory && !probeHome.mkdirs()) throw IOException("cannot create ${entry.distro} home")
      prober.probe(stagingRootfs, probeHome, 60_000, entry).copy(requiredLines = entry.probeMarkers)
    } catch (error: Exception) {
      deleteQuietly(staging)
      return InstallOutcome.Failure(rootfsId, "probe", "probe_failed", error.message ?: "probe failure")
    }
    if (!probe.hasAllExpectedMarkers()) {
      deleteQuietly(staging)
      return InstallOutcome.Failure(rootfsId, "probe", "probe_markers_missing", probe.describeMissing())
    }
    if (probe.leaksHostPath(paths.base.canonicalPath)) {
      deleteQuietly(staging)
      return InstallOutcome.Failure(rootfsId, "probe", "probe_host_path_leak", "guest output exposed an app-private host path")
    }

    val record = ActiveRecord(
      schemaVersion = recordSchemaFor(entry.distro),
      distro = entry.distro,
      rootfsId = rootfsId,
      release = entry.release,
      rootfsSha256 = expectedSha256,
      installedAtIso = java.time.Instant.ofEpochMilli(clock()).toString(),
      probeMarkers = probe.markerLines(),
    )
    try {
      // Write the manifest while the version is still unreferenced. A
      // successful promotion therefore always carries its manifest with it.
      writeJsonAtomically(
        File(staging, "manifest.json"),
        record.toJson().put("sourceUrl", url).put("archiveBytes", expectedBytes),
      )
    } catch (error: IOException) {
      deleteQuietly(staging)
      return InstallOutcome.Failure(rootfsId, "promote", "manifest_write_failed", error.message ?: "manifest failure")
    }

    // Atomic promotion: rename staging into versions/<id>, then flip
    // active.json. A crash between those operations leaves either the old
    // active record or a complete-but-unreferenced version — never a
    // manifest-less version. Reinstalling the active id never removes the
    // currently referenced rootfs before the replacement is known-good.
    val versionDir = paths.versionDir(rootfsId)
    fun success() = InstallOutcome.Success(
      rootfsId = rootfsId,
      archiveBytes = archiveToUse.length(),
      archiveSha256 = expectedSha256,
      extraction = summary,
      probe = probe,
      reusedCachedArchive = reusedCache,
      durationMs = clock() - startedAt,
    )
    if (readActiveRecord()?.rootfsId == rootfsId && versionDir.isDirectory) {
      try {
        deleteTreeIfPresent(staging)
      } catch (error: IOException) {
        return InstallOutcome.Failure(rootfsId, "promote", "staging_cleanup_failed", error.message ?: "cannot clear staging")
      }
      return success()
    }
    fun promote(): InstallOutcome {
      val replacement = paths.versionReplacement(rootfsId)
      try {
        deleteTreeIfPresent(replacement)
        if (versionDir.exists() && !versionDir.renameTo(replacement)) {
          throw IOException("cannot stage the existing versions/$rootfsId for replacement")
        }
        if (!staging.renameTo(versionDir)) {
          if (replacement.exists() && !replacement.renameTo(versionDir)) {
            throw IOException("cannot roll back versions/$rootfsId after promotion failure")
          }
          throw IOException("cannot rename staging into versions/$rootfsId")
        }
      } catch (error: IOException) {
        deleteQuietly(staging)
        return InstallOutcome.Failure(rootfsId, "promote", "promote_failed", error.message ?: "cannot promote version")
      }
      try {
        if (makeActive) writeJsonAtomically(paths.activeRecord, record.toJson())
      } catch (error: IOException) {
        deleteQuietly(versionDir)
        if (replacement.exists() && !replacement.renameTo(versionDir)) {
          return InstallOutcome.Failure(rootfsId, "promote", "rollback_failed", "active record write failed and version rollback failed")
        }
        return InstallOutcome.Failure(rootfsId, "promote", "manifest_write_failed", error.message ?: "manifest failure")
      }
      deleteQuietly(replacement)
      return success()
    }
    // Only replacing an existing version can pull a rootfs out from under a
    // running session, so only that waits for sessions to stop. A new
    // version (e.g. a second user's distribution) promotes beside them.
    if (!versionDir.exists()) return promote()
    return try {
      withHardlinkLock {
        promote().also {
          // Collection failure must not turn a committed installation into a
          // reported install failure. A later lifecycle operation retries it.
          if (it is InstallOutcome.Success) runCatching { pruneHardlinkBacking() }
        }
      }
    } catch (error: RuntimeBusyException) {
      deleteQuietly(staging)
      InstallOutcome.Failure(rootfsId, "promote", "runtime_busy", error.message ?: "runtime is in use")
    } catch (error: IOException) {
      deleteQuietly(staging)
      InstallOutcome.Failure(rootfsId, "promote", "promote_failed", error.message ?: "storage unavailable")
    }
  }

  /** A missing or half-written active record reads as "not installed". */
  @Synchronized
  fun readActiveRecord(): ActiveRecord? {
    val record = readRecordFile(paths.activeRecord) ?: return null
    val rootfs = paths.versionRootfs(record.rootfsId)
    if (!isOwnedDirectory(rootfs) || !rootfs.isDirectory) return null
    val manifest = readRecordFile(paths.versionManifest(record.rootfsId)) ?: return null
    return record.takeIf {
      manifest.schemaVersion == record.schemaVersion &&
        manifest.distro == record.distro &&
        manifest.rootfsId == record.rootfsId &&
        manifest.release == record.release &&
        manifest.rootfsSha256 == record.rootfsSha256 &&
        manifest.installedAtIso == record.installedAtIso &&
        manifest.probeMarkers == record.probeMarkers
    }
  }

  /**
   * Makes an installed version active again without reinstalling it: the
   * active record becomes a copy of that version's own manifest. Returns
   * false when the version is missing or its manifest no longer matches the
   * catalog.
   */
  @Synchronized
  fun activate(rootfsId: String): Boolean {
    if (!AlpineRootfsCatalog.isValidRootfsId(rootfsId)) return false
    val rootfs = paths.versionRootfs(rootfsId)
    if (!isOwnedDirectory(rootfs) || !rootfs.isDirectory) return false
    val manifest = readRecordFile(paths.versionManifest(rootfsId)) ?: return false
    if (manifest.rootfsId != rootfsId) return false
    if (readActiveRecord()?.rootfsId == rootfsId) return true
    return try {
      writeJsonAtomically(paths.activeRecord, manifest.toJson())
      true
    } catch (_: IOException) {
      false
    }
  }

  /** The installed version of [distro], if any; at most one per distribution is kept. */
  @Synchronized
  fun installedVersionFor(distro: String): String? =
    installedVersionIds().firstOrNull { RootfsCatalog.byId(it)?.distro == distro }

  @Synchronized
  fun installedVersionIds(): List<String> {
    val versions = paths.versions.listFiles() ?: return emptyList()
    return versions.filter {
      it.isDirectory &&
        AlpineRootfsCatalog.isValidRootfsId(it.name) &&
        isOwnedDirectory(paths.versionRootfs(it.name)) &&
        paths.versionRootfs(it.name).isDirectory &&
        readRecordFile(paths.versionManifest(it.name))?.let { record -> record.rootfsId == it.name } == true
    }
      .map { it.name }
      .sorted()
  }

  @Synchronized
  fun activeRootfsDir(): File? = readActiveRecord()?.let { paths.versionRootfs(it.rootfsId) }

  /** Rootfs reset removes version data and the active record; user data is only removed when explicitly named. */
  @Synchronized
  fun reset(deleteHome: Boolean = false, deleteWorkspaces: Boolean = false): ResetResult = withHardlinkLock {
    val removedVersions = installedVersionIds()
    deleteTreeIfPresent(paths.activeRecord)
    val versionEntries = paths.versions.listFiles()?.toList().orEmpty()
    for (entry in versionEntries) {
      val isKnownVersion = removedVersions.contains(entry.name)
      val isKnownStaging = entry.name.endsWith(".tmp") &&
        AlpineRootfsCatalog.isValidRootfsId(entry.name.removeSuffix(".tmp"))
      val isKnownReplacement = entry.name.endsWith(".replace.tmp") &&
        AlpineRootfsCatalog.isValidRootfsId(entry.name.removeSuffix(".replace.tmp").removePrefix("."))
      if (isKnownVersion || isKnownStaging || isKnownReplacement) {
        deleteTreeIfPresent(entry)
      }
    }
    paths.downloadsCache.listFiles()?.forEach { deleteTreeIfPresent(it) }
    paths.downloadsIncoming.listFiles()?.forEach { deleteTreeIfPresent(it) }
    var removedHome = false
    var removedWorkspaces = false
    if (deleteHome) {
      removedHome = paths.home.exists() || paths.harnessHomes.exists() || paths.distroHomes.exists()
      deleteTreeIfPresent(paths.home)
      deleteTreeIfPresent(paths.harnessHomes)
      deleteTreeIfPresent(paths.distroHomes)
      RemoteAccess.Store(paths.base).deleteHostKey()
      if (!paths.home.mkdirs() && !paths.home.isDirectory) throw IOException("cannot recreate home")
      if (!paths.harnessHomes.mkdirs() && !paths.harnessHomes.isDirectory) {
        throw IOException("cannot recreate harness homes")
      }
      listOf(
        TerminalRuntimeContract.TOOLCHAIN_TARGET_CLAUDE,
        TerminalRuntimeContract.TOOLCHAIN_TARGET_CODEX,
        TerminalRuntimeContract.TOOLCHAIN_TARGET_OPENCODE,
      ).forEach { harness ->
        val harnessHome = paths.harnessHome(harness)
        if (!harnessHome.mkdirs() && !harnessHome.isDirectory) {
          throw IOException("cannot recreate $harness home")
        }
      }
    }
    if (deleteWorkspaces) {
      removedWorkspaces = paths.defaultWorkspace.exists()
      deleteTreeIfPresent(paths.defaultWorkspace)
      if (!paths.defaultWorkspace.mkdirs() && !paths.defaultWorkspace.isDirectory) {
        throw IOException("cannot recreate workspaces")
      }
    }
    // The reset itself is done; a failed scan only defers reclamation.
    runCatching { pruneHardlinkBacking() }
    ResetResult(removedVersions, removedHome, removedWorkspaces)
  }

  /** PRoot holds a POSIX read lock for its lifetime, including SSH children. */
  private fun <T> withHardlinkLock(action: () -> T): T {
    ensureLayout()
    FileChannel.open(File(paths.hardlinks, ".gc.lock").toPath(), WRITE, CREATE, NOFOLLOW_LINKS).use { channel ->
      val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
        ?: throw RuntimeBusyException()
      lock.use { return action() }
    }
  }

  /**
   * Lifecycle-only mark/sweep. PRoot stores source -> intermediate symlink ->
   * backing file; host nlink is normally ONE even for surviving guest links.
   * Java unlink does not update PRoot's suffix count, so neither count proves
   * liveness. Walk surviving storage without following any directory symlinks,
   * then trace only links inside l. Abort collection if any walk/read fails.
   */
  private fun pruneHardlinkBacking() {
    val backing = paths.hardlinks.canonicalFile.toPath()
    val candidates = Files.newDirectoryStream(backing).use { entries ->
      entries.filter { it.fileName.toString().startsWith(".l2s.") }.toSet()
    }
    if (candidates.isEmpty()) return
    val live = mutableSetOf<Path>()
    // Candidates are canonical. A link written through another spelling of
    // the same directory (/data/user/0 vs /data/data) must still match, or
    // its live backing would be collected. Only the parent is resolved.
    fun canonical(path: Path): Path {
      val absolute = path.toAbsolutePath().normalize()
      val parent = absolute.parent ?: return absolute
      return runCatching { parent.toRealPath().resolve(absolute.fileName) }.getOrDefault(absolute)
    }
    fun mark(path: Path) {
      var current = canonical(path)
      while (current in candidates && live.add(current)) {
        if (!Files.isSymbolicLink(current)) break
        current = canonical(current.parent.resolve(Files.readSymbolicLink(current)))
      }
    }
    Files.walkFileTree(paths.base.canonicalFile.toPath(), object : java.nio.file.SimpleFileVisitor<Path>() {
      override fun preVisitDirectory(dir: Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult =
        if (dir.toAbsolutePath().normalize() == backing) java.nio.file.FileVisitResult.SKIP_SUBTREE
        else java.nio.file.FileVisitResult.CONTINUE

      override fun visitFile(file: Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
        if (attrs.isSymbolicLink) mark(file.parent.resolve(Files.readSymbolicLink(file)))
        return java.nio.file.FileVisitResult.CONTINUE
      }
    })
    // Also retain genuine host hardlinks (e.g. imported files); unavailable
    // attributes fail closed. Android's NIO provider lacks unix:nlink.
    for (entry in candidates) {
      if (Files.isSymbolicLink(entry)) continue
      val links = runCatching { android.system.Os.lstat(entry.toString()).st_nlink }.getOrElse {
        (Files.getAttribute(entry, "unix:nlink", NOFOLLOW_LINKS) as Number).toLong()
      }
      if (links > 1) mark(entry)
    }
    candidates.filter { it !in live }.forEach { entry ->
      // Only unlink direct files/symlinks, never traverse backing directories.
      if (!Files.isDirectory(entry, NOFOLLOW_LINKS)) Files.deleteIfExists(entry)
    }
  }

  /** A session or SSH server holds PRoot's shared lease on the hardlink store. */
  class RuntimeBusyException : IOException("runtime is in use; stop active sessions and SSH access, then retry")

  data class ResetResult(
    val removedVersionIds: List<String>,
    val homeRemoved: Boolean,
    val workspacesRemoved: Boolean,
  )

  private fun sha256OfFile(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
      val buffer = ByteArray(1 shl 16)
      while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }

  private fun readRecordFile(file: File): ActiveRecord? {
    if (Files.isSymbolicLink(file.toPath()) || !file.isFile) return null
    return try {
      val json = JSONObject(file.readText(Charsets.UTF_8))
      val schemaVersion = json.optInt("schemaVersion", -1)
      val id = json.optString("rootfsId")
      val digest = json.optString("rootfsSha256")
      val installedAt = json.optString("installedAtIso")
      val (distro, release) = when (schemaVersion) {
        RootfsCatalog.LEGACY_ALPINE_RECORD_SCHEMA -> RootfsCatalog.DISTRO_ALPINE to json.optString("alpineRelease")
        RootfsCatalog.RECORD_SCHEMA -> json.optString("distro") to json.optString("release")
        else -> return null
      }
      if (!AlpineRootfsCatalog.isValidRootfsId(id) || !sha256Pattern.matches(digest)) return null
      val values = json.optJSONArray("probeMarkers") ?: return null
      val markers = (0 until values.length()).map { index ->
        val value = values.opt(index)
        if (value !is String || value.isBlank()) return null
        value
      }
      val record = ActiveRecord(
        schemaVersion = schemaVersion,
        distro = distro,
        rootfsId = id,
        release = release,
        rootfsSha256 = digest,
        installedAtIso = installedAt,
        probeMarkers = markers,
      )
      if (!isValidRecord(record)) return null
      record
    } catch (_: Exception) {
      null
    }
  }

  private fun deleteTreeIfPresent(file: File) {
    val path = file.toPath()
    if (!file.exists() && !Files.isSymbolicLink(path)) return
    if (Files.isSymbolicLink(path) || !file.isDirectory) {
      if (!Files.deleteIfExists(path)) throw IOException("cannot remove ${file.path}")
      return
    }
    file.listFiles()?.forEach { child -> deleteTreeIfPresent(child) }
    if (!Files.deleteIfExists(path)) throw IOException("cannot remove ${file.path}")
  }

  private fun deleteQuietly(file: File) {
    runCatching { deleteTreeIfPresent(file) }
  }

  private fun isOwnedDirectory(file: File): Boolean {
    if (Files.isSymbolicLink(file.toPath())) return false
    val base = runCatching { paths.base.canonicalFile.path }.getOrNull() ?: return false
    val candidate = runCatching { file.canonicalFile.path }.getOrNull() ?: return false
    return candidate == base || candidate.startsWith(base + File.separator)
  }

  /** A record is only valid for a catalog entry it matches exactly. */
  private fun isValidRecord(record: ActiveRecord): Boolean {
    val entry = RootfsCatalog.byId(record.rootfsId) ?: return false
    return record.schemaVersion == recordSchemaFor(entry.distro) &&
      record.distro == entry.distro &&
      sha256Pattern.matches(record.rootfsSha256) &&
      record.release == entry.release &&
      runCatching { java.time.Instant.parse(record.installedAtIso) }.isSuccess &&
      record.probeMarkers == entry.probeMarkers
  }

  private fun recordSchemaFor(distro: String): Int =
    if (distro == RootfsCatalog.DISTRO_ALPINE) RootfsCatalog.LEGACY_ALPINE_RECORD_SCHEMA else RootfsCatalog.RECORD_SCHEMA

  private fun writeJsonAtomically(destination: File, json: JSONObject) {
    val temp = File(destination.parentFile, ".${destination.name}.tmp")
    temp.writeText(json.toString(), Charsets.UTF_8)
    if (!temp.renameTo(destination)) {
      temp.delete()
      throw IOException("cannot atomically replace ${destination.name}")
    }
  }

  private fun ActiveRecord.toJson(): JSONObject = JSONObject()
    .put("schemaVersion", schemaVersion)
    .apply { if (schemaVersion == RootfsCatalog.RECORD_SCHEMA) put("distro", distro) }
    .put("rootfsId", rootfsId)
    .put(if (schemaVersion == RootfsCatalog.RECORD_SCHEMA) "release" else "alpineRelease", release)
    .put("rootfsSha256", rootfsSha256)
    .put("installedAtIso", installedAtIso)
    .put("probeMarkers", org.json.JSONArray(probeMarkers))

  companion object {
    private val PROOT_TEMP_NAME = Regex("^(?:proot|prooted|prootseq|prootshm|proot-tmpfs-)-([1-9][0-9]*)-[a-zA-Z0-9]{6}$")

    /** PRoot names include the host tracer PID. Never unlink a live session's staging files. */
    internal fun pruneProotTemps(directory: File, hostProc: File = File("/proc")) {
      if (Files.isSymbolicLink(directory.toPath())) return
      directory.listFiles()?.forEach { entry ->
        val pid = PROOT_TEMP_NAME.matchEntire(entry.name)?.groupValues?.get(1) ?: return@forEach
        if (File(hostProc, pid).exists()) return@forEach
        // Best effort when storage is full; walkFileTree never follows symlinks.
        runCatching {
          Files.walkFileTree(entry.toPath(), object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
            override fun visitFile(file: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
              Files.deleteIfExists(file)
              return java.nio.file.FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: java.nio.file.Path, error: IOException?): java.nio.file.FileVisitResult {
              if (error != null) throw error
              Files.deleteIfExists(dir)
              return java.nio.file.FileVisitResult.CONTINUE
            }
          })
        }
      }
    }
  }
}
