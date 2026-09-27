package com.scariflabs.horus.terminal

import java.io.File

/**
 * Result of a non-interactive guest probe through PRoot. Validation compares
 * the bounded, marker-delimited output against the pinned catalog constants;
 * an arbitrary substring match is never proof.
 */
data class GuestProbeResult(
  val exitCode: Int,
  val output: String,
  val durationMs: Long,
  val timedOut: Boolean = false,
) {
  private val requiredLines: List<String> by lazy {
    listOf(
      AlpineRootfsCatalog.ProbeMarkers.BEGIN,
      AlpineRootfsCatalog.ProbeMarkers.EXPECTED_ARCH,
      AlpineRootfsCatalog.ALPINE_RELEASE,
      AlpineRootfsCatalog.ProbeMarkers.EXPECTED_HOME,
      AlpineRootfsCatalog.ProbeMarkers.EXPECTED_APK,
      AlpineRootfsCatalog.ProbeMarkers.EXPECTED_SH,
      AlpineRootfsCatalog.ProbeMarkers.END,
    )
  }

  private fun trimmedLines(): List<String> = output.lineSequence().map { it.trim() }.toList()

  private fun markerBlock(): List<String>? {
    val lines = trimmedLines()
    val begin = lines.indexOf(AlpineRootfsCatalog.ProbeMarkers.BEGIN)
    if (begin < 0) return null
    val endOffset = lines.drop(begin + 1).indexOf(AlpineRootfsCatalog.ProbeMarkers.END)
    if (endOffset < 0) return null
    val end = begin + 1 + endOffset
    return lines.subList(begin, end + 1)
  }

  /** Returns only the exact marker block that was observed, never prefix matches. */
  fun markerLines(): List<String> = markerBlock().orEmpty()

  /** Requires one exact, ordered, boundary-delimited marker block. */
  fun hasAllExpectedMarkers(): Boolean = exitCode == 0 && !timedOut && markerBlock() == requiredLines

  /** The guest home is bound into the probe; a host storage path in guest output means the root is wrong. */
  fun leaksHostPath(hostPrefix: String): Boolean {
    val prefix = hostPrefix.trimEnd('/')
    if (prefix.isBlank()) return false
    val pattern = Regex("(?<![A-Za-z0-9._-])${Regex.escape(prefix)}(?![A-Za-z0-9._-])")
    return pattern.containsMatchIn(output)
  }

  fun describeMissing(): String {
    val observed = markerBlock().orEmpty()
    val missing = requiredLines.filter { marker -> !observed.contains(marker) }
    return "exitCode=$exitCode timedOut=$timedOut missing=[${missing.joinToString(",")}]"
  }
}

/** Runs the pinned non-interactive probe inside the staged rootfs. */
interface GuestProber {
  @Throws(Exception::class)
  fun probe(rootfsDir: File, guestHomeDir: File, timeoutMs: Long): GuestProbeResult
}
