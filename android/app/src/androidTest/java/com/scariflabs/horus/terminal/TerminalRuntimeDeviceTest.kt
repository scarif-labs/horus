package com.scariflabs.horus.terminal

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 0 device proof for the no-op terminal runtime status: the snapshot
 * assembly used by the registered module is verified against live device and
 * application values. The module itself is a thin bridge mapping (unit-tested
 * on the contract) because a ReactApplicationContext cannot be constructed in
 * a connected test.
 */
@RunWith(AndroidJUnit4::class)
class TerminalRuntimeDeviceTest {
  @Test(timeout = 300_000)
  fun phaseOneFreshInstallAndRepeatProbeUseExactGuestMarkers() {
    val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    assertEquals("arm64-v8a", android.os.Build.SUPPORTED_ABIS.firstOrNull())

    val manifestText = targetContext.assets.open("alpine-runtime/manifest.json").bufferedReader().use { it.readText() }
    val packagedManifest = JSONObject(manifestText)
    assertEquals("available", packagedManifest.optString("status"))
    assertEquals("arm64-v8a", packagedManifest.optJSONObject("target")?.optString("abi"))

    val nativeLibraryDir = File(targetContext.applicationInfo.nativeLibraryDir ?: "")
    val located = ProotRuntimeLocator(manifestText).locate(nativeLibraryDir)
    assertTrue("packaged ARM64 PRoot runtime is unavailable", located is ProotRuntimeLocator.Location.Available)
    val runtime = (located as ProotRuntimeLocator.Location.Available).runtime

    // Keep the connected proof isolated from the app's real runtime state. The
    // unique directory is app-private and is deleted in finally, so a rerun
    // cannot consume or destroy a user's rootfs, home, or workspace data.
    val testRoot = File(targetContext.filesDir, "alpine-p1-device-test-${System.nanoTime()}")
    val paths = DistroStorePaths(testRoot)
    val store = DistroStoreCore(
      paths = paths,
      downloader = HttpArchiveDownloader(),
      extractor = SafeTarGzExtractor(),
      prober = ProotGuestProber(runtime, File(paths.sessions, ".proot-scratch")),
    )
    val expectedMarkers = listOf(
      AlpineRootfsCatalog.ProbeMarkers.BEGIN,
      AlpineRootfsCatalog.ProbeMarkers.EXPECTED_ARCH,
      AlpineRootfsCatalog.ALPINE_RELEASE,
      AlpineRootfsCatalog.ProbeMarkers.EXPECTED_HOME,
      AlpineRootfsCatalog.ProbeMarkers.EXPECTED_APK,
      AlpineRootfsCatalog.ProbeMarkers.EXPECTED_SH,
      AlpineRootfsCatalog.ProbeMarkers.END,
    )

    try {
      store.reset()
      val homeSentinel = File(paths.home, "phase1-home-sentinel.txt").apply {
        parentFile!!.mkdirs()
        writeText("preserve-home")
      }
      val workspaceSentinel = File(paths.defaultWorkspace, "phase1-workspace-sentinel.txt").apply {
        parentFile!!.mkdirs()
        writeText("preserve-workspace")
      }

      val first = store.install(downloadTimeoutMs = 120_000)
      assertTrue("fresh Phase 1 install failed: $first", first is DistroStoreCore.InstallOutcome.Success)
      val firstSuccess = first as DistroStoreCore.InstallOutcome.Success
      assertEquals(AlpineRootfsCatalog.ROOTFS_ID, firstSuccess.rootfsId)
      assertEquals(AlpineRootfsCatalog.ROOTFS_SHA256, firstSuccess.archiveSha256)
      assertEquals(AlpineRootfsCatalog.ROOTFS_SIZE_BYTES, firstSuccess.archiveBytes)
      assertEquals(0, firstSuccess.probe.exitCode)
      assertFalse(firstSuccess.probe.timedOut)
      assertTrue(firstSuccess.probe.hasAllExpectedMarkers())
      assertExactGuestMarkers(firstSuccess.probe.output, expectedMarkers)
      assertFalse(firstSuccess.probe.leaksHostPath(paths.base.absolutePath))
      assertFalse(firstSuccess.probe.output.contains(paths.base.absolutePath))
      assertTrue(firstSuccess.extraction.entryCount > 0)
      assertTrue(paths.versionRootfs(AlpineRootfsCatalog.ROOTFS_ID).isDirectory)

      val active = store.readActiveRecord()
      assertNotNull(active)
      assertEquals(AlpineRootfsCatalog.ROOTFS_ID, active!!.rootfsId)
      assertEquals(AlpineRootfsCatalog.ROOTFS_SHA256, active.rootfsSha256)
      assertEquals(expectedMarkers, active.probeMarkers)
      val manifest = JSONObject(paths.versionManifest(AlpineRootfsCatalog.ROOTFS_ID).readText())
      assertEquals(AlpineRootfsCatalog.ROOTFS_ID, manifest.optString("rootfsId"))
      assertEquals(AlpineRootfsCatalog.ROOTFS_SHA256, manifest.optString("rootfsSha256"))
      assertEquals(AlpineRootfsCatalog.ROOTFS_URL, manifest.optString("sourceUrl"))
      assertEquals(AlpineRootfsCatalog.ROOTFS_SIZE_BYTES, manifest.optLong("archiveBytes"))
      assertEquals(expectedMarkers, (0 until manifest.getJSONArray("probeMarkers").length()).map { manifest.getJSONArray("probeMarkers").getString(it) })
      assertTrue(paths.archiveInCache(AlpineRootfsCatalog.ROOTFS_ID).isFile)

      val repeated = store.install(downloadTimeoutMs = 120_000)
      assertTrue("repeated Phase 1 install failed: $repeated", repeated is DistroStoreCore.InstallOutcome.Success)
      val repeatedSuccess = repeated as DistroStoreCore.InstallOutcome.Success
      assertTrue(repeatedSuccess.reusedCachedArchive)
      assertEquals(AlpineRootfsCatalog.ROOTFS_SHA256, repeatedSuccess.archiveSha256)
      assertEquals(0, repeatedSuccess.probe.exitCode)
      assertExactGuestMarkers(repeatedSuccess.probe.output, expectedMarkers)

      val scopedReset = store.reset()
      assertEquals(listOf(AlpineRootfsCatalog.ROOTFS_ID), scopedReset.removedVersionIds)
      assertFalse(scopedReset.homeRemoved)
      assertFalse(scopedReset.workspacesRemoved)
      assertTrue("rootfs reset left an active record", store.readActiveRecord() == null)
      assertTrue("rootfs reset left installed versions", store.installedVersionIds().isEmpty())
      assertFalse(paths.versionRootfs(AlpineRootfsCatalog.ROOTFS_ID).exists())
      assertFalse(paths.archiveInCache(AlpineRootfsCatalog.ROOTFS_ID).exists())
      assertEquals("preserve-home", homeSentinel.readText())
      assertEquals("preserve-workspace", workspaceSentinel.readText())

      android.util.Log.i(LOG_TAG, INSTALL_SUCCESS_MARKER)
      android.util.Log.i(LOG_TAG, PROBE_MARKERS_PREFIX + expectedMarkers.joinToString("|"))
      android.util.Log.i(LOG_TAG, REPEAT_SUCCESS_MARKER)
    } finally {
      assertTrue("test-owned storage cleanup failed", testRoot.deleteRecursively())
    }
  }

  @Test
  fun statusSnapshotMatchesTheDeviceAndApplicationState() {
    val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    val status = TerminalRuntimeContract.buildStatusSnapshot(
      supportedAbis = android.os.Build.SUPPORTED_ABIS,
      apiLevel = android.os.Build.VERSION.SDK_INT,
      appVersion = targetContext.packageManager.getPackageInfo(targetContext.packageName, 0).versionName,
      filesDirPath = targetContext.filesDir.path,
    )

    assertEquals(android.os.Build.SUPPORTED_ABIS.first(), status.abi)
    assertEquals(android.os.Build.VERSION.SDK_INT, status.apiLevel)
    assertEquals(
      targetContext.packageManager.getPackageInfo(targetContext.packageName, 0).versionName,
      status.appVersion,
    )
    assertEquals(
      "${targetContext.filesDir.path}/${TerminalRuntimeContract.STORAGE_ROOT_DIR_NAME}",
      status.storageRoot,
    )
    assertEquals(TerminalRuntimeContract.SCHEMA_VERSION, status.schemaVersion)
    assertEquals(TerminalRuntimeContract.RUNTIME_VERSION, status.runtimeVersion)
    assertTrue(TerminalRuntimeContract.isValidRuntimeState(status.runtimeState))
  }

  private fun assertExactGuestMarkers(output: String, expectedMarkers: List<String>) {
    val exactLines = output.lineSequence()
      .map { it.trim() }
      .filter { line -> expectedMarkers.contains(line) }
      .toList()
    assertEquals(expectedMarkers, exactLines)
    assertTrue("guest HOME marker missing", output.lineSequence().map { it.trim() }.any { it == "/root" })
  }

  private companion object {
    const val LOG_TAG = "AlpineP1DeviceTest"
    const val INSTALL_SUCCESS_MARKER = "ALPINE_P1_INSTALL_SUCCESS"
    const val PROBE_MARKERS_PREFIX = "ALPINE_P1_PROBE_MARKERS="
    const val REPEAT_SUCCESS_MARKER = "ALPINE_P1_REPEAT_SUCCESS"
  }
}
