package com.scariflabs.horus.terminal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class BoundedSessionOutputHistoryTest {

  @Test
  fun `history keeps a bounded ordered copy of output`() {
    val history = BoundedSessionOutputHistory(maxChunks = 2, maxBytes = 5)
    val first = byteArrayOf(1, 2)
    history.add(1L, first)
    first[0] = 9
    history.add(2L, byteArrayOf(3, 4))
    history.add(3L, byteArrayOf(5))

    val events = history.snapshot()
    assertEquals(listOf(2L, 3L), events.map { it.seq })
    assertArrayEquals(byteArrayOf(3, 4), events[0].bytes)
    events[0].bytes[0] = 8
    assertArrayEquals(byteArrayOf(3, 4), history.snapshot()[0].bytes)
  }

  @Test
  fun `default replay retains the full terminal transcript through 174 output chunks`() {
    val history = BoundedSessionOutputHistory()
    repeat(174) { index ->
      history.add(index + 1L, ByteArray(512) { index.toByte() })
    }

    val events = history.snapshot()
    assertEquals(174, events.size)
    assertEquals(1L, events.first().seq)
    assertEquals(174L, events.last().seq)
  }
}
