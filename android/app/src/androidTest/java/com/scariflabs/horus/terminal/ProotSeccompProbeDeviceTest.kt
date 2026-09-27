package com.scariflabs.horus.terminal

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Diagnostic probe: compares PRoot with PROOT_NO_SECCOMP=1 (the shipped
 * default) against PRoot's seccomp acceleration on the connected device.
 *
 * It runs against the already-installed active rootfs read-only (it never
 * installs, resets, or writes the user's home: guest HOME is a private
 * directory deleted in finally). Every step has its own deadline; a hang or
 * crash is recorded as a result, the session is killed, and the next step
 * runs in a fresh session. Only the seccomp-off mode is asserted.
 *
 * Greppable markers (tag SeccompProbe):
 *   SC_RESULT mode=<off|on> step=<name> status=<PASS|FAIL|HANG|EXIT|SKIP> ms=<n> detail=<...>
 *   SC_TIMING mode=<off|on> bench=<name> runs=<ms,ms,ms> median=<ms>
 *   SC_VERBOSE mode=<off|on> ...   (PRoot -v 1 output lines mentioning seccomp)
 *   SC_SUMMARY ...
 */
@RunWith(AndroidJUnit4::class)
class ProotSeccompProbeDeviceTest {

  private class OutputSink {
    private val lock = Object()
    private val stream = ByteArrayOutputStream()

    fun append(chunk: ByteArray) = synchronized(lock) { stream.write(chunk) }

    fun checkpoint(): Int = synchronized(lock) { stream.size() }

    fun linesAfter(offset: Int): List<String> {
      val body = synchronized(lock) {
        val bytes = stream.toByteArray()
        val start = offset.coerceIn(0, bytes.size)
        String(bytes, start, bytes.size - start, Charsets.ISO_8859_1)
      }
      return body.replace(ANSI_ESCAPE, "").replace("\r\n", "\n").replace('\r', '\n').split('\n').map(String::trim).filter(String::isNotEmpty)
    }

    fun tail(limit: Int = 600): String = synchronized(lock) {
      val bytes = stream.toByteArray()
      String(bytes, maxOf(0, bytes.size - limit), minOf(limit, bytes.size), Charsets.ISO_8859_1)
        .replace(Regex("[^\\x20-\\x7e]"), ".")
    }
  }

  private class Listener : TerminalSessionSupervisor.EventListener {
    @Volatile var currentSessionId = ""
    @Volatile var sink = OutputSink()
    var acknowledge: ((String, Long) -> Unit)? = null
    val exits = CopyOnWriteArrayList<TerminalSessionSupervisor.SessionExitInfo>()

    override fun onSessionOutput(sessionId: String, seq: Long, chunk: ByteArray) {
      if (sessionId == currentSessionId) sink.append(chunk)
      acknowledge?.invoke(sessionId, seq)
    }

    override fun onSessionExit(info: TerminalSessionSupervisor.SessionExitInfo) {
      exits += info
    }

    fun exitFor(sessionId: String) = exits.firstOrNull { it.sessionId == sessionId }
  }

  private data class Step(
    val name: String,
    val command: String,
    val timeoutMs: Long,
    val requiresNode: Boolean = false,
    val validate: (List<String>, Int) -> String?,
  )

  private data class StepOutcome(val status: String, val ms: Long, val body: List<String>, val detail: String)

  private inner class ModeRunner(
    private val mode: String,
    private val launcher: ProotSessionLauncher,
    private val rootfsDir: File,
    private val homeDir: File,
  ) {
    private val processTree = ProcessTree()
    private val listener = Listener()
    private val supervisor = TerminalSessionSupervisor(
      backend = JniPtyBackend(),
      listener = listener,
      processTree = processTree,
      sendSignal = { pid, signal -> android.system.Os.kill(pid, signal) },
    )
    private var sessionId: String? = null
    private var sessionPid: Long = -1
    private var counter = 0
    var restarts = 0
      private set

    init {
      listener.acknowledge = { id, seq -> supervisor.acknowledgeOutput(id, seq) }
    }

    /** Opens a fresh guest shell; returns null on success or a failure description. */
    fun open(): Pair<String?, Long> {
      val started = System.nanoTime()
      val spec = launcher.interactiveShellLaunchSpec(rootfsDir, homeDir, sessionCommand = "exec /bin/sh -i")
      val id = supervisor.nextSessionId()
      listener.sink = OutputSink()
      listener.currentSessionId = id
      val start = supervisor.start(
        id,
        TerminalSessionSupervisor.StartSpec(
          argv = spec.argv,
          environment = spec.environment,
          workingDirectory = spec.workingDirectory,
          rows = 24,
          columns = 120,
        ),
      )
      if (start !is TerminalSessionSupervisor.StartOutcome.Success) {
        return "start_failed:$start" to elapsedMs(started)
      }
      sessionId = id
      sessionPid = start.handle.pid.toLong()
      val write = supervisor.write(id, "stty -echo; PS1=''; printf '\\nSC_READY\\n'\n".toByteArray(Charsets.ISO_8859_1))
      if (write !is TerminalSessionSupervisor.WriteOutcome.Success) return "write_failed:$write" to elapsedMs(started)
      val deadline = System.currentTimeMillis() + SHELL_OPEN_TIMEOUT_MS
      while (System.currentTimeMillis() < deadline) {
        if (listener.sink.linesAfter(0).contains("SC_READY")) return null to elapsedMs(started)
        listener.exitFor(id)?.let { return "exited_before_ready:code=${it.exitCode} signal=${it.signal} reason=${it.reason} tail=${listener.sink.tail()}" to elapsedMs(started) }
        Thread.sleep(POLL_MS)
      }
      return "ready_marker_timeout" to elapsedMs(started)
    }

    fun run(step: Step): StepOutcome {
      val id = sessionId ?: return StepOutcome("SKIP", 0, emptyList(), "no_session")
      val n = ++counter
      val begin = "SC_B_$n"
      val endPattern = Regex("^SC_E_$n:(\\d+)$")
      val checkpoint = listener.sink.checkpoint()
      val line = "printf '\\n$begin\\n'; ${step.command}; printf '\\nSC_E_$n:%s\\n' \"\$?\"\n"
      val started = System.nanoTime()
      val write = supervisor.write(id, line.toByteArray(Charsets.ISO_8859_1))
      if (write !is TerminalSessionSupervisor.WriteOutcome.Success) {
        return StepOutcome("FAIL", elapsedMs(started), emptyList(), "write_failed:$write")
      }
      val deadline = System.currentTimeMillis() + step.timeoutMs
      while (System.currentTimeMillis() < deadline) {
        val lines = listener.sink.linesAfter(checkpoint)
        val b = lines.indexOf(begin)
        val e = lines.indexOfFirst { endPattern.matches(it) }
        if (b >= 0 && e > b) {
          val ms = elapsedMs(started)
          val body = lines.subList(b + 1, e)
          val rc = endPattern.find(lines[e])!!.groupValues[1].toInt()
          val problem = step.validate(body, rc)
          return StepOutcome(if (problem == null) "PASS" else "FAIL", ms, body, problem ?: "rc=$rc")
        }
        listener.exitFor(id)?.let {
          return StepOutcome("EXIT", elapsedMs(started), emptyList(), "session_exited:code=${it.exitCode} signal=${it.signal} reason=${it.reason} tail=${listener.sink.tail()}")
        }
        Thread.sleep(POLL_MS)
      }
      return StepOutcome("HANG", elapsedMs(started), emptyList(), "timeout=${step.timeoutMs}ms procs=${describeProcesses()} tail=${listener.sink.tail()}")
    }

    /** Kills the current session and opens a new one. */
    fun recover(): String? {
      kill()
      if (restarts >= MAX_RESTARTS) return "restart_budget_exhausted"
      restarts++
      return open().first
    }

    fun describeProcesses(): String {
      if (sessionPid <= 0) return "none"
      val pids = listOf(sessionPid) + processTree.descendants(sessionPid)
      return pids.joinToString(";") { pid ->
        val status = runCatching { File("/proc/$pid/status").readLines() }.getOrDefault(emptyList())
        fun field(key: String) = status.firstOrNull { it.startsWith("$key:") }?.substringAfter(':')?.trim()?.replace(Regex("\\s+"), " ")
        val wchan = runCatching { File("/proc/$pid/wchan").readText().trim() }.getOrDefault("?")
        "$pid:${field("Name")}:${field("State")}:tracer=${field("TracerPid")}:seccomp=${field("Seccomp")}:wchan=$wchan"
      }
    }

    fun kill() {
      val id = sessionId ?: return
      val stop = runCatching { supervisor.stop(id, "device_test_teardown") }
      val remaining = if (sessionPid > 0) processTree.descendants(sessionPid).size + (if (processTree.isAlive(sessionPid)) 1 else 0) else 0
      Log.i(LOG_TAG, "SC_SESSION_STOP mode=$mode sessionId=$id outcome=${stop.toString().take(200)} remaining=$remaining")
      sessionId = null
      sessionPid = -1
    }

    fun exitCleanly() {
      val id = sessionId ?: return
      supervisor.write(id, "exit\n".toByteArray(Charsets.ISO_8859_1))
      val deadline = System.currentTimeMillis() + 15_000
      while (System.currentTimeMillis() < deadline && listener.exitFor(id) == null) Thread.sleep(POLL_MS)
      val exit = listener.exitFor(id)
      Log.i(LOG_TAG, "SC_SESSION_EXIT mode=$mode code=${exit?.exitCode} signal=${exit?.signal} reason=${exit?.reason}")
      kill()
    }

    fun shutdown() {
      kill()
      runCatching { supervisor.shutdownAll("device_test_teardown") }
    }
  }

  @Test(timeout = 2_400_000)
  fun compareSeccompOffAndOn() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    assertEquals("arm64-v8a", android.os.Build.SUPPORTED_ABIS.firstOrNull())
    Log.i(LOG_TAG, "SC_DEVICE model=${android.os.Build.MODEL} sdk=${android.os.Build.VERSION.SDK_INT} kernel=${System.getProperty("os.version")}")

    val manifestText = context.assets.open("alpine-runtime/manifest.json").bufferedReader().use { it.readText() }
    val located = ProotRuntimeLocator(manifestText).locate(File(context.applicationInfo.nativeLibraryDir ?: ""))
    assertTrue("packaged PRoot runtime is unavailable", located is ProotRuntimeLocator.Location.Available)
    val runtime = (located as ProotRuntimeLocator.Location.Available).runtime

    // Read-only lookup of the user's installed rootfs; never install/reset here.
    val realPaths = DistroStorePaths(File(context.filesDir, TerminalRuntimeContract.STORAGE_ROOT_DIR_NAME))
    val testRoot = File(context.filesDir, "seccomp-probe-${System.nanoTime()}")
    val readOnlyStore = DistroStoreCore(
      paths = realPaths,
      downloader = HttpArchiveDownloader(),
      extractor = SafeTarGzExtractor(),
      prober = ProotGuestProber(runtime, File(testRoot, "prober-scratch")),
    )
    val record = readOnlyStore.readActiveRecord()
    if (record == null) {
      Log.e(LOG_TAG, "SC_ROOTFS_MISSING no active installed rootfs; not provisioning")
    }
    assertTrue("no installed active rootfs on this device", record != null)
    val rootfsDir = realPaths.versionRootfs(record!!.rootfsId)
    Log.i(LOG_TAG, "SC_ROOTFS id=${record.rootfsId} release=${record.alpineRelease}")

    val results = mutableListOf<Triple<String, String, StepOutcome>>()
    try {
      for (mode in listOf("off", "on")) {
        val modeRoot = File(testRoot, mode)
        val home = File(modeRoot, "home").apply { mkdirs() }
        val launcher = ProotSessionLauncher(
          runtime = runtime,
          scratchDir = File(modeRoot, "scratch"),
          seccompAcceleration = mode == "on",
        )
        verboseProbe(mode, launcher, rootfsDir, home)
        results += runMode(mode, launcher, rootfsDir, home).map { Triple(mode, it.first, it.second) }
      }
    } finally {
      testRoot.deleteRecursively()
    }

    for (mode in listOf("off", "on")) {
      val modeResults = results.filter { it.first == mode }
      Log.i(
        LOG_TAG,
        "SC_SUMMARY mode=$mode " + modeResults.groupBy { it.third.status }.entries.joinToString(" ") { "${it.key}=${it.value.size}" } +
          " failing=" + modeResults.filter { it.third.status != "PASS" && it.third.status != "SKIP" }.joinToString(",") { it.second },
      )
    }
    val offCore = results.filter { it.first == "off" && it.second in CORE_STEPS }
    assertTrue(
      "seccomp-off baseline failed: ${offCore.filter { it.third.status != "PASS" }.map { "${it.second}=${it.third.status}:${it.third.detail.take(200)}" }}",
      offCore.size == CORE_STEPS.size && offCore.all { it.third.status == "PASS" },
    )
  }

  /** One non-PTY PRoot run with -v 1 so PRoot reports whether seccomp mode engaged. */
  private fun verboseProbe(mode: String, launcher: ProotSessionLauncher, rootfsDir: File, home: File) {
    val spec = launcher.interactiveShellLaunchSpec(rootfsDir, home, sessionCommand = "printf 'SC_VERBOSE_GUEST_OK\\n'")
    val argv = listOf(spec.argv.first(), "-v", "1") + spec.argv.drop(1)
    val builder = ProcessBuilder(argv).redirectErrorStream(true)
    builder.environment().clear()
    spec.environment.forEach { entry -> builder.environment()[entry.substringBefore('=')] = entry.substringAfter('=') }
    val started = System.nanoTime()
    val process = builder.start()
    process.outputStream.close()
    val collected = ByteArrayOutputStream()
    val reader = Thread {
      runCatching {
        val buffer = ByteArray(4096)
        val input = process.inputStream
        while (true) {
          val read = input.read(buffer)
          if (read < 0) break
          if (collected.size() < 65_536) collected.write(buffer, 0, read)
        }
      }
    }.apply { isDaemon = true; start() }
    val finished = process.waitFor(VERBOSE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    if (!finished) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
    reader.join(2_000)
    val lines = String(collected.toByteArray(), Charsets.ISO_8859_1).lines().filter(String::isNotBlank)
    Log.i(LOG_TAG, "SC_VERBOSE mode=$mode finished=$finished exit=${if (finished) process.exitValue() else "killed"} ms=${elapsedMs(started)} guestOk=${lines.any { it.trim() == "SC_VERBOSE_GUEST_OK" }} lines=${lines.size}")
    lines.filterNot { it.contains("proot info: binding =") }
      .take(60)
      .forEach { Log.i(LOG_TAG, "SC_VERBOSE mode=$mode line=${it.take(300)}") }
  }

  private fun runMode(mode: String, launcher: ProotSessionLauncher, rootfsDir: File, home: File): List<Pair<String, StepOutcome>> {
    val out = mutableListOf<Pair<String, StepOutcome>>()
    fun record(name: String, outcome: StepOutcome) {
      out += name to outcome
      Log.i(LOG_TAG, "SC_RESULT mode=$mode step=$name status=${outcome.status} ms=${outcome.ms} detail=${outcome.detail.take(900)}")
    }
    val runner = ModeRunner(mode, launcher, rootfsDir, home)
    try {
      val (openFailure, openMs) = runner.open()
      record("shell_open", StepOutcome(if (openFailure == null) "PASS" else "FAIL", openMs, emptyList(), openFailure ?: "ready"))
      if (openFailure != null) {
        Log.i(LOG_TAG, "SC_RESULT mode=$mode step=shell_open_procs detail=${runner.describeProcesses()}")
        val retry = runner.recover()
        if (retry != null) {
          record("remaining", StepOutcome("SKIP", 0, emptyList(), "shell never opened: $retry"))
          return out
        }
      }
      Log.i(LOG_TAG, "SC_PROCS mode=$mode ${runner.describeProcesses()}")

      var nodePresent = false
      val timings = linkedMapOf<String, MutableList<Long>>()
      for (step in steps()) {
        if (step.requiresNode && !nodePresent) {
          record(step.name, StepOutcome("SKIP", 0, emptyList(), "node_absent"))
          continue
        }
        val outcome = runner.run(step)
        record(step.name, outcome)
        if (step.name == "node_present" && outcome.status == "PASS") nodePresent = true
        val bench = step.name.substringBefore("_run", missingDelimiterValue = "")
        if (step.name.contains("_run") && outcome.status == "PASS") {
          timings.getOrPut(bench) { mutableListOf() } += outcome.ms
        }
        if (outcome.status == "HANG" || outcome.status == "EXIT") {
          val recovery = runner.recover()
          Log.i(LOG_TAG, "SC_RECOVER mode=$mode after=${step.name} result=${recovery ?: "reopened"} restarts=${runner.restarts}")
          if (recovery != null) {
            record("remaining", StepOutcome("SKIP", 0, emptyList(), "session could not be reopened after ${step.name}: $recovery"))
            break
          }
        }
      }
      timings.forEach { (bench, runs) ->
        Log.i(LOG_TAG, "SC_TIMING mode=$mode bench=$bench runs=${runs.joinToString(",")} median=${runs.sorted()[runs.size / 2]}")
      }
      runner.exitCleanly()
    } finally {
      runner.shutdown()
    }
    return out
  }

  private fun steps(): List<Step> {
    val numeric = Regex("^\\d+$")
    fun expect(vararg lines: String): (List<String>, Int) -> String? = { body, rc ->
      if (rc == 0 && body == lines.toList()) null else "rc=$rc body=${body.take(10)}"
    }
    val count: (List<String>, Int) -> String? = { body, rc ->
      if (rc == 0 && body.size == 1 && numeric.matches(body[0])) null else "rc=$rc body=${body.take(10)}"
    }
    val list = mutableListOf(
      Step("marker", "printf '%s\\n' SC_MARK_BEGIN seccomp_probe_marker SC_MARK_END", 20_000, validate = expect("SC_MARK_BEGIN", "seccomp_probe_marker", "SC_MARK_END")),
      Step("child_sh", "sh -c 'echo child-ok'", 20_000, validate = expect("child-ok")),
      Step("pipeline", "ls / | wc -l", 20_000, validate = count),
      Step("background_wait", "sleep 1 & p=\$!; echo bg-started; wait \$p; echo bg-rc:\$?", 20_000, validate = expect("bg-started", "bg-rc:0")),
      Step("node_present", "command -v node", 20_000) { body, rc ->
        if (rc == 0 && body.size == 1 && body[0].startsWith("/")) null else "rc=$rc body=${body.take(5)}"
      },
      Step("node_print", "node -e 'console.log(1)'", 60_000, requiresNode = true, validate = expect("1")),
    )
    for (run in 1..3) list += Step("find_usr_run$run", "find /usr -type f | wc -l", 180_000, validate = count)
    for (run in 1..3) list += Step("exec_loop_run$run", "i=0; while [ \$i -lt 100 ]; do /bin/true; i=\$((i+1)); done", 120_000, validate = expect())
    for (run in 1..3) list += Step("node_startup_run$run", "node -e 0", 60_000, requiresNode = true, validate = expect())
    return list
  }

  private fun elapsedMs(startNanos: Long) = (System.nanoTime() - startNanos) / 1_000_000

  private companion object {
    const val LOG_TAG = "SeccompProbe"
    const val POLL_MS = 10L
    const val SHELL_OPEN_TIMEOUT_MS = 45_000L
    const val VERBOSE_TIMEOUT_MS = 45_000L
    const val MAX_RESTARTS = 3
    val ANSI_ESCAPE = Regex("\u001b\\[[0-9;?]*[A-Za-z]")
    val CORE_STEPS = setOf("shell_open", "marker", "child_sh", "pipeline", "background_wait")
  }
}
