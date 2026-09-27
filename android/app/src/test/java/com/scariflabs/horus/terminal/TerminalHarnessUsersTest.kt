package com.scariflabs.horus.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalHarnessUsersTest {
  @Test
  fun `each provider has a stable user and private home with the shared workspace group`() {
    val users = listOf(
      TerminalHarnessUsers.forTarget(TerminalRuntimeContract.TOOLCHAIN_TARGET_CLAUDE, "profile"),
      TerminalHarnessUsers.forTarget(TerminalRuntimeContract.TOOLCHAIN_TARGET_CODEX, "profile"),
      TerminalHarnessUsers.forTarget(TerminalRuntimeContract.TOOLCHAIN_TARGET_OPENCODE, "profile"),
    ).map { requireNotNull(it) }

    assertEquals(listOf("harness_claude", "harness_codex", "harness_opencode"), users.map { it.username })
    assertEquals(listOf("claude", "codex", "opencode"), users.map { it.homeKey })
    assertEquals(listOf(61_001, 61_002, 61_003), users.map { it.uid })
    assertTrue(users.all { it.gid == TerminalHarnessUsers.SHARED_WORKSPACE_GID })
    assertEquals(users.size, users.map { it.uid }.distinct().size)
  }

  @Test
  fun `shell targets keep the profile identity and profile usernames cannot collide`() {
    assertNull(TerminalHarnessUsers.forTarget(TerminalRuntimeContract.TOOLCHAIN_TARGET_SHELL, "profile"))

    val user = requireNotNull(
      TerminalHarnessUsers.forTarget(TerminalRuntimeContract.TOOLCHAIN_TARGET_CODEX, "harness_codex"),
    )
    assertNotEquals("harness_codex", user.username)
    assertEquals(61_002, user.uid)
  }
}
