package com.scariflabs.horus.terminal

import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

/**
 * Dual-engine parity and intentional divergence test suite.
 *
 * Exercises both the [TerminalEngineBackend.LIBGHOSTTY] and
 * [TerminalEngineBackend.LEGACY] backends on identical input streams,
 * asserting common behavior for core VT emulation contracts and
 * recording intentional divergences (such as ECMA-48 REP, Mode 2027,
 * and resize line reflow).
 */
class TerminalEngineParityTest {

  private class TestEngineWrapper(
    name: String,
    val backend: TerminalEngineBackend,
    rows: Int = 4,
    cols: Int = 12,
  ) : AutoCloseable {
    private val publishes = AtomicInteger(0)
    private val publishesAtSentinel = AtomicInteger(-1)
    private var seq = 0L

    val engine = NativeTerminalEngine(
      sessionId = "$name-${backend.name.lowercase()}",
      rows = rows,
      columns = cols,
      clipboardWriter = {},
      onOutputGap = {},
      backend = backend,
    ) { _, bytes ->
      if (String(bytes, StandardCharsets.UTF_8) == "\u001b[0n") {
        publishesAtSentinel.set(publishes.get())
      }
    }

    init {
      engine.addListener { publishes.incrementAndGet() }
    }

    fun feed(input: String): NativeTerminalEngine.Frame {
      publishesAtSentinel.set(-1)
      assertTrue(engine.enqueue(++seq, input.toByteArray(StandardCharsets.UTF_8)))
      assertTrue(engine.enqueue(++seq, "\u001b[5n".toByteArray(StandardCharsets.UTF_8)))
      val deadline = System.nanoTime() + 2_000_000_000L
      while (System.nanoTime() < deadline) {
        val mark = publishesAtSentinel.get()
        if (mark >= 0 && publishes.get() > mark) return engine.currentFrame()
        Thread.sleep(2L)
      }
      throw AssertionError("engine [${backend.name}] frame was not published before deadline")
    }

    fun resize(rows: Int, cols: Int) {
      engine.resize(rows, cols)
    }

    override fun close() {
      engine.close()
    }
  }

  private fun rowTexts(frame: NativeTerminalEngine.Frame): List<String> =
    frame.lines.map { it.text.joinToString("").trimEnd() }

  @Test
  fun bothEnginesSupportBracketedPaste() {
    for (backend in TerminalEngineBackend.values()) {
      TestEngineWrapper("paste", backend).use { wrapper ->
        assertFalse("${backend.name} should start without bracketed paste", wrapper.engine.bracketedPaste)

        wrapper.feed("\u001b[?2004h")
        assertTrue("${backend.name} should enable bracketed paste", wrapper.engine.bracketedPaste)

        wrapper.feed("\u001b[?2004l")
        assertFalse("${backend.name} should disable bracketed paste", wrapper.engine.bracketedPaste)
      }
    }
  }

  @Test
  fun decawmAutowrapParityAndDifference() {
    // When DECAWM is disabled (CSI ? 7 l):
    // LIBGHOSTTY implements DEC Mode 7 (DECAWM): autowrap is disabled, characters past margin overwrite the right edge ("1235")
    TestEngineWrapper("decawm", TerminalEngineBackend.LIBGHOSTTY, rows = 3, cols = 4).use { wrapper ->
      wrapper.feed("\u001b[?7l12345")
      val frame = wrapper.engine.currentFrame()
      assertEquals("LIBGHOSTTY: without DECAWM, last character overwrites right margin", "1235", rowTexts(frame)[0])
      assertEquals(3, frame.cursorColumn)

      wrapper.feed("\u001b[2J\u001b[H\u001b[?7h12345")
      val wrapped = wrapper.engine.currentFrame()
      assertEquals("LIBGHOSTTY: with DECAWM, wraps to next line", "1234", rowTexts(wrapped)[0])
      assertEquals("5", rowTexts(wrapped)[1])
      assertEquals(1, wrapped.cursorColumn)
    }

    // LEGACY: DEC Mode 7 is not implemented, so autowrap is always enabled (wraps to next line)
    TestEngineWrapper("decawm", TerminalEngineBackend.LEGACY, rows = 3, cols = 4).use { wrapper ->
      wrapper.feed("\u001b[?7l12345")
      val frame = wrapper.engine.currentFrame()
      assertEquals("LEGACY: ignores Mode 7, always wraps row 0", "1234", rowTexts(frame)[0])
      assertEquals("LEGACY: wraps '5' onto row 1", "5", rowTexts(frame)[1])
      assertEquals(1, frame.cursorColumn)

      wrapper.feed("\u001b[2J\u001b[H\u001b[?7h12345")
      val wrapped = wrapper.engine.currentFrame()
      assertEquals("LEGACY: with DECAWM, wraps to next line", "1234", rowTexts(wrapped)[0])
      assertEquals("5", rowTexts(wrapped)[1])
      assertEquals(1, wrapped.cursorColumn)
    }
  }

  @Test
  fun colorsAndInverseVideoParity() {
    for (backend in TerminalEngineBackend.values()) {
      TestEngineWrapper("colors", backend, rows = 2, cols = 8).use { wrapper ->
        wrapper.feed("\u001b[31;42;7mX\u001b[0m")
        val frame = wrapper.engine.currentFrame()
        val row = frame.lines[0]
        assertEquals("${backend.name}: text", "X", row.text[0])
        // With inverse video resolved at publish, foreground and background are swapped
        assertEquals("${backend.name}: inverse fg matches green", 0xFF00CD00.toInt(), row.foreground[0])
        assertEquals("${backend.name}: inverse bg matches red", 0xFFCD0000.toInt(), row.background[0])
      }
    }
  }

  @Test
  fun repCharacterRepeatIntentionalDifference() {
    // ECMA-48 REP (CSI Ps b) repeats the preceding printed graphic character.
    // LIBGHOSTTY implements REP; LEGACY ignores it.
    TestEngineWrapper("rep", TerminalEngineBackend.LIBGHOSTTY, rows = 2, cols = 8).use { wrapper ->
      val frame = wrapper.feed("A\u001b[4b")
      assertEquals("LIBGHOSTTY executes REP: 1 initial + 4 repeated = 5 'A's", "AAAAA", rowTexts(frame)[0])
      assertEquals(5, frame.cursorColumn)
    }

    TestEngineWrapper("rep", TerminalEngineBackend.LEGACY, rows = 2, cols = 8).use { wrapper ->
      val frame = wrapper.feed("A\u001b[4b")
      assertEquals("LEGACY ignores unsupported REP sequence", "A", rowTexts(frame)[0])
      assertEquals(1, frame.cursorColumn)
    }
  }

  @Test
  fun mode2027GraphemeClusteringParityAndDifference() {
    // Under standard default (Mode 2027 off):
    // LIBGHOSTTY: does not combine skin tone modifier (2 separate cells, matching xterm)
    // LEGACY: always combines skin tone modifier (hardcoded emoji table)
    TestEngineWrapper("mode2027", TerminalEngineBackend.LIBGHOSTTY, rows = 2, cols = 8).use { wrapper ->
      val frame = wrapper.feed("👍🏽x")
      assertEquals("LIBGHOSTTY with Mode 2027 default off keeps base emoji separate", "👍", frame.lines[0].text[0])
      assertEquals(2, frame.lines[0].width[0].toInt())
      assertEquals("modifier occupies its own cell", "🏽", frame.lines[0].text[2])
    }

    TestEngineWrapper("mode2027", TerminalEngineBackend.LEGACY, rows = 2, cols = 8).use { wrapper ->
      val frame = wrapper.feed("👍🏽x")
      assertEquals("LEGACY always combines emoji modifier base into 1 cell", "👍🏽", frame.lines[0].text[0])
      assertEquals(2, frame.lines[0].width[0].toInt())
      assertEquals("x", frame.lines[0].text[2])
    }

    // When Mode 2027 is explicitly enabled via CSI ? 2027 h:
    // LIBGHOSTTY clusters graphemes into a single 2-wide cell
    TestEngineWrapper("mode2027-on", TerminalEngineBackend.LIBGHOSTTY, rows = 2, cols = 8).use { wrapper ->
      val frame = wrapper.feed("\u001b[?2027h👍🏽x")
      assertEquals("LIBGHOSTTY with Mode 2027 ON clusters grapheme into single cell", "👍🏽", frame.lines[0].text[0])
      assertEquals(2, frame.lines[0].width[0].toInt())
      assertEquals("x", frame.lines[0].text[2])
      assertEquals(3, frame.cursorColumn)
    }
  }

  @Test
  fun resizeReflowIntentionalDifference() {
    // When resizing a terminal narrower:
    // LIBGHOSTTY reflows wrapped lines into additional rows (e moves to row 2)
    // LEGACY drops or truncates unreflowed columns
    TestEngineWrapper("reflow", TerminalEngineBackend.LIBGHOSTTY, rows = 4, cols = 4).use { wrapper ->
      wrapper.feed("ab\r\ncde")
      wrapper.resize(4, 2)
      val deadline = System.nanoTime() + 1_000_000_000L
      var frame = wrapper.engine.currentFrame()
      while (frame.columns != 2 && System.nanoTime() < deadline) {
        Thread.sleep(5L)
        frame = wrapper.engine.currentFrame()
      }
      assertEquals("LIBGHOSTTY reflows wrapped line 'cde' across 2-column rows", listOf("ab", "cd", "e", ""), rowTexts(frame))
    }

    TestEngineWrapper("reflow", TerminalEngineBackend.LEGACY, rows = 4, cols = 4).use { wrapper ->
      wrapper.feed("ab\r\ncde")
      wrapper.resize(4, 2)
      val deadline = System.nanoTime() + 1_000_000_000L
      var frame = wrapper.engine.currentFrame()
      while (frame.columns != 2 && System.nanoTime() < deadline) {
        Thread.sleep(5L)
        frame = wrapper.engine.currentFrame()
      }
      assertEquals("LEGACY truncates without reflow across rows", listOf("ab", "cd", "", ""), rowTexts(frame))
    }
  }
}
