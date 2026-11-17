package com.scariflabs.horus.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvisionGateTest {
  @Test fun firstRequestStartsTheInstall() {
    val gate = ProvisionGate<String>()
    assertEquals(ProvisionGate.Admission.STARTED, gate.admit("shell", "first"))
    assertTrue(gate.busy)
  }

  @Test fun repeatRequestForTheRunningTargetWaitsForItsResult() {
    // Onboarding asks again after Android restarts the UI mid-install.
    val gate = ProvisionGate<String>()
    gate.admit("shell", "before-restart")
    assertEquals(ProvisionGate.Admission.JOINED, gate.admit("shell", "after-restart"))
    assertEquals(ProvisionGate.Admission.JOINED, gate.admit("shell", "try-again"))
    assertEquals(listOf("before-restart", "after-restart", "try-again"), gate.finish())
    assertFalse(gate.busy)
  }

  @Test fun requestForAnotherTargetIsRefusedAndNotAnswered() {
    val gate = ProvisionGate<String>()
    gate.admit("shell", "shell")
    assertEquals(ProvisionGate.Admission.BUSY, gate.admit("codex", "codex"))
    assertEquals(listOf("shell"), gate.finish())
  }

  @Test fun finishedInstallFreesTheGateForTheNextOne() {
    val gate = ProvisionGate<String>()
    gate.admit("shell", "first")
    gate.finish()
    assertEquals(ProvisionGate.Admission.STARTED, gate.admit("codex", "second"))
    assertEquals(listOf("second"), gate.finish())
  }

  @Test fun appOutwaitsTheInstallAndItsTeardown() {
    // The service stops a stuck install after RUN_TIMEOUT_MS, then waits up to
    // 2 s for exit and 2 s after a forced kill, plus about 1 s of output drain.
    val teardownMs = 2 * 2_000L + 1_000L
    assertTrue(ProvisionGate.REPLY_TIMEOUT_MS > ProvisionGate.RUN_TIMEOUT_MS + teardownMs)
  }
}
