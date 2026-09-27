package com.scariflabs.horus.terminal

import android.os.SystemClock
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/**
 * Owns the single dropbear PRoot process for opt-in USB SSH. Lives in the
 * :terminal service process next to the PTY sessions so it survives the UI
 * being reclaimed. Restarts a crashed server with a short backoff while the
 * user keeps remote access enabled.
 */
class RemoteAccessServer(
  private val store: RemoteAccess.Store,
  private val processTree: ProcessTree = ProcessTree(),
  private val buildLaunch: () -> TerminalSessionSpecFactory.RemoteAccessBuildOutcome,
  private val onStateChanged: () -> Unit,
) {
  private val lock = Any()
  private var process: Process? = null
  /** dropbear's real pid, reported by the launch script. */
  @Volatile private var serverPid = 0L
  private var generation = 0
  private var consecutiveFailures = 0
  @Volatile private var closed = false
  @Volatile var state: String = RemoteAccess.STATE_STOPPED
    private set

  /** True while the server process exists, including installation. */
  val isActive: Boolean get() = synchronized(lock) { process?.isAlive == true }

  fun start() {
    synchronized(lock) {
      if (closed || process?.isAlive == true) return
      generation += 1
      // A server orphaned when Android killed this process still holds the
      // port; clear it first or the new one cannot listen.
      killStaleServer()
      val launch = when (val built = buildLaunch()) {
        is TerminalSessionSpecFactory.RemoteAccessBuildOutcome.Success -> built.launch
        is TerminalSessionSpecFactory.RemoteAccessBuildOutcome.Failure -> {
          publish(RemoteAccess.STATE_FAILED, built.errorCode)
          return
        }
      }
      val started = try {
        ProcessBuilder(launch.argv).apply {
          launch.workingDirectory?.let(::directory)
          environment().clear()
          launch.environment.forEach { entry ->
            val separator = entry.indexOf('=')
            if (separator > 0) environment()[entry.substring(0, separator)] = entry.substring(separator + 1)
          }
          redirectErrorStream(true)
        }.start()
      } catch (_: Exception) {
        publish(RemoteAccess.STATE_FAILED, "start_failed")
        return
      }
      process = started
      publish(RemoteAccess.STATE_STARTING, "")
      watch(started, generation)
    }
  }

  fun stop() {
    val (stopping, pid) = synchronized(lock) {
      generation += 1
      consecutiveFailures = 0
      (process to serverPid).also {
        process = null
        serverPid = 0L
      }
    }
    killServerTree(pid)
    killStaleServer()
    if (stopping != null) terminate(stopping)
    publish(RemoteAccess.STATE_STOPPED, "")
  }

  fun close() {
    closed = true
    stop()
  }

  private fun watch(started: Process, startedGeneration: Int) {
    val startedAt = SystemClock.elapsedRealtime()
    Thread {
      var lastStage = ""
      try {
        RandomAccessFile(prepareLog(), "rw").use { log ->
          started.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach { line ->
              if (line.startsWith(PID_PREFIX)) {
                line.removePrefix(PID_PREFIX).toLongOrNull()?.let { pid ->
                  synchronized(lock) { if (startedGeneration == generation) serverPid = pid }
                }
                return@forEach
              }
              if (log.length() < MAX_LOG_BYTES) log.write((line.take(MAX_LOG_LINE_CHARS) + "\n").toByteArray(Charsets.UTF_8))
              val stage = line.removePrefix(STAGE_PREFIX).takeIf { line.startsWith(STAGE_PREFIX) } ?: return@forEach
              lastStage = stage
              if (isCurrent(startedGeneration)) {
                when (stage) {
                  "installing" -> publish(RemoteAccess.STATE_INSTALLING, "")
                  "serving" -> publish(RemoteAccess.STATE_RUNNING, "")
                }
              }
            }
          }
        }
      } catch (_: Exception) {
        // Stopping the process closes the stream.
      }
      val exitCode = runCatching { started.waitFor() }.getOrDefault(-1)
      onExit(startedGeneration, startedAt, exitCode, lastStage)
    }.apply {
      name = "horus-remote-access"
      isDaemon = true
    }.start()
  }

  private fun onExit(exitedGeneration: Int, startedAt: Long, exitCode: Int, lastStage: String) {
    val retryDelayMs = synchronized(lock) {
      if (exitedGeneration != generation) return
      process = null
      // Sessions of a crashed server must not linger outside it.
      killServerTree(serverPid)
      serverPid = 0L
      val ranMs = SystemClock.elapsedRealtime() - startedAt
      consecutiveFailures = if (ranMs >= STABLE_RUN_MS) 1 else consecutiveFailures + 1
      val detail = when {
        lastStage.endsWith("_failed") -> lastStage
        else -> "exit_$exitCode"
      }
      publish(RemoteAccess.STATE_FAILED, detail)
      if (closed || !store.isEnabled() || consecutiveFailures > MAX_RESTARTS) return
      RESTART_DELAY_MS * consecutiveFailures
    }
    Thread {
      SystemClock.sleep(retryDelayMs)
      if (isCurrent(exitedGeneration) && store.isEnabled()) start()
    }.apply {
      name = "horus-remote-access-restart"
      isDaemon = true
    }.start()
  }

  private fun isCurrent(expected: Int): Boolean = synchronized(lock) { expected == generation }

  private fun publish(newState: String, detail: String) {
    state = newState
    val pid = if (newState == RemoteAccess.STATE_STOPPED) 0 else android.os.Process.myPid()
    runCatching { store.writeStatus(RemoteAccess.Status(newState, detail, pid)) }
    onStateChanged()
  }

  private fun prepareLog(): File {
    val log = store.logFile
    log.parentFile?.mkdirs()
    // One run per log keeps it small and relevant to the current failure.
    RandomAccessFile(log, "rw").use { it.setLength(0) }
    return log
  }

  /**
   * PRoot does not take its tracees down with it, so kill dropbear and every
   * login it forked by pid before stopping PRoot itself.
   */
  private fun killServerTree(pid: Long) {
    if (pid <= 0L) return
    val tree = listOf(pid) + runCatching { processTree.descendants(pid) }.getOrDefault(emptyList())
    tree.forEach { target -> runCatching { android.system.Os.kill(target.toInt(), android.system.OsConstants.SIGKILL) } }
  }

  private fun killStaleServer() {
    val pid = store.readServerPid()
    if (pid <= 0L) return
    // Only ever a dropbear of ours: pids are reused, and /proc of other apps
    // is hidden anyway.
    val cmdline = runCatching { File("/proc/$pid/cmdline").readText().replace('\u0000', ' ') }.getOrDefault("")
    if (cmdline.startsWith("dropbear ")) killServerTree(pid)
    store.serverPidFile.delete()
  }

  private fun terminate(target: Process) {
    runCatching { target.destroy() }
    if (runCatching { target.waitFor(STOP_WAIT_MS, TimeUnit.MILLISECONDS) }.getOrDefault(false)) return
    runCatching { target.destroyForcibly() }
    runCatching { target.waitFor(STOP_WAIT_MS, TimeUnit.MILLISECONDS) }
  }

  private companion object {
    const val STAGE_PREFIX = "HORUS_REMOTE_STAGE="
    const val PID_PREFIX = "HORUS_REMOTE_PID="
    const val MAX_LOG_BYTES = 64 * 1024L
    const val MAX_LOG_LINE_CHARS = 512
    const val STOP_WAIT_MS = 2_000L
    const val STABLE_RUN_MS = 60_000L
    const val RESTART_DELAY_MS = 5_000L
    const val MAX_RESTARTS = 4
  }
}
