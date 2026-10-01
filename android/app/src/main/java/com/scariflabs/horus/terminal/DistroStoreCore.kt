package com.scariflabs.horus.terminal

import java.io.File
import java.io.IOException
import java.nio.file.Files
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
    fun extract(archive: File, destinationRoot: File): SafeTarGzExtractor.Summary
  }

  sealed interface InstallOutcome {
    data class Success(
      val rootfsId: String,
      val archiveBytes: Long,
      val archiveSha256: String,
      val extraction: SafeTarGzExtractor.Summary,
      val probe: GuestProbeResult,
      val reusedCachedArchive: Boolean,
      val durationMs: Long,
    ) : InstallOutcome

    data class Failure(val rootfsId: String, val stage: String, val reasonCode: String, val detail: String) : InstallOutcome
  }

  data class ActiveRecord(
    val schemaVersion: Int,
    val rootfsId: String,
    val alpineRelease: String,
    val rootfsSha256: String,
    val installedAtIso: String,
    val probeMarkers: List<String>,
  )

  val storage: DistroStorePaths get() = paths

  private val sha256Pattern = Regex("^[a-f0-9]{64}$")

  fun ensureLayout() {
    for (directory in paths.allDirectories()) {
      if (Files.isSymbolicLink(directory.toPath())) {
        throw IOException("storage path is a symbolic link: ${directory.name}")
      }
      if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
        throw IOException("cannot create ${directory.relativeToOrNull(paths.base)}")
      }
    }
  }

  @Synchronized
  fun install(
    rootfsId: String = AlpineRootfsCatalog.ROOTFS_ID,
    url: String = AlpineRootfsCatalog.ROOTFS_URL,
    expectedSha256: String = AlpineRootfsCatalog.ROOTFS_SHA256,
    expectedBytes: Long = AlpineRootfsCatalog.ROOTFS_SIZE_BYTES,
    downloadTimeoutMs: Long = 120_000,
    source: ArchiveDownloader = downloader,
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
      extractor.extract(archiveToUse, stagingRootfs)
    } catch (error: Exception) {
      deleteQuietly(staging)
      val reason = when (error) {
        is SafeTarGzExtractor.UnsafeArchiveException -> "unsafe_archive"
        else -> "extraction_failed"
      }
      return InstallOutcome.Failure(rootfsId, "extract", reason, error.message ?: "extraction failure")
    }

    // The guest must prove itself before promotion.
    val probe = try {
      prober.probe(stagingRootfs, paths.home, 60_000)
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
      schemaVersion = AlpineRootfsCatalog.MANIFEST_SCHEMA_VERSION,
      rootfsId = rootfsId,
      alpineRelease = AlpineRootfsCatalog.ALPINE_RELEASE,
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
    val activeBefore = readActiveRecord()
    val versionDir = paths.versionDir(rootfsId)
    if (activeBefore?.rootfsId == rootfsId && versionDir.isDirectory) {
      try {
        deleteTreeIfPresent(staging)
      } catch (error: IOException) {
        return InstallOutcome.Failure(rootfsId, "promote", "staging_cleanup_failed", error.message ?: "cannot clear staging")
      }
      return InstallOutcome.Success(
        rootfsId = rootfsId,
        archiveBytes = archiveToUse.length(),
        archiveSha256 = expectedSha256,
        extraction = summary,
        probe = probe,
        reusedCachedArchive = reusedCache,
        durationMs = clock() - startedAt,
      )
    }
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
      writeJsonAtomically(paths.activeRecord, record.toJson())
    } catch (error: IOException) {
      deleteQuietly(versionDir)
      if (replacement.exists() && !replacement.renameTo(versionDir)) {
        return InstallOutcome.Failure(rootfsId, "promote", "rollback_failed", "active record write failed and version rollback failed")
      }
      return InstallOutcome.Failure(rootfsId, "promote", "manifest_write_failed", error.message ?: "manifest failure")
    }
    deleteQuietly(replacement)
    return InstallOutcome.Success(
      rootfsId = rootfsId,
      archiveBytes = archiveToUse.length(),
      archiveSha256 = expectedSha256,
      extraction = summary,
      probe = probe,
      reusedCachedArchive = reusedCache,
      durationMs = clock() - startedAt,
    )
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
        manifest.rootfsId == record.rootfsId &&
        manifest.alpineRelease == record.alpineRelease &&
        manifest.rootfsSha256 == record.rootfsSha256 &&
        manifest.installedAtIso == record.installedAtIso &&
        manifest.probeMarkers == record.probeMarkers
    }
  }

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
  fun reset(deleteHome: Boolean = false, deleteWorkspaces: Boolean = false): ResetResult {
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
      removedHome = paths.home.exists() || paths.harnessHomes.exists()
      deleteTreeIfPresent(paths.home)
      deleteTreeIfPresent(paths.harnessHomes)
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
    return ResetResult(removedVersions, removedHome, removedWorkspaces)
  }

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
      val id = json.optString("rootfsId")
      val digest = json.optString("rootfsSha256")
      val release = json.optString("alpineRelease")
      val installedAt = json.optString("installedAtIso")
      if (!AlpineRootfsCatalog.isValidRootfsId(id) ||
        !sha256Pattern.matches(digest) ||
        release != AlpineRootfsCatalog.ALPINE_RELEASE ||
        json.optInt("schemaVersion", -1) != AlpineRootfsCatalog.MANIFEST_SCHEMA_VERSION
      ) return null
      val values = json.optJSONArray("probeMarkers") ?: return null
      val markers = (0 until values.length()).map { index ->
        val value = values.opt(index)
        if (value !is String || value.isBlank()) return null
        value
      }
      val record = ActiveRecord(
        schemaVersion = AlpineRootfsCatalog.MANIFEST_SCHEMA_VERSION,
        rootfsId = id,
        alpineRelease = release,
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

  private fun isValidRecord(record: ActiveRecord): Boolean =
    record.schemaVersion == AlpineRootfsCatalog.MANIFEST_SCHEMA_VERSION &&
      AlpineRootfsCatalog.isValidRootfsId(record.rootfsId) &&
      sha256Pattern.matches(record.rootfsSha256) &&
      record.alpineRelease == AlpineRootfsCatalog.ALPINE_RELEASE &&
      runCatching { java.time.Instant.parse(record.installedAtIso) }.isSuccess &&
      record.probeMarkers == listOf(
        AlpineRootfsCatalog.ProbeMarkers.BEGIN,
        AlpineRootfsCatalog.ProbeMarkers.EXPECTED_ARCH,
        AlpineRootfsCatalog.ALPINE_RELEASE,
        AlpineRootfsCatalog.ProbeMarkers.EXPECTED_HOME,
        AlpineRootfsCatalog.ProbeMarkers.EXPECTED_APK,
        AlpineRootfsCatalog.ProbeMarkers.EXPECTED_SH,
        AlpineRootfsCatalog.ProbeMarkers.END,
      )

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
    .put("rootfsId", rootfsId)
    .put("alpineRelease", alpineRelease)
    .put("rootfsSha256", rootfsSha256)
    .put("installedAtIso", installedAtIso)
    .put("probeMarkers", org.json.JSONArray(probeMarkers))
}
