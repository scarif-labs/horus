package com.scariflabs.horus.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionUnlockGrantTest {
  private var now = 1_000L
  private val grant = SessionUnlockGrant(timeoutMs = 100L) { now }

  @Test
  fun startsLockedAndResumeDoesNotUnlock() {
    assertFalse(grant.apply(SessionUnlockGrant.OP_RESUME))
    assertFalse(grant.apply(SessionUnlockGrant.OP_BACKGROUND))
    assertFalse(grant.apply(SessionUnlockGrant.OP_RESUME))
  }

  @Test
  fun survivesShortBackgroundAndExpiresAfterTimeout() {
    assertTrue(grant.apply(SessionUnlockGrant.OP_GRANT))
    grant.apply(SessionUnlockGrant.OP_BACKGROUND)
    now += 99L
    assertTrue(grant.apply(SessionUnlockGrant.OP_RESUME))

    grant.apply(SessionUnlockGrant.OP_BACKGROUND)
    now += 50L
    // A repeated background report keeps the first timestamp.
    grant.apply(SessionUnlockGrant.OP_BACKGROUND)
    now += 50L
    assertFalse(grant.apply(SessionUnlockGrant.OP_RESUME))
    assertFalse(grant.apply(SessionUnlockGrant.OP_RESUME))
  }

  @Test
  fun revokeLocksImmediately() {
    grant.apply(SessionUnlockGrant.OP_GRANT)
    assertFalse(grant.apply(SessionUnlockGrant.OP_REVOKE))
    assertFalse(grant.apply(SessionUnlockGrant.OP_RESUME))
  }

  @Test
  fun validatesOps() {
    assertTrue(SessionUnlockGrant.isValidOp("grant"))
    assertFalse(SessionUnlockGrant.isValidOp("query"))
    assertFalse(SessionUnlockGrant.isValidOp(null))
  }
}
