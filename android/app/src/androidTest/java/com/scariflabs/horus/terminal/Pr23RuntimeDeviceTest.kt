package com.scariflabs.horus.terminal

import android.net.ConnectivityManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.facebook.react.bridge.BridgeReactContext
import com.facebook.react.uimanager.ThemedReactContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** Physical-kernel regressions in a short, test-owned guest; no user credentials are used. */
@RunWith(AndroidJUnit4::class)
class Pr23RuntimeDeviceTest {
  @Test(timeout = 900_000)
  fun socketDotnetCodexTmpAndInteractiveExitRegressions() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    assertEquals("arm64-v8a", android.os.Build.SUPPORTED_ABIS.first())
    val manifest = context.assets.open("alpine-runtime/manifest.json").bufferedReader().use { it.readText() }
    val location = ProotRuntimeLocator(manifest).locate(File(context.applicationInfo.nativeLibraryDir))
    assertTrue(location.toString(), location is ProotRuntimeLocator.Location.Available)
    val runtime = (location as ProotRuntimeLocator.Location.Available).runtime
    // A long test-directory name would itself defeat the AF_UNIX regression.
    val testRoot = File(context.filesDir, "p23")
    assertFalse("another PR23 test guest already exists", testRoot.exists())
    val paths = DistroStorePaths(testRoot)
    val store = DistroStoreCore(paths, HttpArchiveDownloader(), SafeTarExtractor(), ProotGuestProber(runtime, paths.guestTmp))
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    val dns = connectivity.activeNetwork?.let { connectivity.getLinkProperties(it)?.dnsServers }
      ?.mapNotNull { it.hostAddress }.orEmpty()
    val launcher = ProotSessionLauncher(runtime, paths.guestTmp, dnsServersProvider = { dns }, seccompAcceleration = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("horusSeccomp") == "on",)

    fun run(launch: ProotSessionLauncher.LaunchSpec, seconds: Long = 300): String {
      val log = File(testRoot, "guest-command.log")
      val process = ProcessBuilder(launch.argv).apply {
        environment().clear()
        launch.environment.forEach { entry -> environment()[entry.substringBefore('=')] = entry.substringAfter('=') }
        redirectErrorStream(true)
        redirectOutput(log)
      }.start()
      if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        fail("guest command timeout: ${log.readText().takeLast(3000)}")
      }
      val output = log.readText()
      assertEquals(output.takeLast(4000), 0, process.exitValue())
      android.util.Log.i(TAG, output.takeLast(3000))
      return output
    }

    try {
      val install = store.install()
      assertTrue(install.toString(), install is DistroStoreCore.InstallOutcome.Success)
      val rootfs = requireNotNull(store.activeRootfsDir())
      run(launcher.toolchainProvisionLaunchSpec(rootfs, paths.home, "shell"))
      val dead = File(paths.guestTmp, "proot-2147483647-ABC123").apply { writeText("orphan") }
      val live = File(paths.guestTmp, "proot-${android.os.Process.myPid()}-ABC123").apply { writeText("live") }
      store.ensureLayout()
      assertFalse("orphaned PRoot staging survived startup", dead.exists())
      assertTrue("live process staging was removed", live.exists())
      val env = run(launcher.interactiveShellLaunchSpec(rootfs, paths.home, sessionCommand = """
        set -eu
        test "${'$'}DOTNET_gcServer" = 0
        test "${'$'}DOTNET_GCHeapHardLimit" = 0x40000000
        test "${'$'}DOTNET_CLI_DO_NOT_USE_MSBUILD_SERVER" = 1
        test "${'$'}DOTNET_CLI_USE_MSBUILD_SERVER" = 0
        test "${'$'}UseSharedCompilation" = false
        test -w /tmp
        mkdir -p /tmp/pr23-source /tmp/pr23-alias
        printf preserved > /tmp/pr23-source/payload
        ln /tmp/pr23-source/payload /tmp/pr23-alias/payload
        rm /tmp/pr23-source/payload
        rmdir /tmp/pr23-source
        test "${'$'}(cat /tmp/pr23-alias/payload)" = preserved
        rm -rf /tmp/pr23-alias
        printf writable > /tmp/pr23-writable
        printf 'PR23_ENV_OK tmp=%s gc=%s heap=%s msbuild=%s\n' "${'$'}PROOT_TMP_DIR" "${'$'}DOTNET_gcServer" "${'$'}DOTNET_GCHeapHardLimit" "${'$'}DOTNET_CLI_DO_NOT_USE_MSBUILD_SERVER"
      """.trimIndent()))
      assertTrue(env.contains("PR23_ENV_OK tmp=${paths.guestTmp.canonicalPath} gc=0 heap=0x40000000 msbuild=1"))
      assertEquals("writable", File(paths.guestTmp, "pr23-writable").readText())
      assertTrue(paths.guestTmp.canWrite())

      File(paths.home, "pr23-socket.py").writeText("""
        import os, socket
        directory = '/tmp/claude-messaging-61001'
        os.makedirs(directory, exist_ok=True)
        path = directory + '/12264.sock'
        listener = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        listener.bind(path)
        listener.listen(1)
        client = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        client.connect(path)
        accepted, _ = listener.accept()
        client.sendall(b'pr23')
        assert accepted.recv(4) == b'pr23'
        accepted.close(); client.close(); listener.close(); os.unlink(path)
        print('PR23_SOCKET_OK uid=' + str(os.getuid()))
      """.trimIndent())
      val socket = run(launcher.interactiveShellLaunchSpec(rootfs, paths.home,
        guestUsername = "harness_claude", guestUid = 61_001, guestGid = 1000,
        sessionCommand = "exec python3 ${'$'}HOME/pr23-socket.py"))
      assertTrue(socket.contains("PR23_SOCKET_OK uid=61001"))

      run(launcher.toolchainProvisionLaunchSpec(rootfs, paths.home, "codex"), 480)
      assertTrue("native ARM64 payload was not ready", launcher.hasProvisionedToolchain(rootfs, paths.home, "codex"))
      val codex = run(launcher.interactiveShellLaunchSpec(rootfs, paths.home, sessionCommand = """
        set -eu
        /root/.local/bin/codex --version
        node --input-type=commonjs -e 'const root = process.env.HOME + "/.local/lib/node_modules/@openai/codex"; const p = require.resolve("@openai/codex-linux-arm64/package.json", {paths:[root]}); console.log("PR23_CODEX_ARM64_OK " + require(p).version)'
      """.trimIndent()), 60)
      assertTrue(codex.contains("codex-cli ") && codex.contains("PR23_CODEX_ARM64_OK"))
      run(launcher.interactiveShellLaunchSpec(rootfs, paths.home, sessionCommand = "apk add --no-cache --no-progress dotnet10-sdk"))
      val dotnet = run(launcher.interactiveShellLaunchSpec(rootfs, paths.home, sessionCommand = """
        set -eu
        export DOTNET_CLI_TELEMETRY_OPTOUT=1
        mkdir -p /root/pr23-dotnet
        cd /root/pr23-dotnet
        dotnet --info
        dotnet new console --no-restore
        dotnet restore --ignore-failed-sources
        dotnet build --no-restore
        dotnet bin/Debug/net10.0/pr23-dotnet.dll
        printf 'PR23_DOTNET_OK\n'
      """.trimIndent()))
      assertTrue(dotnet.contains("Hello, World!") && dotnet.contains("PR23_DOTNET_OK"))
      assertFalse(dotnet.contains("0x8007000E"))
      checkInteractiveExits(launcher, rootfs, paths.home)

      android.util.Log.i(TAG, "PR23_RUNTIME_ALL_OK model=${android.os.Build.MODEL} api=${android.os.Build.VERSION.SDK_INT}")
    } finally {
      assertTrue("test guest cleanup failed", testRoot.deleteRecursively())
    }
  }

  @Test(timeout = 900_000)
  fun codexReadinessAndHardlinkLifecycleRecovery() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    assertTrue("use the isolated validation package", context.packageName.endsWith(".pr23check"))
    val manifest = context.assets.open("alpine-runtime/manifest.json").bufferedReader().use { it.readText() }
    val runtime = (ProotRuntimeLocator(manifest).locate(File(context.applicationInfo.nativeLibraryDir)) as ProotRuntimeLocator.Location.Available).runtime
    val paths = DistroStorePaths(File(context.filesDir, "r23"))
    assertFalse("test guest already exists", paths.base.exists())
    val store = DistroStoreCore(paths, HttpArchiveDownloader(), SafeTarExtractor(), ProotGuestProber(runtime, paths.guestTmp))
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    val dns = connectivity.activeNetwork?.let { connectivity.getLinkProperties(it)?.dnsServers }?.mapNotNull { it.hostAddress }.orEmpty()
    val launcher = ProotSessionLauncher(runtime, paths.guestTmp, dnsServersProvider = { dns }, seccompAcceleration = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("horusSeccomp") == "on",)
    fun process(launch: ProotSessionLauncher.LaunchSpec, log: File): Process = ProcessBuilder(launch.argv).apply {
      environment().clear()
      launch.environment.forEach { entry -> environment()[entry.substringBefore('=')] = entry.substringAfter('=') }
      redirectErrorStream(true); redirectOutput(log)
    }.start()
    fun run(launch: ProotSessionLauncher.LaunchSpec) {
      val log = File(paths.base, "recovery.log")
      val guest = process(launch, log)
      // Provisioning now also installs python3 for sudo before Codex's ~150 MB download.
      if (!guest.waitFor(480, TimeUnit.SECONDS)) { guest.destroyForcibly(); fail(log.readText().takeLast(3000)) }
      assertEquals(log.readText().takeLast(3000), 0, guest.exitValue())
    }
    try {
      val installed = store.install()
      assertTrue(installed.toString(), installed is DistroStoreCore.InstallOutcome.Success)
      val root = store.activeRootfsDir()!!
      run(launcher.toolchainProvisionLaunchSpec(root, paths.home, "codex"))
      assertTrue(launcher.hasProvisionedToolchain(root, paths.home, "codex"))
      val check = File(paths.home, "recovery.sh").apply { writeText(
        "set -eu\n" + ProotSessionLauncher.CODEX_INSTALL_SCRIPT + """

        codex_works() { echo unexpected-probe >&2; return 99; }
        npm() { echo unexpected-download >&2; return 99; }
        install_codex_package
        codex_stamped
        mkdir -p /root/links /workspace/links
        printf surviving > /usr/root-only
        ln /usr/root-only /root/links/survivor
        ln /root/links/survivor /workspace/links/survivor
        printf orphan > /usr/root-orphan
        ln /usr/root-orphan /usr/root-orphan-alias
        printf complete > /root/recovery-complete
        """.trimIndent()) }
      run(launcher.interactiveShellLaunchSpec(root, paths.home, paths.defaultWorkspace, sessionCommand = "exec /bin/sh /root/${check.name}"))
      assertEquals("complete", File(paths.home, "recovery-complete").readText())
      val orphanLink = File(root, "usr/root-orphan")
      val orphanIntermediate = File(java.nio.file.Files.readSymbolicLink(orphanLink.toPath()).toString())
      val orphanBacking = File(java.nio.file.Files.readSymbolicLink(orphanIntermediate.toPath()).toString())
      assertEquals(1L, android.system.Os.lstat(orphanBacking.absolutePath).st_nlink)
      val busyLog = File(paths.base, "busy.log")
      val busy = process(launcher.interactiveShellLaunchSpec(root, paths.home, sessionCommand = "printf busy > /root/busy-ready; while [ ! -f /root/busy-stop ]; do sleep 1; done"), busyLog)
      try {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (!File(paths.home, "busy-ready").exists() && System.nanoTime() < deadline) Thread.sleep(50)
        assertTrue(busyLog.readText(), File(paths.home, "busy-ready").exists())
        try { store.reset(); fail("live PRoot permitted reset") } catch (expected: java.io.IOException) {
          assertTrue(expected.message.orEmpty().contains("in use"))
        }
        assertTrue(orphanBacking.exists())
      } finally {
        File(paths.home, "busy-stop").writeText("stop")
        if (!busy.waitFor(30, TimeUnit.SECONDS)) { busy.destroyForcibly(); fail("busy fixture did not exit: ${busyLog.readText()}") }
      }
      store.reset()
      assertFalse(orphanBacking.exists()); assertFalse(orphanIntermediate.exists())
      assertEquals("surviving", File(paths.home, "links/survivor").readText())
      store.reset(deleteHome = true)
      assertEquals("surviving", File(paths.defaultWorkspace, "links/survivor").readText())
      store.reset(deleteWorkspaces = true)
      assertTrue(paths.hardlinks.listFiles()!!.none { it.name.startsWith(".l2s.") })
      android.util.Log.i(TAG, "PR23_RECOVERY_OK stamped-no-probe actual-l2s-nlink=1 live-reset-blocked lifecycle-collected")
    } finally { assertTrue("test guest cleanup failed", paths.base.deleteRecursively()) }
  }

  private fun checkInteractiveExits(launcher: ProotSessionLauncher, rootfs: File, home: File) {
    val bytes = ByteArrayOutputStream()
    fun output(): String = synchronized(bytes) { bytes.toString("UTF-8") }
    val exits = CopyOnWriteArrayList<TerminalSessionSupervisor.SessionExitInfo>()
    lateinit var supervisor: TerminalSessionSupervisor
    val listener = object : TerminalSessionSupervisor.EventListener {
      override fun onSessionOutput(sessionId: String, seq: Long, chunk: ByteArray) {
        synchronized(bytes) { bytes.write(chunk) }
        supervisor.acknowledgeOutput(sessionId, seq)
      }
      override fun onSessionExit(info: TerminalSessionSupervisor.SessionExitInfo) { exits += info }
    }
    supervisor = TerminalSessionSupervisor(JniPtyBackend(), listener, ProcessTree(), { pid, signal -> android.system.Os.kill(pid, signal) })
    fun await(label: String, condition: () -> Boolean) {
      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
      while (System.nanoTime() < deadline && !condition()) Thread.sleep(50)
      assertTrue("$label: ${output().takeLast(2000)}", condition())
    }
    try {
      for (ending in listOf("exit\n", "\u0004", "exit 137\n", "kill -KILL ${'$'}${'$'}\n")) {
        synchronized(bytes) { bytes.reset() }
        val id = supervisor.nextSessionId()
        val launch = launcher.interactiveShellLaunchSpec(rootfs, home)
        val started = supervisor.start(id, TerminalSessionSupervisor.StartSpec(launch.argv, launch.environment, launch.workingDirectory, 24, 80))
        assertTrue(started.toString(), started is TerminalSessionSupervisor.StartOutcome.Success)
        await("initial zsh prompt") { output().contains("└─[") }
        assertTrue(supervisor.write(id, "stty -echo; PROMPT='P23> '\n".toByteArray()) is TerminalSessionSupervisor.WriteOutcome.Success)
        await("test prompt") { output().contains("P23> ") }
        val checkpoint = output().length
        assertTrue(supervisor.write(id, "false\n".toByteArray()) is TerminalSessionSupervisor.WriteOutcome.Success)
        await("failed command returned to prompt") { output().drop(checkpoint).contains("P23> ") }
        assertTrue(supervisor.write(id, ending.toByteArray()) is TerminalSessionSupervisor.WriteOutcome.Success)
        await("shell exit") { exits.any { it.sessionId == id } }
        val exit = exits.first { it.sessionId == id }
        if (ending.startsWith("kill") || ending == "exit 137\n") assertTrue("$exit output=${output().takeLast(2000)}", exit.exitCode == 137 || exit.signal == "sigkill")
        else { assertEquals(exit.toString(), 0, exit.exitCode); assertNull(exit.signal) }
        android.util.Log.i(TAG, "PR23_ZSH_OK ending=${if (ending == "\u0004") "EOF" else ending.trim()} code=${exit.exitCode} signal=${exit.signal}")
      }
    } finally { supervisor.shutdownAll("test_cleanup") }
  }

  @Test
  fun realAndroidInputViewCommitsLiteralTabWithAltAndCtrl() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val writes = mutableListOf<ByteArray>()
    NativeTerminalEngineRegistry.configureInputWriter { _, bytes -> writes += bytes.copyOf() }
    try {
      instrumentation.runOnMainSync {
        val context = instrumentation.targetContext
        val view = TerminalInputView(ThemedReactContext(BridgeReactContext(context), context, "Pr23Input", -1))
        view.setTerminalSessionId("s-pr23-input")
        view.setTerminalEnabled(true)
        for (ctrl in listOf(false, true)) for (alt in listOf(false, true)) {
          view.setLineResetRequest(writes.size + 1)
          view.setCtrlActive(ctrl)
          view.setAltActive(alt)
          view.setText("\t")
        }
      }
      assertEquals(4, writes.size)
      writes.forEach { assertArrayEquals(byteArrayOf(9), it) }
      android.util.Log.i(TAG, "PR23_INPUT_TAB_OK combinations=4 bytes=09")
    } finally { NativeTerminalEngineRegistry.configureInputWriter(null) }
  }

  private companion object { const val TAG = "Pr23DeviceTest" }
}
