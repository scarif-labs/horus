package com.scariflabs.horus.terminal

import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.nio.charset.StandardCharsets
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalEmojiDeviceTest {
  @Test fun skinToneSequenceOccupiesOneWideCell() {
    val engine = NativeTerminalEngine("s-emoji-device", 3, 12) { _, _ -> }
    try {
      assertTrue(engine.enqueue(1L, "\u001b[?2027h👍🏽x".toByteArray(StandardCharsets.UTF_8)))
      val deadline = System.currentTimeMillis() + 5000
      while (engine.currentFrame().lines[0].text[0] != "👍🏽" && System.currentTimeMillis() < deadline) Thread.sleep(10)
      val frame = engine.currentFrame()
      assertEquals("👍🏽", frame.lines[0].text[0])
      assertEquals(2, frame.lines[0].width[0].toInt())
      assertEquals("x", frame.lines[0].text[2])
      assertTrue("Android cannot render the emoji sequence", Paint().hasGlyph("👍🏽"))
    } finally {
      engine.close()
    }
  }
}
