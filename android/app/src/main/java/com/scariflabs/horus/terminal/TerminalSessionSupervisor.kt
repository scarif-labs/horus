package com.scariflabs.horus.terminal

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Native PTY session supervision. One supervisor owns
 * every guest process started through this app: it spawns the launcher's
 * fixed argv on a real controlling terminal, drains the pty master in order,
 * reaps the direct child exactly once, and stops the complete process group
 * with bounded TERM/KILL escalation.
 *
 * Guarantees:
 *  - output chunks are emitted in read order with a per-session monotonic
 *    sequence number from a single reader thread; a bounded bridge queue may
 *    apply backpressure to the reader, while the diagnostic mirror remains
 *    bounded;
 *  - exactly one exit event per session, whichever of the reaper, a stop, or
 *    a shutdown observes the death first;
 *  - a stop returns a teardown observation naming what remains alive, and a
 *    session is only reported clean when nothing in its session id survives;
 *  - every wait is bounded; nothing here can block indefinitely.
 *
 * Lifecycle policy (recorded in the phase evidence): a backgrounded app keeps
 * its sessions while Android keeps the process; bridge invalidation or app
 * destruction runs a bounded shutdownAll; survival across process death is
 * never promised.
 */
class TerminalSessionSupervisor(
  private val backend: PtyBackend,
  private val listener: EventListener,
  private val processTree: ProcessTree = ProcessTree(),
  private val sendSignal: (pid: Int, signal: Int) -> Unit = { pid, signal ->
    android.system.Os.kill(pid, signal)
  },
  private val delayMs: (Long) -> Unit = Thread::sleep,
  private val clock: () -> Long = System::currentTimeMillis,
  private val maxActiveSessions: () -> Int = { TerminalSessionContract.DEFAULT_ACTIVE_SESSIONS },
  private val shouldBlockOutput: (String) -> Boolean = { true },
) {

  interface EventListener {
    /** Called from the session's reader thread in strict read order. */
    fun onSessionOutput(sessionId: String, seq: Long, chunk: ByteArray)

    /** Called at most once per session from whichever thread finishes it. */
    fun onSessionExit(info: SessionExitInfo)
  }

  data class StartSpec(
    val argv: List<String>,
    val environment: List<String>,
    val workingDirectory: File? = null,
    val rows: Int,
    val columns: Int,
    /** Background utility PTYs such as GitHub queries do not consume a user slot. */
    val countsAgainstSessionLimit: Boolean = true,
  )

  data class SessionExitInfo(
    val sessionId: String,
    val exitCode: Int?,
    val signal: String?,
    /** Who asked for the end: "process_exit" or the stop reason. */
    val reason: String,
    val waitStatusUndecodable: Boolean,
  )

  enum class SessionState { RUNNING, EXITED }

  data class SessionHandle(
    val sessionId: String,
    val pid: Int,
    val startedAtMs: Long,
  )

  data class SessionSnapshot(
    val sessionId: String,
    val state: SessionState,
    val pid: Int,
    val startedAtMs: Long,
    val totalOutputBytes: Long,
    val lastEmittedSeq: Long,
    val exit: SessionExitInfo?,
  )

  data class OutputAcknowledgement(
    val acknowledgedSeq: Long,
    val outstandingChunks: Int,
  )

  sealed interface AcknowledgeOutcome {
    data class Acknowledged(val value: OutputAcknowledgement) : AcknowledgeOutcome
    data class Failure(val reasonCode: String, val detail: String) : AcknowledgeOutcome
  }

  data class StopObservation(
    val sessionId: String,
    val exit: SessionExitInfo?,
    val remainingProcessCount: Int,
    val stoppedWithinDeadline: Boolean,
  )

  sealed interface StartOutcome {
    data class Success(val handle: SessionHandle) : StartOutcome
    data class Failure(val reasonCode: String, val detail: String) : StartOutcome
  }

  sealed interface WriteOutcome {
    data class Success(val bytesWritten: Int) : WriteOutcome
    data class Failure(val reasonCode: String, val detail: String) : WriteOutcome
  }

  /** Shared result for the idempotent single-shot operations. */
  enum class SessionOpOutcome { APPLIED, SESSION_NOT_FOUND, SESSION_EXITED, FAILED }

  sealed interface StopOutcome {
    data class Stopped(val observation: StopObservation) : StopOutcome
    data class Failure(val reasonCode: String, val detail: String) : StopOutcome
  }

  private val registryLock = Any()
  private val activeSessions = ConcurrentHashMap<String, LiveSession>()
  private val exitedSessions = LinkedHashMap<String, LiveSession>()
  private val sessionCounter = AtomicLong(0)

  fun start(sessionId: String, spec: StartSpec): StartOutcome {
    if (!TerminalSessionContract.isValidSessionId(sessionId)) {
      return StartOutcome.Failure("invalid_session_id", "session id does not match the contract pattern")
    }
    if (!TerminalSessionContract.isValidRows(spec.rows) ||
      !TerminalSessionContract.isValidColumns(spec.columns)
    ) {
      return StartOutcome.Failure("invalid_request", "rows/columns out of bounds")
    }
    synchronized(registryLock) {
      val limit = configuredSessionLimit()
      val activeCount = activeSessions.values.count { it.countsAgainstSessionLimit }
      if (spec.countsAgainstSessionLimit && activeCount >= limit) {
        return StartOutcome.Failure("session_limit_reached", "active=$activeCount limit=$limit")
      }
      if (activeSessions.containsKey(sessionId) || exitedSessions.containsKey(sessionId)) {
        return StartOutcome.Failure("session_id_in_use", "session id already exists")
      }
      // Keep admission and spawn in one critical section. A second caller
      // cannot pass the limit/duplicate checks while this child is being
      // created, so a rejected child never needs an unowned kill-and-reap
      // fallback.
      val spawned = try {
        backend.createSubprocess(spec.argv, spec.workingDirectory, spec.environment, spec.rows, spec.columns)
      } catch (error: Exception) {
        return StartOutcome.Failure("spawn_failed", error.message ?: "pty spawn failure")
      }
      val session = LiveSession(
        id = sessionId,
        pid = spawned.pid,
        masterFd = spawned.masterFd,
        startedAtMs = clock(),
        countsAgainstSessionLimit = spec.countsAgainstSessionLimit,
        initialRows = spec.rows,
        initialColumns = spec.columns,
      )
      activeSessions[sessionId] = session
      session.reader.start()
      session.reaper.start()
      return StartOutcome.Success(SessionHandle(sessionId = sessionId, pid = spawned.pid, startedAtMs = session.startedAtMs))
    }
  }

  fun write(sessionId: String, bytes: ByteArray): WriteOutcome {
    if (!TerminalSessionContract.isValidSessionId(sessionId)) {
      return WriteOutcome.Failure("invalid_session_id", "session id does not match the contract pattern")
    }
    if (bytes.isEmpty()) return WriteOutcome.Success(0)
    val session = activeSessions[sessionId]
      ?: return lookupExited(sessionId)?.let {
        WriteOutcome.Failure("session_exited", "session already exited")
      } ?: WriteOutcome.Failure("session_not_found", "no such session")
    var written = 0
    val deadline = clock() + TerminalSessionContract.WRITE_DEADLINE_MS
    synchronized(session.writeLock) {
      while (written < bytes.size) {
        if (session.exitEmitted.get()) {
          return WriteOutcome.Failure("session_exited", "session ended during write")
        }
        val accepted = try {
          backend.write(session.masterFd, bytes, written, bytes.size - written)
        } catch (error: Exception) {
          return WriteOutcome.Failure("write_failed", error.message ?: "pty master write failed")
        }
        if (accepted < 0) {
          return WriteOutcome.Failure("write_failed", "pty master accepted no bytes")
        }
        if (accepted == 0) {
          if (clock() >= deadline) {
            return WriteOutcome.Failure("write_timeout", "pty master remained full")
          }
          delayMs(TerminalSessionContract.STOP_POLL_MS)
        } else {
          written += accepted
        }
      }
    }
    return WriteOutcome.Success(written)
  }

  fun resize(sessionId: String, rows: Int, columns: Int): SessionOpOutcome {
    if (!TerminalSessionContract.isValidSessionId(sessionId)) return SessionOpOutcome.SESSION_NOT_FOUND
    if (!TerminalSessionContract.isValidRows(rows) || !TerminalSessionContract.isValidColumns(columns)) {
      return SessionOpOutcome.FAILED
    }
    val session = activeSessions[sessionId] ?: return when {
      lookupExited(sessionId) != null -> SessionOpOutcome.SESSION_EXITED
      else -> SessionOpOutcome.SESSION_NOT_FOUND
    }
    if (session.exitEmitted.get()) return SessionOpOutcome.SESSION_EXITED
    return try {
      synchronized(session.windowSizeLock) {
        backend.setWindowSize(session.masterFd, rows, columns)
        session.rows = rows
        session.columns = columns
        session.windowSizeGeneration += 1
      }
      SessionOpOutcome.APPLIED
    } catch (_: Exception) {
      SessionOpOutcome.FAILED
    }
  }

  /** A pending redraw nudge; pass it back to [finishRedrawNudge]. */
  data class RedrawNudge(val sessionId: String, val rows: Int, val columns: Int, val generation: Long)

  /**
   * Starts a forced repaint: shrinks the PTY by one row without recording it
   * as the session size. Node-based TUIs ignore a SIGWINCH whose size did not
   * change, so a plain signal is not enough. Returns null when the session is
   * gone or the resize fails.
   */
  fun startRedrawNudge(sessionId: String): RedrawNudge? {
    val session = activeSessions[sessionId] ?: return null
    if (session.exitEmitted.get()) return null
    return synchronized(session.windowSizeLock) {
      val rows = session.rows
      val nudgeRows = if (TerminalSessionContract.isValidRows(rows - 1)) rows - 1 else rows + 1
      runCatching { backend.setWindowSize(session.masterFd, nudgeRows, session.columns) }.getOrNull()
        ?: return null
      RedrawNudge(sessionId, rows, session.columns, session.windowSizeGeneration)
    }
  }

  /**
   * Restores the size recorded before [startRedrawNudge]. A real resize in
   * between already set the PTY to its new size, so it is left alone.
   */
  fun finishRedrawNudge(nudge: RedrawNudge): Boolean {
    val session = activeSessions[nudge.sessionId] ?: return false
    if (session.exitEmitted.get()) return false
    return synchronized(session.windowSizeLock) {
      if (session.windowSizeGeneration != nudge.generation) return false
      runCatching { backend.setWindowSize(session.masterFd, nudge.rows, nudge.columns) }.isSuccess
    }
  }

  /** Delivers a named signal to the session's process group (negative pid). */
  fun signal(sessionId: String, signalName: String): SessionOpOutcome {
    val number = TerminalSessionContract.signalNumber(signalName)
      ?: return SessionOpOutcome.FAILED
    if (!TerminalSessionContract.isValidSessionId(sessionId)) return SessionOpOutcome.SESSION_NOT_FOUND
    val session = activeSessions[sessionId] ?: return when {
      lookupExited(sessionId) != null -> SessionOpOutcome.SESSION_EXITED
      else -> SessionOpOutcome.SESSION_NOT_FOUND
    }
    if (session.exitEmitted.get()) return SessionOpOutcome.SESSION_EXITED
    return try {
      sendSignal(-session.pid, number)
      SessionOpOutcome.APPLIED
    } catch (_: Exception) {
      SessionOpOutcome.FAILED
    }
  }

  /**
   * Stops a session with bounded TERM → KILL escalation over the process
   * group, then a session-id sweep for stragglers the group signal cannot
   * reach (guest job control places children in their own process groups).
   * Stopping an already-exited session is idempotent and still sweeps.
   */
  fun stop(sessionId: String, reason: String): StopOutcome {
    if (!TerminalSessionContract.isValidSessionId(sessionId)) {
      return StopOutcome.Failure("invalid_session_id", "session id does not match the contract pattern")
    }
    if (!TerminalSessionContract.isValidStopReason(reason)) {
      return StopOutcome.Failure("invalid_reason", "reason does not match the contract pattern")
    }
    val session = synchronized(registryLock) {
      activeSessions[sessionId] ?: exitedSessions[sessionId]
    } ?: return StopOutcome.Failure("session_not_found", "no session with id $sessionId")

    val existing = session.exitInfo
    if (existing != null) {
      val remaining = sweepSession(session)
      return StopOutcome.Stopped(
        StopObservation(sessionId, existing, remaining, remaining == 0),
      )
    }

    session.pendingStopReason.set(reason)
    // Sweep before waiting for the leader. A guest command may have forked
    // into another process group (or become reparented) while the shell is
    // still alive; waiting first would allow the leader's exit callback to
    // publish a clean session while that child continues running.
    val stoppedInDeadline = sweepSession(session) == 0
    awaitExit(session, TerminalSessionContract.STOP_KILL_WAIT_MS)
    val remaining = sweepSession(session)
    return StopOutcome.Stopped(
      StopObservation(sessionId, session.exitInfo, remaining, stoppedInDeadline && remaining == 0),
    )
  }

  /** Bounded shutdown used when the bridge disconnects or the app is destroyed. */
  fun shutdownAll(reason: String) {
    val ids = activeSessionIds()
    for (id in ids) {
      runCatching { stop(id, reason) }
    }
  }

  fun activeSessionIds(): List<String> = activeSessions.keys().toList().sorted()

  fun snapshot(sessionId: String): SessionSnapshot? {
    if (!TerminalSessionContract.isValidSessionId(sessionId)) return null
    val session = synchronized(registryLock) {
      activeSessions[sessionId] ?: exitedSessions[sessionId]
    } ?: return null
    return SessionSnapshot(
      sessionId = session.id,
      state = if (session.exitEmitted.get()) SessionState.EXITED else SessionState.RUNNING,
      pid = session.pid,
      startedAtMs = session.startedAtMs,
      totalOutputBytes = session.totalOutputBytes.get(),
      lastEmittedSeq = session.outputSeq.get(),
      exit = session.exitInfo,
    )
  }

  /**
   * Records the highest output sequence consumed by the bridge. The reader
   * waits once the bounded output window is full, so this acknowledgement is
   * both a protocol check and the native backpressure release.
   */
  fun acknowledgeOutput(sessionId: String, seq: Long): AcknowledgeOutcome {
    if (!TerminalSessionContract.isValidSessionId(sessionId)) {
      return AcknowledgeOutcome.Failure("invalid_request", "session id does not match the contract pattern")
    }
    if (seq < 1L) {
      return AcknowledgeOutcome.Failure("invalid_request", "output sequence must be positive")
    }
    val session = synchronized(registryLock) {
      activeSessions[sessionId] ?: exitedSessions[sessionId]
    } ?: return AcknowledgeOutcome.Failure("session_not_found", "no session with id $sessionId")

    synchronized(session.outputCreditLock) {
      val emitted = session.outputSeq.get()
      if (seq > emitted) {
        return AcknowledgeOutcome.Failure("invalid_request", "output sequence has not been emitted")
      }
      val previous = session.acknowledgedSeq.get()
      if (seq > previous) session.acknowledgedSeq.set(seq)
      session.outputCreditLock.notifyAll()
      val acknowledged = maxOf(previous, seq)
      val outstanding = (emitted - acknowledged)
        .coerceIn(0L, OUTPUT_ACK_WINDOW.toLong())
        .toInt()
      return AcknowledgeOutcome.Acknowledged(OutputAcknowledgement(acknowledged, outstanding))
    }
  }

  /** Next native session id: `s-<epochMillis>-<sequence>`. */
  fun nextSessionId(): String = "s-${clock()}-${sessionCounter.incrementAndGet()}"

  /** Bounded tail of the diagnostic mirror (never leaves the native layer unredacted). */
  fun diagnosticTail(sessionId: String): ByteArray? =
    (activeSessions[sessionId] ?: lookupExited(sessionId))?.mirror?.toByteArray()

  private fun lookupExited(sessionId: String): LiveSession? = synchronized(registryLock) {
    exitedSessions[sessionId]
  }

  private fun awaitExit(session: LiveSession, waitMs: Long): Boolean {
    val deadline = clock() + waitMs
    while (clock() < deadline) {
      if (session.exitDone.count == 0L) return true
      delayMs(TerminalSessionContract.STOP_POLL_MS)
    }
    return session.exitDone.count == 0L
  }

  private fun awaitGone(candidates: List<Long>, waitMs: Long): Boolean {
    val deadline = clock() + waitMs
    while (clock() < deadline) {
      if (candidates.none(processTree::isAlive)) return true
      delayMs(TerminalSessionContract.STOP_POLL_MS)
    }
    return candidates.none(processTree::isAlive)
  }

  /**
   * Kills everything still alive in the session: the leader's descendants
   * (snapshot before the kills, because orphans reparent away) plus any
   * process whose session id matches, which catches guest job-control
   * children in other process groups.
   */
  private fun sweepSession(session: LiveSession): Int = synchronized(session.cleanupLock) {
    val leaderPid = session.pid.toLong()
    fun candidates(): List<Long> = (listOf(leaderPid) +
      processTree.descendants(leaderPid) +
      processTree.sessionMembers(leaderPid)).distinct()
    val initial = candidates()
    val alive = initial.filter(processTree::isAlive)
    if (alive.isEmpty()) return@synchronized 0

    // The process-group signal handles the common shell/job-control path;
    // individual signals cover children that selected their own group.
    runCatching { sendSignal(-session.pid, TerminalSessionContract.SIGNALS.getValue("sigterm")) }
    alive.filter { it != leaderPid }.forEach { pid ->
      runCatching { sendSignal(pid.toInt(), TerminalSessionContract.SIGNALS.getValue("sigterm")) }
    }
    if (!awaitGone(initial, TerminalSessionContract.STOP_TERM_WAIT_MS)) {
      val survivors = candidates().filter(processTree::isAlive)
      survivors.forEach { pid ->
        val target = if (pid == leaderPid) -session.pid else pid.toInt()
        runCatching { sendSignal(target, TerminalSessionContract.SIGNALS.getValue("sigkill")) }
      }
      awaitGone(survivors, TerminalSessionContract.STOP_KILL_WAIT_MS)
    }
    candidates().count(processTree::isAlive)
  }

  private fun readerLoop(session: LiveSession) {
    val buffer = ByteArray(READ_CHUNK_BYTES)
    while (true) {
      if (!awaitOutputCredit(session)) break
      val read = try {
        backend.read(session.masterFd, buffer)
      } catch (_: Exception) {
        // EBADF after the master closes, or a backend failure: the drain is
        // over; the reaper owns the exit event.
        break
      }
      // The natural-exit drain may have timed out while this read was in
      // flight. Do not deliver bytes after the forced-close deadline.
      if (session.outputStopped.get()) break
      if (read < 0) break
      if (read == 0) continue
      val chunk = buffer.copyOf(read)
      val seq = session.outputSeq.incrementAndGet()
      session.totalOutputBytes.addAndGet(read.toLong())
      session.mirror.append(chunk)
      try {
        listener.onSessionOutput(session.id, seq, chunk)
      } catch (_: Exception) {
        // Never let a consumer failure stall the drain.
      }
    }
  }

  private fun awaitOutputCredit(session: LiveSession): Boolean = synchronized(session.outputCreditLock) {
    while (!session.outputStopped.get() &&
      session.outputSeq.get() - session.acknowledgedSeq.get() >= OUTPUT_ACK_WINDOW
    ) {
      // A detached or stalled bridge must never hold the PTY reader at the
      // output window. The service keeps the bounded replay ring, so moving
      // the native cursor forward here is safe and lets the child continue.
      if (!runCatching { shouldBlockOutput(session.id) }.getOrDefault(true)) {
        session.acknowledgedSeq.set(session.outputSeq.get())
        return@synchronized true
      }
      try {
        session.outputCreditLock.wait(OUTPUT_ACK_WAIT_MS)
      } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        return@synchronized false
      }
    }
    !session.outputStopped.get()
  }

  private fun reaperLoop(session: LiveSession) {
    val encoded = try {
      backend.waitFor(session.pid)
    } catch (_: Exception) {
      -1000
    }
    finish(session, encoded, session.pendingStopReason.get() ?: REASON_PROCESS_EXIT)
  }

  private fun finish(session: LiveSession, encodedStatus: Int, reason: String) {
    synchronized(session.finishLock) {
      if (session.exitEmitted.get()) {
        // A racing stop already finished this session; its event stands.
        return
      }
      // Natural shell exit also owns the complete guest session. Do this
      // before publishing the terminal event so that event consumers and the
      // teardown observation agree about whether descendants remain.
      sweepSession(session)
      val decoded = TerminalSessionContract.decodeExitStatus(encodedStatus)
      val info = SessionExitInfo(
        sessionId = session.id,
        exitCode = decoded?.exitCode,
        signal = decoded?.signal,
        reason = reason,
        waitStatusUndecodable = decoded == null,
      )
      session.exitEmitted.set(true)
      synchronized(session.outputCreditLock) {
        session.outputCreditLock.notifyAll()
      }
      // Drain bytes already queued by the exited process before closing the
      // PTY master. A descendant can keep the slave open, and a full output
      // credit window can stall the reader, so keep this wait bounded.
      runCatching { session.reader.join(READER_DRAIN_JOIN_MS) }
      session.outputStopped.set(true)
      synchronized(session.outputCreditLock) {
        session.outputCreditLock.notifyAll()
      }
      runCatching { backend.close(session.masterFd) }
      runCatching { session.reader.join(READER_DRAIN_JOIN_MS) }
      session.exitInfo = info
      try {
        listener.onSessionExit(info)
      } catch (_: Exception) {
        // The exit event is also mirrored by snapshot(); never crash on emit.
      }
      synchronized(registryLock) {
        activeSessions.remove(session.id)
        exitedSessions[session.id] = session
        while (exitedSessions.size > TerminalSessionContract.MAX_RETAINED_EXITED_SESSIONS) {
          val eldest = exitedSessions.keys.firstOrNull() ?: break
          exitedSessions.remove(eldest)
        }
      }
      session.exitDone.countDown()
    }
  }

  private inner class LiveSession(
    val id: String,
    val pid: Int,
    val masterFd: Int,
    val startedAtMs: Long,
    val countsAgainstSessionLimit: Boolean,
    initialRows: Int,
    initialColumns: Int,
  ) {
    val writeLock = Any()
    val cleanupLock = Any()
    val finishLock = Any()
    val windowSizeLock = Any()
    @Volatile var rows: Int = initialRows
    @Volatile var columns: Int = initialColumns
    var windowSizeGeneration = 0L
    val exitEmitted = AtomicBoolean(false)
    val outputStopped = AtomicBoolean(false)
    val exitDone = CountDownLatch(1)
    val pendingStopReason = AtomicReference<String?>(null)
    val outputCreditLock = Object()
    @Volatile var exitInfo: SessionExitInfo? = null
    val outputSeq = AtomicLong(0)
    val acknowledgedSeq = AtomicLong(0)
    val totalOutputBytes = AtomicLong(0)
    val mirror = BoundedOutputMirror()
    val reader = Thread({ readerLoop(this) }, "alpine-pty-reader-$id").apply { isDaemon = true }
    val reaper = Thread({ reaperLoop(this) }, "alpine-pty-reaper-$id").apply { isDaemon = true }
  }

  private fun configuredSessionLimit(): Int = runCatching { maxActiveSessions() }
    .getOrDefault(TerminalSessionContract.DEFAULT_ACTIVE_SESSIONS)
    .coerceIn(
      TerminalSessionContract.MIN_ACTIVE_SESSIONS,
      TerminalSessionContract.MAX_ACTIVE_SESSIONS,
    )

  companion object {
    const val READ_CHUNK_BYTES = 8 * 1024
    const val READER_DRAIN_JOIN_MS = 2_000L
    const val REASON_PROCESS_EXIT = "process_exit"
    const val OUTPUT_ACK_WINDOW = 32
    const val OUTPUT_ACK_WAIT_MS = 100L
  }
}

/** Fixed-size ring of the most recent output bytes; appends past the cap drop the oldest data. */
class BoundedOutputMirror(
  private val capacityBytes: Int = MIRROR_CAPACITY_BYTES,
) {
  private val buffer = ByteArray(capacityBytes)
  private var writeIndex = 0
  private var stored = 0
  private var droppedBytes = 0L

  @Synchronized
  fun append(chunk: ByteArray) {
    if (chunk.isEmpty()) return
    val take = minOf(chunk.size, capacityBytes)
    if (take < chunk.size) droppedBytes += (chunk.size - take)
    val overflow = maxOf(0, stored + take - capacityBytes)
    if (overflow > 0) droppedBytes += overflow.toLong()
    var offset = chunk.size - take
    var remaining = take
    while (remaining > 0) {
      val space = capacityBytes - writeIndex
      val count = minOf(space, remaining)
      System.arraycopy(chunk, offset, buffer, writeIndex, count)
      writeIndex = (writeIndex + count) % capacityBytes
      offset += count
      remaining -= count
    }
    stored = minOf(capacityBytes, stored + take)
  }

  @Synchronized
  fun toByteArray(): ByteArray {
    val result = ByteArray(stored)
    // The oldest byte sits at the stream start; when the ring is full that is
    // the next write position, otherwise it is writeIndex - stored.
    val start = if (stored == capacityBytes) writeIndex else (writeIndex - stored + capacityBytes) % capacityBytes
    if (start + stored <= capacityBytes) {
      System.arraycopy(buffer, start, result, 0, stored)
    } else {
      val first = capacityBytes - start
      System.arraycopy(buffer, start, result, 0, first)
      System.arraycopy(buffer, 0, result, first, stored - first)
    }
    return result
  }

  @Synchronized
  fun droppedByteCount(): Long = droppedBytes

  companion object {
    const val MIRROR_CAPACITY_BYTES = 16 * 1024
  }
}
