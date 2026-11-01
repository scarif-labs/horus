package com.scariflabs.horus.terminal

/** Text copied from a terminal frame, bounded to keep selection memory use predictable. */
internal data class NativeTerminalTextSnapshot(val text: String, val truncated: Boolean)

internal object NativeTerminalText {
  const val MAX_CHARACTERS = 262_144

  /**
   * Joins soft-wrapped rows and separates hard line breaks. The frame already
   * contains normal scrollback followed by the active screen, in display order.
   */
  fun from(lines: Array<NativeTerminalEngine.FrameRow>): NativeTerminalTextSnapshot {
    val result = StringBuilder(MAX_CHARACTERS)
    var truncated = false
    lines.forEachIndexed { index, line ->
      if (index > 0 && !line.wrapped) result.append('\n')
      val row = StringBuilder(line.columns)
      for (column in 0 until line.columns) {
        val width = line.width[column].toInt()
        if (width == 0) continue
        if (line.flags[column] and NativeTerminalEngine.FLAG_INVISIBLE != 0) {
          repeat(width.coerceAtLeast(1)) { row.append(' ') }
        } else {
          row.append(line.text[column].ifEmpty { " " })
        }
      }
      // Spaces at the end of a soft-wrapped row are part of the same line.
      val next = lines.getOrNull(index + 1)
      if (next?.wrapped == true && next.wrapPadding && row.endsWith(" ")) row.setLength(row.length - 1)
      result.append(if (next?.wrapped == true) row.toString() else row.toString().trimEnd())
      while (result.length > MAX_CHARACTERS) {
        val desiredStart = result.length - MAX_CHARACTERS
        val nextLine = result.indexOf("\n", desiredStart)
        val removeThrough = if (nextLine >= 0 && nextLine + 1 < result.length) {
          nextLine + 1
        } else {
          (desiredStart + if (Character.isLowSurrogate(result[desiredStart])) 1 else 0).coerceAtMost(result.length)
        }
        result.delete(0, removeThrough)
        truncated = true
      }
    }
    while (result.isNotEmpty() && result.last() == '\n') result.setLength(result.length - 1)
    return NativeTerminalTextSnapshot(result.toString(), truncated)
  }
}
