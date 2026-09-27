package com.scariflabs.horus.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 2 session contract rules, mirrored with the JS side. */
class TerminalSessionContractTest {

  @Test
  fun sessionIdsFollowTheNativePattern() {
    assertTrue(TerminalSessionContract.isValidSessionId("s-1694169600-1"))
    assertTrue(TerminalSessionContract.isValidSessionId("s-1"))
    assertFalse(TerminalSessionContract.isValidSessionId("s"))
    assertFalse(TerminalSessionContract.isValidSessionId("../escape"))
    assertFalse(TerminalSessionContract.isValidSessionId("/absolute"))
    assertFalse(TerminalSessionContract.isValidSessionId("UPPER"))
    assertFalse(TerminalSessionContract.isValidSessionId("has space"))
    assertFalse(TerminalSessionContract.isValidSessionId("x".repeat(65)))
    assertFalse(TerminalSessionContract.isValidSessionId(null))
  }

  @Test
  fun rowsAndColumnsStayInsideTheBoundedWindow() {
    assertTrue(TerminalSessionContract.isValidRows(2))
    assertTrue(TerminalSessionContract.isValidRows(250))
    assertFalse(TerminalSessionContract.isValidRows(1))
    assertFalse(TerminalSessionContract.isValidRows(251))
    assertTrue(TerminalSessionContract.isValidColumns(2))
    assertTrue(TerminalSessionContract.isValidColumns(500))
    assertFalse(TerminalSessionContract.isValidColumns(501))
  }

  @Test
  fun stopReasonsAreBoundedMachineStrings() {
    assertTrue(TerminalSessionContract.isValidStopReason("user_stop"))
    assertTrue(TerminalSessionContract.isValidStopReason("bridge_invalidated"))
    assertFalse(TerminalSessionContract.isValidStopReason("User Stop"))
    assertFalse(TerminalSessionContract.isValidStopReason(""))
    assertFalse(TerminalSessionContract.isValidStopReason("x".repeat(65)))
    assertFalse(TerminalSessionContract.isValidStopReason(null))
  }

  @Test
  fun onlyTheDeliverableSignalsHaveNumbers() {
    assertEquals(2, TerminalSessionContract.signalNumber("sigint"))
    assertEquals(15, TerminalSessionContract.signalNumber("sigterm"))
    assertEquals(9, TerminalSessionContract.signalNumber("sigkill"))
    assertNull(TerminalSessionContract.signalNumber("sigstop"))
    assertNull(TerminalSessionContract.signalNumber(null))
  }

  @Test
  fun exitStatusDecodingCoversNormalExitsSignalDeathsAndGarbage() {
    assertEquals(
      TerminalSessionContract.ExitStatus(exitCode = 0, signal = null),
      TerminalSessionContract.decodeExitStatus(0),
    )
    assertEquals(
      TerminalSessionContract.ExitStatus(exitCode = 130, signal = null),
      TerminalSessionContract.decodeExitStatus(130),
    )
    assertEquals(
      TerminalSessionContract.ExitStatus(exitCode = null, signal = "sigkill"),
      TerminalSessionContract.decodeExitStatus(-9),
    )
    assertEquals(
      TerminalSessionContract.ExitStatus(exitCode = null, signal = "sigint"),
      TerminalSessionContract.decodeExitStatus(-2),
    )
    // Signal numbers outside the named table and errno-like failures cannot
    // be decoded; the supervisor records them as undecodable, never guessed.
    assertNull(TerminalSessionContract.decodeExitStatus(-42))
    assertNull(TerminalSessionContract.decodeExitStatus(256))
    assertNull(TerminalSessionContract.decodeExitStatus(-TerminalSessionContract.WAIT_ERROR_BASE - 10))
    assertNotNull(TerminalSessionContract.decodeExitStatus(255))
  }

  @Test
  fun inputBoundsAgreeBetweenBytesAndBase64() {
    assertEquals(16 * 1024, TerminalSessionContract.MAX_INPUT_BYTES)
    assertTrue(TerminalSessionContract.MAX_INPUT_BASE64_CHARS >= TerminalSessionContract.MAX_INPUT_BYTES / 3 * 4)
    assertTrue(TerminalSessionContract.MAX_INPUT_BASE64_CHARS < 24_000)
  }
}
