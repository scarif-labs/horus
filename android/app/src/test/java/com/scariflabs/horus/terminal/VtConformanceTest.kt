package com.scariflabs.horus.terminal

import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.fail
import org.junit.Test

/**
 * Differential conformance against xterm.js (headless).
 *
 * Replays every case of `vt-conformance/cases.json` through
 * [NativeTerminalEngine] and compares the published frame with
 * `vt-conformance/expected.json`, which
 * `scripts/vt-conformance/generate-expected.mjs` produces from
 * `@xterm/headless`. The script header documents the field mapping.
 *
 * Compared per case: alternate screen, cursor visibility, cursor row/column
 * (scrollback rows included), row count (scrollback + screen), and per cell
 * text, width, attribute flags and inverse-resolved colours. A blank cell's
 * foreground is not compared (xterm erases with the current background only;
 * the foreground of a blank, non-underlined cell is never drawn).
 *
 * All mismatches are collected into one report and the test fails once.
 * Regenerate the expectations after editing the corpus:
 *   node scripts/vt-conformance/generate-expected.mjs
 */
class VtConformanceTest {
  private class Case(
    val id: String,
    val rows: Int,
    val columns: Int,
    val input: String,
    val splitAt: List<Int>,
    val note: String?,
  )

  private class ExpectedCell(val text: String, val width: Int, val foreground: Int, val background: Int, val flags: Int)

  @Test
  fun matchesXtermHeadlessReference() {
    val corpus = loadJson("vt-conformance/cases.json").getJSONArray("cases")
    val expectedCases = loadJson("vt-conformance/expected.json").getJSONObject("cases")
    val cases = (0 until corpus.length()).map { parseCase(corpus.getJSONObject(it)) }

    val failures = mutableListOf<String>()
    val allowed = mutableListOf<String>()
    val staleAllowlist = mutableListOf<String>()
    ALLOWLIST.keys.filter { id -> cases.none { it.id == id } }.forEach { staleAllowlist += "$it (no such case)" }

    for (case in cases) {
      val expected = expectedCases.optJSONObject(case.id)
      if (expected == null || expected.getString("input") != case.input ||
        jsonInts(expected.getJSONArray("splitAt")) != case.splitAt
      ) {
        failures += "[${case.id}] expected.json is missing or stale for this case; " +
          "run: node scripts/vt-conformance/generate-expected.mjs"
        continue
      }
      val (frame, sentinelReached) = replay(case)
      val diffs = compare(expected, frame, case.columns).toMutableList()
      if (!sentinelReached) {
        diffs.add(0, "engine never answered the trailing CSI 5 n sentinel (parser left in a string state?)")
      }
      val reason = ALLOWLIST[case.id]
      when {
        reason != null && diffs.isEmpty() -> staleAllowlist += "${case.id} (now matches; remove it)"
        reason != null -> allowed += "[${case.id}] ${diffs.size} difference(s), allowlisted: $reason"
        diffs.isNotEmpty() -> failures += report(case, expected, frame, diffs)
      }
    }

    if (failures.isEmpty() && staleAllowlist.isEmpty()) return
    val message = buildString {
      append("${failures.size} of ${cases.size} VT conformance cases diverge from @xterm/headless")
      append(" (${allowed.size} more allowlisted)\n")
      if (staleAllowlist.isNotEmpty()) append("\nStale allowlist entries:\n  ${staleAllowlist.joinToString("\n  ")}\n")
      failures.forEach { append('\n').append(it).append('\n') }
      if (allowed.isNotEmpty()) append("\nAllowlisted:\n  ${allowed.joinToString("\n  ")}\n")
    }
    fail(message)
  }

  /**
   * Feeds the case in its chunks, then a DSR 5 sentinel as a final chunk.
   * Its reply is written on the parser thread before the batch holding it is
   * published, so the first publish after the reply includes the whole case.
   */
  private fun replay(case: Case): Pair<NativeTerminalEngine.Frame, Boolean> {
    val publishes = AtomicInteger(0)
    val publishesAtSentinel = AtomicInteger(-1)
    val engine = NativeTerminalEngine("vt-${case.id}", case.rows, case.columns) { _, bytes ->
      if (String(bytes, StandardCharsets.UTF_8) == "\u001b[0n") publishesAtSentinel.set(publishes.get())
    }
    try {
      engine.addListener { publishes.incrementAndGet() }
      val bytes = case.input.toByteArray(StandardCharsets.UTF_8)
      val bounds = listOf(0) + case.splitAt + listOf(bytes.size)
      var seq = 1L
      for (index in 0 until bounds.size - 1) {
        check(engine.enqueue(seq++, bytes.copyOfRange(bounds[index], bounds[index + 1])))
      }
      check(engine.enqueue(seq, "\u001b[5n".toByteArray(StandardCharsets.UTF_8)))
      val deadline = System.nanoTime() + 3_000_000_000L
      while (System.nanoTime() < deadline) {
        val mark = publishesAtSentinel.get()
        if (mark >= 0 && publishes.get() > mark) return engine.currentFrame() to true
        Thread.sleep(2L)
      }
      return engine.currentFrame() to false
    } finally {
      engine.close()
    }
  }

  private fun compare(expected: JSONObject, frame: NativeTerminalEngine.Frame, columns: Int): List<String> {
    val diffs = mutableListOf<String>()
    fun check(name: String, want: Any, got: Any) {
      if (want != got) diffs += "$name: expected $want, actual $got"
    }
    check("alternate", expected.getBoolean("alternate"), frame.alternate)
    check("cursorVisible", expected.getBoolean("cursorVisible"), frame.cursorVisible)
    val wantCursor = expected.getInt("cursorRow") to expected.getInt("cursorColumn")
    val gotCursor = frame.cursorRow to frame.cursorColumn
    if (wantCursor != gotCursor) {
      val pending = if (expected.getBoolean("pendingWrap")) " (xterm: wrap pending)" else ""
      diffs += "cursor (row, col): expected $wantCursor$pending, actual $gotCursor"
    }
    val lines = expected.getJSONArray("lines")
    check("rows (scrollback + screen)", lines.length(), frame.lines.size)

    val textDiffs = mutableListOf<String>()
    val widthDiffs = mutableListOf<String>()
    val attrDiffs = mutableListOf<String>()
    for (row in 0 until minOf(lines.length(), frame.lines.size)) {
      val want = expectedCells(lines.getJSONObject(row), columns)
      val got = frame.lines[row]
      for (column in 0 until columns) {
        val cell = want[column]
        val at = "row $row col $column"
        if (column >= got.columns) {
          textDiffs += "$at: expected ${quote(cell.text)}, actual <no cell>"
          continue
        }
        if (cell.text != got.text[column]) {
          textDiffs += "$at: expected ${quote(cell.text)}, actual ${quote(got.text[column])}"
        }
        if (cell.width != got.width[column].toInt()) {
          widthDiffs += "$at: expected ${cell.width}, actual ${got.width[column]}"
        }
        val attrs = mutableListOf<String>()
        if (cell.flags != got.flags[column]) attrs += "flags ${flagNames(cell.flags)} vs ${flagNames(got.flags[column])}"
        if (cell.background != got.background[column]) attrs += "bg ${hex(cell.background)} vs ${hex(got.background[column])}"
        val foregroundVisible = cell.text.isNotBlank() ||
          cell.flags and (NativeTerminalEngine.FLAG_UNDERLINE or NativeTerminalEngine.FLAG_STRIKETHROUGH) != 0
        if (foregroundVisible && cell.foreground != got.foreground[column]) {
          attrs += "fg ${hex(cell.foreground)} vs ${hex(got.foreground[column])}"
        }
        if (attrs.isNotEmpty()) attrDiffs += "$at (${quote(cell.text)}): ${attrs.joinToString(", ")} (expected vs actual)"
      }
    }
    fun summarize(kind: String, list: List<String>) {
      if (list.isEmpty()) return
      diffs += "$kind: ${list.size} cell(s) differ; first: ${list.first()}"
    }
    summarize("text", textDiffs)
    summarize("width", widthDiffs)
    summarize("attributes", attrDiffs)
    return diffs
  }

  private fun expectedCells(line: JSONObject, columns: Int): List<ExpectedCell> {
    val cells = line.getJSONArray("cells")
    val widths = line.getString("widths")
    val foreground = arrayOfNulls<Any>(columns)
    val background = arrayOfNulls<Any>(columns)
    val flags = IntArray(columns)
    val inverse = BooleanArray(columns)
    val runs = line.getJSONArray("attrs")
    for (index in 0 until runs.length()) {
      val run = runs.getJSONObject(index)
      val runFlags = run.getJSONArray("flags")
      var mask = 0
      var runInverse = false
      for (flag in 0 until runFlags.length()) {
        when (val name = runFlags.getString(flag)) {
          "inverse" -> runInverse = true
          else -> mask = mask or (FLAG_BY_NAME[name] ?: error("unknown flag $name"))
        }
      }
      for (column in run.getInt("from") until run.getInt("to")) {
        foreground[column] = run.get("fg")
        background[column] = run.get("bg")
        flags[column] = mask
        inverse[column] = runInverse
      }
    }
    return (0 until columns).map { column ->
      val fg = colorOf(foreground[column], NativeTerminalEngine.DEFAULT_FOREGROUND)
      val bg = colorOf(background[column], NativeTerminalEngine.DEFAULT_BACKGROUND)
      ExpectedCell(
        text = cells.getString(column),
        width = widths[column].digitToInt(),
        foreground = if (inverse[column]) bg else fg,
        background = if (inverse[column]) fg else bg,
        flags = flags[column],
      )
    }
  }

  private fun colorOf(value: Any?, default: Int): Int = when (value) {
    null, "default" -> default
    is Number -> value.toInt()
    else -> error("unexpected colour $value")
  }

  private fun report(case: Case, expected: JSONObject, frame: NativeTerminalEngine.Frame, diffs: List<String>): String =
    buildString {
      append("[${case.id}] ${case.rows}x${case.columns}")
      case.note?.let { append(" -- ").append(it) }
      append("\n  input: ").append(escape(case.input))
      if (case.splitAt.isNotEmpty()) append("  (split at bytes ${case.splitAt})")
      diffs.forEach { append("\n  - ").append(it) }
      val lines = expected.getJSONArray("lines")
      val want = (0 until lines.length()).map { rowString(jsonStrings(lines.getJSONObject(it).getJSONArray("cells"))) }
      val got = frame.lines.map { rowString(it.text.toList()) }
      val width = (want.map { it.length } + listOf(8)).max()
      append("\n  ").append("xterm".padEnd(width + 2)).append("   engine")
      for (row in 0 until maxOf(want.size, got.size)) {
        val left = want.getOrNull(row) ?: ""
        val right = got.getOrNull(row) ?: ""
        val marker = if (left == right) "   " else " ! "
        append("\n  ").append(left.padEnd(width + 2)).append(marker).append(right)
      }
      append("\n  cursor: xterm (${expected.getInt("cursorRow")}, ${expected.getInt("cursorColumn")})")
      append(", engine (${frame.cursorRow}, ${frame.cursorColumn})")
    }

  /** `|` delimits the row; `.` marks a blank cell so spacing is visible. */
  private fun rowString(cells: List<String>): String =
    "|" + cells.joinToString("") { if (it == " ") "." else it } + "|"

  private fun parseCase(json: JSONObject): Case = Case(
    id = json.getString("id"),
    rows = json.getInt("rows"),
    columns = json.getInt("cols"),
    input = json.getString("input"),
    splitAt = json.optJSONArray("splitAt")?.let(::jsonInts) ?: emptyList(),
    note = json.optString("note", "").ifEmpty { null },
  )

  private fun loadJson(resource: String): JSONObject {
    val stream = javaClass.classLoader?.getResourceAsStream(resource)
      ?: error("missing test resource $resource")
    return stream.use { JSONObject(String(it.readBytes(), StandardCharsets.UTF_8)) }
  }

  private fun jsonInts(array: JSONArray): List<Int> = (0 until array.length()).map(array::getInt)

  private fun jsonStrings(array: JSONArray): List<String> = (0 until array.length()).map(array::getString)

  private fun quote(value: String): String = "\"" + escape(value) + "\""

  private fun escape(value: String): String = buildString {
    var index = 0
    while (index < value.length) {
      val codePoint = value.codePointAt(index)
      index += Character.charCount(codePoint)
      when {
        codePoint == 0x1b -> append("\\e")
        codePoint == '\r'.code -> append("\\r")
        codePoint == '\n'.code -> append("\\n")
        codePoint == '\t'.code -> append("\\t")
        codePoint == '\b'.code -> append("\\b")
        codePoint < 0x20 || codePoint in 0x7f..0x9f -> append(String.format("\\x%02x", codePoint))
        Character.getType(codePoint) == Character.NON_SPACING_MARK.toInt() ||
          Character.getType(codePoint) == Character.FORMAT.toInt() -> append(String.format("\\u{%x}", codePoint))
        else -> appendCodePoint(codePoint)
      }
    }
  }

  private fun hex(value: Int): String = String.format("#%08X", value)

  private fun flagNames(flags: Int): String =
    FLAG_BY_NAME.filter { (_, bit) -> flags and bit != 0 }.keys.joinToString("+").ifEmpty { "none" }

  private companion object {
    val FLAG_BY_NAME = linkedMapOf(
      "bold" to NativeTerminalEngine.FLAG_BOLD,
      "italic" to NativeTerminalEngine.FLAG_ITALIC,
      "dim" to NativeTerminalEngine.FLAG_DIM,
      "underline" to NativeTerminalEngine.FLAG_UNDERLINE,
      "strikethrough" to NativeTerminalEngine.FLAG_STRIKETHROUGH,
      "invisible" to NativeTerminalEngine.FLAG_INVISIBLE,
    )

    /**
     * Deliberate, known gaps only. Each entry must still diverge; an entry
     * that starts matching fails the test so it gets removed.
     */
    val ALLOWLIST = mapOf(
      // DECAWM (CSI ?7l) is not implemented: the engine always autowraps.
      "decawm-off" to "DECAWM not implemented",
      "decawm-off-then-on" to "DECAWM not implemented",
      // REP (CSI b) is not implemented.
      "rep-basic" to "REP not implemented",
      "rep-wraps" to "REP not implemented",
    )
  }
}
