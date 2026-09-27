package com.scariflabs.horus.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class PasswordLockoutTest {
  @Test
  fun `first five wrong passwords are free`() {
    for (failures in 0..5) assertEquals(0L, passwordLockoutMs(failures))
  }

  @Test
  fun `lockout doubles from thirty seconds and caps at fifteen minutes`() {
    assertEquals(30_000L, passwordLockoutMs(6))
    assertEquals(60_000L, passwordLockoutMs(7))
    assertEquals(120_000L, passwordLockoutMs(8))
    assertEquals(480_000L, passwordLockoutMs(10))
    assertEquals(900_000L, passwordLockoutMs(11))
    assertEquals(900_000L, passwordLockoutMs(1_000))
    assertEquals(900_000L, passwordLockoutMs(Int.MAX_VALUE))
  }
}
