package com.scariflabs.horus.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeOutputAckBatcherTest {
  private class FakeScheduler : NativeOutputAckBatcher.Scheduler {
    private class Task(val dueAt: Long, val runnable: Runnable, var cancelled: Boolean = false)

    private var now = 0L
    private val tasks = mutableListOf<Task>()

    val pendingCount: Int
      get() = tasks.count { !it.cancelled }

    override fun schedule(delayMs: Long, task: Runnable): NativeOutputAckBatcher.Cancellable {
      val scheduled = Task(now + delayMs, task)
      tasks += scheduled
      return NativeOutputAckBatcher.Cancellable { scheduled.cancelled = true }
    }

    fun advance(ms: Long) {
      now += ms
      val due = tasks.filter { it.dueAt <= now }
      tasks.removeAll(due)
      due.filterNot { it.cancelled }.forEach { it.runnable.run() }
    }
  }

  private val scheduler = FakeScheduler()
  private val acks = mutableListOf<Pair<String, Long>>()
  private val batcher = NativeOutputAckBatcher(
    scheduler = scheduler,
    sendAck = { sessionId, seq -> acks += sessionId to seq },
    threshold = 4,
    flushDelayMs = 16L,
  )

  @Test
  fun flushesOnceTheChunkThresholdIsReached() {
    (1L..3L).forEach { batcher.record("s1", it) }
    assertTrue(acks.isEmpty())
    batcher.record("s1", 4L)
    assertEquals(listOf("s1" to 4L), acks)
    assertEquals(0, scheduler.pendingCount)
    scheduler.advance(100L)
    assertEquals(listOf("s1" to 4L), acks)
  }

  @Test
  fun timerFlushesATrailingPartialBatch() {
    (1L..6L).forEach { batcher.record("s1", it) }
    assertEquals(listOf("s1" to 4L), acks)
    scheduler.advance(15L)
    assertEquals(listOf("s1" to 4L), acks)
    scheduler.advance(1L)
    assertEquals(listOf("s1" to 4L, "s1" to 6L), acks)
    assertEquals(0, scheduler.pendingCount)
  }

  @Test
  fun acknowledgesTheHighestRecordedSeq() {
    batcher.record("s1", 5L)
    batcher.record("s1", 3L)
    batcher.record("s1", 7L)
    batcher.record("s1", 6L)
    assertEquals(listOf("s1" to 7L), acks)
    batcher.record("s1", 9L)
    batcher.record("s1", 8L)
    scheduler.advance(16L)
    assertEquals(listOf("s1" to 7L, "s1" to 9L), acks)
  }

  @Test
  fun keepsSessionsIsolated() {
    (1L..3L).forEach { batcher.record("s1", it) }
    (1L..3L).forEach { batcher.record("s2", it * 10L) }
    batcher.record("s2", 40L)
    assertEquals(listOf("s2" to 40L), acks)
    scheduler.advance(16L)
    assertEquals(listOf("s2" to 40L, "s1" to 3L), acks)
  }

  @Test
  fun clearCancelsThePendingFlush() {
    batcher.record("s1", 1L)
    batcher.record("s2", 2L)
    batcher.clear("s1")
    assertEquals(1, scheduler.pendingCount)
    scheduler.advance(16L)
    assertEquals(listOf("s2" to 2L), acks)
  }

  @Test
  fun closeCancelsEveryPendingFlushAndIgnoresLaterRecords() {
    batcher.record("s1", 1L)
    batcher.record("s2", 2L)
    batcher.close()
    assertEquals(0, scheduler.pendingCount)
    (3L..10L).forEach { batcher.record("s1", it) }
    scheduler.advance(100L)
    assertTrue(acks.isEmpty())
    assertEquals(0, scheduler.pendingCount)
  }

  @Test
  fun sendsNothingWhenNothingIsPending() {
    scheduler.advance(100L)
    batcher.clear("s1")
    assertTrue(acks.isEmpty())
    batcher.record("s1", 1L)
    scheduler.advance(16L)
    scheduler.advance(100L)
    assertEquals(listOf("s1" to 1L), acks)
    assertEquals(0, scheduler.pendingCount)
  }

  @Test
  fun staleTimerDoesNotFlushANewerBatch() {
    batcher.record("s1", 1L)
    batcher.clear("s1")
    scheduler.advance(8L)
    batcher.record("s1", 2L)
    scheduler.advance(8L)
    assertTrue(acks.isEmpty())
    scheduler.advance(8L)
    assertEquals(listOf("s1" to 2L), acks)
  }
}
