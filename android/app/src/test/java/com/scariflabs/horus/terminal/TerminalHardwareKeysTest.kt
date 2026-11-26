package com.scariflabs.horus.terminal

import android.view.KeyEvent
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TerminalHardwareKeysTest {
  @Test
  fun mapsKeysAndModifiers() {
    assertEquals(20, TerminalHardwareKeys.ghosttyKey(KeyEvent.KEYCODE_A))
    assertEquals(45, TerminalHardwareKeys.ghosttyKey(KeyEvent.KEYCODE_Z))
    assertEquals(6, TerminalHardwareKeys.ghosttyKey(KeyEvent.KEYCODE_0))
    assertEquals(78, TerminalHardwareKeys.ghosttyKey(KeyEvent.KEYCODE_DPAD_UP))
    assertEquals(120, TerminalHardwareKeys.ghosttyKey(KeyEvent.KEYCODE_ESCAPE))
    assertEquals(132, TerminalHardwareKeys.ghosttyKey(KeyEvent.KEYCODE_F12))
    assertEquals(0, TerminalHardwareKeys.ghosttyKey(KeyEvent.KEYCODE_VOLUME_UP))
    assertEquals(0, TerminalHardwareKeys.ghosttyKey(KeyEvent.KEYCODE_BACK))
    assertEquals(
      TerminalHardwareKeys.MOD_SHIFT or TerminalHardwareKeys.MOD_CTRL,
      TerminalHardwareKeys.ghosttyMods(KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON or KeyEvent.META_CTRL_ON),
    )
  }

  @Test
  fun typedTextAndConsumedShift() {
    val upper = TerminalHardwareKeys.key(KeyEvent.KEYCODE_A, KeyEvent.META_SHIFT_ON, 'A'.code, 'a'.code)!!
    assertEquals("A", upper.text)
    assertEquals(TerminalHardwareKeys.MOD_SHIFT, upper.consumedMods)
    assertEquals('a'.code, upper.unshifted)
    // Enter types a control character, so the encoder works from the key.
    val enter = TerminalHardwareKeys.key(KeyEvent.KEYCODE_ENTER, KeyEvent.META_SHIFT_ON, '\n'.code, '\n'.code)!!
    assertNull(enter.text)
    assertEquals(0, enter.consumedMods)
    assertNull(TerminalHardwareKeys.key(KeyEvent.KEYCODE_VOLUME_DOWN, 0, 0, 0))
  }

  @Test
  fun encodesForTheAppsKeyboardModes() {
    val engine = NativeTerminalEngine("s-hardware-keys", 4, 20) { _, _ -> }
    try {
      fun encode(keyCode: Int, meta: Int, typed: Int, unshifted: Int): String {
        val key = TerminalHardwareKeys.key(keyCode, meta, typed, unshifted)!!
        val bytes = engine.withNativeHandle { h ->
          TerminalPtyJni.nativeEngineEncodeKeyEvent(h, key.key, key.mods, key.consumedMods, key.unshifted, TerminalHardwareKeys.ACTION_PRESS, key.text)
        }
        return String(bytes ?: ByteArray(0), StandardCharsets.UTF_8)
      }
      fun feed(seq: Long, text: String) {
        engine.enqueue(seq, text.toByteArray(StandardCharsets.UTF_8))
        val deadline = System.nanoTime() + 2_000_000_000L
        // Feeding is asynchronous; a DSR reply would need a writer, so poll the frame.
        while (System.nanoTime() < deadline) {
          Thread.sleep(20)
          if (engine.currentFrame().lines.isNotEmpty()) break
        }
        Thread.sleep(50)
      }

      assertEquals("a", encode(KeyEvent.KEYCODE_A, 0, 'a'.code, 'a'.code))
      assertEquals("A", encode(KeyEvent.KEYCODE_A, KeyEvent.META_SHIFT_ON, 'A'.code, 'a'.code))
      assertEquals("\u0003", encode(KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON, 'c'.code, 'c'.code))
      assertEquals("\u001bx", encode(KeyEvent.KEYCODE_X, KeyEvent.META_ALT_ON, 'x'.code, 'x'.code))
      assertEquals("\u001b[A", encode(KeyEvent.KEYCODE_DPAD_UP, 0, 0, 0))
      assertEquals("\u001b", encode(KeyEvent.KEYCODE_ESCAPE, 0, 0x1b, 0x1b))
      assertEquals("\u007f", encode(KeyEvent.KEYCODE_DEL, 0, 0, 0))

      // Application cursor keys (DECCKM).
      feed(1, "\u001b[?1h")
      assertEquals("\u001bOA", encode(KeyEvent.KEYCODE_DPAD_UP, 0, 0, 0))

      // Kitty keyboard protocol, as Claude Code requests: Shift+Enter is its own key.
      feed(2, "\u001b[?1l\u001b[>1u")
      assertEquals("\u001b[13;2u", encode(KeyEvent.KEYCODE_ENTER, KeyEvent.META_SHIFT_ON, '\n'.code, '\n'.code))
    } finally {
      engine.close()
    }
  }
}
