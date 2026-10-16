package com.scariflabs.horus.terminal

import android.net.ConnectivityManager
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** Service tests use production paths, but bootstrap only the isolated validation package. */
internal object DeviceTestGuest {
  fun ensureInstalled() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val paths = DistroStorePaths(File(context.filesDir, TerminalRuntimeContract.STORAGE_ROOT_DIR_NAME))
    val manifest = context.assets.open("alpine-runtime/manifest.json").bufferedReader().use { it.readText() }
    val location = ProotRuntimeLocator(manifest).locate(File(context.applicationInfo.nativeLibraryDir))
    assertTrue(location.toString(), location is ProotRuntimeLocator.Location.Available)
    val runtime = (location as ProotRuntimeLocator.Location.Available).runtime
    val store = DistroStoreCore(paths, HttpArchiveDownloader(), SafeTarExtractor(), ProotGuestProber(runtime, paths.guestTmp))
    // Ordinary device runs retain the existing read-only rootfs precondition.
    if (!context.packageName.endsWith(".pr23check")) {
      assertTrue("install a guest first, or run the isolated validation package", store.activeRootfsDir() != null)
      return
    }
    if (store.activeRootfsDir() == null) {
      val installed = store.install()
      assertTrue(installed.toString(), installed is DistroStoreCore.InstallOutcome.Success)
    }
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    val dns = connectivity.activeNetwork?.let { connectivity.getLinkProperties(it)?.dnsServers }
      ?.mapNotNull { it.hostAddress }.orEmpty()
    val launcher = ProotSessionLauncher(runtime, paths.guestTmp, dnsServersProvider = { dns })
    val rootfs = requireNotNull(store.activeRootfsDir())
    if (launcher.hasProvisionedToolchain(rootfs, paths.home, "shell") && File(rootfs, "usr/bin/node").canExecute()) return
    val provision = launcher.toolchainProvisionLaunchSpec(rootfs, paths.home, "shell")
    // Exercise the optional V8/thread startup benchmarks in the seccomp matrix too.
    val launch = provision.copy(argv = provision.argv.dropLast(1) + (provision.argv.last() + "\napk add --no-cache --no-progress nodejs"))
    val log = File(paths.base, "device-test-bootstrap.log")
    val process = ProcessBuilder(launch.argv).apply {
      environment().clear()
      launch.environment.forEach { entry -> environment()[entry.substringBefore('=')] = entry.substringAfter('=') }
      redirectErrorStream(true)
      redirectOutput(log)
    }.start()
    if (!process.waitFor(300, TimeUnit.SECONDS)) {
      process.destroyForcibly()
      throw AssertionError("test guest bootstrap timeout: ${log.readText().takeLast(2000)}")
    }
    assertEquals(log.readText().takeLast(3000), 0, process.exitValue())
  }
}
