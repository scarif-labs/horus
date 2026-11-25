package com.scariflabs.horus.terminal

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeTerminalLinksTest {
  @Test
  fun detectsHttpAndHttpsUrlsAsColumnRanges() {
    val (text, width) = row("see https://github.com/a/b and http://localhost:3000/x ok", 70)
    val links = NativeTerminalLinks.detect(text, width)
    val first = "https://github.com/a/b"
    val second = "http://localhost:3000/x"
    val secondStart = 4 + first.length + 5
    assertArrayEquals(intArrayOf(4, 4 + first.length, secondStart, secondStart + second.length), links)
  }

  @Test
  fun trimsTrailingPunctuationAndUnbalancedBrackets() {
    val (text, width) = row("(see https://claude.ai/docs).", 40)
    val links = NativeTerminalLinks.detect(text, width)
    assertArrayEquals(intArrayOf(5, 5 + "https://claude.ai/docs".length), links)
  }

  @Test
  fun skipsCredentialHostsAndUrlsWithoutABoundary() {
    val (text, width) = row("https://github.com@evil.example/x xhttps://github.com/y", 60)
    assertSame(NativeTerminalLinks.NONE, NativeTerminalLinks.detect(text, width))
  }

  @Test
  fun skipsUrlsThatReachTheRightEdgeBecauseTheyMayWrap() {
    val url = "https://github.com/wraps"
    val (text, width) = row("> $url", url.length + 2)
    assertSame(NativeTerminalLinks.NONE, NativeTerminalLinks.detect(text, width))
  }

  @Test
  fun mapsStringIndicesToColumnsAcrossWideGlyphs() {
    // 中 and 文 each occupy two cells: the glyph plus an empty width-0 cell.
    val url = "https://opencode.ai/x"
    val text = arrayOf("中", "", "文", "", " ") + url.map(Char::toString).toTypedArray() + arrayOf(" ", " ")
    val width = ByteArray(text.size) { index -> if (index == 0 || index == 2) 2 else if (text[index].isEmpty()) 0 else 1 }
    val links = NativeTerminalLinks.detect(text, width)
    assertArrayEquals(intArrayOf(5, 5 + url.length), links)
  }

  @Test
  fun urlFreeRowsShareTheEmptyRangesAndSkipTheRegex() {
    val (text, width) = row("plain output: nothing to link here, http: /", 60)
    val before = NativeTerminalLinks.regexScans.get()
    assertSame(NativeTerminalLinks.NONE, NativeTerminalLinks.detect(text, width))
    assertEquals(before, NativeTerminalLinks.regexScans.get())
  }

  @Test
  fun engineRowsCarryLinksAndResolveTheUrlAtACell() {
    val engine = NativeTerminalEngine("s-native-links", 3, 40) { _, _ -> }
    try {
      assertTrue(
        engine.enqueue(
          1L,
          "open \u001b[1mhttps://github.com/x\u001b[0m.\r\nplain".toByteArray(StandardCharsets.UTF_8),
        ),
      )
      val frame = awaitFrame(engine) { it.lines[1].text[0] == "p" }
      assertArrayEquals(intArrayOf(5, 5 + "https://github.com/x".length), frame.lines[0].links)
      assertEquals("https://github.com/x", NativeTerminalLinks.urlAt(frame.lines[0], 5))
      assertEquals("https://github.com/x", NativeTerminalLinks.urlAt(frame.lines[0], 24))
      assertNull(NativeTerminalLinks.urlAt(frame.lines[0], 4))
      assertNull(NativeTerminalLinks.urlAt(frame.lines[0], 25))
      assertSame(NativeTerminalLinks.NONE, frame.lines[1].links)
    } finally {
      engine.close()
    }
  }

  @Test
  fun followsUrlsWrappedAcrossRowsForTapsAndUnderlines() {
    // A 12-column terminal wraps this sign-in URL across three rows.
    val url = "https://claude.com/cai/oauth?code=true"
    val engine = NativeTerminalEngine("s-links-wrapped", 5, 12) { _, _ -> }
    try {
      assertTrue(engine.enqueue(1L, "Sign in:\r\n$url\r\ndone".toByteArray(StandardCharsets.UTF_8)))
      val frame = awaitFrame(engine) { it.lines.any { line -> line.text[0] == "d" } }
      // Rows 1-4 hold the URL (38 chars = 12 + 12 + 12 + 2); row 5 is "done".
      assertEquals(url, NativeTerminalLinks.urlAt(frame.lines, 1, 0))
      assertEquals(url, NativeTerminalLinks.urlAt(frame.lines, 3, 11))
      assertEquals(url, NativeTerminalLinks.urlAt(frame.lines, 4, 1))
      assertNull(NativeTerminalLinks.urlAt(frame.lines, 4, 5))
      assertNull(NativeTerminalLinks.urlAt(frame.lines, 0, 2))
      val cache = NativeTerminalLinks.WrappedLinkCache()
      assertArrayEquals(intArrayOf(0, 12), NativeTerminalLinks.rowLinks(frame.lines, 1, cache))
      assertArrayEquals(intArrayOf(0, 12), NativeTerminalLinks.rowLinks(frame.lines, 3, cache))
      assertArrayEquals(intArrayOf(0, 2), NativeTerminalLinks.rowLinks(frame.lines, 4, cache))
      assertSame(NativeTerminalLinks.NONE, NativeTerminalLinks.rowLinks(frame.lines, 0, cache))
      // Cached by row identity: a second lookup returns the same array.
      assertSame(NativeTerminalLinks.rowLinks(frame.lines, 2, cache), NativeTerminalLinks.rowLinks(frame.lines, 2, cache))
    } finally {
      engine.close()
    }
  }

  @Test
  fun followsAUrlAnAppWrappedInsideAPaddedPanel() {
    // OpenCode wraps text inside a panel with a right margin, so the row
    // ends well before the edge with "http://localhost:" and the port below.
    val engine = NativeTerminalEngine("s-links-panel", 6, 40) { _, _ -> }
    try {
      val output = "  Site is live at http://localhost:   \r\n  8000 (files: index.html).\r\n" +
        "  See https://example.com.   \r\n  next line\r\n"
      assertTrue(engine.enqueue(1L, output.toByteArray(StandardCharsets.UTF_8)))
      val frame = awaitFrame(engine) { it.lines.any { line -> line.text[2] == "n" } }
      val first = frame.lines.indexOfFirst { it.text[2] == "S" && it.text[3] == "i" }
      assertEquals("http://localhost:8000", NativeTerminalLinks.urlAt(frame.lines, first, 20))
      assertEquals("http://localhost:8000", NativeTerminalLinks.urlAt(frame.lines, first + 1, 3))
      assertNull(NativeTerminalLinks.urlAt(frame.lines, first + 1, 8))
      val cache = NativeTerminalLinks.WrappedLinkCache()
      assertArrayEquals(intArrayOf(18, 35), NativeTerminalLinks.rowLinks(frame.lines, first, cache))
      assertArrayEquals(intArrayOf(2, 6), NativeTerminalLinks.rowLinks(frame.lines, first + 1, cache))
      // A finished URL at the end of a row does not swallow the next row.
      val see = frame.lines.indexOfFirst { it.text[2] == "S" && it.text[3] == "e" }
      assertEquals("https://example.com", NativeTerminalLinks.urlAt(frame.lines, see, 10))
      assertSame(NativeTerminalLinks.NONE, NativeTerminalLinks.rowLinks(frame.lines, see + 1, cache))
    } finally {
      engine.close()
    }
  }

  @Test
  fun marksOsc8HyperlinkCells() {
    val engine = NativeTerminalEngine("s-links-osc8", 3, 30) { _, _ -> }
    try {
      val output = "see \u001b]8;;https://a.example/x\u001b\\the docs\u001b]8;;\u001b\\ now"
      assertTrue(engine.enqueue(1L, output.toByteArray(StandardCharsets.UTF_8)))
      val frame = awaitFrame(engine) { it.lines.any { line -> line.text[13] == "n" } }
      val row = frame.lines.first { it.text[0] == "s" }
      val linked = (0 until 17).filter { row.flags[it] and NativeTerminalEngine.FLAG_HYPERLINK != 0 }
      assertEquals((4 until 12).toList(), linked)
      val index = frame.lines.indexOf(row)
      assertEquals("https://a.example/x", NativeTerminalLinks.urlAt(engine, frame, index, 6))
    } finally {
      engine.close()
    }
  }

  @Test
  fun joinsOnlyUrlBreaksThatCannotEndAUrl() {
    val engine = NativeTerminalEngine("s-links-breaks", 10, 40) { _, _ -> }
    try {
      val output = "  https://example.com/docs/   \r\n  Then run it\r\n" +
        "  https://example.com/a-   \r\n  long-path ok\r\n" +
        "  https://example.com:   \r\n  - item\r\n" +
        "  https://example.com/   \r\n  guide/intro done\r\n"
      assertTrue(engine.enqueue(1L, output.toByteArray(StandardCharsets.UTF_8)))
      val frame = awaitFrame(engine) { it.lines.any { line -> line.text[2] == "g" } }
      fun row(prefix: String) = frame.lines.indexOfFirst { line -> line.text.joinToString("").trim().startsWith(prefix) }
      // A slash then a capital is a new sentence.
      assertEquals("https://example.com/docs/", NativeTerminalLinks.urlAt(frame.lines, row("https://example.com/docs/"), 5))
      // A hyphen break joins.
      assertEquals("https://example.com/a-long-path", NativeTerminalLinks.urlAt(frame.lines, row("long-path"), 4))
      // A colon needs a port; a list below is not part of the URL.
      assertEquals("https://example.com", NativeTerminalLinks.urlAt(frame.lines, row("https://example.com:"), 5))
      assertNull(NativeTerminalLinks.urlAt(frame.lines, row("- item"), 2))
      // A slash then lower-case text joins.
      assertEquals("https://example.com/guide/intro", NativeTerminalLinks.urlAt(frame.lines, row("guide/intro"), 3))
    } finally {
      engine.close()
    }
  }

  @Test
  fun followsUrlsAnAppWrappedWithAnIndent() {
    // Claude Code wraps its own text and indents continuation rows by two
    // spaces, so the URL is split by real line breaks.
    val engine = NativeTerminalEngine("s-links-indented", 6, 20) { _, _ -> }
    try {
      val output = "x (https://claude.ai\r\n  /code/artifact/7f1\r\n  07076). Done\r\n"
      assertTrue(engine.enqueue(1L, output.toByteArray(StandardCharsets.UTF_8)))
      val frame = awaitFrame(engine) { it.lines.any { line -> line.text[2] == "0" } }
      val url = "https://claude.ai/code/artifact/7f107076"
      assertEquals(url, NativeTerminalLinks.urlAt(frame.lines, 0, 5))
      assertEquals(url, NativeTerminalLinks.urlAt(frame.lines, 1, 2))
      assertEquals(url, NativeTerminalLinks.urlAt(frame.lines, 2, 6))
      // The indent and the text after the link are not part of it.
      assertNull(NativeTerminalLinks.urlAt(frame.lines, 1, 0))
      assertNull(NativeTerminalLinks.urlAt(frame.lines, 2, 8))
      val cache = NativeTerminalLinks.WrappedLinkCache()
      assertArrayEquals(intArrayOf(3, 20), NativeTerminalLinks.rowLinks(frame.lines, 0, cache))
      assertArrayEquals(intArrayOf(2, 20), NativeTerminalLinks.rowLinks(frame.lines, 1, cache))
      assertArrayEquals(intArrayOf(2, 7), NativeTerminalLinks.rowLinks(frame.lines, 2, cache))
    } finally {
      engine.close()
    }
  }

  @Test
  fun opensAnyWellFormedHost() {
    assertTrue(NativeTerminalLinks.isTrustedUrl("https://claude.com/cai/oauth/authorize?code=true"))
    assertTrue(NativeTerminalLinks.isTrustedUrl("https://example.com/docs"))
    assertTrue(NativeTerminalLinks.isTrustedUrl("http://[fe80::1]:8080/"))
    assertFalse(NativeTerminalLinks.isTrustedUrl("https://user@example.com"))
    assertFalse(NativeTerminalLinks.isTrustedUrl("https://example.com:99999"))
  }

  private fun row(value: String, columns: Int): Pair<Array<String>, ByteArray> {
    val text = Array(columns) { index -> if (index < value.length) value[index].toString() else " " }
    return text to ByteArray(columns) { 1 }
  }

  private fun awaitFrame(
    engine: NativeTerminalEngine,
    predicate: (NativeTerminalEngine.Frame) -> Boolean,
  ): NativeTerminalEngine.Frame {
    val deadline = System.nanoTime() + 1_000_000_000L
    while (System.nanoTime() < deadline) {
      val frame = engine.currentFrame()
      if (predicate(frame)) return frame
      Thread.sleep(5L)
    }
    throw AssertionError("native terminal frame was not published before the deadline")
  }
}
