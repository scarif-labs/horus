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
  // Keep translated AF_UNIX paths below sockaddr_un.sun_path's 108 bytes.
  val guestTmp: File get() = File(base, "t")
  /** Persistent emulated hardlinks; lifecycle collection retains surviving symlink chains. */
  val hardlinks: File get() = File(base, "l")
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

  /** Every distribution other than Alpine keeps its users' homes under here. */
  val distroHomes: File get() = File(base, "homes")

  /**
   * [distro]'s user home. Alpine keeps the `home/` it has always had; other
   * distributions get their own, so tools built for one libc never run on
   * the other.
   */
  fun homeFor(distro: String): File =
    if (distro == RootfsCatalog.DISTRO_ALPINE) home else File(File(distroHomes, safeDistro(distro)), "home")

  /** [distro]'s home for one harness account, separate per distribution like [homeFor]. */
  fun harnessHomeFor(distro: String, harness: String): File {
    if (distro == RootfsCatalog.DISTRO_ALPINE) return harnessHome(harness)
    require(harness in HARNESS_HOME_KEYS) { "harness home key is invalid" }
    return File(File(File(distroHomes, safeDistro(distro)), "harness-homes"), harness)
  }

  /** The user home of every distribution Horus knows, Alpine first. */
  fun userHomes(): List<File> = RootfsCatalog.entries.map { it.distro }.distinct().map(::homeFor)

  private fun safeDistro(distro: String): String {
    require(Regex("^[a-z][a-z0-9-]{0,31}$").matches(distro)) { "distribution name is invalid" }
    return distro
  }

  fun archiveInIncoming(rootfsId: String): File = File(downloadsIncoming, "$rootfsId.tar.gz.part")
  fun archiveInCache(rootfsId: String): File = File(downloadsCache, "$rootfsId.tar.gz")

  /** Every directory the storage contract defines, parents first. */
  fun allDirectories(): List<File> = listOf(
    base, distro, versions, home, harnessHomes,
    *HARNESS_HOME_KEYS.map(::harnessHome).toTypedArray(),
    defaultWorkspace, downloadsIncoming,
    downloadsCache, sessions, guestTmp, hardlinks, diagnostics,
  )

  companion object {
    val HARNESS_HOME_KEYS = listOf("claude", "codex", "opencode")
  }
}
