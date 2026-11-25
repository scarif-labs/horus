package com.scariflabs.horus.terminal

/**
 * Where the terminal view sits over the active screen while it follows the
 * output. The PTY keeps the hidden-keyboard row count, so with the keyboard
 * open the view is shorter than the screen and must pick which rows to show.
 * Offsets are rows from the top of the active screen; rows are relative to it.
 */
internal object TerminalViewport {
  /**
   * Normal screen: show the last written row (or the cursor, if lower) at the
   * bottom edge rather than the blank rows under it. A screen that fits is
   * shown from its top row, so after `clear` scrollback is not pulled back in.
   * [anchorRow] is that row, or -1 when unknown.
   */
  fun tailOffset(rows: Int, visibleRows: Double, anchorRow: Int): Double {
    if (rows <= 0 || visibleRows >= rows) return 0.0
    val anchor = anchorRow.coerceIn(0, rows - 1)
    return (anchor + 1 - visibleRows).coerceIn(0.0, rows - visibleRows)
  }

  /**
   * Alternate screen: keep [previous] and move only far enough to keep the
   * app's cursor (its prompt) visible. [cursorRow] is -1 when unknown.
   */
  fun alternateOffset(previous: Double, rows: Int, visibleRows: Double, cursorRow: Int): Double {
    if (rows <= 0 || visibleRows >= rows) return 0.0
    var offset = previous
    if (cursorRow >= 0) {
      if (cursorRow < offset) offset = cursorRow.toDouble()
      else if (cursorRow + 1 > offset + visibleRows) offset = cursorRow + 1 - visibleRows
    }
    return offset.coerceIn(0.0, rows - visibleRows)
  }
}
