package com.scariflabs.horus.terminal

/** How a pinned rootfs archive is compressed. Always a tar inside. */
enum class ArchiveFormat { TAR_GZ, TAR_XZ }

/**
 * One installable guest distribution, pinned like Alpine: a fixed archive,
 * digest, and size, plus the guest probe that must pass before the rootfs is
 * promoted. Nothing here is ever "latest".
 */
data class RootfsCatalogEntry(
  /** Stable distribution key, e.g. "alpine". Stored in v2 records. */
  val distro: String,
  val rootfsId: String,
  /** The distribution's own release string, as the probe reads it. */
  val release: String,
  val url: String,
  val sha256: String,
  val sizeBytes: Long,
  val archiveFormat: ArchiveFormat,
  val probeBegin: String,
  val probeEnd: String,
  /** Each check runs one guest executable and must print exactly [ProbeCheck.expected]. */
  val probeChecks: List<ProbeCheck>,
) {
  data class ProbeCheck(val argv: List<String>, val expected: String)

  /** The exact, ordered marker block a passing probe produces and a record stores. */
  val probeMarkers: List<String>
    get() = listOf(probeBegin) + probeChecks.map(ProbeCheck::expected) + probeEnd
}

/** Every distribution Horus can install. Alpine is the default. */
object RootfsCatalog {
  const val DISTRO_ALPINE = "alpine"
  const val DISTRO_DEBIAN = "debian"

  /** Alpine's record shape since 1.0.0; still written for Alpine so downgrades keep working. */
  const val LEGACY_ALPINE_RECORD_SCHEMA = AlpineRootfsCatalog.MANIFEST_SCHEMA_VERSION

  /** Records for every other distribution: adds `distro`, renames `alpineRelease` to `release`. */
  const val RECORD_SCHEMA = 2

  val ALPINE = RootfsCatalogEntry(
    distro = DISTRO_ALPINE,
    rootfsId = AlpineRootfsCatalog.ROOTFS_ID,
    release = AlpineRootfsCatalog.ALPINE_RELEASE,
    url = AlpineRootfsCatalog.ROOTFS_URL,
    sha256 = AlpineRootfsCatalog.ROOTFS_SHA256,
    sizeBytes = AlpineRootfsCatalog.ROOTFS_SIZE_BYTES,
    archiveFormat = ArchiveFormat.TAR_GZ,
    probeBegin = AlpineRootfsCatalog.ProbeMarkers.BEGIN,
    probeEnd = AlpineRootfsCatalog.ProbeMarkers.END,
    probeChecks = listOf(
      RootfsCatalogEntry.ProbeCheck(listOf("/bin/uname", "-m"), AlpineRootfsCatalog.ProbeMarkers.EXPECTED_ARCH),
      RootfsCatalogEntry.ProbeCheck(listOf("/bin/cat", "/etc/alpine-release"), AlpineRootfsCatalog.ALPINE_RELEASE),
      RootfsCatalogEntry.ProbeCheck(listOf("/bin/sh", "-c", "printf '%s\\n' \"\$HOME\""), AlpineRootfsCatalog.ProbeMarkers.EXPECTED_HOME),
      RootfsCatalogEntry.ProbeCheck(listOf("/bin/sh", "-c", "command -v apk"), AlpineRootfsCatalog.ProbeMarkers.EXPECTED_APK),
      RootfsCatalogEntry.ProbeCheck(listOf("/bin/sh", "-c", "command -v sh"), AlpineRootfsCatalog.ProbeMarkers.EXPECTED_SH),
    ),
  )

  /**
   * Debian 13 (trixie) slim, pinned 2026-10-12: debuerreotype's 20261005
   * arm64v8 build, recompressed to xz and hosted as a Horus release asset,
   * because debuerreotype's dist branch is force-pushed and its raw URLs do
   * not last. The tar inside is unchanged: `xz -dc` of the asset hashes to
   * the image layer's diff_id, 258ba1f62b513e7bb7492d3cc8ce445e4ef176a14980f193f789b6d7270ae61c.
   *
   * Debian is merged-usr (/bin -> usr/bin), so the probe runs /usr/bin directly.
   */
  val DEBIAN = RootfsCatalogEntry(
    distro = DISTRO_DEBIAN,
    rootfsId = "debian-13.7-20261005-aarch64",
    release = "13.7",
    url = "https://github.com/scarif-labs/horus/releases/download/rootfs-debian-13.7-20261005/debian-trixie-slim-13.7-arm64.tar.xz",
    sha256 = "c4a40504d8eddd7d47125d129296352d3758b1d0ce958bd517b44631aee74eb5",
    sizeBytes = 17_386_584L,
    archiveFormat = ArchiveFormat.TAR_XZ,
    probeBegin = "debian_probe_begin",
    probeEnd = "debian_probe_end",
    probeChecks = listOf(
      RootfsCatalogEntry.ProbeCheck(listOf("/usr/bin/uname", "-m"), AlpineRootfsCatalog.EXPECTED_GUEST_ARCH),
      RootfsCatalogEntry.ProbeCheck(listOf("/usr/bin/cat", "/etc/debian_version"), "13.7"),
      RootfsCatalogEntry.ProbeCheck(listOf("/usr/bin/sh", "-c", "printf '%s\\n' \"\$HOME\""), "/root"),
      RootfsCatalogEntry.ProbeCheck(listOf("/usr/bin/sh", "-c", "command -v apt-get"), "/usr/bin/apt-get"),
      RootfsCatalogEntry.ProbeCheck(listOf("/usr/bin/sh", "-c", "command -v sh"), "/usr/bin/sh"),
    ),
  )

  val DEFAULT: RootfsCatalogEntry = ALPINE

  val entries: List<RootfsCatalogEntry> = listOf(ALPINE, DEBIAN)

  fun byId(rootfsId: String?): RootfsCatalogEntry? = entries.firstOrNull { it.rootfsId == rootfsId }
}
