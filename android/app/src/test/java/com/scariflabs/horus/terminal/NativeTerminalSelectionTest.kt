package com.scariflabs.horus.terminal

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeTerminalSelectionTest {
  @Test
  fun wordStopsAtSpacesQuotesAndBrackets() {
    val lines = arrayOf(row("cat \"/tmp/a b.txt\" (x)"))
    assertEquals(range(0, 5, 0, 11), NativeTerminalSelection.wordAt(lines, 0, 7))
    assertEquals(range(0, 0, 0, 3), NativeTerminalSelection.wordAt(lines, 0, 2))
    assertEquals(range(0, 4, 0, 5), NativeTerminalSelection.wordAt(lines, 0, 4))
    assertEquals(range(0, 3, 0, 4), NativeTerminalSelection.wordAt(lines, 0, 3))
    assertEquals(range(0, 20, 0, 21), NativeTerminalSelection.wordAt(lines, 0, 20))
    assertNull(NativeTerminalSelection.wordAt(lines, 1, 0))
  }

  @Test
  fun wordIncludesWholeWideGlyphsFromEitherHalf() {
    val line = NativeTerminalEngine.FrameRow(
      text = arrayOf("a", "中", "", "b", " "),
      width = byteArrayOf(1, 2, 0, 1, 1),
      foreground = IntArray(5),
      background = IntArray(5),
      flags = IntArray(5),
      wrapped = false,
    )
    val expected = range(0, 0, 0, 4)
    assertEquals(expected, NativeTerminalSelection.wordAt(arrayOf(line), 0, 2))
    assertEquals(expected, NativeTerminalSelection.wordAt(arrayOf(line), 0, 0))
  }

  @Test
  fun textJoinsSoftWrapsAndBreaksHardLines() {
    val lines = arrayOf(row("first line"), row("abcdefghij"), row("klm", wrapped = true), row("next"))
    assertEquals("line\nabcdefghijklm\nne", NativeTerminalSelection.text(lines, range(0, 6, 3, 2)).text)
    assertEquals("cde", NativeTerminalSelection.text(lines, range(1, 2, 1, 5)).text)
    assertEquals("ij", NativeTerminalSelection.text(lines, range(1, 8, 1, 99)).text)
  }

  @Test
  fun selectionFreezesTheDisplayedFrameUntilItEnds() {
    val engine = NativeTerminalEngine("s-selection-freeze", 3, 10) { _, _ -> }
    try {
      assertTrue(engine.enqueue(1L, "one".toByteArray(StandardCharsets.UTF_8)))
      awaitFrame(engine) { it.lines[0].text[0] == "o" }
      val frozen = engine.beginSelection()
      assertSame(frozen, engine.beginSelection())
      assertTrue(engine.enqueue(2L, "\r\ntwo".toByteArray(StandardCharsets.UTF_8)))
      awaitFrame(engine) { it.lines.any { line -> line.text[0] == "t" } }
      assertSame(frozen, engine.displayFrame())
      engine.endSelection()
      assertNull(engine.selectionFrame)
      assertNotSame(frozen, engine.displayFrame())
    } finally {
      engine.close()
    }
  }

  private fun range(startRow: Int, startColumn: Int, endRow: Int, endColumn: Int) =
    NativeTerminalSelectionRange(startRow, startColumn, endRow, endColumn)

  private fun row(text: String, wrapped: Boolean = false): NativeTerminalEngine.FrameRow {
    val cells = text.padEnd(maxOf(text.length, 10)).map { it.toString() }.toTypedArray()
    return NativeTerminalEngine.FrameRow(
      text = cells,
      width = ByteArray(cells.size) { 1 },
      foreground = IntArray(cells.size),
      background = IntArray(cells.size),
      flags = IntArray(cells.size),
      wrapped = wrapped,
    )
  }

  private fun awaitFrame(
    engine: NativeTerminalEngine,
    predicate: (NativeTerminalEngine.Frame) -> Boolean,
  ): NativeTerminalEngine.Frame {
    val deadline = System.nanoTime() + 5_000_000_000L
    while (System.nanoTime() < deadline) {
      val frame = engine.currentFrame()
      if (predicate(frame)) return frame
      Thread.sleep(5L)
    }
    throw AssertionError("native terminal frame was not published before the deadline")
  }
}
