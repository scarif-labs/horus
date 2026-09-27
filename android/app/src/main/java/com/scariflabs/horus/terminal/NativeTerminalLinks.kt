package com.scariflabs.horus.terminal

import java.util.concurrent.atomic.AtomicLong
import java.util.regex.Pattern

/**
 * Trusted-URL detection for one native terminal row. The rules mirror
 * src/terminal/terminalLinks.ts (`findTrustedTerminalLinks` and
 * `isTrustedTerminalUrl`): HTTP(S) only, a boundary before the scheme,
 * trailing prose punctuation and unbalanced closing brackets trimmed, and only
 * local-development or named provider hosts.
 *
 * Ranges are computed once when a [NativeTerminalEngine.FrameRow] is built.
 * A row without a literal "://" returns [NONE] after one allocation-free cell
 * scan and never reaches the regex.
 *
 * [detect] handles one row; a match that runs into the row's last cell is
 * assumed to continue and is skipped there. [rowLinks] and [urlAt] join rows
 * that run edge to edge into one logical line (a long URL wrapped either by
 * the terminal or by the app), so wrapped URLs are underlined and tappable.
 */
internal object NativeTerminalLinks {
  /** Shared empty ranges for rows without links. Never mutate. */
  @JvmField val NONE = IntArray(0)

  /** Counts regex scans; tests use it to prove URL-free rows skip the regex. */
  internal val regexScans = AtomicLong()

  private val HTTP_LINK_PATTERN: Pattern = Pattern.compile("https?://[^\\s\\u00a0<>\"'`]+", Pattern.CASE_INSENSITIVE)
  private const val URL_BOUNDARIES = "\"'()<>{},;:=[]"
  private const val TRAILING_PUNCTUATION = ".,;:!?"
  private val BRACKET_PAIRS = arrayOf('(' to ')', '[' to ']', '{' to '}')
  private val AUTHORITY_PATTERN: Pattern = Pattern.compile("^https?://([^/?#]+)", Pattern.CASE_INSENSITIVE)
  private val IPV6_PATTERN: Pattern = Pattern.compile("^(\\[[0-9a-f:]+\\])(?::(\\d{1,5}))?$", Pattern.CASE_INSENSITIVE)
  private val HOST_PATTERN: Pattern = Pattern.compile("^([a-z0-9.-]+)(?::(\\d{1,5}))?$", Pattern.CASE_INSENSITIVE)
  private val LABEL_PATTERN: Pattern = Pattern.compile("^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$", Pattern.CASE_INSENSITIVE)
  private val TRUSTED_DOMAIN_ROOTS = arrayOf(
    "github.com",
    "anthropic.com",
    "claude.ai",
    "claude.com",
    "openai.com",
    "chatgpt.com",
    "opencode.ai",
  )

  /**
   * Returns link ranges as [start, end) column pairs, or [NONE]. [text] and
   * [width] are one row's cells; a wide glyph's continuation cell has width 0
   * and empty text.
   */
  fun detect(text: Array<String>, width: ByteArray): IntArray {
    if (!mayContainUrl(text)) return NONE
    regexScans.incrementAndGet()
    val rowText = StringBuilder(text.size)
    // charColumn[i] is the cell column that owns character i of rowText.
    var charColumn = IntArray(text.size)
    for (column in text.indices) {
      val cell = text[column]
      for (index in cell.indices) {
        if (rowText.length == charColumn.size) charColumn = charColumn.copyOf(charColumn.size * 2)
        charColumn[rowText.length] = column
        rowText.append(cell[index])
      }
    }
    var ranges = NONE
    var count = 0
    val matcher = HTTP_LINK_PATTERN.matcher(rowText)
    var from = 0
    while (from <= rowText.length && matcher.find(from)) {
      val startIndex = matcher.start()
      from = matcher.end()
      // A match that reaches the right edge is probably soft-wrapped.
      if (matcher.end() == rowText.length) continue
      if (startIndex > 0 && !isUrlBoundary(rowText[startIndex - 1])) continue
      val endIndex = startIndex + trimmedLength(rowText, startIndex, matcher.end())
      if (endIndex <= startIndex || !isTrustedUrl(rowText.substring(startIndex, endIndex))) continue
      from = endIndex
      val lastColumn = charColumn[endIndex - 1]
      val startColumn = charColumn[startIndex]
      val endColumn = lastColumn + maxOf(1, width[lastColumn].toInt())
      if (count + 2 > ranges.size) ranges = ranges.copyOf(maxOf(4, ranges.size * 2))
      ranges[count] = startColumn
      ranges[count + 1] = endColumn
      count += 2
    }
    return when (count) {
      0 -> NONE
      ranges.size -> ranges
      else -> ranges.copyOf(count)
    }
  }

  /** Longest run of edge-to-edge rows joined into one logical line. */
  private const val MAX_WRAPPED_ROWS = 32

  /**
   * Link ranges drawn on [row]. Rows that are not part of a wrapped logical
   * line use their cached single-row ranges; wrapped lines are detected once
   * and cached by row identity in [cache] (rows are immutable and shared
   * across frames, so identity is a safe key).
   */
  fun rowLinks(lines: Array<NativeTerminalEngine.FrameRow>, row: Int, cache: WrappedLinkCache): IntArray {
    if (!continuesToNext(lines, row - 1) && !continuesToNext(lines, row)) return lines[row].links
    val first = logicalStart(lines, row)
    val last = logicalEnd(lines, first)
    if (first == last) return lines[row].links
    return cache.rangesFor(lines, first, last)[row - first]
  }

  /** Returns the URL covering ([row], [column]), following wrapped rows. */
  fun urlAt(lines: Array<NativeTerminalEngine.FrameRow>, row: Int, column: Int): String? {
    if (row !in lines.indices) return null
    val first = logicalStart(lines, row)
    val last = logicalEnd(lines, first)
    if (first == last) return urlAt(lines[row], column)
    val joined = join(lines, first, last)
    var offset = 0
    for (index in first until row) offset += lines[index].columns
    val target = offset + column
    val ranges = detect(joined.text, joined.width)
    var index = 0
    while (index < ranges.size) {
      if (target in ranges[index] until ranges[index + 1]) {
        val url = StringBuilder(ranges[index + 1] - ranges[index])
        for (cell in ranges[index] until ranges[index + 1]) url.append(joined.text[cell])
        return url.toString()
      }
      index += 2
    }
    return null
  }

  /** Per-row link ranges for wrapped logical lines, cached by row identity. Not thread-safe. */
  internal class WrappedLinkCache {
    private class Entry(val rows: Array<NativeTerminalEngine.FrameRow>, val ranges: Array<IntArray>)
    private val entries = java.util.IdentityHashMap<NativeTerminalEngine.FrameRow, Entry>()

    fun rangesFor(lines: Array<NativeTerminalEngine.FrameRow>, first: Int, last: Int): Array<IntArray> {
      val cached = entries[lines[first]]
      if (cached != null && cached.rows.size == last - first + 1 &&
        cached.rows.indices.all { cached.rows[it] === lines[first + it] }
      ) return cached.ranges
      if (entries.size >= MAX_CACHED_LINES) entries.clear()
      val rows = Array(last - first + 1) { lines[first + it] }
      val joined = join(lines, first, last)
      val ranges = splitRanges(rows, detect(joined.text, joined.width))
      entries[lines[first]] = Entry(rows, ranges)
      return ranges
    }

    private companion object {
      const val MAX_CACHED_LINES = 64
    }
  }

  private class Joined(val text: Array<String>, val width: ByteArray)

  private fun join(lines: Array<NativeTerminalEngine.FrameRow>, first: Int, last: Int): Joined {
    var total = 0
    for (index in first..last) total += lines[index].columns
    val text = arrayOfNulls<String>(total)
    val width = ByteArray(total)
    var offset = 0
    for (index in first..last) {
      val line = lines[index]
      for (column in 0 until line.columns) {
        text[offset + column] = line.text[column]
        width[offset + column] = line.width[column]
      }
      offset += line.columns
    }
    @Suppress("UNCHECKED_CAST")
    return Joined(text as Array<String>, width)
  }

  /** Splits joined-line column ranges back into per-row [start, end) pairs. */
  private fun splitRanges(rows: Array<NativeTerminalEngine.FrameRow>, joined: IntArray): Array<IntArray> {
    val perRow = Array(rows.size) { ArrayList<Int>() }
    var index = 0
    while (index < joined.size) {
      var rowStart = 0
      for (rowIndex in rows.indices) {
        val rowEnd = rowStart + rows[rowIndex].columns
        val start = maxOf(joined[index], rowStart)
        val end = minOf(joined[index + 1], rowEnd)
        if (start < end) {
          perRow[rowIndex].add(start - rowStart)
          perRow[rowIndex].add(end - rowStart)
        }
        rowStart = rowEnd
      }
      index += 2
    }
    return Array(rows.size) { if (perRow[it].isEmpty()) NONE else perRow[it].toIntArray() }
  }

  /** True when [row] runs to its last cell and the next row starts with text. */
  private fun continuesToNext(lines: Array<NativeTerminalEngine.FrameRow>, row: Int): Boolean {
    if (row < 0 || row + 1 >= lines.size) return false
    val current = lines[row]
    val next = lines[row + 1]
    if (current.columns == 0 || next.columns == 0) return false
    val lastCell = current.text[current.columns - 1]
    val lastFilled = (lastCell.isEmpty() && current.width[current.columns - 1].toInt() == 0) || lastCell.isNotBlank()
    return lastFilled && next.text[0].isNotBlank()
  }

  private fun logicalStart(lines: Array<NativeTerminalEngine.FrameRow>, row: Int): Int {
    var first = row
    while (first > 0 && row - first < MAX_WRAPPED_ROWS - 1 && continuesToNext(lines, first - 1)) first -= 1
    return first
  }

  private fun logicalEnd(lines: Array<NativeTerminalEngine.FrameRow>, first: Int): Int {
    var last = first
    while (last - first < MAX_WRAPPED_ROWS - 1 && continuesToNext(lines, last)) last += 1
    return last
  }

  /** Returns the URL whose range covers [column] in [row], or null. */
  fun urlAt(row: NativeTerminalEngine.FrameRow, column: Int): String? {
    val links = row.links
    var index = 0
    while (index < links.size) {
      val start = links[index]
      val end = links[index + 1]
      if (column in start until end) {
        val url = StringBuilder(end - start)
        for (cell in start until minOf(end, row.columns)) url.append(row.text[cell])
        return url.toString()
      }
      index += 2
    }
    return null
  }

  /** Allocation-free pre-check for a literal "://" across adjacent cells. */
  private fun mayContainUrl(text: Array<String>): Boolean {
    for (column in 0 until text.size - 2) {
      if (isChar(text[column], ':') && isChar(text[column + 1], '/') && isChar(text[column + 2], '/')) return true
    }
    return false
  }

  private fun isChar(cell: String, expected: Char): Boolean = cell.length == 1 && cell[0] == expected

  private fun isUrlBoundary(character: Char): Boolean =
    Character.isWhitespace(character) || Character.isSpaceChar(character) || URL_BOUNDARIES.indexOf(character) >= 0

  /** Mirrors trimTerminalPunctuation; returns the kept length of [start, end). */
  private fun trimmedLength(text: CharSequence, start: Int, end: Int): Int {
    var length = end - start
    while (length > 0 && TRAILING_PUNCTUATION.indexOf(text[start + length - 1]) >= 0) length -= 1
    for ((open, close) in BRACKET_PAIRS) {
      while (length > 0 && text[start + length - 1] == close) {
        var opening = 0
        var closing = 0
        for (index in start until start + length) {
          when (text[index]) {
            open -> opening += 1
            close -> closing += 1
          }
        }
        if (closing <= opening) break
        length -= 1
      }
    }
    return length
  }

  /** Mirrors isTrustedTerminalUrl in src/terminal/terminalLinks.ts. */
  internal fun isTrustedUrl(value: String): Boolean {
    if (value.any { it.code < 0x20 || it.code == 0x7f }) return false
    val authorityMatch = AUTHORITY_PATTERN.matcher(value)
    if (!authorityMatch.find()) return false
    val authority = authorityMatch.group(1) ?: return false
    if (authority.contains('@')) return false

    val ipv6 = IPV6_PATTERN.matcher(authority)
    if (ipv6.matches()) {
      if (!isValidPort(ipv6.group(2))) return false
      val hostname = ipv6.group(1)!!.let { it.substring(1, it.length - 1) }.lowercase()
      return hostname == "::1"
    }

    val host = HOST_PATTERN.matcher(authority)
    if (!host.matches() || !isValidPort(host.group(2))) return false
    val hostname = host.group(1)!!.removeSuffix(".").lowercase()
    if (hostname.split('.').any { !LABEL_PATTERN.matcher(it).matches() }) return false
    if (hostname == "localhost" || hostname.endsWith(".localhost") || hostname == "127.0.0.1") return true
    return TRUSTED_DOMAIN_ROOTS.any { hostname == it || hostname.endsWith(".$it") }
  }

  private fun isValidPort(port: String?): Boolean {
    if (port == null) return true
    val value = port.toIntOrNull() ?: return false
    return value in 1..65_535
  }
}
