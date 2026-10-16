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
  /** The catalog entry's marker block; the first and last lines delimit it. */
  val requiredLines: List<String> = RootfsCatalog.DEFAULT.probeMarkers,
) {

  private fun trimmedLines(): List<String> = output.lineSequence().map { it.trim() }.toList()

  private fun markerBlock(): List<String>? {
    val lines = trimmedLines()
    val begin = lines.indexOf(requiredLines.first())
    if (begin < 0) return null
    val endOffset = lines.drop(begin + 1).indexOf(requiredLines.last())
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

/** Runs [entry]'s pinned non-interactive probe inside the staged rootfs. */
interface GuestProber {
  @Throws(Exception::class)
  fun probe(rootfsDir: File, guestHomeDir: File, timeoutMs: Long, entry: RootfsCatalogEntry): GuestProbeResult
}
