package com.scariflabs.horus.terminal

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.facebook.react.bridge.BridgeReactContext
import com.facebook.react.bridge.WritableMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Host-shell fixtures only: no rootfs, package manager, credentials, service sessions or user files. */
@RunWith(AndroidJUnit4::class)
class ProvisioningDiagnosticsDeviceTest {
  @Before fun requireIsolatedPackage() {
    assumeTrue("Run only in a separate diagnostics application ID", InstrumentationRegistry.getInstrumentation().targetContext.packageName.startsWith("com.scariflabs.horus.diagnostics"))
  }
  private fun launch(script: String) = ProotSessionLauncher.LaunchSpec(
    listOf("/system/bin/sh", "-c", script), listOf("PATH=/system/bin"), null,
  )
  private fun run(script: String) = ProvisioningProcess.run(launch(script), 3000)

  @Test fun beforeAttachment() {
    val result = run("echo HORUS_INSTALL_STAGE=apk_failed; echo HORUS_INSTALL_COMMAND_EXIT=23; echo 'ENOSPC --password SYNTHETIC_SECRET' >&2; exit 23")
    assertEquals("apk_failed", result.stage)
    assertEquals(23, result.exitCode)
    assertEquals(23, result.commandExitCode)
    assertEquals("No space left on device.", result.log)
  }
  @Test fun nonzero() {
    val result = run("exit 37")
    assertEquals("failed", result.outcome); assertEquals(37, result.exitCode)
  }
  @Test fun stderr() {
    val result = run("echo 'Could not resolve host: private.invalid' >&2; exit 6")
    assertEquals("Could not resolve a download host.", result.log)
    assertEquals(6, result.exitCode)
  }
  @Test fun empty() {
    val result = run("exit 19")
    assertEquals(ProvisioningText.EMPTY, result.log); assertEquals(19, result.exitCode)
  }
  @Test(timeout = 7000) fun timeout() {
    assertEquals("timed_out", ProvisioningProcess.run(launch("exec sleep 30"), 50).outcome)
  }
  @Test(timeout = 7000) fun cancellation() {
    val started = CountDownLatch(1)
    var result: ProvisioningResult? = null
    val worker = Thread { result = ProvisioningProcess.run(launch("exec sleep 30"), 30000, started = { started.countDown() }) }
    worker.start(); assertTrue(started.await(2, TimeUnit.SECONDS))
    worker.interrupt(); worker.join(4000)
    assertFalse(worker.isAlive); assertEquals("cancelled", result?.outcome)
  }
  @Test fun successAndIncomplete() {
    assertTrue(run("echo HORUS_INSTALL_STAGE=ready; exit 0").succeeded)
    assertEquals("incomplete", run("echo HORUS_INSTALL_STAGE=apk_failed; exit 0").outcome)
  }
  @Test(timeout = 7000) fun inheritedPipeHasBoundedReturn() {
    val start = System.nanoTime()
    assertEquals(8, run("sleep 2 & echo ENOSPC >&2; exit 8").exitCode)
    assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1800)
  }
  @Test(timeout = 15000) fun serviceFailureBeforeAnyProcessHasDiagnosticFallback() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val client = TerminalSessionServiceClient(context) { }
    val done = CountDownLatch(1)
    var response: Bundle? = null
    try {
      client.provisionToolchain("diagnostic-missing-rootfs", "shell") { response = it; done.countDown() }
      assertTrue(done.await(10, TimeUnit.SECONDS))
      assertEquals("error", response?.getString("status"))
      assertNotNull(response?.getString("errorCode"))
      assertEquals("not_started", response?.getString("outcome"))
      assertEquals(ProvisioningText.EMPTY, response?.getString("log"))
      assertFalse(response!!.containsKey("exitCode"))
    } finally { client.close() }
  }
  @Test fun bundleToReactNativeMapRetainsOnlySafeDiagnosticFields() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val module = TerminalRuntimeModule(BridgeReactContext(context))
    try {
      val result = run("echo HORUS_INSTALL_STAGE=codex_failed; echo 'EBADENGINE --token SYNTHETIC_SECRET' >&2; exit 7")
      val bundle = Bundle().apply {
        putString("status", "error"); putString("errorCode", result.errorCode())
        result.fields().forEach { (key, value) -> when (value) {
          is String -> putString(key, value)
          is Int -> putInt(key, value)
        } }
      }
      val method = TerminalRuntimeModule::class.java.getDeclaredMethod("provisionResponse", Bundle::class.java, String::class.java).apply { isAccessible = true }
      val wire = method.invoke(module, bundle, "fixture-request") as WritableMap
      assertEquals("error", wire.getString("status"))
      assertEquals("toolchain_node_version", wire.getString("errorCode"))
      assertEquals("failed", wire.getString("outcome"))
      assertEquals("codex_failed", wire.getString("stage"))
      assertEquals(7, wire.getInt("exitCode"))
      assertEquals("EBADENGINE: incompatible Node.js version.", wire.getString("log"))
      assertFalse(wire.toHashMap().toString().contains("SYNTHETIC_SECRET"))
    } finally { module.invalidate() }
  }
}
