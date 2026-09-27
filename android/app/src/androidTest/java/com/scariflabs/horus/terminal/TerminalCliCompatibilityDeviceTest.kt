package com.scariflabs.horus.terminal

import android.net.ConnectivityManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 6 device proof. Each named CLI is evaluated in a clean, test-owned
 * Alpine home. Installer output stays in that home; only bounded, sanitized
 * stage markers are logged so credentials or command output cannot become
 * evidence.
 *
 * Authentication is deliberately not attempted. The auth-resume stage is
 * emitted as not-tested until an opt-in run supplies a real account outside
 * the automated gate.
 */
@RunWith(AndroidJUnit4::class)
class TerminalCliCompatibilityDeviceTest {
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

  @Test(timeout = 1_800_000)
  fun recordsFourCliCompatibilityMatrixOnPhysicalAlpineGuest() {
    val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    assertTrue("Phase 6 requires ARM64", android.os.Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a")

    val manifestText = targetContext.assets.open("alpine-runtime/manifest.json").bufferedReader().use { it.readText() }
    val located = ProotRuntimeLocator(manifestText).locate(File(targetContext.applicationInfo.nativeLibraryDir ?: ""))
    assertTrue("packaged PRoot runtime is unavailable", located is ProotRuntimeLocator.Location.Available)
    val runtime = (located as ProotRuntimeLocator.Location.Available).runtime
    val testRoot = File(targetContext.filesDir, "alpine-p6-device-test-${System.nanoTime()}")
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
    val launcher = ProotSessionLauncher(
      runtime = runtime,
      scratchDir = File(paths.sessions, ".proot-scratch"),
      dnsServersProvider = { dnsServers },
    )
    listener.respondToCursorQuery = { sessionId ->
      supervisor.write(sessionId, "\u001b[1;14R".toByteArray(Charsets.ISO_8859_1))
    }
    listener.acknowledge = { sessionId, seq -> supervisor.acknowledgeOutput(sessionId, seq) }
    var rootfsEvidence: DistroStoreCore.InstallOutcome.Success? = null

    try {
      val install = store.install(downloadTimeoutMs = 120_000)
      assertTrue("rootfs install failed: $install", install is DistroStoreCore.InstallOutcome.Success)
      val rootfs = store.activeRootfsDir()
      assertTrue("active rootfs is missing", rootfs != null && rootfs.isDirectory)
      val success = install as DistroStoreCore.InstallOutcome.Success
      rootfsEvidence = success

      val first = startSession(supervisor, launcher, rootfs!!, paths, listener, expectedExitCount = 1)
      awaitPrompt(sink, 0, 20_000, "first shell")

      var checkpoint = sink.checkpoint()
      write(supervisor, first.sessionId, bootstrapCommand())
      awaitExactLine(sink, checkpoint, "P6_BOOTSTRAP_OK", "bootstrap", 30_000)

      checkpoint = sink.checkpoint()
      write(supervisor, first.sessionId, baseToolchainInstallCommand())
      awaitExactLine(sink, checkpoint, "P6_BASE_OK", "base toolchain", 180_000)

      installCli(
        supervisor,
        first.sessionId,
        sink,
        "claude-code",
        claudeInstallCommand(),
        300_000,
      )
      installCli(supervisor, first.sessionId, sink, "codex-cli", npmInstallCommand("codex-cli", "@openai/codex"), 300_000)
      installCli(supervisor, first.sessionId, sink, "opencode", npmInstallCommand("opencode", "opencode-ai"), 300_000)
      installCli(supervisor, first.sessionId, sink, "gemini-cli", npmInstallCommand("gemini-cli", "@google/gemini-cli"), 300_000)

      runStageCommand(supervisor, first.sessionId, sink, "claude-code", claudeVersionDoctorCommand(), "version-doctor", 120_000)
      runStageCommand(supervisor, first.sessionId, sink, "codex-cli", codexVersionDoctorCommand(), "version-doctor", 120_000)
      runStageCommand(supervisor, first.sessionId, sink, "opencode", opencodeVersionDoctorCommand(), "version-doctor", 120_000)
      runStageCommand(supervisor, first.sessionId, sink, "gemini-cli", geminiVersionDoctorCommand(), "version-doctor", 120_000)
      val sandboxProbe = File(paths.home, ".horus-p6/codex-sandbox-probe.sh")
      InstrumentationRegistry.getInstrumentation().context.assets.open("codex-sandbox-probe.sh").use { input ->
        sandboxProbe.outputStream().use { output -> input.copyTo(output) }
      }
      runSandboxCommand(supervisor, first.sessionId, sink, codexSandboxCommand(), 60_000)
      checkpoint = sink.checkpoint()
      write(supervisor, first.sessionId, codexUnsandboxedProfileCommand())
      awaitExactLine(sink, checkpoint, "P6_CODEX_UNSANDBOXED_OK", "codex unsandboxed profile", 60_000)
      android.util.Log.i(LOG_TAG, "P6_CODEX_UNSANDBOXED_OK")

      runStageCommand(supervisor, first.sessionId, sink, "claude-code", cliExecutionCommand("claude-code"), "execution", 60_000)
      runStageCommand(supervisor, first.sessionId, sink, "codex-cli", cliExecutionCommand("codex-cli"), "execution", 60_000)
      runStageCommand(supervisor, first.sessionId, sink, "opencode", cliExecutionCommand("opencode"), "execution", 60_000)
      runStageCommand(supervisor, first.sessionId, sink, "gemini-cli", cliExecutionCommand("gemini-cli"), "execution", 60_000)

      checkpoint = sink.checkpoint()
      write(supervisor, first.sessionId, authNotTestedCommand())
      for (cli in CLI_IDS) {
        val marker = awaitLinePrefix(sink, checkpoint, "P6_STAGE|$cli|auth-resume|", "auth stage $cli", 30_000)
        logSafeStageMarker(marker)
      }

      val firstStop = supervisor.stop(first.sessionId, "user_stop")
      assertTrue("first Phase 6 shell did not stop cleanly: $firstStop", firstStop is TerminalSessionSupervisor.StopOutcome.Stopped)
      assertTrue("first Phase 6 shell leaked a process", (firstStop as TerminalSessionSupervisor.StopOutcome.Stopped).observation.remainingProcessCount == 0)

      val second = startSession(supervisor, launcher, rootfs, paths, listener, expectedExitCount = 2)
      checkpoint = sink.checkpoint()
      awaitPrompt(sink, checkpoint, 20_000, "relaunch shell")
      checkpoint = sink.checkpoint()
      write(supervisor, second.sessionId, relaunchPersistenceCommand())
      for (cli in CLI_IDS) {
        val marker = awaitLinePrefix(sink, checkpoint, "P6_RELAUNCH|$cli|", "relaunch $cli", 30_000)
        logSafeRelaunchMarker(marker)
      }
      awaitExactLine(sink, checkpoint, "P6_RELAUNCH_OK", "relaunch completion", 30_000)
      android.util.Log.i(LOG_TAG, "P6_RELAUNCH_OK")
      val secondStop = supervisor.stop(second.sessionId, "user_stop")
      assertTrue("second Phase 6 shell did not stop cleanly: $secondStop", secondStop is TerminalSessionSupervisor.StopOutcome.Stopped)
      assertTrue("second Phase 6 shell leaked a process", (secondStop as TerminalSessionSupervisor.StopOutcome.Stopped).observation.remainingProcessCount == 0)
    } finally {
      supervisor.shutdownAll("test_cleanup")
      assertTrue("test-owned Phase 6 storage cleanup failed", testRoot.deleteRecursively())
    }
    val evidence = checkNotNull(rootfsEvidence) { "Phase 6 rootfs evidence is missing" }
    android.util.Log.i(
      LOG_TAG,
      "P6_ROOTFS|id=${evidence.rootfsId}|archive_sha256=${evidence.archiveSha256}|archive_bytes=${evidence.archiveBytes}",
    )
    android.util.Log.i(LOG_TAG, "ALPINE_P6_MATRIX_OK")
  }

  private fun startSession(
    supervisor: TerminalSessionSupervisor,
    launcher: ProotSessionLauncher,
    rootfs: File,
    paths: DistroStorePaths,
    listener: DeviceListener,
    expectedExitCount: Int,
  ): TerminalSessionSupervisor.SessionHandle {
    val sessionId = supervisor.nextSessionId()
    val launch = launcher.interactiveShellLaunchSpec(rootfs, paths.home, paths.defaultWorkspace)
    val outcome = supervisor.start(
      sessionId,
      TerminalSessionSupervisor.StartSpec(launch.argv, launch.environment, launch.workingDirectory, 30, 120),
    )
    assertTrue("Phase 6 session start failed: $outcome", outcome is TerminalSessionSupervisor.StartOutcome.Success)
    assertTrue("Phase 6 session did not receive a positive pid", (outcome as TerminalSessionSupervisor.StartOutcome.Success).handle.pid > 0)
    assertTrue("unexpected prior Phase 6 exit count", listener.exits.size == expectedExitCount - 1)
    return outcome.handle
  }

  private fun write(supervisor: TerminalSessionSupervisor, sessionId: String, command: String) {
    val outcome = supervisor.write(sessionId, (command + "\n").toByteArray(Charsets.ISO_8859_1))
    assertTrue("Phase 6 PTY write failed: $outcome", outcome is TerminalSessionSupervisor.WriteOutcome.Success)
  }

  private fun installCli(
    supervisor: TerminalSessionSupervisor,
    sessionId: String,
    sink: OutputSink,
    cli: String,
    command: String,
    timeoutMs: Long,
  ) {
    val checkpoint = sink.checkpoint()
    write(supervisor, sessionId, command)
    val marker = awaitLinePrefix(sink, checkpoint, "P6_STAGE|$cli|install|", "install $cli", timeoutMs)
    logSafeStageMarker(marker)
  }

  private fun runStageCommand(
    supervisor: TerminalSessionSupervisor,
    sessionId: String,
    sink: OutputSink,
    cli: String,
    command: String,
    stage: String,
    timeoutMs: Long,
  ) {
    val checkpoint = sink.checkpoint()
    write(supervisor, sessionId, command)
    val marker = awaitLinePrefix(sink, checkpoint, "P6_STAGE|$cli|$stage|", "$stage $cli", timeoutMs)
    logSafeStageMarker(marker)
  }

  private fun runSandboxCommand(
    supervisor: TerminalSessionSupervisor,
    sessionId: String,
    sink: OutputSink,
    command: String,
    timeoutMs: Long,
  ) {
    val checkpoint = sink.checkpoint()
    write(supervisor, sessionId, command)
    val marker = awaitLinePrefix(sink, checkpoint, "P6_SANDBOX|codex-cli|", "sandbox codex-cli", timeoutMs)
    logSafeSandboxMarker(marker)
  }

  private fun awaitPrompt(sink: OutputSink, offset: Int, timeoutMs: Long, label: String) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      if (sink.textFrom(offset).contains("# ")) return
      Thread.sleep(50)
    }
    assertTrue("$label prompt did not arrive: ${sink.tail()}", false)
  }

  private fun awaitExactLine(sink: OutputSink, offset: Int, expected: String, label: String, timeoutMs: Long) {
    awaitLinePrefix(sink, offset, expected, label, timeoutMs, exact = true)
  }

  private fun awaitLinePrefix(
    sink: OutputSink,
    offset: Int,
    prefix: String,
    label: String,
    timeoutMs: Long,
    exact: Boolean = false,
  ): String {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      val lines = sink.textFrom(offset).replace('\r', '\n').split('\n').filter(String::isNotEmpty)
      val match = lines.firstOrNull { line -> if (exact) line == prefix else line.startsWith(prefix) }
      if (match != null) return match
      Thread.sleep(100)
    }
    assertTrue("$label marker did not arrive: ${sink.tail()}", false)
    return "unreachable"
  }

  private fun logSafeStageMarker(marker: String) {
    val safe = Regex("^P6_STAGE\\|([a-z0-9-]+)\\|(install|version-doctor|execution|auth-resume)\\|(pass|fail|not-tested|unsupported)\\|v=([A-Za-z0-9._+\\-]+)\\|d=([a-z0-9_+\\-]+)$")
    assertTrue("unsafe Phase 6 stage marker", safe.matches(marker))
    android.util.Log.i(LOG_TAG, marker)
  }

  private fun logSafeRelaunchMarker(marker: String) {
    val safe = Regex("^P6_RELAUNCH\\|([a-z0-9-]+)\\|(pass|not-tested)$")
    assertTrue("unsafe Phase 6 relaunch marker", safe.matches(marker))
    android.util.Log.i(LOG_TAG, marker)
  }

  private fun logSafeSandboxMarker(marker: String) {
    val safe = Regex("^P6_SANDBOX\\|([a-z0-9-]+)\\|(pass|fail|not-tested|unsupported)\\|v=([A-Za-z0-9._+\\-]+)\\|d=([a-z0-9_+\\-]+)$")
    assertTrue("unsafe Phase 6 sandbox marker", safe.matches(marker))
    android.util.Log.i(LOG_TAG, marker)
  }

  private fun bootstrapCommand(): String = """
    stty -echo;
    P6=/root/.horus-p6;
    rm -rf "${'$'}P6";
    mkdir -p "${'$'}P6/log" "${'$'}P6/install" "${'$'}P6/npm";
    p6_emit() { printf 'P6_STAGE|%s|%s|%s|v=%s|d=%s\n' "${'$'}1" "${'$'}2" "${'$'}3" "${'$'}4" "${'$'}5"; };
    p6_version() { value="${'$'}(grep -Eo '[0-9]+[.][0-9]+([.][0-9]+)?([._+-][[:alnum:]][[:alnum:]._-]*)?' "${'$'}1" | head -n 1 | tr -cd '[:alnum:]._+-')"; test -n "${'$'}value" && printf '%s' "${'$'}value" || printf 'unknown'; };
    p6_install_detail() { if grep -Eqi 'permission denied|EACCES' "${'$'}1"; then printf 'permission_denied'; elif grep -Eqi 'unsupported|not supported|no matching|404|403' "${'$'}1"; then printf 'unsupported_or_unavailable'; elif grep -Eqi 'node-gyp|gyp ERR|build failed|compiler' "${'$'}1"; then printf 'native_build_failed'; else printf 'install_failed'; fi; };
    printf 'P6_BOOTSTRAP_OK\n'
  """.trimIndent().replace("\n", " ")

  private fun baseToolchainInstallCommand(): String = """
    if apk add --no-cache --no-progress bash curl ca-certificates gcompat git jq openssh-client-default nodejs npm python3 py3-pip ripgrep libgcc libstdc++ >"${'$'}P6/log/base-install" 2>&1 && /usr/bin/rg --version >/dev/null 2>&1 && /usr/bin/curl --version >/dev/null 2>&1 && /usr/bin/jq --version >/dev/null 2>&1 && /usr/bin/python3 --version >/dev/null 2>&1 && test -e /lib/ld-linux-aarch64.so.1; then printf 'P6_BASE_OK\n'; else printf 'P6_BASE_FAIL\n'; fi
  """.trimIndent().replace("\n", " ")

  private fun claudeInstallCommand(): String = """
    CLAUDE=/root/.local/bin/claude;
    rm -f "${'$'}CLAUDE" "${'$'}P6/claude-install.sh";
    if curl -fsSL https://claude.ai/install.sh -o "${'$'}P6/claude-install.sh" && bash "${'$'}P6/claude-install.sh" >"${'$'}P6/log/claude-install" 2>&1 && test -x "${'$'}CLAUDE"; then
      : > "${'$'}P6/install/claude-code.pass"; PATH="/root/.local/bin:${'$'}PATH"; export PATH; p6_emit claude-code install pass unknown native_install;
    else detail="${'$'}(p6_install_detail "${'$'}P6/log/claude-install")"; p6_emit claude-code install fail unknown "${'$'}detail"; fi
  """.trimIndent().replace("\n", " ")

  private fun npmInstallCommand(cli: String, packageName: String): String {
    if (cli == "codex-cli") return codexNpmInstallCommand(packageName)
    val directory = "${'$'}P6/$cli"
    val binary = cliBinaryPath(cli)
    return """
      dir="$directory"; bin="$binary"; rm -rf "${'$'}dir"; mkdir -p "${'$'}dir";
      if npm --prefix "${'$'}dir" install --no-package-lock --no-audit --no-fund "$packageName" >"${'$'}P6/log/$cli-install" 2>&1 && test -x "${'$'}bin"; then
        : > "${'$'}P6/install/$cli.pass"; p6_emit $cli install pass unknown npm_local;
      else detail="${'$'}(p6_install_detail "${'$'}P6/log/$cli-install")"; p6_emit $cli install fail unknown "${'$'}detail"; fi
    """.trimIndent().replace("\n", " ")
  }

  private fun codexNpmInstallCommand(packageName: String): String = """
    CODEX_PREFIX="${'$'}P6/codex-global"; rm -rf "${'$'}CODEX_PREFIX"; mkdir -p "${'$'}CODEX_PREFIX";
    if npm install --global --prefix "${'$'}CODEX_PREFIX" --no-package-lock --no-audit --no-fund "$packageName" >"${'$'}P6/log/codex-cli-install" 2>&1 && test -x "${'$'}CODEX_PREFIX/bin/codex"; then
      : > "${'$'}P6/install/codex-cli.pass"; p6_emit codex-cli install pass unknown npm_global;
    else detail="${'$'}(p6_install_detail "${'$'}P6/log/codex-cli-install")"; p6_emit codex-cli install fail unknown "${'$'}detail"; fi
  """.trimIndent().replace("\n", " ")

  private fun cliBinaryPath(cli: String): String {
    val binaryName = when (cli) {
      "codex-cli" -> return "${'$'}P6/codex-global/bin/codex"
      "gemini-cli" -> "gemini"
      "opencode" -> "opencode"
      else -> error("unknown npm CLI $cli")
    }
    return "${'$'}P6/$cli/node_modules/.bin/$binaryName"
  }

  private fun claudeVersionDoctorCommand(): String = """
    if [ -f "${'$'}P6/install/claude-code.pass" ] && "${'$'}CLAUDE" --version >"${'$'}P6/log/claude-version" 2>&1; then
      v="${'$'}(p6_version "${'$'}P6/log/claude-version")";
      if "${'$'}CLAUDE" doctor >"${'$'}P6/log/claude-doctor" 2>&1; then p6_emit claude-code version-doctor pass "${'$'}v" version_and_doctor; else p6_emit claude-code version-doctor fail "${'$'}v" doctor_exit; fi;
    else p6_emit claude-code version-doctor not-tested unknown install_failed; fi
  """.trimIndent().replace("\n", " ")

  private fun codexVersionDoctorCommand(): String = """
    CODEX_PREFIX="${'$'}P6/codex-global"; export NPM_CONFIG_PREFIX="${'$'}CODEX_PREFIX";
    CODEX="${cliBinaryPath("codex-cli")}";
    if [ -f "${'$'}P6/install/codex-cli.pass" ] && "${'$'}CODEX" --version >"${'$'}P6/log/codex-version" 2>&1; then
      v="${'$'}(p6_version "${'$'}P6/log/codex-version")";
      if "${'$'}CODEX" doctor >"${'$'}P6/log/codex-doctor" 2>&1; then p6_emit codex-cli version-doctor pass "${'$'}v" version_and_doctor;
      elif grep -Eqi 'no Codex credentials were found|auth mode[[:space:]]+none' "${'$'}P6/log/codex-doctor"; then p6_emit codex-cli version-doctor fail "${'$'}v" doctor_auth_unconfigured;
      else p6_emit codex-cli version-doctor fail "${'$'}v" doctor_exit; fi;
    else p6_emit codex-cli version-doctor not-tested unknown install_failed; fi
  """.trimIndent().replace("\n", " ")

  private fun codexSandboxCommand(): String = """
    CODEX="${cliBinaryPath("codex-cli")}";
    if [ -f "${'$'}P6/install/codex-cli.pass" ]; then
      /bin/sh "${'$'}P6/codex-sandbox-probe.sh" "${'$'}CODEX" "${'$'}P6/log/codex-sandbox";
    else printf 'P6_SANDBOX|codex-cli|not-tested|v=unknown|d=install_failed\n'; fi
  """.trimIndent().replace("\n", " ")

  private fun codexUnsandboxedProfileCommand(): String = """
    PATH="${'$'}P6/codex-global/bin:${'$'}PATH"; export PATH;
    if [ "${'$'}HORUS_CODEX_MODE" = unsandboxed ] && codex --help >"${'$'}P6/log/codex-unsandboxed-help" 2>&1; then printf 'P6_CODEX_UNSANDBOXED_OK\n'; else printf 'P6_CODEX_UNSANDBOXED_FAIL\n'; fi
  """.trimIndent().replace("\n", " ")

  private fun opencodeVersionDoctorCommand(): String = """
    OPENCODE="${'$'}P6/opencode/node_modules/.bin/opencode";
    if [ -f "${'$'}P6/install/opencode.pass" ] && "${'$'}OPENCODE" --version >"${'$'}P6/log/opencode-version" 2>&1; then
      v="${'$'}(p6_version "${'$'}P6/log/opencode-version")";
      if "${'$'}OPENCODE" auth list >"${'$'}P6/log/opencode-auth" 2>&1 && "${'$'}OPENCODE" session list >"${'$'}P6/log/opencode-sessions" 2>&1; then p6_emit opencode version-doctor pass "${'$'}v" version_auth_sessions; else p6_emit opencode version-doctor fail "${'$'}v" auth_or_session_exit; fi;
    else p6_emit opencode version-doctor not-tested unknown install_failed; fi
  """.trimIndent().replace("\n", " ")

  private fun geminiVersionDoctorCommand(): String = """
    GEMINI="${'$'}P6/gemini-cli/node_modules/.bin/gemini";
    if [ -f "${'$'}P6/install/gemini-cli.pass" ] && "${'$'}GEMINI" --version >"${'$'}P6/log/gemini-version" 2>&1; then
      v="${'$'}(p6_version "${'$'}P6/log/gemini-version")";
      if "${'$'}GEMINI" --help >"${'$'}P6/log/gemini-help" 2>&1; then p6_emit gemini-cli version-doctor pass "${'$'}v" version_and_help; else p6_emit gemini-cli version-doctor fail "${'$'}v" help_exit; fi;
    else p6_emit gemini-cli version-doctor not-tested unknown install_failed; fi
  """.trimIndent().replace("\n", " ")

  private fun cliExecutionCommand(cli: String): String = when (cli) {
    "claude-code" -> "if [ -f \"${'$'}P6/install/claude-code.pass\" ] && \"${'$'}CLAUDE\" --help >\"${'$'}P6/log/claude-help\" 2>&1; then p6_emit claude-code execution pass unknown offline_help_only; else p6_emit claude-code execution not-tested unknown install_failed_or_help_exit; fi"
    "codex-cli" -> "if [ -f \"${'$'}P6/install/codex-cli.pass\" ] && \"${'$'}CODEX\" --help >\"${'$'}P6/log/codex-help\" 2>&1; then p6_emit codex-cli execution pass unknown offline_help_only; else p6_emit codex-cli execution not-tested unknown install_failed_or_help_exit; fi"
    "opencode" -> "if [ -f \"${'$'}P6/install/opencode.pass\" ] && \"${'$'}OPENCODE\" run --help >\"${'$'}P6/log/opencode-run-help\" 2>&1; then p6_emit opencode execution pass unknown offline_noninteractive_help; else p6_emit opencode execution not-tested unknown install_failed_or_help_exit; fi"
    "gemini-cli" -> "if [ -f \"${'$'}P6/install/gemini-cli.pass\" ] && \"${'$'}GEMINI\" --help >\"${'$'}P6/log/gemini-help-execution\" 2>&1; then p6_emit gemini-cli execution pass unknown offline_help_only; else p6_emit gemini-cli execution not-tested unknown install_failed_or_help_exit; fi"
    else -> error("unknown CLI $cli")
  }

  private fun authNotTestedCommand(): String = """
    p6_emit claude-code auth-resume not-tested unknown credentials_absent;
    p6_emit codex-cli auth-resume not-tested unknown credentials_absent;
    p6_emit opencode auth-resume not-tested unknown credentials_absent;
    p6_emit gemini-cli auth-resume not-tested unknown credentials_absent;
  """.trimIndent().replace("\n", " ")

  private fun relaunchPersistenceCommand(): String = """
    P6=/root/.horus-p6;
    CLAUDE=/root/.local/bin/claude;
    CODEX="${cliBinaryPath("codex-cli")}";
    OPENCODE="${'$'}P6/opencode/node_modules/.bin/opencode";
    GEMINI="${'$'}P6/gemini-cli/node_modules/.bin/gemini";
    printf 'P6_RELAUNCH_BEGIN\n';
    ok=1;
    for item in claude-code codex-cli opencode gemini-cli; do
      if [ -f "${'$'}P6/install/${'$'}item.pass" ]; then
        if [ "${'$'}item" = claude-code ]; then path="${'$'}CLAUDE"; elif [ "${'$'}item" = codex-cli ]; then path="${'$'}CODEX"; elif [ "${'$'}item" = opencode ]; then path="${'$'}OPENCODE"; else path="${'$'}GEMINI"; fi;
        if test -x "${'$'}path"; then printf 'P6_RELAUNCH|%s|pass\n' "${'$'}item"; else printf 'P6_RELAUNCH|%s|fail\n' "${'$'}item"; ok=0; fi;
      else printf 'P6_RELAUNCH|%s|not-tested\n' "${'$'}item"; fi;
    done;
    if [ "${'$'}ok" -eq 1 ]; then printf 'P6_RELAUNCH_OK\n'; else printf 'P6_RELAUNCH_FAIL\n'; fi
  """.trimIndent().replace("\n", " ")

  private companion object {
    val CLI_IDS = listOf("claude-code", "codex-cli", "opencode", "gemini-cli")
    const val LOG_TAG = "AlpineP6CliMatrixDeviceTest"
  }
}
