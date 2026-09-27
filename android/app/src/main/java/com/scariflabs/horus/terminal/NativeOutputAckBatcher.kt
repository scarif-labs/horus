package com.scariflabs.horus.terminal

/**
 * Coalesces native-path output acknowledgements so the UI process does not
 * make one service IPC per PTY chunk. Service acknowledgements are cumulative
 * (an ack for seq N releases every chunk up to N), so each flush sends only
 * the highest seq recorded for a session.
 *
 * A session flushes when [threshold] chunks are pending, or [flushDelayMs]
 * after its first pending chunk so the tail of a burst is always released.
 * Callers record a seq only after the chunk has been handed to the engine.
 */
internal class NativeOutputAckBatcher(
  private val scheduler: Scheduler,
  private val sendAck: (sessionId: String, seq: Long) -> Unit,
  private val threshold: Int = ACK_BATCH_CHUNKS,
  private val flushDelayMs: Long = ACK_FLUSH_DELAY_MS,
) {
  fun interface Scheduler {
    fun schedule(delayMs: Long, task: Runnable): Cancellable
  }

  fun interface Cancellable {
    fun cancel()
  }

  private class Pending(var highestSeq: Long) {
    var count = 0
    var flush: Cancellable? = null
  }

  private val lock = Any()
  private val pending = HashMap<String, Pending>()
  private var closed = false

  fun record(sessionId: String, seq: Long) {
    var flushSeq = 0L
    synchronized(lock) {
      if (closed) return
      val entry = pending[sessionId] ?: Pending(seq).also { created ->
        pending[sessionId] = created
        created.flush = scheduler.schedule(flushDelayMs) { flushIfCurrent(sessionId, created) }
      }
      entry.highestSeq = maxOf(entry.highestSeq, seq)
      entry.count += 1
      if (entry.count < threshold) return
      pending.remove(sessionId)
      entry.flush?.cancel()
      flushSeq = entry.highestSeq
    }
    sendAck(sessionId, flushSeq)
  }

  /** Drops pending state for an exited or stopped session without acking. */
  fun clear(sessionId: String) {
    synchronized(lock) { pending.remove(sessionId) }?.flush?.cancel()
  }

  /** Cancels every pending flush and ignores later records. */
  fun close() {
    val entries = synchronized(lock) {
      closed = true
      pending.values.toList().also { pending.clear() }
    }
    entries.forEach { it.flush?.cancel() }
  }

  private fun flushIfCurrent(sessionId: String, entry: Pending) {
    synchronized(lock) {
      // A threshold flush or clear may have replaced this entry while the
      // timer was already running; only the entry that armed it may flush.
      if (closed || pending[sessionId] !== entry) return
      pending.remove(sessionId)
    }
    sendAck(sessionId, entry.highestSeq)
  }

  companion object {
    // Well below TerminalSessionSupervisor.OUTPUT_ACK_WINDOW (32) so the PTY
    // reader keeps streaming while a batch ack is still in flight.
    const val ACK_BATCH_CHUNKS = 8
    // About one frame; far below the reader's 100 ms credit wait and the
    // service's 750 ms subscriber idle lease.
    const val ACK_FLUSH_DELAY_MS = 16L
  }
}
