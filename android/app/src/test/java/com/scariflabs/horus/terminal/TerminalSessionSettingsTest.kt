package com.scariflabs.horus.terminal

import java.nio.file.Files
import java.util.Comparator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalSessionSettingsTest {

  @Test
  fun `missing and malformed settings use the bounded default`() {
    val root = Files.createTempDirectory("horus-session-settings").toFile()
    try {
      val file = root.resolve("settings/session-settings.json")
      val settings = TerminalSessionSettings(file)
      assertEquals(TerminalSessionContract.DEFAULT_ACTIVE_SESSIONS, settings.readLimit())

      file.parentFile?.mkdirs()
      file.writeText("{\"maxConcurrentSessions\":99}")
      assertEquals(TerminalSessionContract.DEFAULT_ACTIVE_SESSIONS, settings.readLimit())

      file.writeText("{\"maxConcurrentSessions\":1.5}")
      assertEquals(TerminalSessionContract.DEFAULT_ACTIVE_SESSIONS, settings.readLimit())
    } finally {
      Files.walk(root.toPath()).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach { path -> Files.deleteIfExists(path) }
      }
    }
  }

  @Test
  fun `valid limits replace the file atomically and invalid limits are rejected`() {
    val root = Files.createTempDirectory("horus-session-settings-write").toFile()
    try {
      val settings = TerminalSessionSettings(root.resolve("settings/session-settings.json"))
      assertFalse(settings.writeLimit(TerminalSessionContract.MIN_ACTIVE_SESSIONS - 1))
      assertFalse(settings.writeLimit(TerminalSessionContract.MAX_ACTIVE_SESSIONS + 1))
      assertTrue(settings.writeLimit(TerminalSessionContract.MAX_ACTIVE_SESSIONS))
      assertEquals(TerminalSessionContract.MAX_ACTIVE_SESSIONS, settings.readLimit())
    } finally {
      Files.walk(root.toPath()).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach { path -> Files.deleteIfExists(path) }
      }
    }
  }
}
