package com.scariflabs.horus.terminal

import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalDebugLogTest {
  @Test
  fun persistsStructuredEventsAndSanitizesLineBreaks() {
    val root = Files.createTempDirectory("horus-debug-log").toFile()
    try {
      val file = root.resolve("diagnostics/terminal-debug.log")
      assertTrue(TerminalDebugLog.appendForTest(file, "service_request request=x\nsecret", 123L, 456))
      val text = file.readText()
      assertTrue(text.contains("ts=123 pid=456 event=service_request request=x_secret"))
      assertFalse(text.contains("\nsecret"))
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun rotatesWhenTheBoundedJournalGrowsPastItsLimit() {
    val root = Files.createTempDirectory("horus-debug-log-cap").toFile()
    try {
      val file = root.resolve("terminal-debug.log")
      repeat(7000) { index ->
        assertTrue(TerminalDebugLog.appendForTest(file, "event_$index", index.toLong(), 1))
      }
      assertTrue(file.length() <= TerminalDebugLog.MAX_BYTES)
      assertTrue(file.readText().contains("event_6999"))
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun asynchronousRecordsAreDrainedBeforeTeardown() {
    val root = Files.createTempDirectory("horus-debug-log-async").toFile()
    try {
      val file = root.resolve("diagnostics/terminal-debug.log")
      assertTrue(TerminalDebugLog.recordForTest(file, "async_event", 789L, 123))
      assertTrue(TerminalDebugLog.awaitIdleForTest())
      assertTrue(file.readText().contains("ts=789 pid=123 event=async_event"))
    } finally {
      // Keep the test's pending writer work from leaking into the next test.
      assertTrue(TerminalDebugLog.awaitIdleForTest())
      root.deleteRecursively()
    }
  }
}
