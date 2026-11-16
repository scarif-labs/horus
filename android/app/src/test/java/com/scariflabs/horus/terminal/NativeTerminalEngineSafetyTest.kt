package com.scariflabs.horus.terminal

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Comparator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regressions for memory-safety and encoding bugs in the libghostty JNI bridge. */
class NativeTerminalEngineSafetyTest {
  @Test
  fun aFullScreenOfLongGraphemesGrowsTheRenderBuffer() {
    // libghostty keeps 64 marks per cell: 129 bytes here, so 60x300 cells pass 2 MiB.
    val rows = 60
    val columns = 300
    val engine = NativeTerminalEngine("s-zalgo", rows, columns) { _, _ -> }
    try {
      val cell = "a" + "\u0301".repeat(200)
      var seq = 1L
      repeat(rows) { row ->
        val line = cell.repeat(columns) + if (row < rows - 1) "\r\n" else ""
        assertTrue(engine.enqueue(seq++, line.toByteArray(StandardCharsets.UTF_8)))
      }
      val frame = awaitFrame(engine) { frame ->
        frame.lines.size >= rows && frame.lines.last().text.last().startsWith("a")
      }
      assertEquals("a" + "\u0301".repeat(64), frame.lines.last().text.last())
      assertEquals("a" + "\u0301".repeat(64), frame.lines[frame.lines.size - rows].text[0])
    } finally {
      engine.close()
    }
  }

  @Test
  fun graphemesLongerThanACellCanStoreEndOnAWholeCodePoint() {
    // 64 four-byte marks make a 257-byte grapheme; the frame stores at most 255 bytes.
    val engine = NativeTerminalEngine("s-zalgo-wide", 3, 10) { _, _ -> }
    try {
      val mark = String(Character.toChars(0x1D167))
      assertTrue(engine.enqueue(1L, ("a" + mark.repeat(70) + "b").toByteArray(StandardCharsets.UTF_8)))
      val frame = awaitFrame(engine) { it.lines[0].text[1] == "b" }
      assertEquals("a" + mark.repeat(63), frame.lines[0].text[0])
    } finally {
      engine.close()
    }
  }

  @Test
  fun paletteChangesRecolorRowsThatDidNotChange() {
    val engine = NativeTerminalEngine("s-palette", 3, 10) { _, _ -> }
    try {
      assertTrue(engine.enqueue(1L, "\u001b[31mx\u001b[m".toByteArray(StandardCharsets.UTF_8)))
      val before = awaitFrame(engine) { it.lines[0].text[0] == "x" }.lines[0].foreground[0]
      assertTrue(engine.enqueue(2L, "\u001b]4;1;#00ff00\u0007".toByteArray(StandardCharsets.UTF_8)))
      val after = awaitFrame(engine) { it.lines[0].foreground[0] != before }
      assertEquals(0xFF00FF00.toInt(), after.lines[0].foreground[0])
    } finally {
      engine.close()
    }
  }

  @Test
  fun pasteAndKeyTextEncodeEmojiAsStandardUtf8() {
    val engine = NativeTerminalEngine("s-emoji-encode", 3, 10) { _, _ -> }
    try {
      val emoji = "😀"
      val expected = emoji.toByteArray(StandardCharsets.UTF_8)
      val paste = engine.withNativeHandle { h -> TerminalPtyJni.nativeEngineEncodePaste(h, emoji) }
      assertArrayEquals(expected, paste)
      val key = engine.withNativeHandle { h -> TerminalPtyJni.nativeEngineEncodeKey(h, 0, 0, 1, emoji) }
      assertArrayEquals(expected, key)
    } finally {
      engine.close()
    }
  }

  @Test
  fun formattedTextKeepsEmoji() {
    val engine = NativeTerminalEngine("s-format-emoji", 3, 10) { _, _ -> }
    try {
      assertTrue(engine.enqueue(1L, "hi 😀".toByteArray(StandardCharsets.UTF_8)))
      awaitFrame(engine) { it.lines[0].text[0] == "h" }
      assertEquals("hi 😀", NativeTerminalText.from(engine).text)
    } finally {
      engine.close()
    }
  }

  @Test
  fun hyperlinksResolveOnFrameRowsBelowScrollback() {
    val engine = NativeTerminalEngine("s-osc8-scrollback", 4, 20) { _, _ -> }
    try {
      val output = StringBuilder()
      output.append("\u001b]8;;https://old.example\u0007old\u001b]8;;\u0007\r\n")
      repeat(10) { output.append("line $it\r\n") }
      output.append("\u001b]8;;https://new.example\u0007new\u001b]8;;\u0007")
      assertTrue(engine.enqueue(1L, output.toString().toByteArray(StandardCharsets.UTF_8)))
      val frame = awaitFrame(engine) { frame -> frame.lines.lastOrNull { it.hasVisibleContent }?.text?.get(0) == "n" }
      val newRow = frame.lines.indexOfLast { it.text[0] == "n" }
      val oldRow = frame.lines.indexOfFirst { it.text[0] == "o" }
      assertTrue(oldRow in 0 until newRow)
      assertEquals("https://new.example", NativeTerminalLinks.urlAt(engine, newRow, 1))
      assertEquals("https://old.example", NativeTerminalLinks.urlAt(engine, oldRow, 1))
    } finally {
      engine.close()
    }
  }

  @Test
  fun closingWhileOutputStreamsDoesNotUseTheFreedEngine() {
    val chunk = "0123456789".repeat(400).toByteArray(StandardCharsets.UTF_8)
    repeat(40) { round ->
      val engine = NativeTerminalEngine("s-close-race-$round", 24, 80) { _, _ -> }
      for (seq in 1L..200L) engine.enqueue(seq, chunk)
      Thread.sleep(1L)
      engine.close()
      assertNull(engine.withNativeHandle { it })
    }
  }

  @Test
  fun savingTheSessionLimitKeepsTheEngineBackend() {
    val root = Files.createTempDirectory("horus-session-settings-backend").toFile()
    try {
      val settings = TerminalSessionSettings(root.resolve("settings/session-settings.json"))
      assertTrue(settings.writeEngineBackend(TerminalEngineBackend.LEGACY))
      assertTrue(settings.writeLimit(TerminalSessionContract.MIN_ACTIVE_SESSIONS))
      assertEquals(TerminalEngineBackend.LEGACY, settings.readEngineBackend())
      assertEquals(TerminalSessionContract.MIN_ACTIVE_SESSIONS, settings.readLimit())
    } finally {
      Files.walk(root.toPath()).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach { path -> Files.deleteIfExists(path) }
      }
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
