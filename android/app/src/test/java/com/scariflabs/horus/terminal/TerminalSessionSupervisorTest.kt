package com.scariflabs.horus.terminal

import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Supervisor behavior on the JVM with a fake PTY backend and a fake /proc
 * tree: ordered output, exactly-one exit under a stop/reaper race, bounded
 * TERM→KILL escalation with a session-id sweep, idempotent stop, limits, and
 * the bounded diagnostic mirror. Escalation waits use real (short) sleeps so
 * the suite exercises genuine timer teardown, never unbounded waits.
 */
class TerminalSessionSupervisorTest {

  private class RecordingListener : TerminalSessionSupervisor.EventListener {
    val outputs = CopyOnWriteArrayList<Triple<String, Long, ByteArray>>()
    val exits = CopyOnWriteArrayList<TerminalSessionSupervisor.SessionExitInfo>()
    val eventOrder = CopyOnWriteArrayList<String>()
    override fun onSessionOutput(sessionId: String, seq: Long, chunk: ByteArray) {
      outputs += Triple(sessionId, seq, chunk)
      eventOrder += "output:$seq"
    }

    override fun onSessionExit(info: TerminalSessionSupervisor.SessionExitInfo) {
      exits += info
      eventOrder += "exit"
    }
  }

  private class FakeProcess(val pid: Int, val fd: Int) {
    var exited = false
    var waitStatus = 0
    val pending = ArrayDeque<ByteArray>()
    var masterClosed = false
    var holdPendingOutputAfterExit = false
    val pendingOutputReadEntered = CountDownLatch(1)
    val releasePendingOutputRead = CountDownLatch(1)
  }

  private class FakePtyBackend : PtyBackend {
    val lock = Object()
    val processes = CopyOnWriteArrayList<FakeProcess>()
    val writes = CopyOnWriteArrayList<Pair<Int, ByteArray>>()
    val resizes = CopyOnWriteArrayList<Triple<Int, Int, Int>>()
    val closedFds = CopyOnWriteArrayList<Int>()
    var writeResultOverride: Int? = null
    private var nextPid = 4100
    private var nextFd = 90

    fun processByPid(pid: Int): FakeProcess = processes.first { it.pid == pid }
    fun processByFd(fd: Int): FakeProcess = processes.first { it.fd == fd }

    override fun createSubprocess(
      argv: List<String>,
      cwd: File?,
      environment: List<String>,
      rows: Int,
      columns: Int,
    ): PtyBackend.SpawnedProcess {
      val process = synchronized(lock) {
        FakeProcess(nextPid++, nextFd++).also { processes += it }
      }
      return PtyBackend.SpawnedProcess(process.pid, process.fd)
    }

    override fun setWindowSize(masterFd: Int, rows: Int, columns: Int) {
      synchronized(lock) { resizes += Triple(masterFd, rows, columns) }
    }

    override fun read(masterFd: Int, buffer: ByteArray): Int {
      val process = processByFd(masterFd)
      var holdForTest = false
      synchronized(lock) {
        while (process.pending.isEmpty() && !process.exited && !process.masterClosed) {
          lock.wait()
        }
        if (process.masterClosed) return -1
        if (process.pending.isNotEmpty() && process.exited && process.holdPendingOutputAfterExit) {
          process.pendingOutputReadEntered.countDown()
          holdForTest = true
        } else {
          return readPendingOrEof(process, buffer)
        }
      }
      if (holdForTest && !process.releasePendingOutputRead.await(5, TimeUnit.SECONDS)) return -1
      synchronized(lock) {
        if (process.masterClosed) return -1
        return readPendingOrEof(process, buffer)
      }
    }

    private fun readPendingOrEof(process: FakeProcess, buffer: ByteArray): Int {
      if (process.pending.isEmpty()) return -1
      val chunk = process.pending.first()
      val count = minOf(chunk.size, buffer.size)
      if (count == chunk.size) {
        process.pending.removeFirst()
      } else {
        process.pending[0] = chunk.copyOfRange(count, chunk.size)
      }
      System.arraycopy(chunk, 0, buffer, 0, count)
      return count
    }

    override fun write(masterFd: Int, bytes: ByteArray, offset: Int, count: Int): Int {
      synchronized(lock) { writes += masterFd to bytes.copyOfRange(offset, offset + count) }
      return writeResultOverride ?: count
    }

    override fun waitFor(pid: Int): Int {
      val process = processByPid(pid)
      synchronized(lock) {
        while (!process.exited) {
          lock.wait()
        }
        return process.waitStatus
      }
    }

    override fun close(masterFd: Int) {
      synchronized(lock) {
        closedFds += masterFd
        processByFd(masterFd).apply {
          masterClosed = true
          releasePendingOutputRead.countDown()
        }
        lock.notifyAll()
      }
    }

    fun emitOutput(masterFd: Int, chunk: ByteArray) {
      synchronized(lock) {
        processByFd(masterFd).pending += chunk
        lock.notifyAll()
      }
    }

    fun exitProcess(pid: Int, waitStatus: Int) {
      synchronized(lock) {
        val process = processByPid(pid)
        process.exited = true
        process.waitStatus = waitStatus
        lock.notifyAll()
      }
    }

    fun emitOutputAndExit(masterFd: Int, chunk: ByteArray, waitStatus: Int) {
      synchronized(lock) {
        val process = processByFd(masterFd)
        process.holdPendingOutputAfterExit = true
        process.pending += chunk
        process.exited = true
        process.waitStatus = waitStatus
        lock.notifyAll()
      }
    }
  }

  /** /proc view backed by the fake backend plus killable stragglers. */
  private class FakeProcessTree(private val backend: FakePtyBackend) : ProcessTree(File("/nonexistent-proc")) {
    val stragglers = mutableSetOf<Long>()
    val killedStragglers = mutableSetOf<Long>()

    override fun isAlive(pid: Long): Boolean = when {
      stragglers.contains(pid) -> !killedStragglers.contains(pid)
      else -> synchronized(backend.lock) {
        backend.processes.any { it.pid.toLong() == pid && !it.exited }
      }
    }

    override fun descendants(rootPid: Long): List<Long> = emptyList()

    override fun sessionMembers(sessionLeaderPid: Long): List<Long> = stragglers.toList().sorted()
  }

  private class Harness(
    val backend: FakePtyBackend,
    val listener: RecordingListener,
    val tree: FakeProcessTree,
    val signals: CopyOnWriteArrayList<Pair<Int, Int>>,
    val supervisor: TerminalSessionSupervisor,
  )

  private fun newHarness(
    maxActiveSessions: () -> Int = { TerminalSessionContract.MAX_ACTIVE_SESSIONS },
    shouldBlockOutput: (String) -> Boolean = { true },
  ): Harness {
    val backend = FakePtyBackend()
    val listener = RecordingListener()
    val tree = FakeProcessTree(backend)
    val signals = CopyOnWriteArrayList<Pair<Int, Int>>()
    var now = 0L
    val supervisor = TerminalSessionSupervisor(
      backend = backend,
      listener = listener,
      processTree = tree,
      sendSignal = { pid, signal ->
        signals += pid to signal
        when {
          // Stragglers honor TERM; the session leader ignores it so tests
          // exercise the KILL escalation.
          tree.stragglers.contains(pid.toLong()) && signal == TerminalSessionContract.SIGNALS.getValue("sigterm") ->
            tree.killedStragglers += pid.toLong()
          signal == TerminalSessionContract.SIGNALS.getValue("sigkill") -> {
            val leader = -pid
            if (leader > 0) backend.exitProcess(leader, -9)
          }
        }
      },
      delayMs = { millis ->
        now += maxOf(millis, 1L)
        Thread.sleep(1)
      },
      clock = { now },
      maxActiveSessions = maxActiveSessions,
      shouldBlockOutput = shouldBlockOutput,
    )
    return Harness(backend, listener, tree, signals, supervisor)
  }

  private fun spec(rows: Int = 24, columns: Int = 80, countsAgainstSessionLimit: Boolean = true) = TerminalSessionSupervisor.StartSpec(
    argv = listOf("/bin/sh", "-l"),
    environment = listOf("HOME=/root"),
    workingDirectory = null,
    rows = rows,
    columns = columns,
    countsAgainstSessionLimit = countsAgainstSessionLimit,
  )

  private fun awaitCondition(timeoutMs: Long = 5_000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      if (condition()) return
      Thread.sleep(10)
    }
    assertTrue("condition not met within $timeoutMs ms", condition())
  }

  @Test
  fun startRegistersTheSessionAndRejectsInvalidRequests() {
    val harness = newHarness()
    val outcome = harness.supervisor.start("s-1", spec())
    assertTrue("start failed: $outcome", outcome is TerminalSessionSupervisor.StartOutcome.Success)
    val handle = (outcome as TerminalSessionSupervisor.StartOutcome.Success).handle
    assertTrue(handle.pid > 0)
    assertEquals(listOf("s-1"), harness.supervisor.activeSessionIds())
    assertEquals(TerminalSessionSupervisor.SessionState.RUNNING, harness.supervisor.snapshot("s-1")!!.state)

    assertTrue(harness.supervisor.start("../bad", spec()) is TerminalSessionSupervisor.StartOutcome.Failure)
    val badRows = harness.supervisor.start("s-2", spec(rows = 1))
    assertEquals(
      "invalid_request",
      (badRows as TerminalSessionSupervisor.StartOutcome.Failure).reasonCode,
    )
    val duplicate = harness.supervisor.start("s-1", spec())
    assertEquals(
      "session_id_in_use",
      (duplicate as TerminalSessionSupervisor.StartOutcome.Failure).reasonCode,
    )

    harness.supervisor.stop("s-1", "user_stop")
  }

  @Test
  fun outputArrivesInOrderAndExitIsEmittedExactlyOnce() {
    val harness = newHarness()
    val outcome = harness.supervisor.start("s-1", spec())
    val handle = (outcome as TerminalSessionSupervisor.StartOutcome.Success).handle
    val masterFd = harness.backend.processByPid(handle.pid).fd

    harness.backend.emitOutput(masterFd, "one\n".toByteArray())
    harness.backend.emitOutput(masterFd, "two\n".toByteArray())
    harness.backend.emitOutput(masterFd, "three\n".toByteArray())
    awaitCondition { harness.listener.outputs.size >= 3 }
    harness.backend.exitProcess(handle.pid, 0)
    awaitCondition { harness.listener.exits.isNotEmpty() }

    assertEquals(
      listOf(1L, 2L, 3L),
      harness.listener.outputs.map { it.second },
    )
    assertEquals(
      listOf("one\n", "two\n", "three\n"),
      harness.listener.outputs.map { String(it.third) },
    )
    assertEquals(1, harness.listener.exits.size)
    val exit = harness.listener.exits.single()
    assertEquals(0, exit.exitCode)
    assertEquals(null, exit.signal)
    assertEquals("process_exit", exit.reason)
    assertEquals(listOf("output:1", "output:2", "output:3", "exit"), harness.listener.eventOrder)
    assertEquals(emptyList<String>(), harness.supervisor.activeSessionIds())
    assertEquals(TerminalSessionSupervisor.SessionState.EXITED, harness.supervisor.snapshot("s-1")!!.state)
  }

  @Test
  fun naturalExitDrainsTrailingMarkersBeforePublishingExit() {
    val harness = newHarness()
    val outcome = harness.supervisor.start("s-1", spec())
    val handle = (outcome as TerminalSessionSupervisor.StartOutcome.Success).handle
    val masterFd = harness.backend.processByPid(handle.pid).fd
    val finalMarkers = "HORUS_GITHUB_ACCOUNT_END|0\nHORUS_GITHUB_REPOS_BEGIN\n".toByteArray()

    harness.backend.emitOutputAndExit(masterFd, finalMarkers, 0)
    val fakeProcess = harness.backend.processByPid(handle.pid)
    try {
      assertTrue(
        "reader did not reach the queued trailing output",
        fakeProcess.pendingOutputReadEntered.await(5, TimeUnit.SECONDS),
      )
      assertTrue("PTY master closed before queued trailing output drained", harness.backend.closedFds.isEmpty())
    } finally {
      fakeProcess.releasePendingOutputRead.countDown()
    }
    awaitCondition { harness.listener.exits.isNotEmpty() }

    assertEquals(1, harness.listener.outputs.size)
    assertEquals(String(finalMarkers), String(harness.listener.outputs.single().third))
    assertEquals(listOf("output:1", "exit"), harness.listener.eventOrder)
    assertEquals(1, harness.listener.exits.size)
  }

  @Test
  fun outputAcknowledgementReleasesTheBoundedWindowAndRejectsFutureSequences() {
    val harness = newHarness()
    val outcome = harness.supervisor.start("s-1", spec())
    val handle = (outcome as TerminalSessionSupervisor.StartOutcome.Success).handle
    val masterFd = harness.backend.processByPid(handle.pid).fd

    harness.backend.emitOutput(masterFd, "ack-me\n".toByteArray())
    awaitCondition { harness.listener.outputs.size == 1 }

    val acknowledged = harness.supervisor.acknowledgeOutput("s-1", 1L)
    assertTrue(acknowledged is TerminalSessionSupervisor.AcknowledgeOutcome.Acknowledged)
    val value = (acknowledged as TerminalSessionSupervisor.AcknowledgeOutcome.Acknowledged).value
    assertEquals(1L, value.acknowledgedSeq)
    assertEquals(0, value.outstandingChunks)
    assertEquals(1L, harness.supervisor.snapshot("s-1")!!.lastEmittedSeq)

    val future = harness.supervisor.acknowledgeOutput("s-1", 2L)
    assertEquals(
      "invalid_request",
      (future as TerminalSessionSupervisor.AcknowledgeOutcome.Failure).reasonCode,
    )

    harness.backend.exitProcess(handle.pid, 0)
    awaitCondition { harness.listener.exits.isNotEmpty() }
  }

  @Test
  fun naturalExitRacingStopStillEmitsExactlyOneExit() {
    val harness = newHarness()
    val outcome = harness.supervisor.start("s-1", spec())
    val handle = (outcome as TerminalSessionSupervisor.StartOutcome.Success).handle
    val barrier = CyclicBarrier(2)
    val stopper = Thread {
      barrier.await()
      harness.supervisor.stop("s-1", "user_stop")
    }
    stopper.isDaemon = true
    stopper.start()
    barrier.await()
    harness.backend.exitProcess(handle.pid, 0)
    stopper.join(5_000)
    awaitCondition { harness.listener.exits.isNotEmpty() }

    assertEquals(1, harness.listener.exits.size)
  }

  @Test
  fun stopEscalatesFromTermToKillWhenTermIsIgnored() {
    val harness = newHarness()
    val outcome = harness.supervisor.start("s-1", spec())
    val handle = (outcome as TerminalSessionSupervisor.StartOutcome.Success).handle

    val stopOutcome = harness.supervisor.stop("s-1", "user_stop")
    assertTrue("stop failed: $stopOutcome", stopOutcome is TerminalSessionSupervisor.StopOutcome.Stopped)
    val observation = (stopOutcome as TerminalSessionSupervisor.StopOutcome.Stopped).observation
    awaitCondition { harness.listener.exits.isNotEmpty() }

    assertEquals(0, observation.remainingProcessCount)
    assertTrue(observation.stoppedWithinDeadline)
    val exit = harness.listener.exits.single()
    assertEquals("sigkill", exit.signal)
    assertEquals("user_stop", exit.reason)

    val pid = handle.pid
    val term = TerminalSessionContract.SIGNALS.getValue("sigterm")
    val kill = TerminalSessionContract.SIGNALS.getValue("sigkill")
    assertTrue("TERM to the process group was not first", harness.signals.contains(-pid to term))
    assertTrue("KILL to the process group never happened", harness.signals.contains(-pid to kill))
    assertTrue(
      "escalation order violated",
      harness.signals.indexOf(-pid to term) < harness.signals.indexOf(-pid to kill),
    )
  }

  @Test
  fun stopSweepsStragglersLeftInOtherProcessGroups() {
    val harness = newHarness()
    harness.supervisor.start("s-1", spec())
    harness.tree.stragglers += 9999L
    assertTrue(harness.tree.isAlive(9999L))

    val stopOutcome = harness.supervisor.stop("s-1", "user_stop")
    val observation = (stopOutcome as TerminalSessionSupervisor.StopOutcome.Stopped).observation
    assertEquals(0, observation.remainingProcessCount)
    assertTrue(harness.signals.any { it.first == 9999 && it.second == TerminalSessionContract.SIGNALS.getValue("sigterm") })
    assertTrue("straggler survived the sweep", !harness.tree.isAlive(9999L))
  }

  @Test
  fun stopIsIdempotentAfterANaturalExit() {
    val harness = newHarness()
    val outcome = harness.supervisor.start("s-1", spec())
    val handle = (outcome as TerminalSessionSupervisor.StartOutcome.Success).handle
    harness.backend.exitProcess(handle.pid, 3)
    awaitCondition { harness.supervisor.snapshot("s-1")!!.state == TerminalSessionSupervisor.SessionState.EXITED }

    val first = harness.supervisor.stop("s-1", "user_stop")
    val second = harness.supervisor.stop("s-1", "user_stop")
    for (stop in listOf(first, second)) {
      val observation = (stop as TerminalSessionSupervisor.StopOutcome.Stopped).observation
      assertEquals(3, observation.exit!!.exitCode)
      assertEquals(0, observation.remainingProcessCount)
    }
    assertEquals(1, harness.listener.exits.size)
  }

  @Test
  fun naturalExitCleansSessionMembersBeforePublishingExit() {
    val harness = newHarness()
    val outcome = harness.supervisor.start("s-1", spec())
    val handle = (outcome as TerminalSessionSupervisor.StartOutcome.Success).handle
    harness.tree.stragglers += 9999L
    harness.backend.exitProcess(handle.pid, 0)

    awaitCondition { harness.listener.exits.isNotEmpty() }
    assertTrue("natural exit left a session member alive", !harness.tree.isAlive(9999L))
    assertEquals(1, harness.listener.exits.size)
  }

  @Test
  fun redrawNudgeShrinksThenRestoresTheRecordedSize() {
    val harness = newHarness()
    harness.supervisor.start("s-1", spec())
    assertEquals(TerminalSessionSupervisor.SessionOpOutcome.APPLIED, harness.supervisor.resize("s-1", 40, 60))

    val nudge = harness.supervisor.startRedrawNudge("s-1")!!
    assertEquals(40, nudge.rows)
    assertEquals(60, nudge.columns)
    assertTrue(harness.supervisor.finishRedrawNudge(nudge))
    assertEquals(
      listOf(40 to 60, 39 to 60, 40 to 60),
      harness.backend.resizes.map { it.second to it.third },
    )

    // A real resize between the two halves wins; the restore is skipped.
    val stale = harness.supervisor.startRedrawNudge("s-1")!!
    harness.supervisor.resize("s-1", 30, 50)
    assertFalse(harness.supervisor.finishRedrawNudge(stale))
    assertEquals(30 to 50, harness.backend.resizes.last().let { it.second to it.third })
    assertEquals(null, harness.supervisor.startRedrawNudge("s-none"))
  }

  @Test
  fun writeResizeAndSignalReportTheirOutcomesPrecisely() {
    val harness = newHarness()
    val outcome = harness.supervisor.start("s-1", spec())
    val handle = (outcome as TerminalSessionSupervisor.StartOutcome.Success).handle

    val write = harness.supervisor.write("s-1", "printf ok\n".toByteArray())
    assertEquals(10, (write as TerminalSessionSupervisor.WriteOutcome.Success).bytesWritten)
    assertEquals("printf ok\n", String(harness.backend.writes.single().second))

    assertEquals(
      TerminalSessionSupervisor.SessionOpOutcome.APPLIED,
      harness.supervisor.resize("s-1", 40, 120),
    )
    assertEquals(1, harness.backend.resizes.size)
    assertEquals(40, harness.backend.resizes.single().second)
    assertEquals(120, harness.backend.resizes.single().third)
    assertEquals(
      TerminalSessionSupervisor.SessionOpOutcome.APPLIED,
      harness.supervisor.signal("s-1", "sigint"),
    )
    assertTrue(harness.signals.contains(-handle.pid to TerminalSessionContract.SIGNALS.getValue("sigint")))
    assertEquals(
      TerminalSessionSupervisor.SessionOpOutcome.FAILED,
      harness.supervisor.resize("s-1", 999, 120),
    )
    assertEquals(
      TerminalSessionSupervisor.SessionOpOutcome.SESSION_NOT_FOUND,
      harness.supervisor.resize("s-other", 40, 120),
    )
    assertEquals(
      TerminalSessionSupervisor.SessionOpOutcome.FAILED,
      harness.supervisor.signal("s-1", "sigstop"),
    )

    harness.backend.exitProcess(handle.pid, 0)
    awaitCondition { harness.supervisor.snapshot("s-1")!!.state == TerminalSessionSupervisor.SessionState.EXITED }
    assertEquals(
      TerminalSessionSupervisor.SessionOpOutcome.SESSION_EXITED,
      harness.supervisor.resize("s-1", 40, 120),
    )
    val writeAfterExit = harness.supervisor.write("s-1", "x".toByteArray())
    assertEquals(
      "session_exited",
      (writeAfterExit as TerminalSessionSupervisor.WriteOutcome.Failure).reasonCode,
    )
    val writeUnknown = harness.supervisor.write("s-none", "x".toByteArray())
    assertEquals(
      "session_not_found",
      (writeUnknown as TerminalSessionSupervisor.WriteOutcome.Failure).reasonCode,
    )
  }

  @Test
  fun aStalledWriteIsBoundedByTheNativeDeadline() {
    val harness = newHarness()
    val outcome = harness.supervisor.start("s-1", spec())
    assertTrue(outcome is TerminalSessionSupervisor.StartOutcome.Success)
    harness.backend.writeResultOverride = 0

    val write = harness.supervisor.write("s-1", byteArrayOf(1))
    assertEquals("write_timeout", (write as TerminalSessionSupervisor.WriteOutcome.Failure).reasonCode)
    harness.supervisor.stop("s-1", "user_stop")
  }

  @Test
  fun theFourthSessionIsTheLastUntilOneStops() {
    val harness = newHarness()
    val ids = (1..4).map { index ->
      val outcome = harness.supervisor.start("s-$index", spec())
      assertTrue(outcome is TerminalSessionSupervisor.StartOutcome.Success)
      "s-$index"
    }
    val fifth = harness.supervisor.start("s-5", spec())
    assertEquals(
      "session_limit_reached",
      (fifth as TerminalSessionSupervisor.StartOutcome.Failure).reasonCode,
    )
    harness.supervisor.stop(ids.first(), "user_stop")
    val replacement = harness.supervisor.start("s-5", spec())
    assertTrue(replacement is TerminalSessionSupervisor.StartOutcome.Success)
    for (id in ids.drop(1) + "s-5") {
      harness.supervisor.stop(id, "user_stop")
    }
  }

  @Test
  fun theDefaultSessionLimitAllowsOnlyOneRunningSession() {
    val harness = newHarness(maxActiveSessions = { TerminalSessionContract.DEFAULT_ACTIVE_SESSIONS })
    val first = harness.supervisor.start("s-1", spec())
    assertTrue(first is TerminalSessionSupervisor.StartOutcome.Success)

    val second = harness.supervisor.start("s-2", spec())
    assertEquals(
      "session_limit_reached",
      (second as TerminalSessionSupervisor.StartOutcome.Failure).reasonCode,
    )

    harness.supervisor.stop("s-1", "user_stop")
  }

  @Test
  fun aNonCountedUtilitySessionDoesNotConsumeTheUserSessionSlot() {
    val harness = newHarness(
      maxActiveSessions = { TerminalSessionContract.DEFAULT_ACTIVE_SESSIONS },
    )
    assertTrue(harness.supervisor.start("s-shell", spec()) is TerminalSessionSupervisor.StartOutcome.Success)
    assertTrue(
      harness.supervisor.start("s-github", spec(countsAgainstSessionLimit = false)) is TerminalSessionSupervisor.StartOutcome.Success,
    )
    val secondUserSession = harness.supervisor.start("s-codex", spec())
    assertEquals("session_limit_reached", (secondUserSession as TerminalSessionSupervisor.StartOutcome.Failure).reasonCode)
    harness.supervisor.stop("s-shell", "user_stop")
    harness.supervisor.stop("s-github", "user_stop")
  }

  @Test
  fun aStaleSubscriberCannotHoldThePtyReaderAtTheOutputWindow() {
    val harness = newHarness(shouldBlockOutput = { false })
    val outcome = harness.supervisor.start("s-1", spec())
    val handle = (outcome as TerminalSessionSupervisor.StartOutcome.Success).handle
    val masterFd = harness.backend.processByPid(handle.pid).fd

    repeat(TerminalSessionSupervisor.OUTPUT_ACK_WINDOW + 8) { index ->
      harness.backend.emitOutput(masterFd, "chunk-$index\n".toByteArray())
    }
    awaitCondition { harness.listener.outputs.size >= TerminalSessionSupervisor.OUTPUT_ACK_WINDOW + 8 }
    assertTrue("stale subscriber still applied output backpressure", harness.listener.outputs.size >= TerminalSessionSupervisor.OUTPUT_ACK_WINDOW + 8)

    harness.supervisor.stop("s-1", "user_stop")
  }

  @Test
  fun theDiagnosticMirrorKeepsOnlyTheBoundedTail() {
    val harness = newHarness()
    val outcome = harness.supervisor.start("s-1", spec())
    val handle = (outcome as TerminalSessionSupervisor.StartOutcome.Success).handle

    val first = ByteArray(10 * 1024) { 'a'.code.toByte() }
    val second = ByteArray(10 * 1024) { 'b'.code.toByte() }
    val third = ByteArray(10 * 1024) { 'c'.code.toByte() }
    val masterFd = harness.backend.processByPid(handle.pid).fd
    harness.backend.emitOutput(masterFd, first)
    harness.backend.emitOutput(masterFd, second)
    harness.backend.emitOutput(masterFd, third)
    awaitCondition { harness.listener.outputs.size >= 3 }
    harness.backend.exitProcess(handle.pid, 0)
    awaitCondition { harness.supervisor.snapshot("s-1")!!.state == TerminalSessionSupervisor.SessionState.EXITED }

    val tail = harness.supervisor.diagnosticTail("s-1")!!
    assertEquals(BoundedOutputMirror.MIRROR_CAPACITY_BYTES, tail.size)
    // 30 KiB into a 16 KiB ring: the mirror keeps the last 16 KiB, which is
    // the final 6 KiB of the 'b' chunk followed by all 10 KiB of 'c'.
    val expected = ByteArray(16 * 1024) { index ->
      if (index < 6 * 1024) 'b'.code.toByte() else 'c'.code.toByte()
    }
    assertTrue("mirror tail does not match the most recent bytes", tail.contentEquals(expected))
  }

  @Test
  fun stopRejectsInvalidReasonsAndUnknownSessions() {
    val harness = newHarness()
    val badReason = harness.supervisor.stop("s-1", "Not A Reason")
    assertEquals(
      "invalid_reason",
      (badReason as TerminalSessionSupervisor.StopOutcome.Failure).reasonCode,
    )
    val unknown = harness.supervisor.stop("s-none", "user_stop")
    assertEquals(
      "session_not_found",
      (unknown as TerminalSessionSupervisor.StopOutcome.Failure).reasonCode,
    )
  }
}
