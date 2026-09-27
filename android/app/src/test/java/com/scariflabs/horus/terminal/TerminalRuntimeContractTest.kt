package com.scariflabs.horus.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit coverage for the pure Phase 0 terminal runtime contract. No Android
 * framework types are touched, matching the legacy contract-test style.
 */
class TerminalRuntimeContractTest {
  @Test
  fun `accepts only the declared runtime state`() {
    assertTrue(TerminalRuntimeContract.isValidRuntimeState(TerminalRuntimeContract.RUNTIME_STATE_NOT_INSTALLED))
    assertTrue(TerminalRuntimeContract.isValidRuntimeState(TerminalRuntimeContract.RUNTIME_STATE_READY))
    assertFalse(TerminalRuntimeContract.isValidRuntimeState(null))
    assertFalse(TerminalRuntimeContract.isValidRuntimeState(""))
    assertFalse(TerminalRuntimeContract.isValidRuntimeState("installed"))
  }

  @Test
  fun `request ids and reset scopes follow the declared alphabets`() {
    assertTrue(TerminalRuntimeContract.isValidRequestId("install-2026-09-07.1:x"))
    assertFalse(TerminalRuntimeContract.isValidRequestId(null))
    assertFalse(TerminalRuntimeContract.isValidRequestId(""))
    assertFalse(TerminalRuntimeContract.isValidRequestId("has space"))
    assertFalse(TerminalRuntimeContract.isValidRequestId("x".repeat(65)))
    assertTrue(TerminalRuntimeContract.isValidResetScope(TerminalRuntimeContract.RESET_SCOPE_ROOTFS))
    assertTrue(TerminalRuntimeContract.isValidResetScope(TerminalRuntimeContract.RESET_SCOPE_HOME))
    assertTrue(TerminalRuntimeContract.isValidResetScope(TerminalRuntimeContract.RESET_SCOPE_WORKSPACE))
    assertTrue(TerminalRuntimeContract.isValidResetScope(TerminalRuntimeContract.RESET_SCOPE_ALL_USER_DATA))
    assertFalse(TerminalRuntimeContract.isValidResetScope("unknown"))
    assertFalse(TerminalRuntimeContract.isValidResetScope(null))
    assertTrue(TerminalRuntimeContract.isValidToolchainTarget(TerminalRuntimeContract.TOOLCHAIN_TARGET_SHELL))
    assertTrue(TerminalRuntimeContract.isValidToolchainTarget(TerminalRuntimeContract.TOOLCHAIN_TARGET_GITHUB))
    assertTrue(TerminalRuntimeContract.isValidToolchainTarget(TerminalRuntimeContract.TOOLCHAIN_TARGET_CLAUDE))
    assertTrue(TerminalRuntimeContract.isValidToolchainTarget(TerminalRuntimeContract.TOOLCHAIN_TARGET_CODEX))
    assertTrue(TerminalRuntimeContract.isValidToolchainTarget(TerminalRuntimeContract.TOOLCHAIN_TARGET_OPENCODE))
    assertFalse(TerminalRuntimeContract.isValidToolchainTarget("all"))
  }

  @Test
  fun `normalizes the primary abi without trusting the input array`() {
    assertEquals("arm64-v8a", TerminalRuntimeContract.primaryAbi(arrayOf("arm64-v8a", "x86_64")))
    assertEquals("x86", TerminalRuntimeContract.primaryAbi(arrayOf("", "x86")))
    assertEquals(TerminalRuntimeContract.UNKNOWN, TerminalRuntimeContract.primaryAbi(null))
    assertEquals(TerminalRuntimeContract.UNKNOWN, TerminalRuntimeContract.primaryAbi(arrayOf("")))
  }

  @Test
  fun `computes the documented app-private storage root`() {
    assertEquals(
      "/data/user/0/com.scariflabs.horus/files/horus",
      TerminalRuntimeContract.storageRoot("/data/user/0/com.scariflabs.horus/files"),
    )
    assertEquals(TerminalRuntimeContract.UNKNOWN, TerminalRuntimeContract.storageRoot(null))
    assertEquals(TerminalRuntimeContract.UNKNOWN, TerminalRuntimeContract.storageRoot(" "))
  }

  @Test
  fun `snapshot assembly normalizes explicit inputs`() {
    val status = TerminalRuntimeContract.buildStatusSnapshot(
      supportedAbis = arrayOf("", "arm64-v8a"),
      apiLevel = 37,
      appVersion = null,
      filesDirPath = " ",
    )
    assertEquals("arm64-v8a", status.abi)
    assertEquals(37, status.apiLevel)
    assertEquals(TerminalRuntimeContract.UNKNOWN, status.appVersion)
    assertEquals(TerminalRuntimeContract.UNKNOWN, status.storageRoot)
  }

  @Test
  fun `status snapshot carries the pinned phase 0 constants`() {
    val status = TerminalRuntimeStatus(
      abi = "arm64-v8a",
      apiLevel = 35,
      appVersion = "0.0.1",
      storageRoot = "/data/user/0/com.scariflabs.horus/files/horus",
    )
    assertEquals(TerminalRuntimeContract.SCHEMA_VERSION, status.schemaVersion)
    assertEquals(TerminalRuntimeContract.RUNTIME_STATE_NOT_INSTALLED, status.runtimeState)
    assertEquals(TerminalRuntimeContract.RUNTIME_VERSION, status.runtimeVersion)
    assertEquals(4, status.schemaVersion)
    assertEquals("p2-pty", status.runtimeVersion)
    assertTrue(TerminalRuntimeContract.isValidRuntimeState(status.runtimeState))
  }
}
