package com.scariflabs.horus.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Covers the number conversion behind TerminalRuntimeModule.intField(). */
class TerminalRuntimeModuleFieldsTest {
  @Test
  fun `exact integers in Int range convert`() {
    assertEquals(24, exactIntOrNull(24.0))
    assertEquals(0, exactIntOrNull(0.0))
    assertEquals(0, exactIntOrNull(-0.0))
    assertEquals(-5, exactIntOrNull(-5.0))
    assertEquals(Int.MAX_VALUE, exactIntOrNull(Int.MAX_VALUE.toDouble()))
    assertEquals(Int.MIN_VALUE, exactIntOrNull(Int.MIN_VALUE.toDouble()))
  }

  @Test
  fun `non-finite values are rejected instead of becoming zero or saturating`() {
    assertNull(exactIntOrNull(Double.NaN))
    assertNull(exactIntOrNull(Double.POSITIVE_INFINITY))
    assertNull(exactIntOrNull(Double.NEGATIVE_INFINITY))
  }

  @Test
  fun `fractions are rejected instead of truncated`() {
    assertNull(exactIntOrNull(24.7))
    assertNull(exactIntOrNull(-0.5))
    assertNull(exactIntOrNull(Double.MIN_VALUE))
  }

  @Test
  fun `values outside Int range are rejected instead of saturated`() {
    assertNull(exactIntOrNull(Int.MAX_VALUE.toDouble() + 1.0))
    assertNull(exactIntOrNull(Int.MIN_VALUE.toDouble() - 1.0))
    assertNull(exactIntOrNull(1e12))
  }
}
