package com.scariflabs.horus.terminal

import android.net.ConnectivityManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** A disposable Alpine guest; never touches the installed app's Linux profile. */
@RunWith(AndroidJUnit4::class)
class SudoPrivacyDeviceTest {
  @Test(timeout = 600_000) fun sudoDoesNotLogArgumentsAndRemovesLegacyLog() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    assertTrue(context.packageName.startsWith("com.scariflabs.horus.diagnostics"))
    val base = File(context.filesDir, "sudo-privacy-${System.nanoTime()}")
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
        fail("guest timed out: ${log.readText().takeLast(2000)}")
      }
      assertEquals(log.readText().takeLast(2000), 0, process.exitValue())
      return log.readText()
    }
    try {
      assertTrue(store.install(downloadTimeoutMs = 120_000) is DistroStoreCore.InstallOutcome.Success)
      val root = store.activeRootfsDir()!!
      paths.home.mkdirs()
      run(launcher.toolchainProvisionLaunchSpec(root, paths.home, "shell"), 300)
      val legacy = File(root, "run/horus-sudo/helper.log")
      legacy.parentFile!!.mkdirs()
      legacy.writeText("old-sensitive-data")
      val result = run(launcher.toolchainSessionLaunchSpec(
        root, paths.home, guestUsername = "privacytest", target = "shell", provision = false, rootHelper = true,
        sessionCommand = "sudo /bin/sh -c 'test \"${'$'}1\" = SYNTHETIC_SECRET && echo SUDO_PRIVACY_OK' sh SYNTHETIC_SECRET",
      ), 60)
      assertTrue(result, result.contains("SUDO_PRIVACY_OK"))
      assertFalse("legacy log remains", legacy.exists())
      val errors = File(root, "run/horus-sudo/helper-errors.log")
      assertFalse("command argument leaked", errors.exists() && errors.readText().contains("SYNTHETIC_SECRET"))
    } finally {
      assertTrue(base.deleteRecursively())
    }
  }
}
