package com.scariflabs.horus.terminal

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in physical tests: downloads disposable Alpine stores; never uses the installed Linux store. */
@RunWith(AndroidJUnit4::class)
class PackageLockFollowupDeviceTest {
  @Test(timeout = 180_000) fun productionLoggerDropsPackageLockOnPhysicalAlpine() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val base = File(context.filesDir, "v111-logger-${System.nanoTime()}")
    val paths = DistroStorePaths(base)
    val manifest = context.assets.open("alpine-runtime/manifest.json").bufferedReader().use { it.readText() }
    val location = ProotRuntimeLocator(manifest).locate(File(context.applicationInfo.nativeLibraryDir))
    assertTrue(location is ProotRuntimeLocator.Location.Available)
    val runtime = (location as ProotRuntimeLocator.Location.Available).runtime
    val store = DistroStoreCore(paths, HttpArchiveDownloader(), SafeTarExtractor(), ProotGuestProber(runtime, paths.guestTmp))
    val children = mutableListOf<Process>()
    try {
      assertTrue(store.install(downloadTimeoutMs = 120_000) is DistroStoreCore.InstallOutcome.Success)
      val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java)
      val dns = connectivity.activeNetwork?.let { connectivity.getLinkProperties(it)?.dnsServers }?.mapNotNull { it.hostAddress }.orEmpty()
      val launcher = ProotSessionLauncher(runtime, paths.guestTmp, dnsServersProvider = { dns }, hardlinkDir = paths.hardlinks)
      val spec = launcher.toolchainProvisionLaunchSpec(store.activeRootfsDir()!!, paths.home, "shell")
      val generated = spec.argv.last()
      val logger = generated.substring(generated.indexOf("run_logged() {"), generated.indexOf("# The installers download silently"))
      val script = "mkdir -p /root/.cache/horus\n" + ProotSessionLauncher.PACKAGE_LOCK_SCRIPT + "\n" + logger + "\n" + """
        set -e
        round=0
        while [ "${'$'}round" -lt 20 ]; do
          pkg_lock_take
          run_logged /root/.cache/horus/logger-probe.log /bin/true
          pkg_lock_release
          exec 7>>"${'$'}pkg_lock_file"
          flock -n 7 || { echo "LOCK_LEAK round=${'$'}round"; exit 77; }
          exec 7>&-
          round=${'$'}((round + 1))
        done
        echo PRODUCTION_LOGGER_LOCK_RELEASED_20_ROUNDS
      """.trimIndent()
      val output = File(context.filesDir, "validation111-evidence/logger-lock.log").apply { parentFile!!.mkdirs() }
      val process = ProcessBuilder(spec.argv.dropLast(1) + script).apply {
        environment().clear()
        spec.environment.forEach { environment()[it.substringBefore('=')] = it.substringAfter('=') }
        redirectErrorStream(true); redirectOutput(output)
      }.start().also { children.add(it) }
      val completed = process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)
      if (!completed) process.destroyForcibly()
      assertTrue("logger fixture timed out", completed)
      assertEquals(output.readText(), 0, process.exitValue())
      assertTrue(output.readText().contains("PRODUCTION_LOGGER_LOCK_RELEASED_20_ROUNDS"))
    } finally {
      children.filter { it.isAlive }.forEach { it.destroyForcibly() }
      children.forEach { it.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) }
      base.deleteRecursively()
    }
  }

  @Test(timeout = 180_000) fun sshLazyPackageHelperHonorsTheSharedRootfsLock() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val base = File(context.filesDir, "v111-ssh-lazy-${System.nanoTime()}")
    val paths = DistroStorePaths(base)
    val manifest = context.assets.open("alpine-runtime/manifest.json").bufferedReader().use { it.readText() }
    val located = ProotRuntimeLocator(manifest).locate(File(context.applicationInfo.nativeLibraryDir)) as ProotRuntimeLocator.Location.Available
    val runtime = located.runtime
    val store = DistroStoreCore(paths, HttpArchiveDownloader(), SafeTarExtractor(), ProotGuestProber(runtime, paths.guestTmp))
    val children = mutableListOf<Process>()
    try {
      assertTrue(store.install(downloadTimeoutMs = 120_000) is DistroStoreCore.InstallOutcome.Success)
      val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java)
      val dns = connectivity.activeNetwork?.let { connectivity.getLinkProperties(it)?.dnsServers }?.mapNotNull { it.hostAddress }.orEmpty()
      val launcher = ProotSessionLauncher(runtime, paths.guestTmp, dnsServersProvider = { dns }, hardlinkDir = paths.hardlinks)
      val root = store.activeRootfsDir()!!
      val spec = launcher.toolchainProvisionLaunchSpec(root, paths.home, "shell")
      val remote = launcher.remoteAccessLaunchSpec(root, paths.home, null, "validation")
      val remoteScript = remote.argv[remote.argv.indexOf("horus-remote-access") - 1]
      val helperStart = remoteScript.indexOf("system_packages() {")
      val sharedStart = remoteScript.lastIndexOf("pkg_lock_file=/var/lib/horus/packages.lock", helperStart)
      assertTrue("lazy installer does not embed the shared rootfs lock", sharedStart > remoteScript.lastIndexOf("HORUS_INSTALLER", helperStart))
      val helper = remoteScript.substring(sharedStart, remoteScript.indexOf("install_claude() {"))
      val evidence = File(context.filesDir, "validation111-evidence").apply { mkdirs() }
      fun start(script: String, name: String): Process = ProcessBuilder(spec.argv.dropLast(1) + script).apply {
        environment().clear()
        spec.environment.forEach { environment()[it.substringBefore('=')] = it.substringAfter('=') }
        redirectErrorStream(true); redirectOutput(File(evidence, name))
      }.start().also { children.add(it) }
      val holder = start(ProotSessionLauncher.PACKAGE_LOCK_SCRIPT + "\npkg_lock_take\ntouch /var/lib/horus/lazy-holder-started\nsleep 20\npkg_lock_release\n", "ssh-lazy-holder.log")
      val ready = File(root, "var/lib/horus/lazy-holder-started")
      val deadline = System.currentTimeMillis() + 10_000
      while (!ready.exists() && System.currentTimeMillis() < deadline) Thread.sleep(20)
      assertTrue(ready.exists())
      val candidate = start(helper + "\nsystem_packages busybox\n", "ssh-lazy-helper.log")
      val completedEarly = candidate.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
      val bypass = completedEarly && holder.isAlive
      File(evidence, "ssh-lazy-lock-result.txt").writeText("helper_finished_while_rootfs_lock_held=$bypass\nhelper_exit=${if (completedEarly) candidate.exitValue() else "pending"}\nexecution_identity=root guest; production system_packages helper, real apk busybox; no SSH login\n")
      assertTrue(holder.waitFor(25, java.util.concurrent.TimeUnit.SECONDS))
      assertTrue(candidate.waitFor(60, java.util.concurrent.TimeUnit.SECONDS))
      assertEquals(0, candidate.exitValue())
      assertFalse("SSH lazy system_packages entered real apk and completed while another session held packages.lock", bypass)
      // Exercise lock-file access and queuing with the actual guest profile UID as well.
      val userHolder = start(ProotSessionLauncher.PACKAGE_LOCK_SCRIPT + "\npkg_lock_take\ntouch /var/lib/horus/user-holder-started\nsleep 8\npkg_lock_release\n", "ssh-lazy-user-holder.log")
      val userReady = File(root, "var/lib/horus/user-holder-started")
      val userDeadline = System.currentTimeMillis() + 10_000
      while (!userReady.exists() && System.currentTimeMillis() < userDeadline) Thread.sleep(20)
      assertTrue(userReady.exists())
      val userSpec = launcher.interactiveShellLaunchSpec(root, paths.home, guestUsername = "validation",
        sessionCommand = "echo SSH_HELPER_UID=\$(id -u)\n" + helper + "\nsystem_packages busybox\n")
      val userOutput = File(evidence, "ssh-lazy-user-helper.log")
      val userProcess = ProcessBuilder(userSpec.argv).apply {
        environment().clear()
        userSpec.environment.forEach { environment()[it.substringBefore('=')] = it.substringAfter('=') }
        redirectErrorStream(true); redirectOutput(userOutput)
      }.start().also { children.add(it) }
      assertFalse("guest-user helper bypassed rootfs lock", userProcess.waitFor(3, java.util.concurrent.TimeUnit.SECONDS))
      assertTrue(userHolder.waitFor(12, java.util.concurrent.TimeUnit.SECONDS))
      assertTrue(userProcess.waitFor(60, java.util.concurrent.TimeUnit.SECONDS))
      assertEquals(userOutput.readText(), 0, userProcess.exitValue())
      assertTrue(userOutput.readText().contains("SSH_HELPER_UID=1000"))
      assertTrue(userOutput.readText().contains("Waiting for another install to finish…"))
      File(evidence, "ssh-lazy-lock-result.txt").appendText("PASS actual guest UID 1000 also waits on shared rootfs lock and completes real apk operation\n")

    } finally {
      children.filter { it.isAlive }.forEach { it.destroyForcibly() }
      children.forEach { it.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) }
      base.deleteRecursively()
    }
  }

}
