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
 * The shell's system packages report progress on both distributions: apt's
 * Status-Fd and apk's --progress-fd become "Downloading/Installing
 * packages… N%" lines. Also logs how long each install takes.
 */
@RunWith(AndroidJUnit4::class)
class PackageProgressDeviceTest {
  @Test(timeout = 1_200_000)
  fun shellInstallReportsPackageProgressOnAlpineAndDebian() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val manifestText = context.assets.open("alpine-runtime/manifest.json").bufferedReader().use { it.readText() }
    val located = ProotRuntimeLocator(manifestText).locate(File(context.applicationInfo.nativeLibraryDir ?: ""))
    assertTrue("packaged PRoot runtime is unavailable", located is ProotRuntimeLocator.Location.Available)
    val runtime = (located as ProotRuntimeLocator.Location.Available).runtime
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    val dnsServers = connectivity.activeNetwork
      ?.let { connectivity.getLinkProperties(it)?.dnsServers }
      ?.mapNotNull { it.hostAddress?.trim()?.takeIf(String::isNotEmpty) }
      .orEmpty()

    for (entry in listOf(RootfsCatalog.DEBIAN, RootfsCatalog.ALPINE)) {
      val testRoot = File(context.filesDir, "package-progress-test-${entry.distro}-${System.nanoTime()}")
      val paths = DistroStorePaths(testRoot)
      val store = DistroStoreCore(
        paths = paths,
        downloader = HttpArchiveDownloader(),
        extractor = SafeTarExtractor(),
        prober = ProotGuestProber(runtime, paths.guestTmp),
      )
      val launcher = ProotSessionLauncher(
        runtime = runtime,
        scratchDir = paths.guestTmp,
        hardlinkDir = paths.hardlinks,
        dnsServersProvider = { dnsServers },
        seccompAcceleration = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("horusSeccomp") == "on",
      )
      try {
        val install = store.install(entry = entry, downloadTimeoutMs = 300_000)
        assertTrue("${entry.distro} install failed: $install", install is DistroStoreCore.InstallOutcome.Success)
        val rootfs = store.activeRootfsDir()!!
        val home = paths.homeFor(entry.distro).apply { mkdirs() }
        val started = System.currentTimeMillis()
        val (exit, lines) = runLaunch(
          launcher.toolchainProvisionLaunchSpec(rootfs, home, TerminalRuntimeContract.TOOLCHAIN_TARGET_SHELL),
          900,
        )
        val elapsed = System.currentTimeMillis() - started
        logTimeline(entry.distro, lines)
        val output = lines.joinToString("\n") { it.second }
        val progress = lines.map { it.second }.filter { it.matches(Regex("(Downloading|Installing) packages… \\d{1,3}%")) }
        android.util.Log.i(LOG_TAG, "PACKAGE_PROGRESS distro=${entry.distro} exit=$exit ms=$elapsed lines=${progress.size} last=${progress.lastOrNull()}")
        assertEquals("${entry.distro} shell provisioning failed:\n${output.takeLast(3_000)}", 0, exit)
        assertTrue("${entry.distro} reported no installing progress: ${output.takeLast(2_000)}", progress.any { it.startsWith("Installing packages") })
        // The app reads the line while tools install; it is cleared once they finish.
        assertEquals("", File(home, ".cache/horus/provision-progress").readText().trim())
      } finally {
        testRoot.deleteRecursively()
      }
    }
  }

  /** Runs [launch]; each output line comes back with the milliseconds since start at which it arrived. */
  private fun runLaunch(launch: ProotSessionLauncher.LaunchSpec, timeoutSeconds: Long): Pair<Int, List<Pair<Long, String>>> {
    val started = System.currentTimeMillis()
    val process = ProcessBuilder(launch.argv).apply {
      launch.workingDirectory?.let(::directory)
      environment().clear()
      launch.environment.forEach { entry ->
        val separator = entry.indexOf('=')
        if (separator > 0) environment()[entry.substring(0, separator)] = entry.substring(separator + 1)
      }
      redirectErrorStream(true)
    }.start()
    val lines = java.util.Collections.synchronizedList(mutableListOf<Pair<Long, String>>())
    val drain = Thread {
      process.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
        lines.add(System.currentTimeMillis() - started to line.replace("\r", "").trim())
      }
    }.apply {
      isDaemon = true
      start()
    }
    val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
    if (!completed) {
      process.destroyForcibly()
      process.waitFor(2, TimeUnit.SECONDS)
    }
    drain.join(2_000)
    assertTrue("launch timed out", completed)
    return process.exitValue() to lines.toList()
  }

  /** Where the time went: phase starts, and the longest silences with the line before each. */
  private fun logTimeline(distro: String, lines: List<Pair<Long, String>>) {
    fun firstAt(prefix: String) = lines.firstOrNull { it.second.startsWith(prefix) }?.first
    android.util.Log.i(
      LOG_TAG,
      "TIMELINE distro=$distro index=${firstAt("Get:") ?: firstAt("fetch")} download=${firstAt("Downloading packages")} " +
        "install=${firstAt("Installing packages")} end=${lines.lastOrNull()?.first}",
    )
    lines.zipWithNext()
      .map { (before, after) -> (after.first - before.first) to before.second }
      .sortedByDescending { it.first }
      .take(8)
      .forEach { (gap, line) -> android.util.Log.i(LOG_TAG, "GAP distro=$distro ms=$gap after=${line.take(100)}") }
  }

  private companion object {
    const val LOG_TAG = "PackageProgressTest"
  }
}
