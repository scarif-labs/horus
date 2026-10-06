package com.scariflabs.horus.terminal

import android.net.ConnectivityManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 2 physical-device proof: a real interactive guest shell through the
 * app-owned PTY helper and PRoot. Every wait is
 * bounded and every assertion is marker-delimited; the teardown
 * observation records session id, exit code, signal, and the child-count
 * observation that no session process survives the stop.
 *
 * The test owns a private storage root that is deleted in finally; it never
 * touches the app's real runtime state, home, or workspace data.
 */
@RunWith(AndroidJUnit4::class)
class TerminalPtyDeviceTest {

  private class OutputSink {
    private val lock = Object()
    private val stream = ByteArrayOutputStream()
    val seqs = CopyOnWriteArrayList<Long>()

    fun append(seq: Long, chunk: ByteArray) {
      synchronized(lock) {
        seqs += seq
        stream.write(chunk)
      }
    }

    fun text(): String = synchronized(lock) {
      String(stream.toByteArray(), Charsets.ISO_8859_1)
    }

    fun checkpoint(): Int = synchronized(lock) { stream.size() }

    fun hasExactLinesAfter(offset: Int, vararg expected: String): Boolean {
      val body = synchronized(lock) {
        val bytes = stream.toByteArray()
        val start = offset.coerceIn(0, bytes.size)
        String(bytes.copyOfRange(start, bytes.size), Charsets.ISO_8859_1)
      }
      val lines = body.replace("\r\n", "\n").replace('\r', '\n').split('\n').filter(String::isNotEmpty)
      return lines.windowed(expected.size, 1, partialWindows = false).any { it == expected.toList() }
    }

    fun hasExactBytesAfter(offset: Int, expected: ByteArray): Boolean = synchronized(lock) {
      val bytes = stream.toByteArray()
      val start = offset.coerceIn(0, bytes.size)
      val available = bytes.size - start
      available >= expected.size &&
        bytes.copyOfRange(start, start + expected.size).contentEquals(expected)
    }

    fun tail(limit: Int = 1200): String = synchronized(lock) {
      val bytes = stream.toByteArray()
      String(bytes.copyOfRange(maxOf(0, bytes.size - limit), bytes.size), Charsets.ISO_8859_1)
    }

    fun hexAfter(offset: Int, limit: Int = 256): String = synchronized(lock) {
      val bytes = stream.toByteArray()
      val start = offset.coerceIn(0, bytes.size)
      bytes.copyOfRange(start, minOf(bytes.size, start + limit))
        .joinToString(" ") { "%02x".format(it) }
    }

    fun hexHead(limit: Int = 96): String = synchronized(lock) {
      stream.toByteArray().take(limit).joinToString(" ") { "%02x".format(it) }
    }

    /** Hex of the bytes between two markers (inclusive), for diagnostics. */
    fun segmentHex(begin: String, end: String): String = synchronized(lock) {
      val body = text()
      val from = body.indexOf(begin)
      if (from < 0) return "begin-marker-absent"
      val to = body.indexOf(end, from + begin.length)
      if (to < 0) return "end-marker-absent"
      body.substring(from, to + end.length)
        .toByteArray(Charsets.ISO_8859_1)
        .joinToString(" ") { "%02x".format(it) }
        .take(400)
    }
  }

  private class DeviceListener(val sink: OutputSink) : TerminalSessionSupervisor.EventListener {
    val exits = CopyOnWriteArrayList<TerminalSessionSupervisor.SessionExitInfo>()
    var acknowledge: ((String, Long) -> Unit)? = null
    var respondToCursorQuery: ((String) -> Unit)? = null
    private var cursorQueryTail = emptyList<Byte>()
    override fun onSessionOutput(sessionId: String, seq: Long, chunk: ByteArray) {
      if (sessionId == currentSessionId) sink.append(seq, chunk)
      val combined = cursorQueryTail + chunk.toList()
      if (combined.windowed(4, 1, partialWindows = false).any {
        it == listOf(0x1b.toByte(), 0x5b.toByte(), 0x36.toByte(), 0x6e.toByte())
      }) {
        respondToCursorQuery?.invoke(sessionId)
      }
      cursorQueryTail = combined.takeLast(3)
      // The production bridge releases the bounded native output window with
      // acknowledgeSessionOutput. Keep the connected test on the same wire
      // contract so a verbose guest cannot stall its own PTY reader.
      acknowledge?.invoke(sessionId, seq)
    }

    override fun onSessionExit(info: TerminalSessionSupervisor.SessionExitInfo) {
      exits += info
    }

    @Volatile
    var currentSessionId: String = ""
  }

  private fun awaitCondition(timeoutMs: Long, message: String, condition: () -> Boolean): String? {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      if (condition()) return null
      Thread.sleep(50)
    }
    return condition().let { passed -> if (passed) null else message }
  }

  private fun writeCommand(supervisor: TerminalSessionSupervisor, sessionId: String, command: String) {
    val outcome = supervisor.write(sessionId, (command + "\n").toByteArray(Charsets.ISO_8859_1))
    assertTrue(
      "write failed for command `${command.take(40)}`: $outcome",
      outcome is TerminalSessionSupervisor.WriteOutcome.Success,
    )
  }

  private fun awaitExit(
    listener: DeviceListener,
    expectedCount: Int,
    timeoutMs: Long,
  ): TerminalSessionSupervisor.SessionExitInfo? {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      if (listener.exits.size >= expectedCount) return listener.exits[expectedCount - 1]
      Thread.sleep(50)
    }
    return listener.exits.getOrNull(expectedCount - 1)
  }

  @Test(timeout = 900_000)
  fun interactiveShellSurvivesCtrlCResizeAndStopsWithoutOrphans() {
    val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    assertEquals("arm64-v8a", android.os.Build.SUPPORTED_ABIS.firstOrNull())

    val manifestText = targetContext.assets.open("alpine-runtime/manifest.json").bufferedReader().use { it.readText() }
    val located = ProotRuntimeLocator(manifestText).locate(File(targetContext.applicationInfo.nativeLibraryDir ?: ""))
    assertTrue("packaged PRoot runtime is unavailable", located is ProotRuntimeLocator.Location.Available)
    val runtime = (located as ProotRuntimeLocator.Location.Available).runtime

    val testRoot = File(targetContext.filesDir, "alpine-p2-device-test-${System.nanoTime()}")
    val paths = DistroStorePaths(testRoot)
    val store = DistroStoreCore(
      paths = paths,
      downloader = HttpArchiveDownloader(),
      extractor = SafeTarGzExtractor(),
      prober = ProotGuestProber(runtime, File(paths.sessions, ".proot-scratch")),
    )
    val sink = OutputSink()
    val listener = DeviceListener(sink)
    val processTree = ProcessTree()
    val supervisor = TerminalSessionSupervisor(
      backend = JniPtyBackend(),
      listener = listener,
      processTree = processTree,
      sendSignal = { pid, signal -> android.system.Os.kill(pid, signal) },
    )
    val launcher = ProotSessionLauncher(
      runtime = runtime,
      scratchDir = File(paths.sessions, ".proot-scratch"),
      dnsServersProvider = {
        val connectivity = targetContext.getSystemService(ConnectivityManager::class.java)
        connectivity.activeNetwork
          ?.let { connectivity.getLinkProperties(it)?.dnsServers }
          ?.mapNotNull { it.hostAddress?.trim()?.takeIf(String::isNotEmpty) }
          .orEmpty()
      },
    )
    listener.acknowledge = { id, seq -> supervisor.acknowledgeOutput(id, seq) }
    listener.respondToCursorQuery = { id ->
      // CPR reports the cursor position, not the window dimensions. The
      // initial `localhost:~# ` prompt leaves the cursor at column 14.
      supervisor.write(id, "\u001b[1;14R".toByteArray(Charsets.ISO_8859_1))
    }

    try {
      store.reset()
      val install = store.install(downloadTimeoutMs = 120_000)
      assertTrue("rootfs install failed: $install", install is DistroStoreCore.InstallOutcome.Success)
      val rootfsDir = store.activeRootfsDir()
      assertTrue(rootfsDir != null && rootfsDir.isDirectory)

      // This byte/CPR gate expects the minirootfs BusyBox shell prompt.
      // Select it explicitly; the app's default zsh needs provisioning and
      // performs different prompt negotiation.
      // Session 1: the full interactive gate.
      val sessionId = supervisor.nextSessionId()
      listener.currentSessionId = sessionId
      val start = supervisor.start(sessionId, launcher.interactiveShellLaunchSpec(rootfsDir!!, paths.home, sessionCommand = "exec /bin/sh -l").let {
        TerminalSessionSupervisor.StartSpec(
          argv = it.argv,
          environment = it.environment,
          workingDirectory = it.workingDirectory,
          rows = 24,
          columns = 80,
        )
      })
      assertTrue("interactive session failed to start: $start", start is TerminalSessionSupervisor.StartOutcome.Success)
      val handle = (start as TerminalSessionSupervisor.StartOutcome.Success).handle
      assertTrue(handle.pid > 0)
      android.util.Log.i(LOG_TAG, "ALPINE_P2_PTY_READY sessionId=$sessionId pid=${handle.pid}")

      // Diagnostic window: liveness, prompt bytes, and the fixed guest argv
      // observed for the first seconds; also proves the shell reached its
      // prompt before the first command is written.
      val diagStart = System.currentTimeMillis()
      while (System.currentTimeMillis() - diagStart < 3_000) {
        val alive = processTree.isAlive(handle.pid.toLong())
        android.util.Log.i(
          DIAG_TAG,
          "t=${System.currentTimeMillis() - diagStart}ms alive=$alive bytes=${sink.text().length} exits=${listener.exits.size} hex=${sink.hexHead()} text=${sink.text().take(60).replace(Regex("[^\\x20-\\x7e\\n\\r]"), ".")}",
        )
        Thread.sleep(1_000)
      }
      // The initial CPR response proves prompt negotiation. Do not inject
      // later CPR replies into the shell while it is reading test commands.
      listener.respondToCursorQuery = null

      // Disable terminal command echo before the assertions begin. Typed
      // command text must never be able to satisfy an output marker.
      writeCommand(supervisor, sessionId, "stty -echo; printf 'P2_ECHO_OFF\\n'")
      var failure = awaitCondition(30_000, "echo-disable marker never arrived; tail=${sink.tail()}") {
        sink.hasExactLinesAfter(0, "P2_ECHO_OFF")
      }
      assertTrue(failure, failure == null)

      // 1. printf round trip through the login shell.
      var checkpoint = sink.checkpoint()
      writeCommand(supervisor, sessionId, "printf '%s\\n' P2_ECHO_BEGIN alpine_pty_roundtrip P2_ECHO_END")
      failure = awaitCondition(60_000, "echo markers never arrived; tail=${sink.tail()}") {
        sink.hasExactLinesAfter(checkpoint, "P2_ECHO_BEGIN", "alpine_pty_roundtrip", "P2_ECHO_END")
      }
      assertTrue(failure, failure == null)
      android.util.Log.i(LOG_TAG, "ALPINE_P2_ECHO_OK")

      // 2. Deliberate guest environment (HOME and TERM from the launcher).
      checkpoint = sink.checkpoint()
      writeCommand(supervisor, sessionId, "printf 'P2_ENV_BEGIN:%s:%s:%s\\nP2_ENV_END\\n' \"\$HOME\" \"\$TERM\" \"\$LANG\"")
      failure = awaitCondition(30_000, "env markers never arrived; tail=${sink.tail()}") {
        sink.hasExactLinesAfter(
          checkpoint,
          "P2_ENV_BEGIN:/root:${ProotSessionLauncher.DEFAULT_TERM}:${ProotSessionLauncher.GUEST_LANG}",
          "P2_ENV_END",
        )
      }
      assertTrue(failure, failure == null)
      android.util.Log.i(LOG_TAG, "ALPINE_P2_ENV_OK")

      // 3. cat passes bytes through the pipe inside the guest.
      checkpoint = sink.checkpoint()
      writeCommand(supervisor, sessionId, "printf 'p2_cat_marker_payload\\n' | cat")
      failure = awaitCondition(30_000, "cat marker never arrived; tail=${sink.tail()}") {
        sink.hasExactLinesAfter(checkpoint, "p2_cat_marker_payload")
      }
      assertTrue(failure, failure == null)
      android.util.Log.i(LOG_TAG, "ALPINE_P2_CAT_OK")

      // 4. Colors and cursor movement survive the round trip byte-for-byte.
      // Busybox printf accepts the conventional three-digit octal escape.
      checkpoint = sink.checkpoint()
      writeCommand(supervisor, sessionId, "printf 'P2_ESC_BEGIN\\033[31mR\\033[0m\\033[1B\\033[2KP2_ESC_END\\n'")
      val expectedEscape = "P2_ESC_BEGIN" + 0x1b.toChar() + "[31mR" + 0x1b.toChar() + "[0m" + 0x1b.toChar() + "[1B" + 0x1b.toChar() + "[2KP2_ESC_END"
      failure = awaitCondition(30_000, "escape sequence was not delivered intact; segment=${sink.segmentHex("P2_ESC_BEGIN", "P2_ESC_END")} hex=${sink.hexAfter(checkpoint)} tail=${sink.tail()}") {
        sink.hasExactBytesAfter(checkpoint, (expectedEscape + "\r\n").toByteArray(Charsets.ISO_8859_1)) ||
          sink.hasExactBytesAfter(checkpoint, (expectedEscape + "\n").toByteArray(Charsets.ISO_8859_1))
      }
      assertTrue(failure, failure == null)
      android.util.Log.i(LOG_TAG, "ALPINE_P2_ESC_OK")

      // 5. sleep + Ctrl-C (VINTR byte 0x03), proving an interrupt, not "^" "C".
      checkpoint = sink.checkpoint()
      writeCommand(supervisor, sessionId, "stty -a; printf 'P2_TTY_STATE_END\\n'")
      failure = awaitCondition(30_000, "tty state marker never arrived; tail=${sink.tail()}") {
        sink.hasExactLinesAfter(checkpoint, "P2_TTY_STATE_END")
      }
      assertTrue(failure, failure == null)
      android.util.Log.i(LOG_TAG, "ALPINE_P2_TTY_STATE ${sink.tail()}")
      failure = awaitCondition(10_000, "shell prompt did not return after tty state; tail=${sink.tail()}") {
        sink.text().let { it.substring(checkpoint.coerceAtMost(it.length)) }.contains("localhost:~# ")
      }
      assertTrue(failure, failure == null)

      // 6. Live /proc output + VINTR: top must render a process table and
      // return to the shell when interrupted instead of becoming stuck.
      // BusyBox indents its PID column. Match the complete header line;
      // batch mode needs no CPR replies injected into the shell input.
      val topHeader = Regex("(^|[\\r\\n])[ \\t]*PID[ \\t]+PPID[ \\t]+USER[ \\t]+STAT[ \\t]+VSZ[ \\t]+%VSZ[ \\t]+CPU[ \\t]+%CPU[ \\t]+COMMAND[\\r\\n]")
      checkpoint = sink.checkpoint()
      writeCommand(supervisor, sessionId, "printf '\\nP2_TOP_BATCH_BEGIN\\n'; top -b -n 1; printf '\\nP2_TOP_BATCH_RESULT:%s\\nP2_TOP_BATCH_END\\n' \"\$?\"")
      failure = awaitCondition(30_000, "top batch never rendered a process header") {
        val text = sink.text()
        val segment = text.substring(checkpoint.coerceAtMost(text.length))
        sink.hasExactLinesAfter(checkpoint, "P2_TOP_BATCH_BEGIN") &&
          topHeader.containsMatchIn(segment) &&
          sink.hasExactLinesAfter(checkpoint, "P2_TOP_BATCH_RESULT:0", "P2_TOP_BATCH_END")
      }
      assertTrue("$failure; tail=${sink.tail()}", failure == null)

      checkpoint = sink.checkpoint()
      writeCommand(supervisor, sessionId, "printf '\\nP2_TOP_BEGIN\\n'; top -b")
      failure = awaitCondition(30_000, "continuous top never rendered a process header") {
        val text = sink.text()
        val segment = text.substring(checkpoint.coerceAtMost(text.length))
        sink.hasExactLinesAfter(checkpoint, "P2_TOP_BEGIN") && topHeader.containsMatchIn(segment)
      }
      assertTrue("$failure; tail=${sink.tail()}", failure == null)
      val topCtrlCCheckpoint = sink.checkpoint()
      val topCtrlC = supervisor.write(sessionId, byteArrayOf(0x03))
      assertTrue("top ctrl-c write failed: $topCtrlC", topCtrlC is TerminalSessionSupervisor.WriteOutcome.Success)
      failure = awaitCondition(10_000, "shell prompt never returned after top ctrl-c") {
        sink.text().let { it.substring(topCtrlCCheckpoint.coerceAtMost(it.length)) }.contains("localhost:~# ")
      }
      assertTrue("$failure; tail=${sink.tail()}", failure == null)
      checkpoint = sink.checkpoint()
      writeCommand(supervisor, sessionId, "printf '\\nP2_TOP_CTRLC:%s\\nP2_TOP_END\\n' \"\$?\"")
      failure = awaitCondition(30_000, "top ctrl-c result marker never arrived") {
        sink.hasExactLinesAfter(checkpoint, "P2_TOP_CTRLC:130", "P2_TOP_END")
      }
      assertTrue("$failure; tail=${sink.tail()}", failure == null)
      android.util.Log.i(LOG_TAG, "ALPINE_P2_TOP_OK")

      // 7. sleep + Ctrl-C (VINTR byte 0x03), proving an interrupt, not "^" "C".
      writeCommand(supervisor, sessionId, "sleep 30")
      // Diagnostic pause: inspect the device process groups while sleep is
      // definitely in the foreground before injecting VINTR.
      Thread.sleep(3_000)
      android.util.Log.i(
        LOG_TAG,
        "ALPINE_P2_BEFORE_CTRL_C descendants=${processTree.descendants(handle.pid.toLong())} session=${processTree.sessionMembers(handle.pid.toLong())}",
      )
      val ctrlCCheckpoint = sink.checkpoint()
      val ctrlC = supervisor.write(sessionId, byteArrayOf(0x03))
      assertTrue("ctrl-c write failed: $ctrlC", ctrlC is TerminalSessionSupervisor.WriteOutcome.Success)
      android.util.Log.i(
        LOG_TAG,
        "ALPINE_P2_AFTER_CTRL_C descendants=${processTree.descendants(handle.pid.toLong())} session=${processTree.sessionMembers(handle.pid.toLong())}",
      )
      failure = awaitCondition(10_000, "shell prompt never returned after ctrl-c; tail=${sink.tail()}") {
        sink.text().let { it.substring(ctrlCCheckpoint.coerceAtMost(it.length)) }.contains("localhost:~# ")
      }
      assertTrue(failure, failure == null)
      checkpoint = sink.checkpoint()
      writeCommand(supervisor, sessionId, "printf 'P2_CTRLC_BEGIN:%s\\nP2_CTRLC_END\\n' \"\$?\"")
      failure = awaitCondition(30_000, "ctrl-c did not interrupt sleep; tail=${sink.tail()}") {
        sink.hasExactLinesAfter(checkpoint, "P2_CTRLC_BEGIN:130", "P2_CTRLC_END")
      }
      assertTrue(failure, failure == null)
      android.util.Log.i(LOG_TAG, "ALPINE_P2_CTRL_C_OK status=130")

      // 6. Resize changes stty size inside the guest.
      checkpoint = sink.checkpoint()
      writeCommand(supervisor, sessionId, "printf 'P2_SIZE1_BEGIN\\n'; stty size; printf 'P2_SIZE1_END\\n'")
      failure = awaitCondition(30_000, "initial stty size missing; tail=${sink.tail()}") {
        sink.hasExactLinesAfter(checkpoint, "P2_SIZE1_BEGIN", "24 80", "P2_SIZE1_END")
      }
      assertTrue(failure, failure == null)
      assertTrue(
        "resize was not applied",
        supervisor.resize(sessionId, 40, 120) == TerminalSessionSupervisor.SessionOpOutcome.APPLIED,
      )
      checkpoint = sink.checkpoint()
      writeCommand(supervisor, sessionId, "printf 'P2_SIZE2_BEGIN\\n'; stty size; printf 'P2_SIZE2_END\\n'")
      failure = awaitCondition(30_000, "resized stty size missing; tail=${sink.tail()}") {
        sink.hasExactLinesAfter(checkpoint, "P2_SIZE2_BEGIN", "40 120", "P2_SIZE2_END")
      }
      assertTrue(failure, failure == null)
      android.util.Log.i(LOG_TAG, "ALPINE_P2_RESIZE_OK 24x80->40x120")

      // 7. Bulk ordered output with SIGPIPE working under PRoot.
      checkpoint = sink.checkpoint()
      writeCommand(supervisor, sessionId, "printf 'P2_YES_BEGIN\\n'; yes | head -c 4096 | wc -c; printf 'P2_YES_END\\n'")
      failure = awaitCondition(30_000, "yes/wc pipeline never completed; tail=${sink.tail()}") {
        sink.hasExactLinesAfter(checkpoint, "P2_YES_BEGIN", "4096", "P2_YES_END")
      }
      assertTrue(failure, failure == null)
      android.util.Log.i(LOG_TAG, "ALPINE_P2_YES_OK bytes=4096")

      // 8. Ordered stream: every output event advanced the sequence by one.
      assertTrue("output sequence was not strictly ordered: ${sink.seqs.take(32)}", sink.seqs.zipWithNext().all { (a, b) -> b == a + 1 })

      // 9. Natural shell exit emits exactly one exit event with code 0.
      writeCommand(supervisor, sessionId, "exit")
      val exit = awaitExit(listener, expectedCount = 1, timeoutMs = 30_000)
      assertTrue("session did not exit; tail=${sink.tail()}", exit != null)
      assertEquals(0, exit!!.exitCode)
      assertEquals(TerminalSessionSupervisor.REASON_PROCESS_EXIT, exit.reason)
      Thread.sleep(500)
      assertEquals("exit event was not emitted exactly once", 1, listener.exits.size)
      android.util.Log.i(
        LOG_TAG,
        "ALPINE_P2_EXIT_OK sessionId=$sessionId exitCode=${exit.exitCode ?: "none"} signal=${exit.signal ?: "none"} reason=${exit.reason}",
      )

      // Session 2: a stop with a long-running foreground command leaves no orphan.
      val secondId = supervisor.nextSessionId()
      sink.seqs.clear()
      listener.currentSessionId = secondId
      val second = supervisor.start(secondId, launcher.interactiveShellLaunchSpec(rootfsDir, paths.home, sessionCommand = "exec /bin/sh -l").let {
        TerminalSessionSupervisor.StartSpec(
          argv = it.argv,
          environment = it.environment,
          workingDirectory = it.workingDirectory,
          rows = 24,
          columns = 80,
        )
      })
      assertTrue("second session failed to start: $second", second is TerminalSessionSupervisor.StartOutcome.Success)
      val secondHandle = (second as TerminalSessionSupervisor.StartOutcome.Success).handle
      writeCommand(supervisor, secondId, "sleep 300")
      Thread.sleep(2_000)

      val stop = supervisor.stop(secondId, "user_stop")
      assertTrue("stop failed: $stop", stop is TerminalSessionSupervisor.StopOutcome.Stopped)
      val observation = (stop as TerminalSessionSupervisor.StopOutcome.Stopped).observation
      assertEquals("stop left processes behind", 0, observation.remainingProcessCount)
      assertTrue("stop did not finish within its escalation deadline", observation.stoppedWithinDeadline)

      // Independent observation: nothing in the session survives.
      val stragglers = processTree.sessionMembers(secondHandle.pid.toLong()) +
        processTree.descendants(secondHandle.pid.toLong()) +
        if (processTree.isAlive(secondHandle.pid.toLong())) listOf(secondHandle.pid.toLong()) else emptyList()
      assertTrue("processes survived the stop: $stragglers", stragglers.isEmpty())
      val secondExit = awaitExit(listener, expectedCount = 2, timeoutMs = 10_000)
      assertTrue("stopped session never reported an exit", secondExit != null)
      android.util.Log.i(
        LOG_TAG,
        "ALPINE_P2_STOP_OK sessionId=$secondId remaining=${observation.remainingProcessCount} stragglers=${stragglers.size} stoppedWithinDeadline=${observation.stoppedWithinDeadline} exitSignal=${secondExit?.signal ?: "none"} exitCode=${secondExit?.exitCode ?: "none"} reason=${secondExit?.reason}",
      )

      // The first session must still be the only exit before the second one.
      assertEquals(2, listener.exits.size)
      assertEquals(0, supervisor.activeSessionIds().size)
      android.util.Log.i(LOG_TAG, "ALPINE_P2_SESSIONS_CLEAN active=0 exits=2")
    } finally {
      runCatching { supervisor.shutdownAll("device_test_teardown") }
      runCatching { store.reset() }
      testRoot.deleteRecursively()
    }
  }

  private companion object {
    const val LOG_TAG = "AlpineP2DeviceTest"
    const val DIAG_TAG = "AlpineP2Diag"
  }
}
