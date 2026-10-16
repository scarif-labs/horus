package com.scariflabs.horus.terminal

import android.net.ConnectivityManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 4 on a device: the same provisioning script installs every target
 * with apt in the pinned Debian rootfs, and sessions switch to a named user
 * with setpriv, since Debian has no BusyBox su. Runs in a test-owned store.
 */
@RunWith(AndroidJUnit4::class)
class DebianToolchainDeviceTest {
  @Test(timeout = 2_700_000)
  fun provisionsEveryTargetWithAptAndRunsSessionsAsTheUser() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val manifestText = context.assets.open("alpine-runtime/manifest.json").bufferedReader().use { it.readText() }
    val located = ProotRuntimeLocator(manifestText).locate(File(context.applicationInfo.nativeLibraryDir ?: ""))
    assertTrue("packaged PRoot runtime is unavailable", located is ProotRuntimeLocator.Location.Available)
    val runtime = (located as ProotRuntimeLocator.Location.Available).runtime
    val testRoot = File(context.filesDir, "debian-toolchain-device-test-${System.nanoTime()}")
    val paths = DistroStorePaths(testRoot)
    val store = DistroStoreCore(
      paths = paths,
      downloader = HttpArchiveDownloader(),
      extractor = SafeTarExtractor(),
      prober = ProotGuestProber(runtime, paths.guestTmp),
    )
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    val dnsServers = connectivity.activeNetwork
      ?.let { connectivity.getLinkProperties(it)?.dnsServers }
      ?.mapNotNull { it.hostAddress?.trim()?.takeIf(String::isNotEmpty) }
      .orEmpty()
    val launcher = ProotSessionLauncher(
      runtime = runtime,
      scratchDir = paths.guestTmp,
      hardlinkDir = paths.hardlinks,
      dnsServersProvider = { dnsServers },
      seccompAcceleration = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("horusSeccomp") == "on",
    )
    val debian = RootfsCatalog.DISTRO_DEBIAN

    try {
      val install = store.install(entry = RootfsCatalog.DEBIAN, downloadTimeoutMs = 300_000)
      assertTrue("debian install failed: $install", install is DistroStoreCore.InstallOutcome.Success)
      val rootfs = store.activeRootfsDir()!!
      val home = paths.homeFor(debian).apply { mkdirs() }

      val targets = listOf(
        TerminalRuntimeContract.TOOLCHAIN_TARGET_SHELL to "zsh",
        TerminalRuntimeContract.TOOLCHAIN_TARGET_GITHUB to "gh",
        TerminalRuntimeContract.TOOLCHAIN_TARGET_CLAUDE to "claude",
        TerminalRuntimeContract.TOOLCHAIN_TARGET_CODEX to "codex",
        TerminalRuntimeContract.TOOLCHAIN_TARGET_OPENCODE to "opencode",
      )
      for ((target, command) in targets) {
        // Harnesses get their own Debian harness home, as the app gives them.
        val targetHome = if (command in setOf("claude", "codex", "opencode")) {
          paths.harnessHomeFor(debian, target).apply { mkdirs() }
        } else {
          home
        }
        val started = System.currentTimeMillis()
        val result = runLaunch(launcher.toolchainProvisionLaunchSpec(rootfs, targetHome, target), 900)
        android.util.Log.i(LOG_TAG, "DEBIAN_PROVISION target=$target exit=${result.first} ms=${System.currentTimeMillis() - started}")
        val logs = File(targetHome, ".cache/horus").listFiles { file -> file.name.endsWith(".log") }.orEmpty()
          .joinToString("\n") { "== ${it.name}\n${it.readText().takeLast(1_500)}" }
        assertEquals("$target provisioning failed:\n${result.second.takeLast(3_000)}\n$logs", 0, result.first)
        assertTrue("$target is not ready after provisioning", launcher.hasProvisionedToolchain(rootfs, targetHome, target))

        val user = if (targetHome == home) "vishesh" else TerminalHarnessUsers.forTarget(target, "vishesh")!!.username
        val uid = if (targetHome == home) ProotSessionLauncher.GUEST_UID else TerminalHarnessUsers.forTarget(target, "vishesh")!!.uid
        val gid = if (targetHome == home) ProotSessionLauncher.GUEST_GID else TerminalHarnessUsers.SHARED_WORKSPACE_GID
        val marker = "DEBIAN_${command.uppercase()}_OK"
        val session = launcher.toolchainSessionLaunchSpec(
          rootfsDir = rootfs,
          guestHomeDir = targetHome,
          workspaceDir = paths.defaultWorkspace.apply { mkdirs() },
          guestUsername = user,
          guestUid = uid,
          guestGid = gid,
          target = target,
          provision = false,
          sessionCommand = "[ \"\$(id -un)\" = $user ] && [ \"\$HOME\" = /home/$user ] && " +
            "command -v $command >/dev/null && $command --version >/dev/null 2>&1 && echo $marker",
        )
        val sessionResult = runLaunch(session, 120)
        assertTrue(
          "$target session as $user failed: ${sessionResult.second.takeLast(2_000)}",
          // The line may start with the cursor-up and erase codes the handoff marker leaves behind.
          sessionResult.second.replace('\r', '\n').lineSequence().any { it.trim().endsWith(marker) },
        )
        android.util.Log.i(LOG_TAG, "DEBIAN_SESSION_OK target=$target user=$user")
      }
    } finally {
      testRoot.deleteRecursively()
    }
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
    val drain = Thread { process.inputStream.use { it.copyTo(output) } }.apply {
      isDaemon = true
      start()
    }
    val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
    if (!completed) {
      process.destroyForcibly()
      process.waitFor(2, TimeUnit.SECONDS)
    }
    drain.join(2_000)
    assertTrue("debian launch timed out:\n${output.toString(Charsets.ISO_8859_1.name()).takeLast(3_000)}", completed)
    return process.exitValue() to output.toString(Charsets.ISO_8859_1.name())
  }

  private companion object {
    const val LOG_TAG = "DebianToolchainTest"
  }
}
