package com.scariflabs.horus.terminal

import java.nio.file.Files
import java.util.Comparator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalSessionJournalTest {

  @Test
  fun `journal round trips bounded launcher metadata and removes records`() {
    val root = Files.createTempDirectory("horus-session-journal").toFile()
    try {
      val file = root.resolve("sessions/active-sessions.json")
      val journal = TerminalSessionJournal(file)
      val record = TerminalSessionJournal.Record(
        sessionId = "s-1",
        rows = 24,
        columns = 80,
        command = "gh auth login",
        startedAtMs = 123L,
        toolchainTarget = TerminalRuntimeContract.TOOLCHAIN_TARGET_GITHUB,
      )

      journal.upsert(record)
      assertEquals(listOf(record), journal.read())

      journal.upsert(record.copy(columns = 100))
      assertEquals(100, journal.read().single().columns)
      journal.remove(record.sessionId)
      assertTrue(journal.read().isEmpty())
      assertTrue(!file.exists())
    } finally {
      Files.walk(root.toPath()).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach { path -> Files.deleteIfExists(path) }
      }
    }
  }

  @Test
  fun `journal ignores malformed and over-capacity records`() {
    val root = Files.createTempDirectory("horus-session-journal-invalid").toFile()
    try {
      val file = root.resolve("active-sessions.json")
      file.parentFile?.mkdirs()
      file.writeText("{\"schemaVersion\":1,\"sessions\":[{\"sessionId\":\"../bad\"}]}")
      assertTrue(TerminalSessionJournal(file).read().isEmpty())

      val journal = TerminalSessionJournal(root.resolve("capacity.json"))
      repeat(TerminalSessionContract.MAX_ACTIVE_SESSIONS) { index ->
        assertEquals(
          TerminalSessionJournal.UpsertOutcome.Stored,
          journal.upsert(
            TerminalSessionJournal.Record("s-$index", 24, 80, null, index.toLong()),
          ),
        )
      }
      val atCapacity = journal.read()
      assertEquals(
        TerminalSessionJournal.UpsertOutcome.CapacityReached(
          activeCount = TerminalSessionContract.MAX_ACTIVE_SESSIONS,
          limit = TerminalSessionContract.MAX_ACTIVE_SESSIONS,
        ),
        journal.upsert(TerminalSessionJournal.Record("s-extra", 24, 80, null, 5L)),
      )
      assertEquals("capacity rejection preserves existing records", atCapacity, journal.read())

      val updated = atCapacity.first().copy(columns = 100)
      assertEquals(TerminalSessionJournal.UpsertOutcome.Stored, journal.upsert(updated))
      assertEquals(updated, journal.read().first())
      assertEquals(TerminalSessionContract.MAX_ACTIVE_SESSIONS, journal.read().size)
    } finally {
      Files.walk(root.toPath()).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach { path -> Files.deleteIfExists(path) }
      }
    }
  }

  @Test
  fun `github utility record does not consume the user session capacity`() {
    val root = Files.createTempDirectory("horus-session-journal-github").toFile()
    try {
      val journal = TerminalSessionJournal(root.resolve("capacity.json"))
      repeat(TerminalSessionContract.MAX_ACTIVE_SESSIONS) { index ->
        assertEquals(
          TerminalSessionJournal.UpsertOutcome.Stored,
          journal.upsert(TerminalSessionJournal.Record("s-user-$index", 24, 80, null, index.toLong())),
        )
      }
      assertEquals(
        TerminalSessionJournal.UpsertOutcome.CapacityReached(
          activeCount = TerminalSessionContract.MAX_ACTIVE_SESSIONS,
          limit = TerminalSessionContract.MAX_ACTIVE_SESSIONS,
        ),
        journal.upsert(
          TerminalSessionJournal.Record(
            "s-user-extra",
            24,
            80,
            null,
            5L,
            TerminalRuntimeContract.TOOLCHAIN_TARGET_CODEX,
          ),
        ),
      )
      assertEquals(
        TerminalSessionJournal.UpsertOutcome.Stored,
        journal.upsert(
          TerminalSessionJournal.Record(
            "s-github",
            24,
            80,
            "gh api user",
            6L,
            TerminalRuntimeContract.TOOLCHAIN_TARGET_GITHUB,
          ),
        ),
      )
      assertEquals(5, journal.read().size)
    } finally {
      Files.walk(root.toPath()).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach { path -> Files.deleteIfExists(path) }
      }
    }
  }

  @Test
  fun `journal ignores an oversized file before parsing it`() {
    val root = Files.createTempDirectory("horus-session-journal-large").toFile()
    try {
      val file = root.resolve("active-sessions.json")
      file.writeText("{" + "x".repeat(32 * 1024) + "}")
      assertTrue(TerminalSessionJournal(file).read().isEmpty())
    } finally {
      Files.walk(root.toPath()).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach { path -> Files.deleteIfExists(path) }
      }
    }
  }
}
