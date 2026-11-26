package com.scariflabs.horus.terminal

import android.net.ConnectivityManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real provisioning entry point in a disposable Debian rootfs. */
@RunWith(AndroidJUnit4::class)
class DebianRecoveryDeviceTest {
  @Test(timeout = 1_200_000) fun shellProvisioningRepairsUnconfiguredPackage() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    assertTrue(context.packageName.startsWith("com.scariflabs.horus.diagnostics"))
    val base = File(context.filesDir, "debian-recovery-${System.nanoTime()}")
    val paths = DistroStorePaths(base)
    val manifest = context.assets.open("alpine-runtime/manifest.json").bufferedReader().use { it.readText() }
    val runtime = (ProotRuntimeLocator(manifest).locate(File(context.applicationInfo.nativeLibraryDir)) as ProotRuntimeLocator.Location.Available).runtime
    val store = DistroStoreCore(paths, HttpArchiveDownloader(), SafeTarExtractor(), ProotGuestProber(runtime, paths.guestTmp))
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    val dns = connectivity.activeNetwork?.let { connectivity.getLinkProperties(it)?.dnsServers }?.mapNotNull { it.hostAddress }.orEmpty()
    val launcher = ProotSessionLauncher(runtime, paths.guestTmp, dnsServersProvider = { dns }, hardlinkDir = paths.hardlinks)
    fun run(spec: ProotSessionLauncher.LaunchSpec, seconds: Long): String {
      val log = File(base, "test-output.log")
      val process = ProcessBuilder(spec.argv).apply {
        environment().clear()
        spec.environment.forEach { environment()[it.substringBefore('=')] = it.substringAfter('=') }
        redirectErrorStream(true); redirectOutput(log)
      }.start()
      if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        fail("guest timed out: ${log.readText().takeLast(3000)}")
      }
      assertEquals(log.readText().takeLast(3000), 0, process.exitValue())
      return log.readText()
    }
    try {
      assertTrue(store.install(entry = RootfsCatalog.DEBIAN, downloadTimeoutMs = 300_000) is DistroStoreCore.InstallOutcome.Success)
      val root = store.activeRootfsDir()!!
      val home = paths.homeFor(RootfsCatalog.DISTRO_DEBIAN).apply { mkdirs() }
      val prepare = """
        set -eu
        mkdir -p /tmp/recovery-fixture/DEBIAN
        chmod 755 /tmp/recovery-fixture /tmp/recovery-fixture/DEBIAN
        printf 'Package: horus-recovery-fixture\nVersion: 1\nArchitecture: all\nMaintainer: Test <test@example.invalid>\nDescription: temporary recovery fixture\n' > /tmp/recovery-fixture/DEBIAN/control
        dpkg-deb --build /tmp/recovery-fixture /tmp/recovery-fixture.deb
        dpkg --unpack /tmp/recovery-fixture.deb
        dpkg --audit | grep horus-recovery-fixture
      """.trimIndent()
      run(launcher.interactiveShellLaunchSpec(root, home, sessionCommand = prepare), 60)
      val provision = run(launcher.toolchainProvisionLaunchSpec(root, home, "shell"), 900)
      assertTrue(provision, provision.contains("Finishing an earlier install that was interrupted"))
      val verify = run(launcher.interactiveShellLaunchSpec(root, home, sessionCommand =
        "dpkg-query -W -f='\${db:Status-Status}' horus-recovery-fixture"), 60)
      assertEquals("installed", verify.trim())
    } finally {
      assertTrue(base.deleteRecursively())
    }
  }
}
