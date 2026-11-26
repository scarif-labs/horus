package com.scariflabs.horus.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalViewportTest {
  @Test
  fun anchorsTheNormalScreenOnTheLastWrittenRowNotTheBlankBottom() {
    // A 45-row screen behind a 24-row keyboard-open view.
    // Output fills rows 0..14: nothing to scroll, the blank rows stay below.
    assertEquals(0.0, TerminalViewport.tailOffset(45, 24.0, 14), 0.0)
    // Output reaches row 39: scroll just far enough to show it.
    assertEquals(16.0, TerminalViewport.tailOffset(45, 24.0, 39), 0.0)
    // A cursor below the last text (a fresh prompt line) is the anchor instead.
    assertEquals(7.0, TerminalViewport.tailOffset(45, 24.0, 30), 0.0)
    // A blank screen and a full one clamp to the screen's bounds.
    assertEquals(0.0, TerminalViewport.tailOffset(45, 24.0, -1), 0.0)
    assertEquals(21.0, TerminalViewport.tailOffset(45, 24.0, 44), 0.0)
  }

  @Test
  fun showsAScreenThatFitsFromItsTopRow() {
    // After `clear` the prompt is on row 0 with blank rows below: keyboard
    // hidden or open, the view starts at the screen's top, not in scrollback.
    assertEquals(0.0, TerminalViewport.tailOffset(45, 45.0, 0), 0.0)
    assertEquals(0.0, TerminalViewport.tailOffset(45, 45.4, 0), 0.0)
    assertEquals(0.0, TerminalViewport.tailOffset(45, 24.0, 0), 0.0)
    // Output further down still wins.
    assertEquals(17.0, TerminalViewport.tailOffset(45, 24.0, 40), 0.0)
  }

  @Test
  fun keepsTheAlternateOffsetAndOnlyRevealsAHiddenPrompt() {
    // A 37-row app behind a 12-row view.
    assertEquals(0.0, TerminalViewport.alternateOffset(0.0, 37, 12.0, 4), 0.0)
    assertEquals(3.0, TerminalViewport.alternateOffset(0.0, 37, 12.0, 14), 0.0)
    assertEquals(4.0, TerminalViewport.alternateOffset(5.3, 37, 12.0, 4), 0.0)
    assertEquals(5.3, TerminalViewport.alternateOffset(5.3, 37, 12.0, 8), 0.0)
    // No cursor: stay put. A view that fits: no offset.
    assertEquals(5.3, TerminalViewport.alternateOffset(5.3, 37, 12.0, -1), 0.0)
    assertEquals(0.0, TerminalViewport.alternateOffset(5.3, 37, 40.0, 30), 0.0)
  }
}
