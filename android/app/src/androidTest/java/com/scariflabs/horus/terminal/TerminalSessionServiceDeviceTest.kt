package com.scariflabs.horus.terminal

import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.TimeUnit
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies the Android process/lifecycle boundary without starting a guest session. */
@RunWith(AndroidJUnit4::class)
class TerminalSessionServiceDeviceTest {

  @Test(timeout = 45_000)
  fun startsAsForegroundServiceInDedicatedProcess() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    val intent = Intent(context, TerminalSessionService::class.java).apply {
      action = TerminalSessionServiceProtocol.ACTION
    }

    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
      } else {
        @Suppress("DEPRECATION")
        context.startService(intent)
      }

      await("foreground service record") {
        val dump = shell(instrumentation, "dumpsys activity services ${context.packageName}")
        dump.contains("TerminalSessionService") &&
          (dump.contains("isForeground=true") || dump.contains("foregroundId=1379"))
      }
      await("dedicated service process") {
        shell(instrumentation, "ps -A -o PID,NAME,ARGS")
          .lineSequence()
          .any { line -> line.trim().split(Regex("\\s+"), limit = 3).getOrNull(1) == "${context.packageName}:terminal" }
      }
    } finally {
      context.stopService(intent)
      // The instrumentation APK has a separate UID, so use the test shell to
      // stop this test-owned, sessionless service if Context.stopService was
      // rejected by the framework's caller check.
      shell(instrumentation, "am stopservice -n ${context.packageName}/.terminal.TerminalSessionService")
      await("service record stop", timeoutMs = 10_000) {
        !shell(instrumentation, "dumpsys activity services ${context.packageName}")
          .contains("TerminalSessionService")
      }
    }
  }

  private fun await(label: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    while (System.nanoTime() < deadline) {
      if (runCatching { condition() }.getOrDefault(false)) return
      Thread.sleep(100)
    }
    throw AssertionError("timed out waiting for $label")
  }

  private fun shell(instrumentation: android.app.Instrumentation, command: String): String {
    val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
    return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
      stream.readBytes().toString(Charsets.UTF_8)
    }
  }
}
