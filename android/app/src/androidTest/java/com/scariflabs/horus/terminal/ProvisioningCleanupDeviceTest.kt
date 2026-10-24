package com.scariflabs.horus.terminal

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Network/physical tests, exclusively in a disposable application and stores. */
@RunWith(AndroidJUnit4::class)
class ProvisioningCleanupDeviceTest {
  private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
  @Before fun isolate() {
    assumeTrue(context.packageName.startsWith("com.scariflabs.horus.diagnostics"))
  }
  private fun evidence(text: String) {
    android.util.Log.i("HorusCleanupTest", text)
    File(context.filesDir, "cleanup-evidence.txt").appendText(text + "\n")
  }
  private fun host(script: String) = ProotSessionLauncher.LaunchSpec(
    listOf(File(context.applicationInfo.nativeLibraryDir, "libhorus_provision.so").absolutePath,
      "/system/bin/sh", "-c", script), listOf("PATH=/system/bin"), null,
  )
  private fun awaitFile(file: File) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
    while ((!file.exists() || file.length() == 0L) && System.nanoTime() < deadline) Thread.sleep(20)
    assertTrue("fixture not ready: ${file.name}", file.exists() && file.length() > 0)
  }
  @Test(timeout = 20000) fun androidTimeoutKillsIgnoringChildAndRetryWorks() {
    val marker = File(context.cacheDir, "cleanup-child")
    marker.delete()
    val result = ProvisioningProcess.run(host("trap '' TERM; sleep 60 & echo \$! > '${marker.absolutePath}'; wait"), 500)
    assertEquals("timed_out", result.outcome)
    val pid = marker.readText().trim().toLong()
    assertFalse("child $pid survived", ProcessTree().isAlive(pid))
    assertTrue(ProvisioningProcess.run(host("exit 0"), 3000).succeeded)
    evidence("host timeout outcome=${result.outcome} exit=${result.exitCode} child=$pid alive=false retry=success")
    marker.delete()
  }
  @Test(timeout = 20000) fun androidRegistrationCancellationCannotSucceed() {
    var cancelled = false
    val result = ProvisioningProcess.run(host("exit 0"), 3000,
      started = { cancelled = true }, cancelled = { cancelled })
    assertEquals("cancelled", result.outcome)
    evidence("registration cancellation=${result.outcome}")
  }
  @Test(timeout = 20000) fun androidParentDeathTriggersOrphanCleanup() {
    val base = File(context.cacheDir, "parent-death-${System.nanoTime()}").apply { mkdirs() }
    val childFile = File(base, "child")
    val supervisorFile = File(base, "supervisor")
    val helper = File(context.applicationInfo.nativeLibraryDir, "libhorus_provision.so")
    val script = "'$helper' /system/bin/sh -c 'sleep 60 & echo \$! > ${childFile.absolutePath}; wait' & echo \$! > ${supervisorFile.absolutePath}; wait"
    val parent = ProcessBuilder("/system/bin/sh", "-c", script).apply {
      environment().clear(); environment()["PATH"] = "/system/bin"
      redirectErrorStream(true)
    }.start()
    try {
      awaitFile(childFile); awaitFile(supervisorFile)
      val child = childFile.readText().trim().toLong()
      val supervisor = supervisorFile.readText().trim().toLong()
      parent.destroy(); assertTrue(parent.waitFor(3, TimeUnit.SECONDS))
      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6)
      while ((ProcessTree().isAlive(child) || ProcessTree().isAlive(supervisor)) && System.nanoTime() < deadline) Thread.sleep(20)
      assertFalse(ProcessTree().isAlive(child)); assertFalse(ProcessTree().isAlive(supervisor))
      evidence("android parent_death parent=${parent.exitValue()} supervisor=$supervisor child=$child alive=false")
    } finally {
      parent.destroy(); parent.waitFor(3, TimeUnit.SECONDS)
      base.deleteRecursively()
    }
  }
  @Test(timeout = 10000) fun serviceShutdownCompletesARequestStillQueuedBeforeStartup() {
    val service = TerminalSessionService()
    val executorField = TerminalSessionService::class.java.getDeclaredField("provisionExecutor").apply { isAccessible = true }
    val executor = executorField.get(service) as java.util.concurrent.ExecutorService
    val stoppingField = TerminalSessionService::class.java.getDeclaredField("stopping").apply { isAccessible = true }
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val replied = CountDownLatch(1)
    var result: ProvisioningResult? = null
    try {
      executor.execute {
        entered.countDown()
        while (release.count > 0) try { release.await() } catch (_: InterruptedException) { }
      }
      assertTrue(entered.await(2, TimeUnit.SECONDS))
      executor.execute {
        result = ProvisioningProcess.run(host("exit 0"), 1000,
          started = { fail("a cancelled queued request spawned a process") },
          cancelled = { stoppingField.getBoolean(service) })
        replied.countDown()
      }
      stoppingField.setBoolean(service, true)
      TerminalSessionService::class.java.getDeclaredMethod("cancelProvisioning").apply { isAccessible = true }.invoke(service)
      assertTrue(replied.await(1, TimeUnit.SECONDS))
      assertEquals("cancelled", result?.outcome)
      evidence("service queued request cancellation=${result?.outcome} reply=delivered spawn=false")
    } finally {
      release.countDown(); executor.shutdownNow(); executor.awaitTermination(2, TimeUnit.SECONDS)
    }
  }
  @Test(timeout = 2700000) fun disposableAlpineAndDebianCancelLockRetryAndDpkgRecovery() {
    val manifest = context.assets.open("alpine-runtime/manifest.json").bufferedReader().use { it.readText() }
    val runtime = (ProotRuntimeLocator(manifest).locate(File(context.applicationInfo.nativeLibraryDir)) as ProotRuntimeLocator.Location.Available).runtime
    for (entry in listOf(RootfsCatalog.ALPINE, RootfsCatalog.DEBIAN)) {
      val base = File(context.filesDir, "cleanup-store-${System.nanoTime()}")
      val paths = DistroStorePaths(base)
      val store = DistroStoreCore(paths, HttpArchiveDownloader(), SafeTarExtractor(), ProotGuestProber(runtime, paths.guestTmp))
      var worker: Thread? = null
      try {
        val installed = store.install(entry = entry, downloadTimeoutMs = 300000)
        assertTrue("disposable store install: $installed", installed is DistroStoreCore.InstallOutcome.Success)
        val root = store.activeRootfsDir()!!
        val home = paths.homeFor(entry.distro).apply { mkdirs() }
        val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java)
        val dns = connectivity.activeNetwork?.let { connectivity.getLinkProperties(it)?.dnsServers }?.mapNotNull { it.hostAddress }.orEmpty()
        val launcher = ProotSessionLauncher(runtime, paths.guestTmp, hardlinkDir = paths.hardlinks, dnsServersProvider = { dns })
        val original = launcher.toolchainProvisionLaunchSpec(root, home, "shell")
        fun launch(script: String) = original.copy(argv = original.argv.dropLast(1) + script)
        val marker = File(home, "child")
        var result: ProvisioningResult? = null
        val started = CountDownLatch(1)
        worker = Thread {
          result = ProvisioningProcess.run(launch(ProotSessionLauncher.PACKAGE_LOCK_SCRIPT +
            "\npkg_lock_take\nsleep 60 & echo \$! >/root/child; wait"), 120000,
            started = { started.countDown() })
        }
        worker.start(); assertTrue(started.await(10, TimeUnit.SECONDS)); awaitFile(marker)
        val pid = marker.readText().trim().toLong()
        evidence("${entry.distro} before cancel guest_child=$pid stat=" + File("/proc/$pid/stat").readText().trim())
        worker.interrupt(); worker.join(10000)
        assertFalse(worker.isAlive); assertEquals("cancelled", result?.outcome)
        assertFalse(ProcessTree().isAlive(pid))
        val retry = ProvisioningProcess.run(launch(ProotSessionLauncher.PACKAGE_LOCK_SCRIPT +
          "\npkg_lock_take\n" + if (entry.distro == RootfsCatalog.DISTRO_DEBIAN) "dpkg --audit; dpkg-query -W dpkg" else "apk info -e busybox"), 30000)
        assertTrue("package lock retry: $retry", retry.succeeded)
        evidence("${entry.distro} cancel=${result?.outcome} exit=${result?.exitCode} child=$pid alive=false package_lock_retry=success")
        if (entry.distro == RootfsCatalog.DISTRO_DEBIAN) {
          val control = File(home, "fixture/DEBIAN").apply { mkdirs(); setReadable(true, false); setExecutable(true, false) }
          File(control, "control").writeText("Package: horus-cleanup-fixture\nVersion: 1.0\nArchitecture: all\nMaintainer: Horus Test <test@example.invalid>\nDescription: disposable cleanup regression\n")
          File(control, "postinst").apply {
            writeText("#!/bin/sh\nif [ ! -f /root/configure-once ]; then touch /root/configure-once; echo \$\$ > /root/configuring; sleep 60; fi\nexit 0\n")
            setReadable(true, false); setExecutable(true, false)
          }
          val build = ProvisioningProcess.run(launch("dpkg-deb --build /root/fixture /root/fixture.deb >/root/fixture-build.log 2>&1"), 30000)
          assertTrue("disposable package build: $build " + File(home, "fixture-build.log").readText(), build.succeeded)
          worker = Thread {
            result = ProvisioningProcess.run(launch(ProotSessionLauncher.PACKAGE_LOCK_SCRIPT + "\npkg_lock_take\ndpkg -i /root/fixture.deb"), 120000)
          }
          worker.start(); awaitFile(File(home, "configuring")); worker.interrupt(); worker.join(10000)
          assertFalse(worker.isAlive); assertEquals("cancelled", result?.outcome)
          val recover = ProvisioningProcess.run(launch(ProotSessionLauncher.PACKAGE_LOCK_SCRIPT +
            "\npkg_lock_take\ndpkg --configure -a && test \"\$(dpkg-query -W -f='\${Status}' horus-cleanup-fixture)\" = 'install ok installed'"), 30000)
          assertTrue("interrupted dpkg must recover: $recover", recover.succeeded)
          evidence("debian interrupted postinst=${result?.outcome} dpkg_configure_retry=success status=installed")
        }
        assertTrue(ProvisioningProcess.run(launch("/bin/sh -c 'exit 0'"), 5000).succeeded)
        // Match the service's 15-minute production deadline for slow PRoot/dpkg.
        val ordinary = ProvisioningProcess.run(original, 900000)
        assertTrue("ordinary shell provisioning: $ordinary", ordinary.succeeded)
        assertTrue(launcher.hasProvisionedToolchain(root, home, "shell"))
        evidence("${entry.distro} ordinary_shell_provision=success")
      } finally {
        worker?.takeIf { it.isAlive }?.let { it.interrupt(); it.join(10000) }
        // Never delete a rootfs while a test-owned worker can still mutate it.
        if (worker?.isAlive != true) base.deleteRecursively()
      }
    }
  }
}
