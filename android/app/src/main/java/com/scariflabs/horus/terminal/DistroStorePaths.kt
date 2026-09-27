package com.scariflabs.horus.terminal

import java.io.File

/**
 * The documented app-private storage contract. Pure path mapping
 * over a base directory so unit tests can exercise every transition with
 * temporary directories. Nothing here creates or deletes files; the store
 * owns mutation.
 */
data class DistroStorePaths(val base: File) {
  val distro: File get() = File(base, "distro")
  val activeRecord: File get() = File(distro, "active.json")
  val versions: File get() = File(distro, "versions")
  val home: File get() = File(base, "home")
  val harnessHomes: File get() = File(base, "harness-homes")
  val defaultWorkspace: File get() = File(base, "workspaces/default")
  val downloadsIncoming: File get() = File(base, "downloads/incoming")
  val downloadsCache: File get() = File(base, "downloads/cache")
  val sessions: File get() = File(base, "sessions")
  val diagnostics: File get() = File(base, "diagnostics")

  fun versionDir(rootfsId: String): File = File(versions, rootfsId)
  fun versionRootfs(rootfsId: String): File = File(versionDir(rootfsId), "rootfs")
  fun versionManifest(rootfsId: String): File = File(versionDir(rootfsId), "manifest.json")
  fun versionStaging(rootfsId: String): File = File(versions, "$rootfsId.tmp")
  fun versionReplacement(rootfsId: String): File = File(versions, ".$rootfsId.replace.tmp")

  fun harnessHome(harness: String): File {
    require(harness in HARNESS_HOME_KEYS) { "harness home key is invalid" }
    return File(harnessHomes, harness)
  }

  fun archiveInIncoming(rootfsId: String): File = File(downloadsIncoming, "$rootfsId.tar.gz.part")
  fun archiveInCache(rootfsId: String): File = File(downloadsCache, "$rootfsId.tar.gz")

  /** Every directory the storage contract defines, parents first. */
  fun allDirectories(): List<File> = listOf(
    base, distro, versions, home, harnessHomes,
    *HARNESS_HOME_KEYS.map(::harnessHome).toTypedArray(),
    defaultWorkspace, downloadsIncoming,
    downloadsCache, sessions, diagnostics,
  )

  private companion object {
    val HARNESS_HOME_KEYS = listOf("claude", "codex", "opencode")
  }
}
