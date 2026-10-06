package com.scariflabs.horus.terminal

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real service state and PTYs; rootfs must already be installed on the test device. */
@RunWith(AndroidJUnit4::class)
class TerminalSessionServiceDeviceTest {
  private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
  private val context get() = instrumentation.targetContext

  @Test(timeout = 45_000)
  fun idleBindingDoesNotStartOrPromoteService() {
    val connected = CountDownLatch(1)
    val connection = object : ServiceConnection {
      override fun onServiceConnected(name: ComponentName?, binder: IBinder?) { connected.countDown() }
      override fun onServiceDisconnected(name: ComponentName?) = Unit
    }
    assertTrue(context.bindService(Intent(context, TerminalSessionService::class.java), connection, Context.BIND_AUTO_CREATE))
    try {
      assertTrue(connected.await(10, TimeUnit.SECONDS))
      val dump = serviceDump()
      assertTrue(dump.contains("TerminalSessionService"))
      assertFalse(dump.contains("isForeground=true"))
      assertFalse(dump.contains("startRequested=true"))
    } finally {
      context.unbindService(connection)
    }
    await("idle bound service destruction") { !serviceDump().contains("TerminalSessionService") }
  }

  @Test(timeout = 120_000)
  fun sessionsRetainServiceAndFinalExitClearsStartedLifetimeAcrossRepeatedCycles() {
    val client = TerminalSessionServiceClient(context) { }
    val owned = mutableSetOf<String>()
    try {
      repeat(3) { cycle ->
        val first = request { id, callback ->
          client.startSession(id, 24, 80, "exec /bin/sh", "shell", true, callback)
        }.getString(TerminalSessionServiceProtocol.KEY_SESSION_ID)!!
        val second = request { id, callback ->
          client.startSession(id, 24, 80, "echo second-$cycle; exec /bin/sh", "shell", true, callback)
        }.getString(TerminalSessionServiceProtocol.KEY_SESSION_ID)!!
        owned += first
        owned += second
        assertTrue(first != second)
        await("active foreground service") {
          serviceDump().let { it.contains("isForeground=true") && it.contains("startRequested=true") && it.contains("startCommandResult=1") }
        }
        request { id, callback -> client.stopSession(id, first, "user_requested", callback) }
        owned -= first
        assertTrue(serviceDump().contains("isForeground=true"))
        val listed = request { id, callback -> client.listSessions(id, callback) }
        val remaining = listed.getParcelableArrayList<Bundle>(TerminalSessionServiceProtocol.KEY_SESSIONS).orEmpty()
        assertEquals(listOf(second), remaining.map { it.getString(TerminalSessionServiceProtocol.KEY_SESSION_ID) })
        request { id, callback -> client.stopSession(id, second, "user_requested", callback) }
        owned -= second
        await("final exit clears started lifetime while client remains bound") {
          serviceDump().let { !it.contains("isForeground=true") && it.contains("startRequested=false") }
        }
      }
    } finally {
      owned.forEach { session -> runCatching { request { id, callback -> client.stopSession(id, session, "user_requested", callback) } } }
      client.close()
    }
    await("final unbind destroys idle service") { !serviceDump().contains("TerminalSessionService") }
  }

  @Test(timeout = 360_000)
  fun provisioningRetainsForegroundLifetimeUntilCompletion() {
    val client = TerminalSessionServiceClient(context) { }
    val done = CountDownLatch(1)
    var response: Bundle? = null
    // Force the real preparation path on repeat runs, preserving the marker
    // from this test install. No credentials or guest files are removed.
    val ready = File(context.filesDir, "${TerminalRuntimeContract.STORAGE_ROOT_DIR_NAME}/home/.cache/horus/github.ready")
    val previousReady = ready.takeIf(File::isFile)?.readBytes()
    if (ready.exists()) assertTrue(ready.delete())
    try {
      assertTrue(TerminalSessionSpecFactory(context).buildProvision("github") is TerminalSessionSpecFactory.ProvisionBuildOutcome.Work)
      client.provisionToolchain("lifecycle-provision", "github") { response = it; done.countDown() }
      await("provisioning foreground service") {
        serviceDump().let { it.contains("isForeground=true") && it.contains("startRequested=true") }
      }
      assertFalse("provisioning completed before retention was observed", done.await(100, TimeUnit.MILLISECONDS))
      assertTrue("provisioning did not complete", done.await(310, TimeUnit.SECONDS))
      assertEquals(requireNotNull(response).toString(), TerminalSessionServiceProtocol.STATUS_SUCCESS,
        response?.getString(TerminalSessionServiceProtocol.KEY_STATUS))
      await("provisioning completion clears started lifetime") { serviceDump().contains("startRequested=false") }
    } finally {
      client.close()
      if (previousReady != null) ready.writeBytes(previousReady)
    }
    await("provisioning idle destruction") { !serviceDump().contains("TerminalSessionService") }
  }

  @Test(timeout = 180_000)
  fun remoteAccessRetainsServiceUntilDisabledWithoutTerminalSessions() {
    val preferences = context.getSharedPreferences("horus_profile", Context.MODE_PRIVATE)
    val createdProfile = !PasswordGate.isConfigured(context)
    if (createdProfile) {
      assertTrue(PasswordGate.create(context, "Horus-device-test-42"))
      assertTrue(preferences.edit().commit()) // Flush before the other process reads it.
    }
    val controller = RemoteAccessController(context)
    try {
      assertTrue(controller.enable())
      await("remote access foreground service") {
        serviceDump().let { it.contains("isForeground=true") && it.contains("startRequested=true") }
      }
      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
      while (controller.snapshot().state != RemoteAccess.STATE_RUNNING && System.nanoTime() < deadline) Thread.sleep(200)
      assertEquals(controller.snapshot().toString(), RemoteAccess.STATE_RUNNING, controller.snapshot().state)
      // The server is loopback-only and has no test pairing keys.
      assertTrue(serviceDump().contains("isForeground=true"))
    } finally {
      controller.disable()
      try {
        await("remote disable destroys idle service") { !serviceDump().contains("TerminalSessionService") }
      } finally {
        if (createdProfile) preferences.edit().clear().commit()
      }
    }
  }

  private var sequence = 0
  private fun request(send: (String, (Bundle) -> Unit) -> Unit): Bundle {
    val latch = CountDownLatch(1)
    var response: Bundle? = null
    send("lifecycle-device-${++sequence}") { response = it; latch.countDown() }
    assertTrue("request timed out", latch.await(15, TimeUnit.SECONDS))
    val result = requireNotNull(response)
    assertEquals(result.toString(), TerminalSessionServiceProtocol.STATUS_SUCCESS, result.getString(TerminalSessionServiceProtocol.KEY_STATUS))
    return result
  }

  private fun serviceDump() = shell("dumpsys activity services ${context.packageName}")
  private fun await(label: String, condition: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    while (System.nanoTime() < deadline) {
      if (condition()) return
      Thread.sleep(100)
    }
    throw AssertionError("timed out waiting for $label")
  }

  private fun shell(command: String): String {
    val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
    return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes().toString(Charsets.UTF_8) }
  }
}
