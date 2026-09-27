package com.scariflabs.horus.terminal

import android.net.ConnectivityManager
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
 * Phase 5 device proof. This is intentionally a clean, test-owned Alpine
 * guest: it exercises the real apk package transaction and the same PTY/PRoot
 * launcher used by the app without changing the user's persistent terminal
 * data. A second shell proves that the installed packages and user files are
 * visible after session teardown and relaunch.
 */
@RunWith(AndroidJUnit4::class)
class TerminalToolchainDeviceTest {
  private class OutputSink {
    private val lock = Object()
    private val output = ByteArrayOutputStream()

    fun append(bytes: ByteArray) = synchronized(lock) { output.write(bytes) }

    fun checkpoint(): Int = synchronized(lock) { output.size() }

    fun textFrom(offset: Int): String = synchronized(lock) {
      val bytes = output.toByteArray()
      String(bytes.copyOfRange(offset.coerceIn(0, bytes.size), bytes.size), Charsets.ISO_8859_1)
    }

    fun tail(limit: Int = 1200): String = synchronized(lock) {
      val bytes = output.toByteArray()
      String(bytes.copyOfRange(maxOf(0, bytes.size - limit), bytes.size), Charsets.ISO_8859_1)
    }
  }

  private class DeviceListener(private val sink: OutputSink) : TerminalSessionSupervisor.EventListener {
    val exits = CopyOnWriteArrayList<TerminalSessionSupervisor.SessionExitInfo>()
    var acknowledge: ((String, Long) -> Unit)? = null
    var respondToCursorQuery: ((String) -> Unit)? = null
    private var cursorTail = emptyList<Byte>()

    override fun onSessionOutput(sessionId: String, seq: Long, chunk: ByteArray) {
      sink.append(chunk)
      val combined = cursorTail + chunk.toList()
      if (combined.windowed(4, 1, partialWindows = false).any {
          it == listOf(0x1b.toByte(), 0x5b.toByte(), 0x36.toByte(), 0x6e.toByte())
        }) {
        respondToCursorQuery?.invoke(sessionId)
      }
      cursorTail = combined.takeLast(3)
      acknowledge?.invoke(sessionId, seq)
    }

    override fun onSessionExit(info: TerminalSessionSupervisor.SessionExitInfo) {
      exits += info
    }
  }

  @Test(timeout = 900_000)
  fun installsBaseProfileAndKeepsItAcrossNewShellSession() {
    val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    assertEquals("arm64-v8a", android.os.Build.SUPPORTED_ABIS.firstOrNull())

    val manifestText = targetContext.assets.open("alpine-runtime/manifest.json").bufferedReader().use { it.readText() }
    val located = ProotRuntimeLocator(manifestText).locate(File(targetContext.applicationInfo.nativeLibraryDir ?: ""))
    assertTrue("packaged PRoot runtime is unavailable", located is ProotRuntimeLocator.Location.Available)
    val runtime = (located as ProotRuntimeLocator.Location.Available).runtime
    val testRoot = File(targetContext.filesDir, "alpine-p5-device-test-${System.nanoTime()}")
    val paths = DistroStorePaths(testRoot)
    val store = DistroStoreCore(
      paths = paths,
      downloader = HttpArchiveDownloader(),
      extractor = SafeTarGzExtractor(),
      prober = ProotGuestProber(runtime, File(paths.sessions, ".proot-scratch")),
    )
    val sink = OutputSink()
    val listener = DeviceListener(sink)
    val supervisor = TerminalSessionSupervisor(
      backend = JniPtyBackend(),
      listener = listener,
      processTree = ProcessTree(),
      sendSignal = { pid, signal -> android.system.Os.kill(pid, signal) },
    )
    val dnsServers = targetContext.getSystemService(ConnectivityManager::class.java).activeNetwork
      ?.let { targetContext.getSystemService(ConnectivityManager::class.java).getLinkProperties(it)?.dnsServers }
      ?.mapNotNull { it.hostAddress?.trim()?.takeIf(String::isNotEmpty) }
      .orEmpty()
    android.util.Log.i(LOG_TAG, "P5_DNS_HOST=${dnsServers.joinToString(",")}")
    val launcher = ProotSessionLauncher(
      runtime = runtime,
      scratchDir = File(paths.sessions, ".proot-scratch"),
      dnsServersProvider = { dnsServers },
    )
    listener.acknowledge = { sessionId, seq -> supervisor.acknowledgeOutput(sessionId, seq) }
    listener.respondToCursorQuery = { sessionId ->
      supervisor.write(sessionId, "\u001b[1;14R".toByteArray(Charsets.ISO_8859_1))
    }

    try {
      android.util.Log.i(LOG_TAG, "P5_PHASE=install_begin")
      val install = store.install(downloadTimeoutMs = 120_000)
      android.util.Log.i(LOG_TAG, "P5_PHASE=install_complete outcome=${install::class.java.simpleName}")
      assertTrue("rootfs install failed: $install", install is DistroStoreCore.InstallOutcome.Success)
      val rootfs = store.activeRootfsDir()
      assertTrue(rootfs != null && rootfs.isDirectory)

      android.util.Log.i(LOG_TAG, "P5_PHASE=first_shell_begin")
      val first = startSession(
        supervisor,
        launcher,
        rootfs!!,
        paths,
        listener,
        expectedExitCount = 1,
        toolchainTarget = TerminalRuntimeContract.TOOLCHAIN_TARGET_SHELL,
      )
      android.util.Log.i(LOG_TAG, "P5_PHASE=first_shell_started session=${first.sessionId}")
      awaitPrompt(sink, 20_000)
      assertTrue("bare-terminal install target was not visible", sink.tail(8_000).contains("HORUS_INSTALL_TARGET=shell"))
      assertTrue("bare-terminal install completion was not visible", sink.tail(8_000).contains("HORUS_TOOLCHAIN_READY"))
      android.util.Log.i(LOG_TAG, "P5_PHASE=first_prompt_observed")
      listener.respondToCursorQuery = null

      var checkpoint = sink.checkpoint()
      android.util.Log.i(LOG_TAG, "P5_PHASE=echo_disable_begin")
      write(supervisor, first.sessionId, "stty -echo; printf 'P5_ECHO_OFF\\n'")
      awaitMarkers(sink, checkpoint, listOf("P5_ECHO_OFF"), "echo disable")

      checkpoint = sink.checkpoint()
      write(
        supervisor,
        first.sessionId,
        "printf 'P5_DB_BEGIN\\n'; touch /lib/apk/db/.horus-p5-write; touchStatus=\$?; rm -f /lib/apk/db/.horus-p5-write; cp /lib/apk/db/installed /root/.horus-p5-installed; printf '\\n' >> /lib/apk/db/installed; appendStatus=\$?; cp /root/.horus-p5-installed /lib/apk/db/installed; rm -f /root/.horus-p5-installed; printf 'P5_DB_WRITE:%s\\nP5_DB_APPEND:%s\\nP5_DB_END\\n' \"\$touchStatus\" \"\$appendStatus\"",
      )
      awaitMarkersInOrder(
        sink,
        checkpoint,
        listOf("P5_DB_BEGIN", "P5_DB_WRITE:0", "P5_DB_APPEND:0", "P5_DB_END"),
        "apk database write probe",
      )
      checkpoint = sink.checkpoint()
      write(
        supervisor,
        first.sessionId,
        "printf 'P5_INSTALL_BEGIN\\n'; apk add --no-cache --no-progress bash curl ca-certificates gcompat git jq openssh-client-default nodejs npm python3 py3-pip ripgrep libgcc libstdc++ >/root/.horus-p5-apk.log 2>&1; status=\$?; if [ \"\$status\" -ne 0 ]; then tail -30 /root/.horus-p5-apk.log; fi; printf 'P5_INSTALL_RESULT:%s\\n' \"\$status\"; printf 'P5_INSTALL_END\\n'",
      )
      awaitMarkersInOrder(
        sink,
        checkpoint,
        listOf("P5_INSTALL_BEGIN", "P5_INSTALL_RESULT:0", "P5_INSTALL_END"),
        "toolchain install",
        timeoutMs = 120_000,
      )
      android.util.Log.i(LOG_TAG, "P5_PHASE=install_markers_observed")
      android.util.Log.i(LOG_TAG, "ALPINE_P5_INSTALL_OK")

      checkpoint = sink.checkpoint()
      android.util.Log.i(LOG_TAG, "P5_PHASE=probe_begin")
      write(
        supervisor,
        first.sessionId,
        "printf 'P5_PROBE_BEGIN\\n'; printf 'P5_CHECK|apk|%s\\n' \"\$(command -v apk >/dev/null 2>&1 && echo pass || echo fail)\"; printf 'P5_CHECK|shell|%s\\n' \"\$(command -v sh >/dev/null 2>&1 && command -v bash >/dev/null 2>&1 && echo pass || echo fail)\"; printf 'P5_CHECK|transfer|%s\\n' \"\$(/usr/bin/curl --version >/dev/null 2>&1 && echo pass || echo fail)\"; printf 'P5_CHECK|certificates|%s\\n' \"\$([ -s /etc/ssl/certs/ca-certificates.crt ] && echo pass || echo fail)\"; printf 'P5_CHECK|git|%s\\n' \"\$(command -v git >/dev/null 2>&1 && echo pass || echo fail)\"; printf 'P5_CHECK|ssh|%s\\n' \"\$(command -v ssh >/dev/null 2>&1 && echo pass || echo fail)\"; printf 'P5_CHECK|node-npm|%s\\n' \"\$(command -v node >/dev/null 2>&1 && command -v npm >/dev/null 2>&1 && [ \"\$(node -p 'process.platform + \"/\" + process.arch')\" = linux/arm64 ] && echo pass || echo fail)\"; printf 'P5_CHECK|python-pip|%s\\n' \"\$(/usr/bin/python3 -m pip --version >/dev/null 2>&1 && echo pass || echo fail)\"; printf 'P5_CHECK|ripgrep|%s\\n' \"\$(/usr/bin/rg --version >/dev/null 2>&1 && echo pass || echo fail)\"; printf 'P5_CHECK|jq|%s\\n' \"\$(/usr/bin/jq --version >/dev/null 2>&1 && echo pass || echo fail)\"; printf 'P5_CHECK|gcompat-loader|%s\\n' \"\$(apk info -e gcompat >/dev/null 2>&1 && [ -e /lib/ld-linux-aarch64.so.1 ] && echo pass || echo fail)\"; printf 'P5_CHECK|native-libraries|%s\\n' \"\$(apk info -e libgcc >/dev/null 2>&1 && apk info -e libstdc++ >/dev/null 2>&1 && echo pass || echo fail)\"; printf 'P5_CHECK|linux-arm64|%s\\n' \"\$([ \"\$(uname -s)\" = Linux ] && [ \"\$(uname -m)\" = aarch64 ] && echo pass || echo fail)\"; printf 'P5_PROBE_END\\n'",
      )
      val expectedChecks = listOf(
        "P5_PROBE_BEGIN",
        "P5_CHECK|apk|pass",
        "P5_CHECK|shell|pass",
        "P5_CHECK|transfer|pass",
        "P5_CHECK|certificates|pass",
        "P5_CHECK|git|pass",
        "P5_CHECK|ssh|pass",
        "P5_CHECK|node-npm|pass",
        "P5_CHECK|python-pip|pass",
        "P5_CHECK|ripgrep|pass",
        "P5_CHECK|jq|pass",
        "P5_CHECK|gcompat-loader|pass",
        "P5_CHECK|native-libraries|pass",
        "P5_CHECK|linux-arm64|pass",
        "P5_PROBE_END",
      )
      awaitMarkers(sink, checkpoint, expectedChecks, "toolchain probe")
      android.util.Log.i(LOG_TAG, "ALPINE_P5_PROBE_OK")

      checkpoint = sink.checkpoint()
      android.util.Log.i(LOG_TAG, "P5_PHASE=toolchain_workflows_begin")
      write(supervisor, first.sessionId, toolchainWorkflowCommand())
      awaitMarkersInOrder(
        sink,
        checkpoint,
        listOf(
          "P5_WORKFLOWS_BEGIN",
          "P5_NPM_OK",
          "P5_PYTHON_OK",
          "P5_GIT_OK",
          "P5_TLS_OK",
          "P5_DOWNLOAD_INTERRUPTED_OK",
          "P5_DOWNLOAD_RETRY_OK",
          "P5_WORKFLOWS_END",
        ),
        "toolchain workflow checks",
        timeoutMs = 120_000,
      )
      android.util.Log.i(LOG_TAG, "ALPINE_P5_WORKFLOWS_OK")

      checkpoint = sink.checkpoint()
      android.util.Log.i(LOG_TAG, "P5_PHASE=persistence_write_begin")
      write(supervisor, first.sessionId, "mkdir -p /root/.horus-p5 /workspace; printf 'home-persistent\\n' > /root/.horus-p5/sentinel; printf 'workspace-persistent\\n' > /workspace/sentinel; printf 'P5_SENTINELS_WRITTEN\\n'")
      awaitMarkers(sink, checkpoint, listOf("P5_SENTINELS_WRITTEN"), "sentinel write")
      assertTrue((supervisor.stop(first.sessionId, "user_stop") as TerminalSessionSupervisor.StopOutcome.Stopped).observation.remainingProcessCount == 0)

      val second = startSession(supervisor, launcher, rootfs, paths, listener, 2)
      android.util.Log.i(LOG_TAG, "P5_PHASE=second_shell_started session=${second.sessionId}")
      awaitPrompt(sink, 20_000)
      android.util.Log.i(LOG_TAG, "P5_PHASE=second_prompt_observed")
      checkpoint = sink.checkpoint()
      write(supervisor, second.sessionId, "printf 'P5_RELAUNCH_BEGIN\\n'; cat /root/.horus-p5/sentinel; cat /workspace/sentinel; command -v node; printf 'P5_RELAUNCH_END\\n'")
      awaitMarkers(sink, checkpoint, listOf("P5_RELAUNCH_BEGIN", "home-persistent", "workspace-persistent", "/usr/bin/node", "P5_RELAUNCH_END"), "new shell persistence")
      val stop = supervisor.stop(second.sessionId, "user_stop") as TerminalSessionSupervisor.StopOutcome.Stopped
      assertEquals(0, stop.observation.remainingProcessCount)
      android.util.Log.i(LOG_TAG, "ALPINE_P5_RELAUNCH_OK")
    } finally {
      supervisor.shutdownAll("test_cleanup")
      assertTrue("test-owned storage cleanup failed: ${testRoot.absolutePath}", testRoot.deleteRecursively())
    }
  }

  @Test(timeout = 600_000)
  fun streamsGithubInstallBeforeTheInteractiveSession() {
    val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    assertTrue("GitHub lazy provisioning requires ARM64", android.os.Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a")

    val manifestText = targetContext.assets.open("alpine-runtime/manifest.json").bufferedReader().use { it.readText() }
    val located = ProotRuntimeLocator(manifestText).locate(File(targetContext.applicationInfo.nativeLibraryDir ?: ""))
    assertTrue("packaged PRoot runtime is unavailable", located is ProotRuntimeLocator.Location.Available)
    val runtime = (located as ProotRuntimeLocator.Location.Available).runtime
    val testRoot = File(targetContext.filesDir, "alpine-github-lazy-device-test-${System.nanoTime()}")
    val paths = DistroStorePaths(testRoot)
    val store = DistroStoreCore(
      paths = paths,
      downloader = HttpArchiveDownloader(),
      extractor = SafeTarGzExtractor(),
      prober = ProotGuestProber(runtime, File(paths.sessions, ".proot-scratch")),
    )
    val dnsServers = targetContext.getSystemService(ConnectivityManager::class.java).activeNetwork
      ?.let { targetContext.getSystemService(ConnectivityManager::class.java).getLinkProperties(it)?.dnsServers }
      ?.mapNotNull { it.hostAddress?.trim()?.takeIf(String::isNotEmpty) }
      .orEmpty()
    val launcher = ProotSessionLauncher(
      runtime = runtime,
      scratchDir = File(paths.sessions, ".proot-scratch"),
      dnsServersProvider = { dnsServers },
    )

    try {
      val install = store.install(downloadTimeoutMs = 120_000)
      assertTrue("rootfs install failed: $install", install is DistroStoreCore.InstallOutcome.Success)
      val rootfs = store.activeRootfsDir()
      assertTrue("active rootfs is missing", rootfs != null && rootfs.isDirectory)

      val visibleInstall = launcher.toolchainSessionLaunchSpec(
        rootfsDir = rootfs!!,
        guestHomeDir = paths.home,
        workspaceDir = paths.defaultWorkspace,
        target = TerminalRuntimeContract.TOOLCHAIN_TARGET_GITHUB,
        sessionCommand = "printf 'ALPINE_VISIBLE_GITHUB_INSTALL_OK\\n'",
      )
      val visibleInstallResult = runLaunch(visibleInstall, 180)
      val githubInstallLog = File(paths.home, ".cache/horus/github-install.log")
        .let { file -> if (file.isFile) file.readText().takeLast(2_000) else "<missing>" }
      assertEquals("GitHub visible install failed: $githubInstallLog", 0, visibleInstallResult.first)
      assertTrue("GitHub install target marker was not visible", visibleInstallResult.second.contains("HORUS_INSTALL_TARGET=github"))
      assertTrue("GitHub install completion marker was not visible", visibleInstallResult.second.contains("HORUS_TOOLCHAIN_READY"))
      assertTrue("GitHub session handoff marker was not visible", visibleInstallResult.second.contains("HORUS_INSTALL_HANDOFF="))
      assertTrue("GitHub session handoff marker was not visible", visibleInstallResult.second.contains("ALPINE_VISIBLE_GITHUB_INSTALL_OK"))
      assertTrue(
        "GitHub readiness marker was not created",
        launcher.hasProvisionedToolchain(rootfs, paths.home, TerminalRuntimeContract.TOOLCHAIN_TARGET_GITHUB),
      )

      val probe = launcher.interactiveShellLaunchSpec(
        rootfs,
        paths.home,
        paths.defaultWorkspace,
        sessionCommand = "stty echo; exec zsh -lic 'command -v gh >/dev/null && gh --version >/dev/null 2>&1 && print -r -- ALPINE_GITHUB_LAZY_OK'",
      )
      val probeResult = runLaunch(probe, 60)
      assertEquals("GitHub executable probe failed", 0, probeResult.first)
      assertTrue(
        "GitHub executable marker was not observed",
        probeResult.second.replace('\r', '\n').contains("ALPINE_GITHUB_LAZY_OK"),
      )
      android.util.Log.i(LOG_TAG, "ALPINE_GITHUB_LAZY_OK")
    } finally {
      assertTrue("test-owned storage cleanup failed: ${testRoot.absolutePath}", testRoot.deleteRecursively())
    }
  }

  @Test(timeout = 900_000)
  fun provisionsHarnessTargetsBeforeTheirInteractiveSessions() {
    val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    assertTrue("Harness lazy provisioning requires ARM64", android.os.Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a")

    val manifestText = targetContext.assets.open("alpine-runtime/manifest.json").bufferedReader().use { it.readText() }
    val located = ProotRuntimeLocator(manifestText).locate(File(targetContext.applicationInfo.nativeLibraryDir ?: ""))
    assertTrue("packaged PRoot runtime is unavailable", located is ProotRuntimeLocator.Location.Available)
    val runtime = (located as ProotRuntimeLocator.Location.Available).runtime
    val testRoot = File(targetContext.filesDir, "alpine-harness-lazy-device-test-${System.nanoTime()}")
    val paths = DistroStorePaths(testRoot)
    val store = DistroStoreCore(
      paths = paths,
      downloader = HttpArchiveDownloader(),
      extractor = SafeTarGzExtractor(),
      prober = ProotGuestProber(runtime, File(paths.sessions, ".proot-scratch")),
    )
    val dnsServers = targetContext.getSystemService(ConnectivityManager::class.java).activeNetwork
      ?.let { targetContext.getSystemService(ConnectivityManager::class.java).getLinkProperties(it)?.dnsServers }
      ?.mapNotNull { it.hostAddress?.trim()?.takeIf(String::isNotEmpty) }
      .orEmpty()
    val launcher = ProotSessionLauncher(
      runtime = runtime,
      scratchDir = File(paths.sessions, ".proot-scratch"),
      dnsServersProvider = { dnsServers },
    )
    val targets = listOf(
      Triple(TerminalRuntimeContract.TOOLCHAIN_TARGET_CLAUDE, "claude", "ALPINE_CLAUDE_LAZY_OK"),
      Triple(TerminalRuntimeContract.TOOLCHAIN_TARGET_CODEX, "codex", "ALPINE_CODEX_LAZY_OK"),
      Triple(TerminalRuntimeContract.TOOLCHAIN_TARGET_OPENCODE, "opencode", "ALPINE_OPENCODE_LAZY_OK"),
    )

    try {
      val install = store.install(downloadTimeoutMs = 120_000)
      assertTrue("rootfs install failed: $install", install is DistroStoreCore.InstallOutcome.Success)
      val rootfs = store.activeRootfsDir()
      assertTrue("active rootfs is missing", rootfs != null && rootfs.isDirectory)

      for ((target, command, marker) in targets) {
        val provision = launcher.toolchainProvisionLaunchSpec(rootfs!!, paths.home, target)
        val provisionResult = runLaunch(provision, 300)
        val installLogName = when (target) {
          TerminalRuntimeContract.TOOLCHAIN_TARGET_CLAUDE -> "claude-install.log"
          TerminalRuntimeContract.TOOLCHAIN_TARGET_CODEX -> "codex-install.log"
          else -> "opencode-install.log"
        }
        val installLog = File(paths.home, ".cache/horus/$installLogName")
          .let { file -> if (file.isFile) file.readText().takeLast(2_000) else "<missing>" }
        assertEquals("$target provisioning failed: $installLog", 0, provisionResult.first)
        val readyMarker = File(paths.home, ".cache/horus/$target.ready")
        val launcherFile = File(paths.home, ".local/bin/$command")
        val zshBin = listOf(File(rootfs, "bin/zsh"), File(rootfs, "usr/bin/zsh"))
          .joinToString { "${it.path}(file=${it.isFile},exec=${it.canExecute()})" }
        assertTrue(
          "$target readiness check failed: marker=${readyMarker.isFile}, launcher=${launcherFile.path}(file=${launcherFile.isFile},exec=${launcherFile.canExecute()}), zsh=$zshBin, installLog=$installLog",
          launcher.hasProvisionedToolchain(rootfs, paths.home, target),
        )

        val probe = launcher.interactiveShellLaunchSpec(
          rootfs,
          paths.home,
          paths.defaultWorkspace,
          sessionCommand = "exec zsh -lic 'command -v $command >/dev/null && $command --version >/dev/null 2>&1 && print -r -- $marker'",
        )
        val probeResult = runLaunch(probe, 60)
        assertEquals("$target executable probe failed: ${probeResult.second.takeLast(2_000)}", 0, probeResult.first)
        assertTrue(
          "$target executable marker was not observed",
          probeResult.second.replace('\r', '\n').contains(marker),
        )
        val nonRootProbe = launcher.interactiveShellLaunchSpec(
          rootfs,
          paths.home,
          paths.defaultWorkspace,
          guestUsername = "luna",
          sessionCommand = "exec zsh -lic 'command -v $command >/dev/null && $command --version >/dev/null 2>&1 && print -r -- $marker'",
        )
        val nonRootProbeResult = runLaunch(nonRootProbe, 60)
        assertEquals(
          "$target non-root executable probe failed: ${nonRootProbeResult.second.takeLast(2_000)}",
          0,
          nonRootProbeResult.first,
        )
        assertTrue(
          "$target non-root executable marker was not observed",
          nonRootProbeResult.second.replace('\r', '\n').contains(marker),
        )

        if (target == TerminalRuntimeContract.TOOLCHAIN_TARGET_CODEX ||
          target == TerminalRuntimeContract.TOOLCHAIN_TARGET_OPENCODE
        ) {
          corruptHarnessPayload(paths.home, target)
          assertTrue(
            "$target corrupted payload was incorrectly considered ready",
            !launcher.hasProvisionedToolchain(rootfs, paths.home, target),
          )
          val repairResult = runLaunch(provision, 300)
          val repairLog = File(paths.home, ".cache/horus/$installLogName")
            .let { file -> if (file.isFile) file.readText().takeLast(2_000) else "<missing>" }
          assertEquals("$target repair failed: $repairLog", 0, repairResult.first)
          val repairedProbeResult = runLaunch(probe, 60)
          assertEquals(
            "$target repaired executable probe failed: ${repairedProbeResult.second.takeLast(2_000)}",
            0,
            repairedProbeResult.first,
          )
          assertTrue(
            "$target repaired executable marker was not observed",
            repairedProbeResult.second.replace('\r', '\n').contains(marker),
          )
          val repairedNonRootProbeResult = runLaunch(nonRootProbe, 60)
          assertEquals(
            "$target repaired non-root executable probe failed: ${repairedNonRootProbeResult.second.takeLast(2_000)}",
            0,
            repairedNonRootProbeResult.first,
          )
          assertTrue(
            "$target repaired non-root executable marker was not observed",
            repairedNonRootProbeResult.second.replace('\r', '\n').contains(marker),
          )
        }
      }
      android.util.Log.i(LOG_TAG, "ALPINE_HARNESS_LAZY_OK")
    } finally {
      assertTrue("test-owned storage cleanup failed: ${testRoot.absolutePath}", testRoot.deleteRecursively())
    }
  }

  private fun corruptHarnessPayload(home: File, target: String) {
    val payload = when (target) {
      TerminalRuntimeContract.TOOLCHAIN_TARGET_CODEX ->
        File(home, ".local/lib/node_modules/@openai/codex/bin/codex.js")
      TerminalRuntimeContract.TOOLCHAIN_TARGET_OPENCODE ->
        File(home, ".local/lib/node_modules/opencode-ai/bin/opencode.exe")
      else -> error("unsupported repair target: $target")
    }
    requireNotNull(payload.parentFile).mkdirs()
    val entrypoint = payload.path
    payload.writeText("#!/bin/sh\nexec /usr/bin/node $entrypoint \"\$@\"\n")
  }

  private fun startSession(
    supervisor: TerminalSessionSupervisor,
    launcher: ProotSessionLauncher,
    rootfs: File,
    paths: DistroStorePaths,
    listener: DeviceListener,
    expectedExitCount: Int,
    toolchainTarget: String? = null,
  ): TerminalSessionSupervisor.SessionHandle {
    val sessionId = supervisor.nextSessionId()
    val launch = if (toolchainTarget == null) {
      launcher.interactiveShellLaunchSpec(rootfs, paths.home, paths.defaultWorkspace)
    } else {
      launcher.toolchainSessionLaunchSpec(
        rootfsDir = rootfs,
        guestHomeDir = paths.home,
        workspaceDir = paths.defaultWorkspace,
        target = toolchainTarget,
      )
    }
    val outcome = supervisor.start(
      sessionId,
      TerminalSessionSupervisor.StartSpec(launch.argv, launch.environment, launch.workingDirectory, 24, 80),
    )
    android.util.Log.i(LOG_TAG, "P5_PHASE=supervisor_start_result session=$sessionId outcome=${outcome::class.java.simpleName}")
    assertTrue("session start failed: $outcome", outcome is TerminalSessionSupervisor.StartOutcome.Success)
    assertTrue("session did not receive a positive pid", (outcome as TerminalSessionSupervisor.StartOutcome.Success).handle.pid > 0)
    assertTrue("unexpected prior exit count", listener.exits.size == expectedExitCount - 1)
    return outcome.handle
  }

  private fun write(supervisor: TerminalSessionSupervisor, sessionId: String, command: String) {
    val outcome = supervisor.write(sessionId, (command + "\n").toByteArray(Charsets.ISO_8859_1))
    assertTrue("write failed: $outcome", outcome is TerminalSessionSupervisor.WriteOutcome.Success)
  }

  private fun runLaunch(launch: ProotSessionLauncher.LaunchSpec, timeoutSeconds: Long): Pair<Int, String> {
    val process = ProcessBuilder(launch.argv).apply {
      launch.workingDirectory?.let(::directory)
      environment().clear()
      launch.environment.forEach { entry ->
        val separator = entry.indexOf('=')
        if (separator > 0) environment()[entry.substring(0, separator)] = entry.substring(separator + 1)
      }
      redirectErrorStream(true)
    }.start()
    val output = ByteArrayOutputStream()
    val drain = Thread {
      process.inputStream.use { it.copyTo(output) }
    }.apply {
      isDaemon = true
      name = "alpine-github-lazy-device-output"
    }
    drain.start()
    val completed = try {
      process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
    } finally {
      if (!process.isAlive) {
        runCatching { process.inputStream.close() }
      }
    }
    if (!completed) {
      process.destroy()
      if (!process.waitFor(2, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        process.waitFor(2, TimeUnit.SECONDS)
      }
    }
    if (drain.isAlive) drain.join(2_000)
    assertTrue("bounded Alpine launch timed out", completed)
    return process.exitValue() to output.toString(Charsets.ISO_8859_1.name())
  }

  /**
   * Exercise the user-facing toolchain workflows in the same test-owned
   * Alpine home/workspace used by the install and probe checks. Every marker
   * is emitted only after the preceding operation and its bounded assertion
   * succeeds; no package contents or command output are copied to the host.
   */
  private fun toolchainWorkflowCommand(): String = """
    printf '\n'; printf 'P5_WORKFLOWS_BEGIN\n';
    rm -rf /root/.horus-p5/npm-smoke /root/.horus-p5/python-venv /root/.horus-p5/python-target /workspace/p5-git;
    mkdir -p /root/.horus-p5;
    if npm install --prefix /root/.horus-p5/npm-smoke --no-save --no-package-lock --ignore-scripts --no-audit --no-fund is-number@7.0.0 >/root/.horus-p5/npm-smoke.log 2>&1 && test -f /root/.horus-p5/npm-smoke/node_modules/is-number/index.js; then printf 'P5_NPM_OK\n'; else printf 'P5_NPM_FAIL\n'; fi;
    if python3 -m venv /root/.horus-p5/python-venv >/root/.horus-p5/python-venv.log 2>&1 && /root/.horus-p5/python-venv/bin/pip install --no-cache-dir --disable-pip-version-check --quiet --target /root/.horus-p5/python-target six==1.17.0 >/root/.horus-p5/python-package.log 2>&1 && PYTHONPATH=/root/.horus-p5/python-target /root/.horus-p5/python-venv/bin/python -c 'import six; assert six.__version__ == "1.17.0"'; then printf 'P5_PYTHON_OK\n'; else printf 'P5_PYTHON_FAIL\n'; fi;
    mkdir -p /workspace/p5-git;
    if git -C /workspace/p5-git init -q && git -C /workspace/p5-git config user.name horus-p5 && git -C /workspace/p5-git config user.email horus-p5@example.invalid && printf 'phase5\n' > /workspace/p5-git/README && git -C /workspace/p5-git add README && git -C /workspace/p5-git commit -qm p5-smoke-commit && test "${'$'}(git -C /workspace/p5-git log -1 --format=%s)" = p5-smoke-commit && test -z "${'$'}(git -C /workspace/p5-git status --porcelain)"; then printf 'P5_GIT_OK\n'; else printf 'P5_GIT_FAIL\n'; fi;
    if curl -fsS --max-time 30 https://registry.npmjs.org/is-number >/dev/null; then printf 'P5_TLS_OK\n'; else printf 'P5_TLS_FAIL\n'; fi;
    download_url=https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/aarch64/alpine-minirootfs-3.24.0-aarch64.tar.gz;
    partial=/root/.horus-p5/download.partial;
    retry_tmp=/root/.horus-p5/download.retry;
    retry_final=/root/.horus-p5/download.final;
    retry_marker=/root/.horus-p5/download.success;
    rm -f "${'$'}partial" "${'$'}retry_tmp" "${'$'}retry_final" "${'$'}retry_marker";
    curl -fsSL --max-time 60 -o "${'$'}partial" "${'$'}download_url" & download_pid=${'$'}!;
    sleep 0.1;
    kill "${'$'}download_pid" >/dev/null 2>&1;
    wait "${'$'}download_pid";
    interrupted_status=${'$'}?;
    if [ "${'$'}interrupted_status" -ne 0 ] && [ ! -e "${'$'}retry_final" ] && [ ! -e "${'$'}retry_marker" ]; then printf 'P5_DOWNLOAD_INTERRUPTED_OK\n'; else printf 'P5_DOWNLOAD_INTERRUPTED_FAIL\n'; fi;
    rm -f "${'$'}partial";
    if curl -fsSL --max-time 120 -o "${'$'}retry_tmp" "${'$'}download_url" && test -s "${'$'}retry_tmp" && mv "${'$'}retry_tmp" "${'$'}retry_final" && test -s "${'$'}retry_final"; then : > "${'$'}retry_marker"; printf 'P5_DOWNLOAD_RETRY_OK\n'; else printf 'P5_DOWNLOAD_RETRY_FAIL\n'; fi;
    printf 'P5_WORKFLOWS_END\n'
  """.trimIndent().replace("\n", " ")

  private fun awaitPrompt(sink: OutputSink, timeoutMs: Long) {
    android.util.Log.i(LOG_TAG, "P5_PHASE=prompt_wait_begin timeoutMs=$timeoutMs")
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      if (sink.tail().contains("# ")) return
      Thread.sleep(50)
    }
    android.util.Log.e(LOG_TAG, "P5_PHASE=prompt_wait_timeout tail=${sink.tail().replace('\n', '|')}")
    assertTrue("shell prompt did not arrive: ${sink.tail()}", false)
  }

  private fun awaitMarkers(
    sink: OutputSink,
    offset: Int,
    expected: List<String>,
    label: String,
    timeoutMs: Long = 30_000,
  ) {
    android.util.Log.i(LOG_TAG, "P5_PHASE=markers_wait_begin label=$label timeoutMs=$timeoutMs")
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      val lines = sink.textFrom(offset).replace('\r', '\n').split('\n').filter(String::isNotEmpty)
      if (lines.windowed(expected.size, 1, partialWindows = false).any { it == expected }) return
      Thread.sleep(50)
    }
    android.util.Log.e(LOG_TAG, "P5_PHASE=markers_wait_timeout label=$label tail=${sink.tail().replace('\n', '|')}")
    assertTrue("$label markers did not arrive: ${sink.tail()}", false)
  }

  private fun awaitMarkersInOrder(
    sink: OutputSink,
    offset: Int,
    expected: List<String>,
    label: String,
    timeoutMs: Long = 30_000,
  ) {
    android.util.Log.i(LOG_TAG, "P5_PHASE=ordered_markers_wait_begin label=$label timeoutMs=$timeoutMs")
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      var next = 0
      for (line in sink.textFrom(offset).replace('\r', '\n').split('\n').filter(String::isNotEmpty)) {
        if (line == expected[next]) {
          next += 1
          if (next == expected.size) return
        }
      }
      Thread.sleep(50)
    }
    android.util.Log.e(LOG_TAG, "P5_PHASE=ordered_markers_wait_timeout label=$label tail=${sink.tail().replace('\n', '|')}")
    assertTrue("$label markers did not arrive in order: ${sink.tail()}", false)
  }

  private companion object {
    const val LOG_TAG = "AlpineP5ToolchainDeviceTest"
  }
}
