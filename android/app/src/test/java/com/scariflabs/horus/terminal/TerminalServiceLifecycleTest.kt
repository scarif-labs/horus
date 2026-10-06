package com.scariflabs.horus.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalServiceLifecycleTest {
  @Test fun idleAndEmptySystemRestartDoNotJustifyForegroundOrStickyLife() {
    assertFalse(TerminalServiceLifecycle.Work().durable)
  }

  @Test fun activeOrPersistedSessionsRetainTheService() {
    assertTrue(TerminalServiceLifecycle.Work(sessions = true).durable)
  }

  @Test fun provisioningRetainsTheServiceWithoutSessions() {
    assertTrue(TerminalServiceLifecycle.Work(provisioning = true).durable)
  }

  @Test fun enabledRemoteAccessRetainsTheServiceIncludingServerRestartBackoff() {
    assertTrue(TerminalServiceLifecycle.Work(remoteAccess = true).durable)
  }

  @Test fun finalSessionStopsOnlyAfterAllOtherDurableWorkEnds() {
    val active = TerminalServiceLifecycle.Work(sessions = true, remoteAccess = true)
    assertTrue(active.copy(sessions = false).durable)
    assertFalse(active.copy(sessions = false, remoteAccess = false).durable)
  }

  @Test fun newSessionAfterIdleCanRetainTheServiceAgain() {
    val idle = TerminalServiceLifecycle.Work()
    assertFalse(idle.durable)
    assertTrue(idle.copy(sessions = true).durable)
    assertFalse(idle.copy(sessions = true).copy(sessions = false).durable)
  }
}
