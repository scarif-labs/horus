package com.scariflabs.horus.terminal

/**
 * Pinned Alpine rootfs catalog entry (never
 * install "latest" during a gate). The digest is the official one published
 * beside the image at pin time (2026-09-07).
 */
object AlpineRootfsCatalog {
  const val ROOTFS_ID = "alpine-3.24.0-aarch64"
  const val ROOTFS_URL = "https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/aarch64/alpine-minirootfs-3.24.0-aarch64.tar.gz"
  const val ROOTFS_SHA256 = "4b8cd66a6688b2a87276c39843ed89c3a06d9534fc6a5823c586aff2696c1f2a"
  const val ROOTFS_SIZE_BYTES = 4_043_766L
  const val ALPINE_RELEASE = "3.24.0"
  const val ALPINE_BRANCH = "v3.24"
  const val EXPECTED_GUEST_ARCH = "aarch64"
  const val MANIFEST_SCHEMA_VERSION = 1

  /**
   * Phase 1 non-interactive guest probe. The command body is the exact probe
   * from the plan; the begin/end markers give boundary-delimited evidence
   * lines instead of substring guessing.
   */
  const val PROBE_COMMAND =
    "echo ${ProbeMarkers.BEGIN}; uname -m; cat /etc/alpine-release; printf '%s\\n' \"\$HOME\"; command -v apk; command -v sh; echo ${ProbeMarkers.END}"

  /** Marker names kept in one place so tests, native code, and evidence agree. */
  object ProbeMarkers {
    const val BEGIN = "alpine_probe_begin"
    const val END = "alpine_probe_end"
    const val EXPECTED_ARCH = EXPECTED_GUEST_ARCH
    const val EXPECTED_HOME = "/root"
    const val EXPECTED_APK = "/sbin/apk"
    const val EXPECTED_SH = "/bin/sh"
  }

  fun isValidRootfsId(id: String?): Boolean =
    id != null && Regex("^[a-z0-9][a-z0-9.-]{0,63}$").matches(id)
}
