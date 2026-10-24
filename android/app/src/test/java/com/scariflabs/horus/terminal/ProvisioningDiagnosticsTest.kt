package com.scariflabs.horus.terminal

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class ProvisioningDiagnosticsTest {
  private fun launch(script: String) = ProotSessionLauncher.LaunchSpec(listOf("/bin/sh", "-c", script), emptyList(), null)
  private fun run(script: String) = ProvisioningProcess.run(launch(script), 3000)

  @Test fun failureBeforeAttachmentRetainsStageStatusAndSafeOutput() {
    val result = run("echo HORUS_INSTALL_STAGE=apk_failed; echo HORUS_INSTALL_COMMAND_EXIT=23; echo 'ENOSPC /private --password SYNTHETIC_SECRET' >&2; exit 23")
    assertEquals("failed", result.outcome)
    assertEquals(23, result.exitCode)
    assertEquals(23, result.commandExitCode)
    assertEquals("apk_failed", result.stage)
    assertEquals("No space left on device.", result.log)
    assertEquals("toolchain_install_failed", result.errorCode())
    assertEquals(result.log, result.fields()["log"])
  }
  @Test fun nonzeroExitDoesNotBecomeSuccess() {
    val result = run("exit 37")
    assertEquals(37, result.exitCode)
    assertFalse(result.succeeded)
  }
  @Test fun usefulStderrSurvives() {
    val result = run("echo 'curl: (6) Could not resolve host: private.invalid' >&2; exit 6")
    assertEquals(6, result.exitCode)
    assertEquals("Could not resolve a download host.", result.log)
    assertEquals("toolchain_network", result.errorCode())
  }
  @Test fun emptyFailureHasExplicitFallback() {
    val result = run("exit 19")
    assertEquals(ProvisioningText.EMPTY, result.log)
    assertEquals(19, result.exitCode)
  }
  @Test(timeout = 5000) fun timeoutIsDistinctAndReleasesProcessOwnership() {
    var owned: Process? = null
    val result = ProvisioningProcess.run(launch("exec sleep 30"), 50,
      started = { owned = it }, finished = { assertFalse(it.isAlive); owned = null })
    assertEquals("timed_out", result.outcome)
    assertNull(owned)
  }
  @Test(timeout = 5000) fun cancellationIsDistinctAndPreservesInterrupt() {
    val started = CountDownLatch(1)
    var result: ProvisioningResult? = null
    var interrupted = false
    val worker = Thread {
      result = ProvisioningProcess.run(launch("exec sleep 30"), 30000, started = { started.countDown() })
      interrupted = Thread.currentThread().isInterrupted
    }
    worker.start()
    assertTrue(started.await(2, TimeUnit.SECONDS))
    worker.interrupt(); worker.join(3000)
    assertFalse(worker.isAlive)
    assertEquals("cancelled", result?.outcome)
    assertTrue(interrupted)
  }
  @Test fun successRemainsSuccess() {
    val result = run("echo HORUS_INSTALL_STAGE=ready; exit 0")
    assertTrue(result.succeeded)
    assertEquals(0, result.exitCode)
  }
  @Test fun spawnFailureHasNoPrivateExceptionText() {
    val result = ProvisioningProcess.run(ProotSessionLauncher.LaunchSpec(listOf("/nonexistent/private/secret"), emptyList(), null), 500)
    assertEquals("spawn_failed", result.outcome)
    assertNull(result.exitCode)
    assertEquals(ProvisioningText.EMPTY, result.log)
  }
  @Test fun falseSuccessAndPrivateFallbackAreHandled() {
    assertEquals("incomplete", run("echo HORUS_INSTALL_STAGE=apk_failed; exit 0").outcome)
    val result = ProvisioningResult("failed", 1).withPrivateFallback("codex_failed", "npm error EBADENGINE --token SECRET\nHORUS_INSTALL_COMMAND_EXIT=7")
    assertEquals("codex_failed", result.stage)
    assertEquals(7, result.commandExitCode)
    assertEquals("toolchain_node_version", result.errorCode())
    assertEquals(1, result.exitCode)
    assertEquals("EBADENGINE: incompatible Node.js version.", result.log)
    assertEquals("toolchain_normalization_failed", ProvisioningResult("failed", stage = "normalize_failed").errorCode())
    assertEquals("toolchain_incomplete", ProvisioningResult("incomplete", 0).errorCode())
    assertEquals("toolchain_install_failed", ProvisioningResult("failed", stage = "codex_failed", log = "No space left on device.").errorCode())
    assertEquals("toolchain_install_failed", ProvisioningResult("failed", stage = "codex_failed", log = "A required platform package is unavailable.").errorCode())
  }
  @Test fun invalidUtf8EscapesAndMultilineSecretsAreNeverExported() {
    val result = run("printf '\\377\\376\\300\\n'; printf '\\033[31mENOSPC password=SYNTHETIC_ESCAPE_SECRET\\033[0m\\n'; printf 'password:\\nSYNTHETIC_MULTILINE_SECRET\\n'; exit 17")
    assertEquals(17, result.exitCode)
    assertEquals("No space left on device.", result.log)
    assertEquals("No space left on device.", result.fields()["log"])
  }
  @Test fun sharedSanitizationCasesMatchNativeBoundary() {
    val cases = JSONArray(javaClass.getResource("/provisioning-diagnostics.json")!!.readText())
    for (index in 0 until cases.length()) {
      val case = cases.getJSONObject(index)
      val output = ProvisioningOutput().apply { append(case.getString("raw")); finish() }
      assertEquals(case.getString("raw"), case.getString("safe"), output.result("failed", 1).log)
    }
  }
  @Test(timeout = 5000) fun outputFloodIsDrainedWithBoundedRetention() {
    val result = run("i=0; while [ \"\$i\" -lt 5000 ]; do echo 'ENOSPC TOKEN=SECRET'; i=\$((i+1)); done; exit 9")
    assertEquals(9, result.exitCode)
    assertEquals("No space left on device.", result.log)
    val output = ProvisioningOutput()
    repeat(100) { output.append("SECRET".repeat(1000)) }
    output.append("\nHORUS_INSTALL_STAGE=private\nHORUS_INSTALL_COMMAND_EXIT=999\n")
    output.finish()
    val bounded = output.result("failed", 1)
    assertNull(bounded.stage); assertNull(bounded.commandExitCode)
    assertEquals(ProvisioningText.OMITTED, bounded.log)
    assertTrue(bounded.log.length <= ProvisioningText.MAX_LOG_CHARS)
  }
  @Test(timeout = 5000) fun inheritedOutputPipeCannotBlockCompletion() {
    val start = System.nanoTime()
    val result = run("sleep 2 & echo ENOSPC >&2; exit 8")
    assertEquals(8, result.exitCode)
    assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1800)
  }
}
