package com.scariflabs.horus.terminal

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeTerminalEngineTest {
  @Test
  fun parsesOpenCodeAlternateScreenAndPublishesNativeFrame() {
    val engine = NativeTerminalEngine("s-native-test", 4, 12) { _, _ -> }
    try {
      assertTrue(
        engine.enqueue(
          1L,
          "\u001b[?1049h\u001b[2J\u001b[H\u001b[31mHello\u001b[0m".toByteArray(StandardCharsets.UTF_8),
        ),
      )
      val frame = awaitFrame(engine) { it.alternate && it.lines[0].text[0] == "H" }
      assertTrue(frame.alternate)
      assertTrue(frame.hasVisibleContent)
      assertEquals("H", frame.lines[0].text[0])
      assertEquals("e", frame.lines[0].text[1])
      assertEquals(0xFFCD0000.toInt(), frame.lines[0].foreground[0])
      assertEquals(5, frame.cursorColumn)
    } finally {
      engine.close()
    }
  }

  @Test
  fun joinsSplitUtf8CombiningMarksAndAnswersCursorQueries() {
    val replies = mutableListOf<String>()
    val engine = NativeTerminalEngine("s-native-test", 3, 12) { _, bytes ->
      synchronized(replies) { replies += String(bytes, StandardCharsets.UTF_8) }
    }
    try {
      assertFalse(engine.currentFrame().hasVisibleContent)
      assertTrue(engine.enqueue(1L, byteArrayOf('e'.code.toByte(), 0xCC.toByte())))
      awaitFrame(engine) { it.lines[0].text[0] == "e" }
      assertTrue(engine.currentFrame().hasVisibleContent)
      assertTrue(engine.enqueue(2L, byteArrayOf(0x81.toByte())))
      assertTrue(engine.enqueue(3L, "\u001b[6n".toByteArray(StandardCharsets.UTF_8)))
      val frame = awaitFrame(engine) { it.lines[0].text[0] == "e\u0301" }
      assertEquals("e\u0301", frame.lines[0].text[0])
      assertEquals("\u001b[1;2R", awaitReply(replies))
    } finally {
      engine.close()
    }
  }

  @Test
  fun reportsOutputGapAndDropsStateCutMidSequence() {
    val gaps = java.util.concurrent.atomic.AtomicInteger(0)
    val engine = NativeTerminalEngine(
      "s-native-gap",
      3,
      12,
      onOutputGap = { gaps.incrementAndGet() },
    ) { _, _ -> }
    try {
      // Chunk 1 leaves a colour and an unfinished CSI; chunk 2 is lost.
      assertTrue(engine.enqueue(1L, "a\u001b[31mb\u001b[3".toByteArray(StandardCharsets.UTF_8)))
      assertTrue(engine.enqueue(3L, "Xy".toByteArray(StandardCharsets.UTF_8)))
      val frame = awaitFrame(engine) { it.lines[0].text[2] == "X" }
      assertEquals(1, gaps.get())
      assertEquals("y", frame.lines[0].text[3])
      assertEquals(0xFFF2F4F5.toInt(), frame.lines[0].foreground[2])
      // Contiguous output after the gap does not report again.
      assertTrue(engine.enqueue(4L, "z".toByteArray(StandardCharsets.UTF_8)))
      awaitFrame(engine) { it.lines[0].text[4] == "z" }
      assertEquals(1, gaps.get())
    } finally {
      engine.close()
    }
  }

  @Test
  fun tracksBracketedPasteMode() {
    val engine = NativeTerminalEngine("s-native-paste", 2, 8) { _, _ -> }
    try {
      assertFalse(engine.bracketedPaste)
      assertTrue(engine.enqueue(1L, "\u001b[?2004hA".toByteArray(StandardCharsets.UTF_8)))
      awaitFrame(engine) { it.lines[0].text[0] == "A" }
      assertTrue(engine.bracketedPaste)
      assertTrue(engine.enqueue(2L, "\u001b[?2004lB".toByteArray(StandardCharsets.UTF_8)))
      awaitFrame(engine) { it.lines[0].text[1] == "B" }
      assertFalse(engine.bracketedPaste)
    } finally {
      engine.close()
    }
  }

  @Test
  fun treatsAFreshEngineStartingMidStreamAsAGap() {
    val gaps = java.util.concurrent.atomic.AtomicInteger(0)
    val engine = NativeTerminalEngine("s-native-gap-fresh", 2, 8, onOutputGap = { gaps.incrementAndGet() }) { _, _ -> }
    try {
      assertTrue(engine.enqueue(40L, "hi".toByteArray(StandardCharsets.UTF_8)))
      awaitFrame(engine) { it.lines[0].text[0] == "h" }
      assertEquals(1, gaps.get())
    } finally {
      engine.close()
    }
  }

  @Test
  fun keepsBoundedNormalScrollbackForNativeScrolling() {
    val engine = NativeTerminalEngine("s-native-scrollback", 2, 8) { _, _ -> }
    try {
      assertTrue(engine.enqueue(1L, "one\r\ntwo\r\nthree".toByteArray(StandardCharsets.UTF_8)))
      val frame = awaitFrame(engine) { it.contentRows == 3 && it.lines[0].text[0] == "o" }
      assertFalse(frame.alternate)
      assertEquals(3, frame.contentRows)
      assertEquals("n", frame.lines[0].text[1])
      assertEquals("t", frame.lines[1].text[0])
      assertEquals("t", frame.lines[2].text[0])
      assertEquals(2, frame.cursorRow)
    } finally {
      engine.close()
    }
  }

  @Test
  fun holdsSynchronizedOutputUntilTheUpdateCloses() {
    val replies = mutableListOf<String>()
    val engine = NativeTerminalEngine("s-native-sync", 2, 8) { _, bytes ->
      synchronized(replies) { replies += String(bytes, StandardCharsets.UTF_8) }
    }
    try {
      assertTrue(engine.enqueue(1L, "\u001b[?2026\$p".toByteArray(StandardCharsets.UTF_8)))
      assertEquals("\u001b[?2026;2\$y", awaitReply(replies))
      assertTrue(engine.enqueue(2L, "\u001b[?2026hAB".toByteArray(StandardCharsets.UTF_8)))
      Thread.sleep(60L)
      assertEquals(" ", engine.currentFrame().lines[0].text[0])
      assertTrue(engine.enqueue(3L, "C\u001b[?2026l".toByteArray(StandardCharsets.UTF_8)))
      val frame = awaitFrame(engine) { it.lines[0].text[0] == "A" }
      assertEquals("C", frame.lines[0].text[2])
    } finally {
      engine.close()
    }
  }

  @Test
  fun publishesAbandonedSynchronizedOutputAfterTheSafetyTimeout() {
    val engine = NativeTerminalEngine("s-native-sync-timeout", 2, 8) { _, _ -> }
    try {
      assertTrue(engine.enqueue(1L, "\u001b[?2026hX".toByteArray(StandardCharsets.UTF_8)))
      awaitFrame(engine) { it.lines[0].text[0] == "X" }
    } finally {
      engine.close()
    }
  }

  @Test
  fun forwardsOsc52ClipboardWritesAndRefusesReads() {
    val clipboard = mutableListOf<String>()
    val engine = NativeTerminalEngine("s-native-osc52", 2, 8, clipboardWriter = { synchronized(clipboard) { clipboard += it } }) { _, _ -> }
    try {
      // "hello" in base64, then a read request (refused), both BEL and ST terminated.
      assertTrue(engine.enqueue(1L, "\u001b]52;c;aGVsbG8=\u0007\u001b]52;c;?\u001b\\x".toByteArray(StandardCharsets.UTF_8)))
      awaitRows(engine, listOf("x", ""))
      assertEquals(listOf("aGVsbG8="), synchronized(clipboard) { clipboard.toList() })
    } finally {
      engine.close()
    }
  }

  @Test
  fun ignoresPrefixedAndIntermediateCsiSequences() {
    val engine = NativeTerminalEngine("s-native-csi-prefix", 2, 8) { _, _ -> }
    try {
      // Kitty keyboard push/pop/query, modifyOtherKeys and cursor style must
      // not restore the cursor or change attributes.
      val input = "ab\u001b[>1u\u001b[>4;2mc\u001b[?u\u001b[<u\u001b[2 qd"
      assertTrue(engine.enqueue(1L, input.toByteArray(StandardCharsets.UTF_8)))
      val frame = awaitRows(engine, listOf("abcd", ""))
      assertEquals(0, frame.lines[0].flags[2])
      assertEquals(4, frame.cursorColumn)
    } finally {
      engine.close()
    }
  }

  @Test
  fun eraseDisplayKeepsTheCursorAndScrollbackEraseKeepsTheScreen() {
    val engine = NativeTerminalEngine("s-native-erase", 2, 8) { _, _ -> }
    try {
      assertTrue(engine.enqueue(1L, "abc\u001b[3Jd".toByteArray(StandardCharsets.UTF_8)))
      awaitRows(engine, listOf("abcd", ""))
      assertTrue(engine.enqueue(2L, "\u001b[2Je".toByteArray(StandardCharsets.UTF_8)))
      awaitRows(engine, listOf("    e", ""))
    } finally {
      engine.close()
    }
  }

  @Test
  fun keepsCursorMovesTabsAndBackspaceInsideTheRow() {
    val engine = NativeTerminalEngine("s-native-columns", 3, 8) { _, _ -> }
    try {
      val input = "\u001b[20Cx\r\n\u001b[2;20Hy\r\n\u001b[3;7H\tz"
      assertTrue(engine.enqueue(1L, input.toByteArray(StandardCharsets.UTF_8)))
      awaitRows(engine, listOf("       x", "       y", "       z"))
      // BS from a pending wrap moves from the last column, as in xterm.
      assertTrue(engine.enqueue(2L, "\u001b[2J\u001b[Habcdefgh\bX".toByteArray(StandardCharsets.UTF_8)))
      awaitRows(engine, listOf("abcdefXh", "", ""))
    } finally {
      engine.close()
    }
  }

  @Test
  fun swallowsCharsetDesignators() {
    val engine = NativeTerminalEngine("s-native-charset", 2, 8) { _, _ -> }
    try {
      // Designators print nothing; G1 is only used after SO.
      assertTrue(engine.enqueue(1L, "a\u001b(Bb\u001b)0c\u001b%Gd".toByteArray(StandardCharsets.UTF_8)))
      awaitRows(engine, listOf("abcd", ""))
      // ESC # 8 is DECALN: fill the screen with E and home the cursor.
      assertTrue(engine.enqueue(2L, "\u001b#8x".toByteArray(StandardCharsets.UTF_8)))
      awaitRows(engine, listOf("xEEEEEEE", "EEEEEEEE"))
    } finally {
      engine.close()
    }
  }

  @Test
  fun parsesColonSubparametersAndUnderlineColour() {
    val engine = NativeTerminalEngine("s-native-sgr", 2, 8) { _, _ -> }
    try {
      val input = "\u001b[4:3mu\u001b[4:0mv\u001b[38:2::255:0:0mR\u001b[0m\u001b[58;2;1;2;3mQ\u001b[38;2;0;255;0mG\u001b[38:5:196mP"
      assertTrue(engine.enqueue(1L, input.toByteArray(StandardCharsets.UTF_8)))
      val row = awaitRows(engine, listOf("uvRQGP", "")).lines[0]
      assertEquals(NativeTerminalEngine.FLAG_UNDERLINE, row.flags[0])
      assertEquals(0, row.flags[1])
      assertEquals(0xFFFF0000.toInt(), row.foreground[2])
      assertEquals(0, row.flags[3])
      assertEquals(NativeTerminalEngine.DEFAULT_FOREGROUND, row.foreground[3])
      assertEquals(0xFF00FF00.toInt(), row.foreground[4])
      assertEquals(0xFFFF0000.toInt(), row.foreground[5])
    } finally {
      engine.close()
    }
  }

  @Test
  fun ignoresLineEditsBelowTheScrollRegion() {
    val engine = NativeTerminalEngine("s-native-il-outside", 4, 8) { _, _ -> }
    try {
      assertTrue(engine.enqueue(1L, "\u001b[1;2r\u001b[4;1Hz\u001b[L\u001b[M!".toByteArray(StandardCharsets.UTF_8)))
      awaitRows(engine, listOf("", "", "", "z!"))
    } finally {
      engine.close()
    }
  }

  @Test
  fun sharesUnchangedScrollbackRowsAcrossPublishes() {
    val engine = NativeTerminalEngine("s-native-share-scrollback", 2, 8) { _, _ -> }
    try {
      assertTrue(engine.enqueue(1L, "one\r\ntwo\r\nthree".toByteArray(StandardCharsets.UTF_8)))
      val first = awaitFrame(engine) { it.contentRows == 3 && it.lines[2].text[0] == "t" }
      assertTrue(engine.enqueue(2L, "!".toByteArray(StandardCharsets.UTF_8)))
      val second = awaitFrame(engine) { it.lines[2].text[5] == "!" }
      assertEquals(listOf("one", "two", "three!"), rowTexts(second))
      assertSame(first.lines[0], second.lines[0])
    } finally {
      engine.close()
    }
  }

  @Test
  fun reusesCleanActiveRowsAndResnapshotsWrittenRows() {
    val engine = NativeTerminalEngine("s-native-dirty-rows", 3, 8) { _, _ -> }
    try {
      assertTrue(engine.enqueue(1L, "a\r\nb".toByteArray(StandardCharsets.UTF_8)))
      val first = awaitFrame(engine) { it.lines[1].text[0] == "b" }
      assertTrue(engine.enqueue(2L, "\u001b[2;2Hc".toByteArray(StandardCharsets.UTF_8)))
      val second = awaitFrame(engine) { it.lines[1].text[1] == "c" }
      assertEquals(listOf("a", "bc", ""), rowTexts(second))
      assertSame(first.lines[0], second.lines[0])
      assertSame(first.lines[2], second.lines[2])
      assertNotSame(first.lines[1], second.lines[1])
      assertEquals(listOf("a", "b", ""), rowTexts(first))
    } finally {
      engine.close()
    }
  }

  @Test
  fun republishesRowsChangedByScrollEraseAndLineEdits() {
    val engine = NativeTerminalEngine("s-native-line-edits", 4, 8) { _, _ -> }
    try {
      // Each step waits for its publish so the next step starts from cached rows.
      val steps = listOf(
        "\u001b[?1049h\u001b[Ha1\r\nb2\r\nc3\r\nd4" to listOf("a1", "b2", "c3", "d4"),
        "\u001b[S" to listOf("b2", "c3", "d4", ""),
        "\u001b[T" to listOf("", "b2", "c3", "d4"),
        "\u001b[2;2H\u001b[K" to listOf("", "b", "c3", "d4"),
        "\u001b[3;1H\u001b[L" to listOf("", "b", "", "c3"),
        "\u001b[1;1H\u001b[M" to listOf("b", "", "c3", ""),
        "\u001b[3;1H\u001b[@" to listOf("b", "", " c3", ""),
        "\u001b[3;1H\u001b[P" to listOf("b", "", "c3", ""),
        "\u001b[3;1H\u001b[X" to listOf("b", "", " 3", ""),
        "\u001b[2J\u001b[4;1Hz" to listOf("", "", "", "z"),
        "\u001b[Hq\u001b[2;3r\u001b[2;1Hm\u001b[3;1Hn\n" to listOf("q", "n", "", "z"),
        "\u001b[2;1H\u001bM" to listOf("q", "", "n", "z"),
      )
      steps.forEachIndexed { index, (input, expected) ->
        assertTrue(engine.enqueue(index + 1L, input.toByteArray(StandardCharsets.UTF_8)))
        awaitRows(engine, expected)
      }
    } finally {
      engine.close()
    }
  }

  @Test
  fun rendersInverseVideoWithSwappedColours() {
    val engine = NativeTerminalEngine("s-native-inverse", 3, 8) { _, _ -> }
    try {
      assertTrue(engine.enqueue(1L, "\u001b[31;7mR\u001b[0mS".toByteArray(StandardCharsets.UTF_8)))
      val first = awaitFrame(engine) { it.lines[0].text[1] == "S" }
      assertInverseRow(first.lines[0])
      assertTrue(engine.enqueue(2L, "\r\n\n\n".toByteArray(StandardCharsets.UTF_8)))
      val second = awaitFrame(engine) { it.contentRows == 4 }
      assertEquals(listOf("RS", "", "", ""), rowTexts(second))
      assertInverseRow(second.lines[0])
      assertSame(first.lines[0], second.lines[0])
    } finally {
      engine.close()
    }
  }

  @Test
  fun keepsTheCursorLineAndHistoryWhenTheScreenGetsShorter() {
    val engine = NativeTerminalEngine("s-native-shrink", 3, 8) { _, _ -> }
    try {
      // "h" scrolls into history; the prompt sits on the last screen row.
      assertTrue(engine.enqueue(1L, "h\r\na\r\nb\r\n\$ ".toByteArray(StandardCharsets.UTF_8)))
      awaitRows(engine, listOf("h", "a", "b", "\$"))
      engine.resize(2, 8)
      val frame = awaitFrame(engine) { it.rows == 2 }
      assertEquals(listOf("h", "a", "b", "\$"), rowTexts(frame))
      assertEquals(3, frame.cursorRow)
      assertEquals(2, frame.cursorColumn)
    } finally {
      engine.close()
    }
  }

  @Test
  fun republishesContentAfterResize() {
    val engine = NativeTerminalEngine("s-native-resize", 3, 8) { _, _ -> }
    try {
      assertTrue(engine.enqueue(1L, "ab\r\ncd".toByteArray(StandardCharsets.UTF_8)))
      awaitRows(engine, listOf("ab", "cd", ""))
      engine.resize(4, 6)
      val grown = awaitFrame(engine) { it.rows == 4 && it.columns == 6 }
      assertEquals(listOf("ab", "cd", "", ""), rowTexts(grown))
      assertTrue(grown.lines.all { it.columns == 6 })
      assertTrue(engine.enqueue(2L, "e".toByteArray(StandardCharsets.UTF_8)))
      awaitRows(engine, listOf("ab", "cde", "", ""))
      engine.resize(4, 2)
      val shrunk = awaitFrame(engine) { it.columns == 2 }
      assertEquals(listOf("ab", "cd", "", ""), rowTexts(shrunk))
      assertTrue(shrunk.lines.all { it.columns == 2 })
    } finally {
      engine.close()
    }
  }

  @Test
  fun republishesContentAcrossAlternateScreenSwitches() {
    val engine = NativeTerminalEngine("s-native-alternate", 3, 8) { _, _ -> }
    try {
      assertTrue(engine.enqueue(1L, "norm".toByteArray(StandardCharsets.UTF_8)))
      val normal = awaitRows(engine, listOf("norm", "", ""))
      // As in xterm, the alternate screen starts at the normal cursor; home it.
      assertTrue(engine.enqueue(2L, "\u001b[?1049h\u001b[Halt".toByteArray(StandardCharsets.UTF_8)))
      val alternate = awaitFrame(engine) { it.alternate && rowTexts(it) == listOf("alt", "", "") }
      assertEquals(3, alternate.contentRows)
      assertTrue(engine.enqueue(3L, "\u001b[?1049l".toByteArray(StandardCharsets.UTF_8)))
      val restored = awaitFrame(engine) { !it.alternate }
      assertEquals(listOf("norm", "", ""), rowTexts(restored))
      assertSame(normal.lines[0], restored.lines[0])
      assertTrue(engine.enqueue(4L, "\u001b[?1049h".toByteArray(StandardCharsets.UTF_8)))
      val cleared = awaitFrame(engine) { it.alternate }
      assertEquals(listOf("", "", ""), rowTexts(cleared))
      assertFalse(cleared.hasVisibleContent)
    } finally {
      engine.close()
    }
  }

  private fun assertInverseRow(row: NativeTerminalEngine.FrameRow) {
    assertEquals(NativeTerminalEngine.DEFAULT_BACKGROUND, row.foreground[0])
    assertEquals(0xFFCD0000.toInt(), row.background[0])
    assertEquals(0, row.flags[0] and NativeTerminalEngine.FLAG_INVERSE)
    assertEquals(NativeTerminalEngine.DEFAULT_FOREGROUND, row.foreground[1])
    assertEquals(NativeTerminalEngine.DEFAULT_BACKGROUND, row.background[1])
  }

  @Test
  fun handlesC1ControlsOverlongCsiAndClampedCursorReports() {
    val replies = mutableListOf<String>()
    val clipboard = mutableListOf<String>()
    val engine = NativeTerminalEngine(
      "s-native-c1",
      2,
      8,
      clipboardWriter = { synchronized(clipboard) { clipboard += it } },
    ) { _, bytes -> synchronized(replies) { replies += String(bytes, StandardCharsets.UTF_8) } }
    try {
      // DSR 6 from a pending wrap reports the last column, as the frame shows.
      assertTrue(engine.enqueue(1L, "abcdefgh\u001b[6n".toByteArray(StandardCharsets.UTF_8)))
      assertEquals("\u001b[1;8R", awaitReply(replies))
      // C1 OSC ... ST still delivers OSC 52; CAN abandons an OSC; an
      // over-long CSI is skipped through its final byte.
      val overlong = "\u001b[" + "1;".repeat(100) + "4m"
      val input = "\r\u009d52;c;aGk=\u009c\u001b]0;t\u0018Y$overlong" + "Z"
      assertTrue(engine.enqueue(2L, input.toByteArray(StandardCharsets.UTF_8)))
      val frame = awaitRows(engine, listOf("YZcdefgh", ""))
      assertEquals(0, frame.lines[0].flags[1])
      assertEquals(listOf("aGk="), synchronized(clipboard) { clipboard.toList() })
    } finally {
      engine.close()
    }
  }

  /**
   * Every cell-mutation path must mark its row dirty, or a clean row's cached
   * snapshot is republished stale. Each step lands on rows that were already
   * published, and the frame must equal a fresh engine replaying the whole
   * stream at once.
   */
  @Test
  fun incrementalPublishesMatchAFreshReplay() {
    val steps = listOf(
      "\u4e2d\u6587ab\r\n\u001b(0lqk\u001b(B\r\ne\u0301x\r\n0123456",
      // Overwrite a wide character's right half: its lead is blanked.
      "\u001b[1;2HX",
      // ECH on a wide continuation blanks the lead too.
      "\u001b[1;4H\u001b[1X",
      // DCH on a row of line-drawing characters.
      "\u001b[2;1H\u001b[P",
      // A combining mark joins a clean row's cell.
      "\u001b[3;3H\u0301",
      // IRM inserts two cells for a wide character.
      "\u001b[4h\u001b[3;1H\u65e5\u001b[4l",
      // A wide character at the last column blanks the skipped cell.
      "\u001b[4;8H\u001b[44m\u4e2d\u001b[0m",
      // Alternate screen filled with the current background, then left.
      "\u001b[41m\u001b[?1049h\u001b[0mA\u001b[?1049l",
      // IL inside a region, then EL1 and ICH on published rows.
      "\u001b[2;3r\u001b[2;1H\u001b[L\u001b[r\u001b[4;3H\u001b[1K\u001b[1;1H\u001b[2@",
      // C1 NEL, DECALN, then SO with DEC graphics in G1.
      "\u0085Z\u001b#8\u001b)0\u000eq\u000f",
    )
    val incremental = SettlingEngine(4, 8)
    try {
      val stream = StringBuilder()
      steps.forEachIndexed { index, step ->
        stream.append(step)
        val published = incremental.settle(step)
        val fresh = SettlingEngine(4, 8)
        try {
          assertFramesEqual("after step $index", fresh.settle(stream.toString()), published)
        } finally {
          fresh.close()
        }
      }
    } finally {
      incremental.close()
    }
  }

  @Test
  fun defaultsEmptyMissingAndOverflowingCsiParameters() {
    val engine = SettlingEngine(4, 12)
    try {
      // Overflowing numbers read as missing (the default), not as clamped values.
      val frame = engine.settle(
        "\u001b[;5HA\u001b[3;HB\u001b[99999999999;2HC\u001b[2;1H\u001b[0CD\u001b[99999CE",
      )
      assertEquals(listOf(" C  A", " D         E", "B", ""), rowTexts(frame))
    } finally {
      engine.close()
    }
  }

  @Test
  fun readsMissingOverflowingAndSignedSgrParameters() {
    val engine = SettlingEngine(2, 8)
    try {
      val row = engine.settle(
        "\u001b[31;99999999999mA" +
          "\u001b[31m\u001b[-2147483648mB" +
          "\u001b[;1mC" +
          "\u001b[0m\u001b[38;2;-5;300;+7mD" +
          "\u001b[0m\u001b[38;5;mE",
      ).lines[0]
      assertEquals("A", row.text[0])
      // An overflow is a missing parameter, which is 0 (reset).
      assertEquals(NativeTerminalEngine.DEFAULT_FOREGROUND, row.foreground[0])
      // Int.MIN_VALUE is a real (unknown) attribute, not a missing one.
      assertEquals(0xFFCD0000.toInt(), row.foreground[1])
      assertEquals(NativeTerminalEngine.FLAG_BOLD, row.flags[2])
      assertEquals(NativeTerminalEngine.DEFAULT_FOREGROUND, row.foreground[2])
      assertEquals(0xFF00FF07.toInt(), row.foreground[3])
      assertEquals(NativeTerminalEngine.DEFAULT_FOREGROUND, row.foreground[4])
      assertEquals(0, row.flags[4])
    } finally {
      engine.close()
    }
  }

  @Test
  fun parsesColonAndTruncatedLegacyExtendedColours() {
    val engine = SettlingEngine(2, 8)
    try {
      val row = engine.settle(
        "\u001b[48:2:1:10:20:30mA" +
          "\u001b[0m\u001b[58:2::1:2:3;1mB" +
          "\u001b[0m\u001b[58;5;9;3mC" +
          "\u001b[0m\u001b[48;2;1;2mD" +
          "\u001b[0m\u001b[38:2:1:2:3mE",
      ).lines[0]
      assertEquals(0xFF0A141E.toInt(), row.background[0])
      assertEquals(NativeTerminalEngine.FLAG_BOLD, row.flags[1])
      assertEquals(NativeTerminalEngine.DEFAULT_FOREGROUND, row.foreground[1])
      assertEquals(NativeTerminalEngine.FLAG_ITALIC, row.flags[2])
      assertEquals(NativeTerminalEngine.DEFAULT_FOREGROUND, row.foreground[2])
      // A truncated legacy colour sets nothing and swallows its arguments.
      assertEquals(NativeTerminalEngine.DEFAULT_BACKGROUND, row.background[3])
      assertEquals(0, row.flags[3])
      // Colon form without the colour-space slot: 38:2:r:g:b.
      assertEquals(0xFF010203.toInt(), row.foreground[4])
    } finally {
      engine.close()
    }
  }

  @Test
  fun routesIntermediatesAndPrivateMarkers() {
    val replies = mutableListOf<String>()
    val replying = NativeTerminalEngine("s-native-csi-routing", 2, 8) { _, bytes ->
      synchronized(replies) { replies += String(bytes, StandardCharsets.UTF_8) }
    }
    try {
      // DECRQM needs `?`, `$` and a body that is exactly one number.
      val input = "\u001b[?2026;1\$p\u001b[?2026:1\$p\u001b[2026\$p\u001b[?+2026\$p\u001b[5n"
      assertTrue(replying.enqueue(1L, input.toByteArray(StandardCharsets.UTF_8)))
      awaitFrame(replying) { synchronized(replies) { replies.lastOrNull() == "\u001b[0n" } }
      assertEquals(listOf("\u001b[?2026;2\$y", "\u001b[0n"), synchronized(replies) { replies.toList() })
    } finally {
      replying.close()
    }

    val engine = SettlingEngine(2, 8)
    try {
      // DECSTR (`!p`) only without a marker; private, `>`, `=` and
      // intermediate CSI C must not move the cursor.
      val row = engine.settle(
        "\u001b[1;31m\u001b[!pA\u001b[1m\u001b[?!pB\u001b[0m\u001b[?2CC\u001b[>2CD\u001b[=2CE\u001b[2 CF",
      ).lines[0]
      assertEquals("ABCDEF", row.text.joinToString("").trimEnd())
      assertEquals(0, row.flags[0])
      assertEquals(NativeTerminalEngine.DEFAULT_FOREGROUND, row.foreground[0])
      assertEquals(NativeTerminalEngine.FLAG_BOLD, row.flags[1])
    } finally {
      engine.close()
    }
  }

  @Test
  fun ignoresCsiParametersPastTheThirtySecond() {
    val engine = SettlingEngine(2, 8)
    try {
      val row = engine.settle("\u001b[${"0;".repeat(32)}1mA\u001b[0m\u001b[${"0;".repeat(31)}1mB").lines[0]
      assertEquals(0, row.flags[0])
      assertEquals(NativeTerminalEngine.FLAG_BOLD, row.flags[1])
      assertTrue(engine.settle("\u001b[?${"0;".repeat(32)}25l").cursorVisible)
      assertFalse(engine.settle("\u001b[?${"0;".repeat(31)}25l").cursorVisible)
    } finally {
      engine.close()
    }
  }

  /** Feeds output and waits for the publish that follows a DSR 5 sentinel. */
  private class SettlingEngine(rows: Int, columns: Int) {
    private val publishes = java.util.concurrent.atomic.AtomicInteger(0)
    private val publishesAtSentinel = java.util.concurrent.atomic.AtomicInteger(-1)
    private var seq = 0L
    private val engine = NativeTerminalEngine("s-native-settle", rows, columns) { _, bytes ->
      if (String(bytes, StandardCharsets.UTF_8) == "\u001b[0n") publishesAtSentinel.set(publishes.get())
    }

    init {
      engine.addListener { publishes.incrementAndGet() }
    }

    fun settle(input: String): NativeTerminalEngine.Frame {
      publishesAtSentinel.set(-1)
      assertTrue(engine.enqueue(++seq, input.toByteArray(StandardCharsets.UTF_8)))
      assertTrue(engine.enqueue(++seq, "\u001b[5n".toByteArray(StandardCharsets.UTF_8)))
      val deadline = System.nanoTime() + 2_000_000_000L
      while (System.nanoTime() < deadline) {
        val mark = publishesAtSentinel.get()
        if (mark >= 0 && publishes.get() > mark) return engine.currentFrame()
        Thread.sleep(2L)
      }
      throw AssertionError("native terminal frame was not published before the deadline")
    }

    fun close() = engine.close()
  }

  private fun assertFramesEqual(
    message: String,
    expected: NativeTerminalEngine.Frame,
    actual: NativeTerminalEngine.Frame,
  ) {
    assertEquals("$message: rows", rowTexts(expected), rowTexts(actual))
    assertEquals("$message: cursor", expected.cursorRow to expected.cursorColumn, actual.cursorRow to actual.cursorColumn)
    assertEquals("$message: alternate", expected.alternate, actual.alternate)
    expected.lines.zip(actual.lines).forEachIndexed { row, (want, got) ->
      assertEquals("$message: row $row text", want.text.toList(), got.text.toList())
      assertEquals("$message: row $row width", want.width.toList(), got.width.toList())
      assertEquals("$message: row $row fg", want.foreground.toList(), got.foreground.toList())
      assertEquals("$message: row $row bg", want.background.toList(), got.background.toList())
      assertEquals("$message: row $row flags", want.flags.toList(), got.flags.toList())
    }
  }

  private fun rowTexts(frame: NativeTerminalEngine.Frame): List<String> =
    frame.lines.map { it.text.joinToString("").trimEnd() }

  private fun awaitRows(engine: NativeTerminalEngine, expected: List<String>): NativeTerminalEngine.Frame = try {
    awaitFrame(engine) { rowTexts(it) == expected }
  } catch (error: AssertionError) {
    throw AssertionError("expected rows $expected but saw ${rowTexts(engine.currentFrame())}", error)
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

  private fun awaitReply(replies: MutableList<String>): String {
    val deadline = System.nanoTime() + 1_000_000_000L
    while (System.nanoTime() < deadline) {
      synchronized(replies) {
        replies.firstOrNull()?.let { return it }
      }
      Thread.sleep(5L)
    }
    throw AssertionError("native terminal reply was not emitted before the deadline")
  }
}
