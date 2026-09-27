package com.scariflabs.horus.terminal

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Launches the pinned non-interactive guest probe through PRoot with a fixed
 * argument vector (never a concatenated shell string) and an explicit guest
 * environment. First-launcher policy: bind only the guest rootfs
 * and the persistent home; /dev, /proc, and /sys stay unbound unless a probe
 * proves otherwise.
 *
 * Environment decisions, recorded deliberately:
 *  - PROOT_LOADER/PROOT_LOADER_32 override the compiled-in Termux libexec
 *    paths with this app's native library directory;
 *  - PROOT_TMP_DIR points at app-private scratch because Android has no
 *    writable /tmp for the loader staging copy;
 *  - PROOT_NO_SECCOMP=1 avoids seccomp-filter interactions on modern Android
 *    kernels. The pinned PRoot build is exercised in ptrace-only mode because
 *    its seccomp path is not reliable on the supported Android API level;
 *  - PROOT_NO_O_TMPFILE=1 disables the /proc/self/fd O_TMPFILE path, matching
 *    the interactive launcher so apk falls back to named temp/rename;
 *  - LD_LIBRARY_PATH resolves libtalloc.so and libandroid-shmem.so from the
 *    native library directory;
 *  - the child environment is built from scratch; no Android or host-shell
 *    variables leak into the guest.
 */
class ProotGuestProber(
  private val runtime: ProotRuntimeLocator.LocatedRuntime,
  private val scratchDir: File,
) : GuestProber {

  override fun probe(rootfsDir: File, guestHomeDir: File, timeoutMs: Long): GuestProbeResult {
    require(timeoutMs > 0) { "probe timeout must be positive" }
    require(rootfsDir.isDirectory) { "rootfs directory is missing" }
    require(guestHomeDir.isDirectory) { "guest home directory is missing" }
    if (!scratchDir.exists() && !scratchDir.mkdirs() && !scratchDir.isDirectory) {
      throw IllegalStateException("cannot create proot scratch directory")
    }
    val startedAt = System.currentTimeMillis()
    val rootfsPath = rootfsDir.canonicalFile.absolutePath
    val homePath = guestHomeDir.canonicalFile.absolutePath
    val scratchPath = scratchDir.canonicalFile.absolutePath
    // PRoot on the supported Android API level can run a single guest
    // executable reliably in ptrace-only mode, but a shell that subsequently
    // execs several commands may hang in the ptrace event path. Keep the
    // probe non-interactive and bounded while launching each required guest
    // executable in its own PRoot process. The host only assembles the marker
    // block after every value has been observed from the guest rootfs.
    val commands = listOf(
      ProbeCommand(listOf("/bin/uname", "-m"), AlpineRootfsCatalog.ProbeMarkers.EXPECTED_ARCH),
      ProbeCommand(listOf("/bin/cat", "/etc/alpine-release"), AlpineRootfsCatalog.ALPINE_RELEASE),
      ProbeCommand(listOf("/bin/sh", "-c", "printf '%s\\n' \"\$HOME\""), AlpineRootfsCatalog.ProbeMarkers.EXPECTED_HOME),
      ProbeCommand(listOf("/bin/sh", "-c", "command -v apk"), AlpineRootfsCatalog.ProbeMarkers.EXPECTED_APK),
      ProbeCommand(listOf("/bin/sh", "-c", "command -v sh"), AlpineRootfsCatalog.ProbeMarkers.EXPECTED_SH),
    )
    val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    val observed = mutableListOf<String>()
    for (command in commands) {
      val remainingNanos = deadlineNanos - System.nanoTime()
      if (remainingNanos <= 0L) {
        return GuestProbeResult(
          exitCode = -1,
          output = observed.joinToString("\n"),
          durationMs = System.currentTimeMillis() - startedAt,
          timedOut = true,
        )
      }
      val commandResult = runGuestCommand(
        rootfsPath = rootfsPath,
        homePath = homePath,
        scratchPath = scratchPath,
        guestCommand = command.argv,
        timeoutMs = maxOf(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos)),
      )
      val rawOutput = commandResult.output.trim()
      if (commandResult.timedOut || commandResult.exitCode != 0) {
        return GuestProbeResult(
          exitCode = commandResult.exitCode,
          output = (observed + rawOutput).filter { it.isNotEmpty() }.joinToString("\n"),
          durationMs = System.currentTimeMillis() - startedAt,
          timedOut = commandResult.timedOut,
        )
      }
      val lines = rawOutput.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
      if (lines.size != 1 || lines.single() != command.expectedOutput) {
        return GuestProbeResult(
          exitCode = 1,
          output = (observed + rawOutput).filter { it.isNotEmpty() }.joinToString("\n"),
          durationMs = System.currentTimeMillis() - startedAt,
        )
      }
      observed += lines.single()
    }

    val markerOutput = listOf(
      AlpineRootfsCatalog.ProbeMarkers.BEGIN,
      *observed.toTypedArray(),
      AlpineRootfsCatalog.ProbeMarkers.END,
    ).joinToString("\n") + "\n"
    return GuestProbeResult(
      exitCode = 0,
      output = markerOutput,
      durationMs = System.currentTimeMillis() - startedAt,
    )
  }

  private data class ProbeCommand(
    val argv: List<String>,
    val expectedOutput: String,
  )

  private data class CommandResult(
    val exitCode: Int,
    val output: String,
    val timedOut: Boolean,
  )

  private fun runGuestCommand(
    rootfsPath: String,
    homePath: String,
    scratchPath: String,
    guestCommand: List<String>,
    timeoutMs: Long,
  ): CommandResult {
    val argv = listOf(
      runtime.prootBin.absolutePath,
      "-0",
      "-r", rootfsPath,
      "-b", "$homePath:/root",
      "-w", "/root",
    ) + guestCommand
    val process = ProcessBuilder(argv).apply {
      redirectErrorStream(true)
      environment().clear()
      environment()["PROOT_LOADER"] = runtime.loaderBin.absolutePath
      environment()["PROOT_LOADER_32"] = runtime.loaderBin.absolutePath
      environment()["PROOT_TMP_DIR"] = scratchPath
      // The bundled PRoot seccomp path crashes on the supported device/API;
      // ptrace-only mode is the verified compatibility path for this PoC.
      environment()["PROOT_NO_SECCOMP"] = "1"
      // Keep first-boot probing aligned with interactive apk provisioning:
      // Android PRoot cannot complete O_TMPFILE linkat through /proc/self/fd.
      environment()["PROOT_NO_O_TMPFILE"] = "1"
      environment()["LD_LIBRARY_PATH"] = runtime.libraryDir.absolutePath
      environment()["HOME"] = "/root"
      environment()["TERM"] = "dumb"
      environment()["PATH"] = "/usr/sbin:/usr/bin:/sbin:/bin"
      environment()["LANG"] = "C.UTF-8"
    }.start()

    // Drain on a reader thread so a silent guest cannot block the timeout.
    val output = StringBuilder()
    val reader = Thread {
      try {
        process.inputStream.use { input ->
          val buffer = ByteArray(8192)
          while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            synchronized(output) {
              val remaining = MAX_PROBE_OUTPUT_CHARS - output.length
              if (remaining > 0) {
                output.append(String(buffer, 0, minOf(read, remaining), Charsets.UTF_8))
              }
            }
            if (synchronized(output) { output.length >= MAX_PROBE_OUTPUT_CHARS }) break
          }
        }
      } catch (_: Exception) {
        // The exit path closes the stream; the process result remains authoritative.
      }
    }
    reader.isDaemon = true
    reader.start()

    var timedOut = false
    try {
      if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
        timedOut = true
        terminateProcessTree(process)
      }
      reader.join(2_000)
    } catch (error: Exception) {
      terminateProcessTree(process)
      throw error
    } finally {
      runCatching { process.inputStream.close() }
      if (reader.isAlive) {
        reader.interrupt()
        runCatching { reader.join(500) }
      }
    }
    return CommandResult(
      exitCode = if (timedOut) -1 else runCatching { process.exitValue() }.getOrDefault(-1),
      output = synchronized(output) { output.toString() },
      timedOut = timedOut,
    )
  }

  companion object {
    const val MAX_PROBE_OUTPUT_CHARS = 64 * 1024
    private const val MAX_DESCENDANT_DEPTH = 8
    private const val MAX_DESCENDANTS = 128
    private const val TERMINATION_WAIT_MS = 2_000L

    /**
     * PRoot can fork the guest loader and shell. Process.destroy() only
     * targets the direct PRoot process, so timeout cleanup also snapshots and
     * terminates a bounded descendant set. Every wait is bounded; a probe
     * cannot leave an unowned guest process behind indefinitely.
     */
    private fun terminateProcessTree(process: Process) {
      val rootPid = processPid(process)
      val before = rootPid?.let { descendants(it) }.orEmpty()
      runCatching {
        process.destroy()
        if (!process.waitFor(TERMINATION_WAIT_MS, TimeUnit.MILLISECONDS) && process.isAlive) {
          process.destroyForcibly()
          process.waitFor(TERMINATION_WAIT_MS, TimeUnit.MILLISECONDS)
        }
      }
      val after = rootPid?.let { descendants(it) }.orEmpty()
      (before + after).distinct().asReversed().forEach { pid ->
        if (pidAlive(pid)) runCatching { android.os.Process.killProcess(pid.toInt()) }
      }
      val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TERMINATION_WAIT_MS)
      while (System.nanoTime() < deadline && (rootPid?.let(::pidAlive) == true || before.any(::pidAlive) || after.any(::pidAlive))) {
        try {
          Thread.sleep(25)
        } catch (_: InterruptedException) {
          Thread.currentThread().interrupt()
          break
        }
      }
    }

    private fun processPid(process: Process): Long? = runCatching {
      process.javaClass.getMethod("pid").invoke(process) as Long
    }.getOrNull()

    private fun pidAlive(pid: Long): Boolean = pid > 0 && File("/proc/$pid").isDirectory

    private fun descendants(rootPid: Long): List<Long> {
      val seen = linkedSetOf<Long>()
      fun visit(parentPid: Long, depth: Int) {
        if (depth >= MAX_DESCENDANT_DEPTH || seen.size >= MAX_DESCENDANTS) return
        val childrenFile = File("/proc/$parentPid/task/$parentPid/children")
        val children = runCatching { childrenFile.readText().take(4096).trim() }
          .getOrDefault("")
          .split(Regex("\\s+"))
          .mapNotNull { it.toLongOrNull() }
          .filter { it > 0L }
        children.forEach { childPid ->
          if (seen.add(childPid)) visit(childPid, depth + 1)
        }
      }
      visit(rootPid, 0)
      return seen.toList()
    }
  }
}
