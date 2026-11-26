package com.scariflabs.horus.terminal

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Scrolling, selection and frame reuse on the libghostty engine. */
class NativeTerminalViewportTest {
  private fun lines(from: Int, count: Int): ByteArray =
    (from until from + count).joinToString("") { "line $it\r\n" }.toByteArray(StandardCharsets.UTF_8)

  private fun NativeTerminalEngine.FrameRow.string(): String = text.joinToString("").trimEnd()

  private fun NativeTerminalEngine.Frame.rowText(screenRow: Int): String? =
    lines.getOrNull(frameRow(screenRow))?.string()

  @Test
  fun aScrolledBackViewStaysOnItsTextWhileOutputArrives() {
    val engine = NativeTerminalEngine("s-pinned", 5, 20) { _, _ -> }
    try {
      assertTrue(engine.enqueue(1L, lines(0, 30)))
      awaitFrame(engine) { it.totalRows >= 30 }
      engine.setViewport(follow = false, topRow = 5, above = 5, below = 5)
      val pinned = awaitFrame(engine) { !it.followingActive && it.viewportTop == 5 }
      assertEquals("line 5", pinned.rowText(5))
      assertEquals("line 0", pinned.rowText(0))
      assertEquals(15, pinned.lines.size)

      assertTrue(engine.enqueue(2L, lines(30, 40)))
      val later = awaitFrame(engine) { it.totalRows >= 70 }
      assertEquals(5, later.viewportTop)
      assertEquals("line 5", later.rowText(5))
    } finally {
      engine.close()
    }
  }

  @Test
  fun followingShowsTheActiveScreen() {
    val engine = NativeTerminalEngine("s-follow", 5, 20) { _, _ -> }
    try {
      assertTrue(engine.enqueue(1L, lines(0, 30)))
      engine.setViewport(follow = false, topRow = 0, above = 0, below = 0)
      awaitFrame(engine) { it.viewportTop == 0 && it.totalRows >= 30 }
      engine.setViewport(follow = true, topRow = 0, above = 2, below = 0)
      val following = awaitFrame(engine) { it.followingActive }
      assertEquals(following.activeTop, following.viewportTop)
      assertEquals(2, following.overscanAbove)
      assertEquals(following.rows + 2, following.lines.size)
      // The active screen ends with the cursor's blank line under "line 29".
      assertEquals("line 29", following.rowText(following.activeTop + following.rows - 2))
    } finally {
      engine.close()
    }
  }

  @Test
  fun aSelectionMovesWithItsTextAndCopies() {
    val engine = NativeTerminalEngine("s-select", 5, 30) { _, _ -> }
    try {
      assertTrue(engine.enqueue(1L, "say hello world\r\n".toByteArray(StandardCharsets.UTF_8)))
      val frame = awaitFrame(engine) { it.lines.any { row -> row.string() == "say hello world" } }
      val row = frame.screenRow(frame.lines.indexOfFirst { it.string() == "say hello world" })
      val word = engine.selectWord(column = 6, screenRow = row)
      assertNotNull(word)
      assertEquals(listOf(4, row, 8, row), word!!.toList())
      assertEquals("hello", engine.selectionText())
      val selected = awaitFrame(engine) { it.selection != null }
      val index = selected.frameRow(row)
      assertEquals(4, selected.selection!![index * 2])
      assertEquals(9, selected.selection!![index * 2 + 1])

      // Between words, the nearest word is taken; on a blank line, the cell.
      assertEquals(listOf(4, row, 8, row), engine.selectWord(column = 3, screenRow = row)!!.toList())
      assertEquals(listOf(25, row, 25, row), engine.selectWord(column = 25, screenRow = row)!!.toList())
      engine.selectWord(column = 6, screenRow = row)

      // Extend to "world", then push the text up into scrollback.
      assertTrue(engine.selectRange(4, row, 14, row))
      assertEquals("hello world", engine.selectionText())
      assertTrue(engine.enqueue(2L, lines(0, 12)))
      awaitFrame(engine) { it.totalRows >= 13 }
      assertEquals("hello world", engine.selectionText())

      engine.clearSelection()
      assertNull(engine.selectionText())
      awaitFrame(engine) { it.selection == null }
    } finally {
      engine.close()
    }
  }

  @Test
  fun aWordEndsAtAnUnwrittenCell() {
    val engine = NativeTerminalEngine("s-select-gap", 4, 30) { _, _ -> }
    try {
      // Write "Run", skip a cell without writing it, then "/connect".
      assertTrue(engine.enqueue(1L, "Run\u001b[5G/connect".toByteArray(StandardCharsets.UTF_8)))
      val frame = awaitFrame(engine) { it.lines.any { row -> row.text[4] == "/" } }
      val row = frame.screenRow(frame.lines.indexOfFirst { it.text[4] == "/" })
      assertEquals(listOf(4, row, 11, row), engine.selectWord(column = 6, screenRow = row)!!.toList())
      assertEquals("/connect", engine.selectionText())
      assertEquals(listOf(4, row, 11, row), engine.selectWord(column = 3, screenRow = row)!!.toList())
    } finally {
      engine.close()
    }
  }

  @Test
  fun selectAllCoversTheScrollback() {
    val engine = NativeTerminalEngine("s-select-all", 4, 20) { _, _ -> }
    try {
      assertTrue(engine.enqueue(1L, lines(0, 20)))
      awaitFrame(engine) { it.totalRows >= 20 }
      assertTrue(engine.selectAll())
      val text = engine.selectionText()!!
      assertTrue(text, text.startsWith("line 0\n"))
      assertTrue(text, text.contains("line 19"))
    } finally {
      engine.close()
    }
  }

  @Test
  fun unchangedRowsAreReusedAcrossFrames() {
    val engine = NativeTerminalEngine("s-reuse", 5, 20) { _, _ -> }
    try {
      assertTrue(engine.enqueue(1L, "one\r\ntwo\r\nthree".toByteArray(StandardCharsets.UTF_8)))
      val first = awaitFrame(engine) { it.lines.any { row -> row.string() == "three" } }
      assertTrue(engine.enqueue(2L, "!".toByteArray(StandardCharsets.UTF_8)))
      val second = awaitFrame(engine) { it.lines.any { row -> row.string() == "three!" } }
      val oneBefore = first.lines.first { it.string() == "one" }
      val oneAfter = second.lines.first { it.string() == "one" }
      assertSame(oneBefore, oneAfter)
      assertFalse(second.lines.any { it.string() == "three" })
    } finally {
      engine.close()
    }
  }

  @Test
  fun theCursorIsHiddenWhenScrolledAwayFromIt() {
    val engine = NativeTerminalEngine("s-cursor", 4, 20) { _, _ -> }
    try {
      assertTrue(engine.enqueue(1L, lines(0, 20)))
      awaitFrame(engine) { it.cursorVisible && it.totalRows >= 20 }
      engine.setViewport(follow = false, topRow = 0, above = 0, below = 0)
      val scrolled = awaitFrame(engine) { it.viewportTop == 0 && !it.followingActive }
      assertFalse(scrolled.cursorVisible)
      assertEquals(-1, scrolled.cursorRow)
    } finally {
      engine.close()
    }
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
