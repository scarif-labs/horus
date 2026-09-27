package com.scariflabs.horus.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HarnessStartupRedrawTest {
  private var now = 1_000L
  private val redraw = HarnessStartupRedraw(clock = { now }, settleMs = 500L, fallbackMs = 10_000L)

  private fun out(sessionId: String, text: String) = redraw.onOutput(sessionId, text.toByteArray(Charsets.ISO_8859_1))

  @Test
  fun waitsForTheTuiToEnableBracketedPasteAndSettle() {
    out("s-1", "HORUS_TOOLCHAIN_READY\r\nHORUS_INSTALL_HANDOFF=harness_claude\r\n")
    now += 2_000L
    // The handoff alone, or silence while the binary boots, is not enough.
    assertEquals(emptyList<String>(), redraw.pollDue())
    out("s-1", "\u001b[?25l\u001b[?2004h")
    out("s-1", "frame")
    now += 400L
    assertEquals(emptyList<String>(), redraw.pollDue())
    now += 100L
    assertEquals(listOf("s-1"), redraw.pollDue())
    // Only once per session.
    out("s-1", "\u001b[?2004h more")
    now += 1_000L
    assertEquals(emptyList<String>(), redraw.pollDue())
    assertFalse(redraw.hasPending())
  }

  @Test
  fun findsMarkersSplitAcrossChunks() {
    out("s-1", "HORUS_INSTALL_HAN")
    out("s-1", "DOFF=harness_codex\r\n\u001b[?20")
    out("s-1", "04h")
    assertTrue(redraw.hasPending())
    now += 500L
    assertEquals(listOf("s-1"), redraw.pollDue())
  }

  @Test
  fun ignoresOutputBeforeTheHandoffAndFallsBackAfterTheDeadline() {
    out("s-1", "\u001b[?2004h installer output")
    assertFalse(redraw.hasPending())
    out("s-1", "HORUS_INSTALL_HANDOFF=harness_opencode\r\n")
    now += 9_999L
    assertEquals(emptyList<String>(), redraw.pollDue())
    now += 1L
    assertEquals(listOf("s-1"), redraw.pollDue())
  }
}
