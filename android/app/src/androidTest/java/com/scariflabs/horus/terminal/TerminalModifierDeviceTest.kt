package com.scariflabs.horus.terminal

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.facebook.react.bridge.BridgeReactContext
import com.facebook.react.uimanager.ThemedReactContext
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalModifierDeviceTest {
  @Test fun lockedControlAppliesAcrossMultipleCommitsUntilReleased() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val writes = mutableListOf<String>()
    NativeTerminalEngineRegistry.configureInputWriter { _, bytes -> writes += String(bytes, Charsets.UTF_8) }
    try {
      instrumentation.runOnMainSync {
        val context = instrumentation.targetContext
        val view = TerminalInputView(ThemedReactContext(BridgeReactContext(context), context, "ModifierTest", -1))
        view.setTerminalSessionId("s-modifier-test")
        view.setTerminalEnabled(true)
        view.setCtrlLocked(true)
        view.setCtrlActive(true)
        view.setText("a")
        view.setText("b")
        view.setCtrlLocked(false)
        view.setCtrlActive(false)
        view.setText("c")
        view.setLineResetRequest(1)
        view.setAltLocked(true)
        view.setAltActive(true)
        view.setText("a")
        view.setText("b")
        view.setAltLocked(false)
        view.setAltActive(false)
        view.setText("c")
      }
      assertEquals(listOf("\u0001", "\u0002", "c", "\u001ba", "\u001bb", "c"), writes)
    } finally {
      NativeTerminalEngineRegistry.configureInputWriter(null)
    }
  }
}
