package com.scariflabs.horus.terminal

/**
 * A selected span of a frozen frame, from ([startRow], [startColumn]) up to but
 * not including ([endRow], [endColumn]). Rows index [NativeTerminalEngine.Frame.lines].
 */
internal data class NativeTerminalSelectionRange(
  val startRow: Int,
  val startColumn: Int,
  val endRow: Int,
  val endColumn: Int,
)

/** Word lookup and text extraction for long-press selection on the canvas. */
internal object NativeTerminalSelection {
  /** Characters that end a word besides whitespace: quotes, brackets, pipes and box drawing. */
  private const val WORD_BREAKS = "\"'`()[]{}<>|│┃─━╭╮╰╯┌┐└┘├┤┬┴┼"

  /**
   * The word around ([row], [column]): the run of non-blank cells that are not
   * [WORD_BREAKS]. A blank or break cell selects just itself. Returns null
   * when the point is outside the frame.
   */
  fun wordAt(lines: Array<NativeTerminalEngine.FrameRow>, row: Int, column: Int): NativeTerminalSelectionRange? {
    val line = lines.getOrNull(row) ?: return null
    if (line.columns == 0) return null
    var lead = column.coerceIn(0, line.columns - 1)
    // A tap on the right half of a wide glyph belongs to its lead cell.
    while (lead > 0 && line.width[lead].toInt() == 0) lead -= 1
    if (!isWordCell(line, lead)) {
      return NativeTerminalSelectionRange(row, lead, row, cellEnd(line, lead))
    }
    var start = lead
    while (start > 0) {
      var previous = start - 1
      while (previous > 0 && line.width[previous].toInt() == 0) previous -= 1
      if (!isWordCell(line, previous)) break
      start = previous
    }
    var end = cellEnd(line, lead)
    while (end < line.columns && isWordCell(line, end)) end = cellEnd(line, end)
    return NativeTerminalSelectionRange(row, start, row, end)
  }

  /**
   * The text of [range], joining soft-wrapped rows like
   * [NativeTerminalText.from]. The range is clamped to the frame.
   */
  fun text(lines: Array<NativeTerminalEngine.FrameRow>, range: NativeTerminalSelectionRange): NativeTerminalTextSnapshot {
    if (lines.isEmpty()) return NativeTerminalTextSnapshot("", false)
    val startRow = range.startRow.coerceIn(0, lines.lastIndex)
    val endRow = range.endRow.coerceIn(startRow, lines.lastIndex)
    val slices = Array(endRow - startRow + 1) { index ->
      val row = startRow + index
      val line = lines[row]
      var from = if (row == startRow) range.startColumn.coerceIn(0, line.columns) else 0
      val to = if (row == endRow) range.endColumn.coerceIn(from, line.columns) else line.columns
      // Do not start on the right half of a wide glyph.
      while (from > 0 && from < line.columns && line.width[from].toInt() == 0) from -= 1
      slice(line, from, to)
    }
    return NativeTerminalText.from(slices)
  }

  private fun isWordCell(line: NativeTerminalEngine.FrameRow, column: Int): Boolean {
    if (line.flags[column] and NativeTerminalEngine.FLAG_INVISIBLE != 0) return false
    val text = line.text[column]
    if (text.isBlank()) return false
    return !(text.length == 1 && WORD_BREAKS.indexOf(text[0]) >= 0)
  }

  /** The column after the glyph starting at [column]. */
  private fun cellEnd(line: NativeTerminalEngine.FrameRow, column: Int): Int =
    (column + line.width[column].toInt().coerceAtLeast(1)).coerceAtMost(line.columns)

  private fun slice(line: NativeTerminalEngine.FrameRow, from: Int, to: Int): NativeTerminalEngine.FrameRow =
    NativeTerminalEngine.FrameRow(
      text = line.text.copyOfRange(from, to),
      width = line.width.copyOfRange(from, to),
      foreground = line.foreground.copyOfRange(from, to),
      background = line.background.copyOfRange(from, to),
      flags = line.flags.copyOfRange(from, to),
      wrapped = line.wrapped,
      wrapPadding = line.wrapPadding,
    )
}
