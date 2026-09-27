package com.scariflabs.horus.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HarnessTurnTrackerTest {
  private var now = 10_000L
  private val tracker = HarnessTurnTracker(clock = { now }, quietMs = 1_000L, minTurnMs = 500L)

  private fun outputFor(sessionId: String, durationMs: Long) {
    var elapsed = 0L
    while (elapsed <= durationMs) {
      tracker.onOutput(sessionId)
      now += 100L
      elapsed += 100L
    }
  }

  @Test
  fun reportsATurnOnceOutputGoesQuietAfterWork() {
    assertTrue(tracker.onInput("s-1", "fix it\r".toByteArray()))
    assertEquals(HarnessTurnTracker.State.WORKING, tracker.state("s-1"))
    outputFor("s-1", 800L)
    assertEquals(emptyList<String>(), tracker.pollFinished())
    now += 1_000L
    assertEquals(listOf("s-1"), tracker.pollFinished())
    assertEquals(HarnessTurnTracker.State.IDLE, tracker.state("s-1"))
    assertEquals(emptyList<String>(), tracker.pollFinished())
  }

  @Test
  fun terminalRepliesAndTypingDoNotStartATurn() {
    assertFalse(tracker.onInput("s-1", "\u001b[12;4R".toByteArray()))
    assertFalse(tracker.onInput("s-1", "abc".toByteArray()))
    outputFor("s-1", 800L)
    now += 2_000L
    assertEquals(emptyList<String>(), tracker.pollFinished())
    assertFalse(tracker.hasActiveTurns())
  }

  @Test
  fun dropsBriefSubmitsWithoutReportingThem() {
    tracker.onInput("s-1", "\r".toByteArray())
    outputFor("s-1", 100L)
    now += 1_000L
    assertEquals(emptyList<String>(), tracker.pollFinished())
    assertFalse(tracker.hasActiveTurns())
  }

  @Test
  fun ongoingOutputKeepsTheTurnOpenAndSessionsAreIndependent() {
    tracker.onInput("s-1", "\r".toByteArray())
    tracker.onInput("s-2", "\r".toByteArray())
    outputFor("s-1", 2_000L)
    // s-2 produced nothing since submit: abandoned, not reported.
    assertEquals(emptyList<String>(), tracker.pollFinished())
    assertEquals(HarnessTurnTracker.State.WORKING, tracker.state("s-1"))
    assertEquals(HarnessTurnTracker.State.IDLE, tracker.state("s-2"))
    now += 1_000L
    assertEquals(listOf("s-1"), tracker.pollFinished())
  }
}
