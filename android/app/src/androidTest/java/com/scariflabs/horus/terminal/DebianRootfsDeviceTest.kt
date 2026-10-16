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
 * Device proof for the Debian catalog entry: the pinned xz archive downloads,
 * extracts, and passes its probe under the packaged PRoot, next to an Alpine
 * install in the same store. Runs in a test-owned directory, never the app's
 * real runtime state.
 */
@RunWith(AndroidJUnit4::class)
class DebianRootfsDeviceTest {
  @Test(timeout = 600_000)
  fun debianInstallsBesideAlpineAndPassesItsProbe() {
    val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    val manifestText = targetContext.assets.open("alpine-runtime/manifest.json").bufferedReader().use { it.readText() }
    val located = ProotRuntimeLocator(manifestText).locate(File(targetContext.applicationInfo.nativeLibraryDir ?: ""))
    assertTrue("packaged ARM64 PRoot runtime is unavailable", located is ProotRuntimeLocator.Location.Available)
    val runtime = (located as ProotRuntimeLocator.Location.Available).runtime

    val testRoot = File(targetContext.filesDir, "debian-device-test-${System.nanoTime()}")
    val paths = DistroStorePaths(testRoot)
    val prober = ProotGuestProber(runtime, paths.guestTmp)
    val store = DistroStoreCore(
      paths = paths,
      downloader = HttpArchiveDownloader(),
      extractor = SafeTarExtractor(),
      prober = prober,
    )
    val debian = RootfsCatalog.DEBIAN

    try {
      val alpine = store.install(downloadTimeoutMs = 120_000)
      assertTrue("alpine install failed: $alpine", alpine is DistroStoreCore.InstallOutcome.Success)

      val installed = store.install(entry = debian, downloadTimeoutMs = 300_000)
      assertTrue("debian install failed: $installed", installed is DistroStoreCore.InstallOutcome.Success)
      val success = installed as DistroStoreCore.InstallOutcome.Success
      assertEquals(debian.sha256, success.archiveSha256)
      assertEquals(debian.sizeBytes, success.archiveBytes)
      assertTrue(success.extraction.skippedEntries.isEmpty())
      assertEquals(debian.probeMarkers, success.probe.markerLines())
      assertFalse(success.probe.leaksHostPath(paths.base.absolutePath))

      val active = store.readActiveRecord()
      assertNotNull(active)
      assertEquals(RootfsCatalog.DISTRO_DEBIAN, active!!.distro)
      assertEquals(debian.release, active.release)
      val record = JSONObject(paths.activeRecord.readText())
      assertEquals(RootfsCatalog.RECORD_SCHEMA, record.getInt("schemaVersion"))
      assertEquals(
        setOf(AlpineRootfsCatalog.ROOTFS_ID, debian.rootfsId),
        store.installedVersionIds().toSet(),
      )

      // Facts Phase 4 relies on, observed in the guest: glibc's loader is
      // native (no gcompat), dpkg reports arm64, and Debian's sftp-server path.
      val facts = debian.copy(
        probeChecks = listOf(
          RootfsCatalogEntry.ProbeCheck(listOf("/usr/bin/dpkg", "--print-architecture"), "arm64"),
          RootfsCatalogEntry.ProbeCheck(listOf("/usr/bin/sh", "-c", "test -e /lib/ld-linux-aarch64.so.1 && echo glibc"), "glibc"),
          RootfsCatalogEntry.ProbeCheck(listOf("/usr/bin/sh", "-c", "test -e /etc/apt/sources.list.d/debian.sources && echo deb822"), "deb822"),
        ),
      )
      val observed = prober.probe(paths.versionRootfs(debian.rootfsId), paths.home, 60_000, facts)
      assertTrue("guest facts: ${observed.output}", observed.hasAllExpectedMarkers())

      android.util.Log.i(LOG_TAG, "DEBIAN_INSTALL_SUCCESS ${success.durationMs}ms ${success.extraction}")
      android.util.Log.i(LOG_TAG, "DEBIAN_PROBE_MARKERS=" + success.probe.markerLines().joinToString("|"))
    } finally {
      assertTrue("test-owned storage cleanup failed", testRoot.deleteRecursively())
    }
  }

  private companion object {
    const val LOG_TAG = "DebianDeviceTest"
  }
}
