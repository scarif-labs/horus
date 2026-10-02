package com.scariflabs.horus.terminal

import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Native terminal state used by interactive harness surfaces.
 *
 * The service still owns the PTY. Ordered output arrives in the RN process
 * through the existing Messenger boundary, but it stops at this bounded
 * parser queue instead of being base64-decoded, parsed by xterm headless, and
 * reconciled through React. The Canvas view reads immutable published frames.
 */
internal object NativeTerminalEngineRegistry {
  private val engines = ConcurrentHashMap<String, NativeTerminalEngine>()
  @Volatile private var inputWriter: ((String, ByteArray) -> Unit)? = null
  @Volatile private var clipboardWriter: ((String) -> Unit)? = null
  @Volatile private var outputGapListener: ((String) -> Unit)? = null

  fun configureInputWriter(writer: ((String, ByteArray) -> Unit)?) {
    inputWriter = writer
  }

  /** Receives OSC 52 clipboard writes as their base64 payload. */
  fun configureClipboardWriter(writer: ((String) -> Unit)?) {
    clipboardWriter = writer
  }

  /**
   * Receives the session id when an engine skips output it never saw. The caller should make the app redraw; the engine cannot recover the
   * lost escape-sequence state on its own.
   */
  fun configureOutputGapListener(listener: ((String) -> Unit)?) {
    outputGapListener = listener
  }

  fun writeInput(sessionId: String, bytes: ByteArray): Boolean {
    if (sessionId.isEmpty() || bytes.isEmpty()) return false
    val writer = inputWriter ?: return false
    return runCatching {
      writer(sessionId, bytes.copyOf())
      true
    }.getOrDefault(false)
  }

  fun ensure(sessionId: String, rows: Int, columns: Int): NativeTerminalEngine {
    val engine = obtain(sessionId, rows, columns)
    engine.resize(rows, columns)
    return engine
  }

  private fun obtain(sessionId: String, rows: Int, columns: Int): NativeTerminalEngine =
    engines.computeIfAbsent(sessionId) {
      NativeTerminalEngine(
        sessionId,
        rows,
        columns,
        clipboardWriter = { payload -> clipboardWriter?.invoke(payload) },
        onOutputGap = { outputGapListener?.invoke(sessionId) },
      ) { id, bytes ->
        inputWriter?.invoke(id, bytes)
      }
    }

  fun enqueueOutput(
    sessionId: String,
    seq: Long,
    rows: Int,
    columns: Int,
    bytes: ByteArray,
  ): Boolean {
    if (sessionId.isEmpty() || seq < 1L || bytes.isEmpty()) return false
    // The event's size is the session's launch size; the service does not
    // update it on resize. Use it only to create the engine, never to resize
    // one the canvas or resizeSession has already sized.
    return obtain(sessionId, rows, columns).enqueue(seq, bytes)
  }

  fun get(sessionId: String): NativeTerminalEngine? = engines[sessionId]

  /** Whether the app in [sessionId] asked for bracketed paste (mode 2004). */
  fun isBracketedPaste(sessionId: String): Boolean = engines[sessionId]?.bracketedPaste == true

  /** True while a canvas is subscribed to the session's frames. */
  fun isRendered(sessionId: String): Boolean = engines[sessionId]?.hasListeners() == true

  fun close(sessionId: String) {
    engines.remove(sessionId)?.close()
  }

  fun closeAll() {
    val current = engines.values.toList()
    engines.clear()
    current.forEach(NativeTerminalEngine::close)
  }
}

internal class NativeTerminalEngine(
  private val sessionId: String,
  rows: Int,
  columns: Int,
  private val clipboardWriter: (String) -> Unit = {},
  private val onOutputGap: () -> Unit = {},
  private val inputWriter: (String, ByteArray) -> Unit,
) {
  /**
   * Immutable snapshot of one row, shared by reference across published
   * frames until the row changes. Inverse video is resolved here: foreground
   * and background are already swapped and FLAG_INVERSE is cleared, so a
   * renderer draws the fields as they are. Never mutate after construction.
   */
  internal class FrameRow(
    val text: Array<String>,
    val width: ByteArray,
    val foreground: IntArray,
    val background: IntArray,
    val flags: IntArray,
  ) {
    val columns: Int get() = text.size
    val hasVisibleContent: Boolean = text.any { it.isNotBlank() }
    /**
     * Trusted single-row URLs as [start, end) column pairs, computed once per
     * row object (so shared rows never rescan). Rows without "://" skip the
     * regex and share [NativeTerminalLinks.NONE].
     */
    val links: IntArray = NativeTerminalLinks.detect(text, width)
  }

  /**
   * [lines] holds scrollback rows followed by the active screen rows. Rows
   * captured before a width change may be narrower or wider than [columns].
   */
  internal data class Frame(
    val rows: Int,
    val columns: Int,
    val contentRows: Int,
    val lines: Array<FrameRow>,
    val cursorRow: Int,
    val cursorColumn: Int,
    val cursorVisible: Boolean,
    val alternate: Boolean,
    val hasVisibleContent: Boolean,
    /** Last row (in [lines]) with visible text, or -1 when every row is blank. */
    val lastContentRow: Int,
    val mouseTracking: Boolean,
    val mouseSgr: Boolean,
  )

  private sealed interface Command {
    data class Output(val seq: Long, val bytes: ByteArray) : Command
    data class Resize(val rows: Int, val columns: Int) : Command
  }

  private enum class ParserState { NORMAL, ESC, ESC_INTERMEDIATE, CSI, CSI_IGNORE, OSC, STRING }

  /**
   * Mutable cell grid. Every cell write must mark its row dirty; a clean row
   * reuses its cached [FrameRow] on the next publish.
   */
  private class Screen(initialRows: Int, initialColumns: Int) {
    var rows: Int = boundRows(initialRows)
      private set
    var columns: Int = boundColumns(initialColumns)
      private set
    var cursorRow = 0
    var cursorColumn = 0
    var scrollTop = 0
    var scrollBottom = rows - 1
    // DECSC state is per screen, as in xterm.
    var savedRow = 0
    var savedColumn = 0
    var savedForeground = DEFAULT_FOREGROUND
    var savedBackground = DEFAULT_BACKGROUND
    var savedFlags = 0
    var savedGraphics = false
    // HTS/TBC tab stops; every 8 columns by default.
    val tabStops = BooleanArray(columns) { it % 8 == 0 }
    var texts = Array(rows * columns) { " " }
    var widths = ByteArray(rows * columns) { 1 }
    var foreground = IntArray(rows * columns) { DEFAULT_FOREGROUND }
    var background = IntArray(rows * columns) { DEFAULT_BACKGROUND }
    var flags = IntArray(rows * columns)
    // A clean row's cached snapshot matches its cells exactly.
    private val snapshots = arrayOfNulls<FrameRow>(rows)
    private val dirty = BooleanArray(rows) { true }

    fun index(row: Int, column: Int): Int = row * columns + column

    fun markDirty(row: Int) {
      if (row in 0 until rows) dirty[row] = true
    }

    fun clearAll(foregroundColor: Int, backgroundColor: Int, cellFlags: Int = 0) {
      clearRows(foregroundColor, backgroundColor, cellFlags)
      cursorRow = 0
      cursorColumn = 0
      scrollTop = 0
      scrollBottom = rows - 1
      for (column in 0 until columns) tabStops[column] = column % 8 == 0
    }

    /** DECALN: every cell becomes [text] with the given attributes. */
    fun fillText(text: String, foregroundColor: Int, backgroundColor: Int, cellFlags: Int) {
      fillRange(0, texts.size, foregroundColor, backgroundColor, cellFlags)
      texts.fill(text)
    }

    /** Next tab stop right of [column], or the last column. */
    fun nextTabStop(column: Int): Int {
      var next = column + 1
      while (next < columns && !tabStops[next]) next += 1
      return next.coerceAtMost(columns - 1)
    }

    /** Previous tab stop left of [column], or the first column. */
    fun previousTabStop(column: Int): Int {
      var previous = column - 1
      while (previous > 0 && !tabStops[previous]) previous -= 1
      return previous.coerceIn(0, columns - 1)
    }

    /** Blanks every cell, keeping the cursor, margins and saved cursor. */
    fun clearRows(foregroundColor: Int, backgroundColor: Int, cellFlags: Int = 0) {
      fillRange(0, texts.size, foregroundColor, backgroundColor, cellFlags)
    }

    /** Forgets the DECSC state (RIS). */
    fun resetSavedCursor() {
      savedRow = 0
      savedColumn = 0
      savedForeground = DEFAULT_FOREGROUND
      savedBackground = DEFAULT_BACKGROUND
      savedFlags = 0
      savedGraphics = false
    }

    fun clearRow(row: Int, from: Int, to: Int, foregroundColor: Int, backgroundColor: Int, cellFlags: Int = 0) {
      val start = from.coerceIn(0, columns)
      val end = to.coerceIn(start, columns)
      fillRange(index(row, start), index(row, end), foregroundColor, backgroundColor, cellFlags)
    }

    fun clearCell(row: Int, column: Int, foregroundColor: Int, backgroundColor: Int, cellFlags: Int = 0) {
      if (row !in 0 until rows || column !in 0 until columns) return
      val offset = index(row, column)
      dirty[row] = true
      texts[offset] = " "
      widths[offset] = 1
      foreground[offset] = foregroundColor
      background[offset] = backgroundColor
      flags[offset] = cellFlags
    }

    fun scrollUp(count: Int, foregroundColor: Int, backgroundColor: Int) {
      repeat(count.coerceIn(0, rows)) {
        for (row in scrollTop until scrollBottom) copyRow(row + 1, row)
        clearRow(scrollBottom, 0, columns, foregroundColor, backgroundColor)
      }
    }

    fun scrollDown(count: Int, foregroundColor: Int, backgroundColor: Int) {
      repeat(count.coerceIn(0, rows)) {
        for (row in scrollBottom downTo scrollTop + 1) copyRow(row - 1, row)
        clearRow(scrollTop, 0, columns, foregroundColor, backgroundColor)
      }
    }

    fun insertLines(count: Int, foregroundColor: Int, backgroundColor: Int) {
      // IL/DL are ignored outside the scroll region, as in xterm.
      if (cursorRow !in scrollTop..scrollBottom) return
      val amount = count.coerceIn(0, scrollBottom - cursorRow + 1)
      repeat(amount) {
        for (row in scrollBottom downTo cursorRow + 1) copyRow(row - 1, row)
        clearRow(cursorRow, 0, columns, foregroundColor, backgroundColor)
      }
    }

    fun deleteLines(count: Int, foregroundColor: Int, backgroundColor: Int) {
      // IL/DL are ignored outside the scroll region, as in xterm.
      if (cursorRow !in scrollTop..scrollBottom) return
      val amount = count.coerceIn(0, scrollBottom - cursorRow + 1)
      repeat(amount) {
        for (row in cursorRow until scrollBottom) copyRow(row + 1, row)
        clearRow(scrollBottom, 0, columns, foregroundColor, backgroundColor)
      }
    }

    /** Moves every row up by [count], blanking the bottom, and follows with the cursor. */
    fun shiftUp(count: Int) {
      val amount = count.coerceIn(0, rows)
      for (row in 0 until rows - amount) copyRow(row + amount, row)
      for (row in rows - amount until rows) clearRow(row, 0, columns, DEFAULT_FOREGROUND, DEFAULT_BACKGROUND)
      cursorRow = (cursorRow - amount).coerceAtLeast(0)
    }

    fun resized(newRows: Int, newColumns: Int): Screen {
      val result = Screen(newRows, newColumns)
      val copiedRows = minOf(rows, result.rows)
      val copiedColumns = minOf(columns, result.columns)
      for (row in 0 until copiedRows) {
        for (column in 0 until copiedColumns) {
          val source = index(row, column)
          val target = result.index(row, column)
          result.texts[target] = texts[source]
          result.widths[target] = widths[source]
          result.foreground[target] = foreground[source]
          result.background[target] = background[source]
          result.flags[target] = flags[source]
        }
      }
      result.cursorRow = cursorRow.coerceIn(0, result.rows - 1)
      result.cursorColumn = cursorColumn.coerceIn(0, result.columns)
      result.scrollTop = scrollTop.coerceIn(0, result.rows - 1)
      result.scrollBottom = scrollBottom.coerceIn(result.scrollTop, result.rows - 1)
      result.savedRow = savedRow.coerceIn(0, result.rows - 1)
      result.savedColumn = savedColumn.coerceIn(0, result.columns - 1)
      result.savedForeground = savedForeground
      result.savedBackground = savedBackground
      result.savedFlags = savedFlags
      result.savedGraphics = savedGraphics
      for (column in 0 until copiedColumns) result.tabStops[column] = tabStops[column]
      return result
    }

    /** Returns the cached snapshot for a clean row, re-snapshotting a dirty one. */
    fun snapshotRow(row: Int): FrameRow {
      val bounded = row.coerceIn(0, rows - 1)
      val cached = snapshots[bounded]
      if (!dirty[bounded] && cached != null) return cached
      val start = index(bounded, 0)
      val rowForeground = IntArray(columns)
      val rowBackground = IntArray(columns)
      val rowFlags = IntArray(columns)
      for (column in 0 until columns) {
        val source = start + column
        val cellFlags = flags[source]
        val inverse = cellFlags and FLAG_INVERSE != 0
        rowForeground[column] = if (inverse) background[source] else foreground[source]
        rowBackground[column] = if (inverse) foreground[source] else background[source]
        rowFlags[column] = cellFlags and FLAG_INVERSE.inv()
      }
      val snapshot = FrameRow(
        text = texts.copyOfRange(start, start + columns),
        width = widths.copyOfRange(start, start + columns),
        foreground = rowForeground,
        background = rowBackground,
        flags = rowFlags,
      )
      snapshots[bounded] = snapshot
      dirty[bounded] = false
      return snapshot
    }

    /** Moves a row's cells and its cached snapshot, so scrolling re-snapshots only the new blank rows. */
    private fun copyRow(sourceRow: Int, targetRow: Int) {
      snapshots[targetRow] = snapshots[sourceRow]
      dirty[targetRow] = dirty[sourceRow]
      val source = index(sourceRow, 0)
      val target = index(targetRow, 0)
      texts.copyInto(texts, target, source, source + columns)
      widths.copyInto(widths, target, source, source + columns)
      foreground.copyInto(foreground, target, source, source + columns)
      background.copyInto(background, target, source, source + columns)
      flags.copyInto(flags, target, source, source + columns)
    }

    private fun fillRange(
      start: Int,
      end: Int,
      foregroundColor: Int,
      backgroundColor: Int,
      cellFlags: Int,
    ) {
      if (end <= start) return
      for (row in start / columns..(end - 1) / columns) dirty[row] = true
      for (offset in start until end) {
        texts[offset] = " "
        widths[offset] = 1
        foreground[offset] = foregroundColor
        background[offset] = backgroundColor
        flags[offset] = cellFlags
      }
    }

    companion object {
      private fun boundRows(value: Int): Int = value.coerceIn(2, 250)
      private fun boundColumns(value: Int): Int = value.coerceIn(2, 500)
    }
  }

  private val commands = ArrayBlockingQueue<Command>(1024)
  private val closed = AtomicBoolean(false)
  private val highestQueuedSeq = AtomicLong(0)
  private var lastAppliedSeq = 0L
  private var normal = Screen(rows, columns)
  private var alternate = Screen(rows, columns)
  private var active = normal
  private val normalScrollback = ArrayDeque<FrameRow>()
  private var inAlternate = false
  private var cursorVisible = true
  private var originMode = false
  private var insertMode = false
  // LNM (mode 20): LF, VT and FF also return the carriage.
  private var lineFeedMode = false
  // Character sets: G0/G1 are US ASCII or DEC Special Graphics; SO/SI pick
  // one. [graphicsActive] is the one in use, a single branch when printing.
  private var g0Graphics = false
  private var g1Graphics = false
  private var shiftOut = false
  private var graphicsActive = false
  private var mouseTracking = false
  private var mouseSgr = false
  // Read off the parser thread when the user pastes.
  @Volatile var bracketedPaste = false
    private set
  private var synchronizedOutput = false
  private var synchronizedOutputStartedAt = 0L
  private var currentForeground = DEFAULT_FOREGROUND
  private var currentBackground = DEFAULT_BACKGROUND
  private var currentFlags = 0
  private var parserState = ParserState.NORMAL
  private var escIntermediate = 0
  private val csi = StringBuilder()
  // CSI parameters parsed from [csi] without allocating: group g (one `;`
  // parameter) holds csiValues[csiGroupStarts[g] until csiGroupStarts[g + 1]],
  // its `:` sub-parameters after the first. Missing values are CSI_MISSING.
  private val csiValues = LongArray(MAX_CSI_CHARS + 1)
  private val csiGroupStarts = IntArray(MAX_CSI_PARAMS + 1)
  private var csiParamCount = 0
  private val osc = StringBuilder()
  private var utf8Tail = ByteArray(0)
  private val listeners = CopyOnWriteArrayList<() -> Unit>()
  @Volatile private var publishedFrame: Frame = buildFrame()
  private val parserThread = Thread({ parseLoop() }, "opencode-native-terminal").apply { isDaemon = true }
  @Volatile private var requestedRows = rows.coerceIn(2, 250)
  @Volatile private var requestedColumns = columns.coerceIn(2, 500)

  init {
    parserThread.start()
  }

  fun currentFrame(): Frame = publishedFrame

  fun hasListeners(): Boolean = listeners.isNotEmpty()

  fun addListener(listener: () -> Unit): AutoCloseable {
    listeners.add(listener)
    return AutoCloseable { listeners.remove(listener) }
  }

  // Takes ownership of [bytes] uncopied; callers never write to it again (output arrives as a fresh Binder-unparcelled array).
  fun enqueue(seq: Long, bytes: ByteArray): Boolean {
    if (closed.get() || seq < 1L || bytes.isEmpty()) return false
    while (true) {
      val previous = highestQueuedSeq.get()
      if (seq <= previous) return true
      if (highestQueuedSeq.compareAndSet(previous, seq)) {
        if (commands.offer(Command.Output(seq, bytes))) return true
        highestQueuedSeq.compareAndSet(seq, previous)
        return false
      }
    }
  }

  fun resize(newRows: Int, newColumns: Int) {
    if (closed.get()) return
    val boundedRows = newRows.coerceIn(2, 250)
    val boundedColumns = newColumns.coerceIn(2, 500)
    if (requestedRows == boundedRows && requestedColumns == boundedColumns) return
    requestedRows = boundedRows
    requestedColumns = boundedColumns
    commands.offer(Command.Resize(boundedRows, boundedColumns))
  }

  fun close() {
    if (!closed.compareAndSet(false, true)) return
    commands.clear()
    parserThread.interrupt()
    listeners.clear()
  }

  private fun parseLoop() {
    try {
      while (!closed.get()) {
        apply(commands.take())

        // Coalesce writes that arrive back to back (one TUI redraw is often
        // split across several PTY chunks), but publish as soon as the stream
        // goes quiet so a keystroke echo is not held for a whole frame. While
        // the app has DEC synchronized output (mode 2026) open, hold the frame
        // until it closes the update or the safety timeout expires.
        val batchStartedAt = System.nanoTime()
        var lastAppliedAt = batchStartedAt
        while (!closed.get()) {
          val now = System.nanoTime()
          val deadline = if (synchronizedOutput && now - synchronizedOutputStartedAt < SYNCHRONIZED_OUTPUT_TIMEOUT_NANOS) {
            synchronizedOutputStartedAt + SYNCHRONIZED_OUTPUT_TIMEOUT_NANOS
          } else {
            minOf(batchStartedAt + NATIVE_FRAME_BATCH_NANOS, lastAppliedAt + NATIVE_FRAME_QUIET_NANOS)
          }
          val remaining = deadline - now
          if (remaining <= 0L) break
          val next = commands.poll(remaining, TimeUnit.NANOSECONDS) ?: break
          apply(next)
          lastAppliedAt = System.nanoTime()
        }
        publish()
      }
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
    }
  }

  private fun apply(command: Command) {
    try {
      applyUnchecked(command)
    } catch (error: RuntimeException) {
      // A malformed or unexpected sequence must not kill the parser thread
      // (which would freeze the session, or crash the app when uncaught).
      // Drop the rest of this chunk and keep parsing the stream.
      parserState = ParserState.NORMAL
      csi.setLength(0)
      osc.setLength(0)
      if (command is Command.Output) lastAppliedSeq = maxOf(lastAppliedSeq, command.seq)
    }
  }

  private fun applyUnchecked(command: Command) {
    when (command) {
      is Command.Output -> {
        if (command.seq <= lastAppliedSeq) return
        // Output is numbered from 1 with no holes. A jump means chunks were
        // dropped (the replay ring was trimmed while the UI was frozen or
        // dead, or this queue overflowed). Their mode, charset, and scroll
        // region changes are gone, so restart the parser from a neutral
        // state and ask the app to repaint over it.
        if (command.seq != lastAppliedSeq + 1L) recoverFromOutputGap()
        consume(command.bytes)
        lastAppliedSeq = command.seq
      }
      is Command.Resize -> resizeNow(command.rows, command.columns)
    }
  }

  private fun recoverFromOutputGap() {
    parserState = ParserState.NORMAL
    escIntermediate = 0
    csi.setLength(0)
    osc.setLength(0)
    utf8Tail = ByteArray(0)
    synchronizedOutput = false
    g0Graphics = false
    g1Graphics = false
    shiftOut = false
    graphicsActive = false
    currentForeground = DEFAULT_FOREGROUND
    currentBackground = DEFAULT_BACKGROUND
    currentFlags = 0
    for (screen in arrayOf(normal, alternate)) {
      screen.scrollTop = 0
      screen.scrollBottom = screen.rows - 1
    }
    runCatching { onOutputGap() }
  }

  private fun resizeNow(newRows: Int, newColumns: Int) {
    val boundedRows = newRows.coerceIn(2, 250)
    val columnsChanged = newColumns.coerceIn(2, 500) != normal.columns
    // When the screen gets shorter (e.g. the keyboard opens), keep the
    // cursor line on screen as xterm does: rows above it move into
    // scrollback instead of the bottom rows, and the prompt, being dropped.
    val overflow = normal.cursorRow - (boundedRows - 1)
    if (overflow > 0) {
      for (row in 0 until overflow) normalScrollback.addLast(normal.snapshotRow(row))
      while (normalScrollback.size > MAX_SCROLLBACK_ROWS) normalScrollback.removeFirst()
      normal.shiftUp(overflow)
    }
    normal = normal.resized(newRows, newColumns)
    alternate = alternate.resized(newRows, newColumns)
    // A height-only change keeps history; rows captured at another width
    // would no longer line up with the grid.
    if (columnsChanged) normalScrollback.clear()
    active = if (inAlternate) alternate else normal
  }

  private fun publish() {
    publishedFrame = buildFrame()
    listeners.forEach { listener -> runCatching { listener() } }
  }

  /**
   * Runs on the parser thread. Scrollback rows are shared by reference and
   * clean active rows reuse their cached snapshot, so the row array is the
   * only allocation unless rows changed since the last publish.
   */
  private fun buildFrame(): Frame {
    val historyRows = if (inAlternate) 0 else normalScrollback.size
    val scrollback = normalScrollback.iterator()
    val lines = Array(historyRows + active.rows) { row ->
      if (row < historyRows) scrollback.next() else active.snapshotRow(row - historyRows)
    }
    var lastContentRow = lines.size - 1
    while (lastContentRow >= 0 && !lines[lastContentRow].hasVisibleContent) lastContentRow -= 1
    return Frame(
      rows = active.rows,
      columns = active.columns,
      contentRows = lines.size,
      lines = lines,
      cursorRow = historyRows + active.cursorRow,
      cursorColumn = active.cursorColumn.coerceAtMost(active.columns - 1),
      cursorVisible = cursorVisible,
      alternate = inAlternate,
      hasVisibleContent = lastContentRow >= 0,
      lastContentRow = lastContentRow,
      mouseTracking = mouseTracking,
      mouseSgr = mouseSgr,
    )
  }

  private fun consume(bytes: ByteArray) {
    val text = decodeUtf8(bytes)
    var index = 0
    while (index < text.length) {
      val codePoint = text.codePointAt(index)
      index += Character.charCount(codePoint)
      consumeCodePoint(codePoint)
    }
  }

  private fun decodeUtf8(bytes: ByteArray): String {
    val merged = if (utf8Tail.isEmpty()) bytes else utf8Tail + bytes
    var carryStart = merged.size
    var index = merged.lastIndex
    while (index >= 0 && isContinuation(merged[index])) index -= 1
    if (index >= 0) {
      val expected = when {
        merged[index].toInt() and 0x80 == 0 -> 1
        merged[index].toInt() and 0xe0 == 0xc0 -> 2
        merged[index].toInt() and 0xf0 == 0xe0 -> 3
        merged[index].toInt() and 0xf8 == 0xf0 -> 4
        else -> 0
      }
      if (expected > 0 && merged.size - index < expected) carryStart = index
    }
    utf8Tail = merged.copyOfRange(carryStart, merged.size)
    return String(merged, 0, carryStart, StandardCharsets.UTF_8)
  }

  /**
   * VT500-style state machine, following xterm.js: CAN/SUB abort any
   * sequence, ESC starts a new escape from any state (ending an OSC or DCS),
   * C1 controls act the same in every state, DEL is ignored, and C0 controls
   * inside an escape or CSI sequence execute without ending it.
   */
  private fun consumeCodePoint(codePoint: Int) {
    if (codePoint < 0x20 || codePoint in 0x7f..0x9f) {
      when {
        codePoint == 0x18 || codePoint == 0x1a -> {
          abortSequence()
          return
        }
        codePoint == 0x1b -> {
          if (parserState == ParserState.OSC) finishOsc()
          csi.setLength(0)
          parserState = ParserState.ESC
          return
        }
        codePoint == 0x7f -> return
        codePoint >= 0x80 -> {
          consumeC1(codePoint)
          return
        }
      }
    }
    when (parserState) {
      ParserState.NORMAL -> if (codePoint < 0x20) executeControl(codePoint) else putCodePoint(codePoint)
      ParserState.ESC -> consumeEsc(codePoint)
      ParserState.ESC_INTERMEDIATE -> consumeEscIntermediate(codePoint)
      ParserState.CSI -> consumeCsi(codePoint)
      ParserState.CSI_IGNORE -> consumeCsiIgnore(codePoint)
      ParserState.OSC -> consumeOsc(codePoint)
      // DCS, SOS, PM and APC payloads are ignored until ST, ESC, CAN or SUB.
      ParserState.STRING -> Unit
    }
  }

  private fun abortSequence() {
    csi.setLength(0)
    osc.setLength(0)
    parserState = ParserState.NORMAL
  }

  /** C1 controls (U+0080..U+009F), as xterm.js handles them in any state. */
  private fun consumeC1(codePoint: Int) {
    // ST ends an OSC as ESC \ does; elsewhere it just returns to ground.
    if (codePoint == 0x9c && parserState == ParserState.OSC) {
      finishOsc()
      return
    }
    abortSequence()
    when (codePoint) {
      0x84 -> index()
      0x85 -> nextLine()
      0x88 -> setTabStop()
      0x90, 0x98, 0x9e, 0x9f -> parserState = ParserState.STRING
      0x9b -> parserState = ParserState.CSI
      0x9d -> parserState = ParserState.OSC
    }
  }

  /** C0 controls, which also execute inside ESC and CSI sequences. */
  private fun executeControl(codePoint: Int) {
    when (codePoint) {
      // A pending wrap (cursorColumn == columns) sits on the last column.
      0x08 -> active.cursorColumn = (minOf(active.cursorColumn, active.columns - 1) - 1).coerceAtLeast(0)
      // HT leaves a pending wrap pending, as in xterm.
      0x09 -> if (active.cursorColumn < active.columns) {
        active.cursorColumn = active.nextTabStop(active.cursorColumn)
      }
      0x0a, 0x0b, 0x0c -> {
        if (lineFeedMode) active.cursorColumn = 0
        lineFeed()
      }
      0x0d -> active.cursorColumn = 0
      0x0e -> {
        shiftOut = true
        graphicsActive = g1Graphics
      }
      0x0f -> {
        shiftOut = false
        graphicsActive = g0Graphics
      }
      else -> Unit
    }
  }

  private fun consumeEsc(codePoint: Int) {
    if (codePoint < 0x20) {
      executeControl(codePoint)
      return
    }
    parserState = ParserState.NORMAL
    when (codePoint) {
      '['.code -> {
        csi.setLength(0)
        parserState = ParserState.CSI
      }
      ']'.code -> {
        osc.setLength(0)
        parserState = ParserState.OSC
      }
      'P'.code, 'X'.code, '^'.code, '_'.code -> parserState = ParserState.STRING
      '7'.code -> saveCursor()
      '8'.code -> restoreCursor()
      'D'.code -> index()
      'E'.code -> nextLine()
      'M'.code -> reverseIndex()
      'H'.code -> setTabStop()
      'c'.code -> reset()
      in 0x20..0x2f -> {
        escIntermediate = codePoint
        parserState = ParserState.ESC_INTERMEDIATE
      }
      else -> Unit
    }
  }

  /** ESC <intermediate> <final>: charset designation, DECALN, ... */
  private fun consumeEscIntermediate(codePoint: Int) {
    when (codePoint) {
      in 0x00..0x1f -> executeControl(codePoint)
      in 0x20..0x2f -> Unit
      in 0x30..0x7e -> {
        parserState = ParserState.NORMAL
        dispatchEscIntermediate(escIntermediate, codePoint)
      }
      else -> parserState = ParserState.NORMAL
    }
  }

  private fun dispatchEscIntermediate(intermediate: Int, final: Int) {
    when (intermediate) {
      // Designate G0 / G1: '0' is DEC Special Graphics, anything else is
      // treated as US ASCII (national replacement sets are not supported).
      '('.code -> g0Graphics = final == '0'.code
      ')'.code, '-'.code -> g1Graphics = final == '0'.code
      '%'.code -> if (final == '@'.code || final == 'G'.code) {
        shiftOut = false
        g0Graphics = false
      }
      '#'.code -> if (final == '8'.code) screenAlignment()
    }
    graphicsActive = if (shiftOut) g1Graphics else g0Graphics
  }

  /** DECALN: fills the screen with E and homes the cursor. */
  private fun screenAlignment() {
    active.fillText("E", currentForeground, currentBackground, currentFlags)
    setCursor(0, 0)
  }

  private fun nextLine() {
    active.cursorColumn = 0
    index()
  }

  /** HTS (ESC H, C1 0x88): a tab stop at the cursor column. */
  private fun setTabStop() {
    if (active.cursorColumn < active.columns) active.tabStops[active.cursorColumn] = true
  }

  private fun consumeCsi(codePoint: Int) {
    when {
      codePoint < 0x20 -> executeControl(codePoint)
      codePoint in 0x40..0x7e -> {
        handleCsi(codePoint.toChar())
        csi.setLength(0)
        parserState = ParserState.NORMAL
      }
      // Anything else (non-ASCII) is a malformed sequence: drop it.
      codePoint > 0x7e -> abortSequence()
      csi.length < MAX_CSI_CHARS -> csi.appendCodePoint(codePoint)
      else -> {
        csi.setLength(0)
        parserState = ParserState.CSI_IGNORE
      }
    }
  }

  /** Skips an over-long CSI through its final byte without acting on it. */
  private fun consumeCsiIgnore(codePoint: Int) {
    when {
      codePoint < 0x20 -> executeControl(codePoint)
      codePoint in 0x40..0x7e -> parserState = ParserState.NORMAL
      codePoint > 0x7e -> parserState = ParserState.NORMAL
    }
  }

  private fun consumeOsc(codePoint: Int) {
    when {
      codePoint == 0x07 -> finishOsc()
      // Other C0 controls are ignored inside an OSC string.
      codePoint < 0x20 -> Unit
      else -> appendOsc(codePoint)
    }
  }

  private fun appendOsc(codePoint: Int) {
    if (osc.length < MAX_OSC_CHARS) osc.appendCodePoint(codePoint)
  }

  private fun finishOsc() {
    val value = osc.toString()
    val separator = value.indexOf(';')
    val code = value.take(separator.coerceAtLeast(0)).toIntOrNull()
    val payload = if (separator >= 0) value.substring(separator + 1) else ""
    if (code == 52) {
      // OSC 52 ; selection ; base64. Writes only: a "?" read request is
      // refused so guest programs cannot read the device clipboard.
      val data = payload.substringAfter(';', "")
      if (data.isNotEmpty() && data != "?") runCatching { clipboardWriter(data) }
    } else if (payload == "?" && (code == 10 || code == 11)) {
      val color = if (code == 10) "F2F2/F4F4/F5F5" else "0909/0C0C/0D0D"
      reply("\u001b]$code;rgb:$color\u001b\\")
    }
    osc.setLength(0)
    parserState = ParserState.NORMAL
  }

  private fun handleCsi(final: Char) {
    // CSI [private marker <=>?] params [intermediates 0x20-0x2f] final.
    val length = csi.length
    var start = 0
    while (start < length && csi[start] in '<'..'?') start += 1
    var end = length
    while (end > start && csi[end - 1].code in 0x20..0x2f) end -= 1
    // The marker or intermediate character when there is exactly one, 0 for
    // none, MULTIPLE for more.
    val marker = when (start) {
      0 -> 0
      1 -> csi[0].code
      else -> MULTIPLE
    }
    val intermediate = when (length - end) {
      0 -> 0
      1 -> csi[end].code
      else -> MULTIPLE
    }
    parseCsiParameters(start, end)
    // Sequences with intermediates or a non-DEC private marker (kitty
    // keyboard `CSI > 1 u`, modifyOtherKeys `CSI > 4;2 m`, cursor style
    // `CSI 2 SP q`, ...) must never reach the plain handlers below, where
    // they would restore the cursor or change attributes.
    if (intermediate != 0) {
      if (final == 'p' && intermediate == '$'.code && marker == '?'.code) {
        // The whole body must be one number: `2026:1` or `2026;1` is no mode.
        val mode = csiValue(0)
        reportPrivateMode(if (csiParamCount == 1 && csiGroupSize(0) == 1 && mode != CSI_MISSING) mode.toInt() else null)
      }
      if (final == 'p' && intermediate == '!'.code && marker == 0) softReset()
      return
    }
    if (marker == '>'.code || marker == '<'.code || marker == '='.code) {
      if (final == 'c' && marker == '>'.code) reply("\u001b[>0;276;0c")
      return
    }
    val privateMode = marker == '?'.code
    if (privateMode && final !in "hlJKn") return
    fun parameter(index: Int = 0): Int = csiInt(index, 1).coerceAtLeast(1)
    fun parameterZero(index: Int): Int = csiInt(index, 0).coerceAtLeast(0)

    when (final) {
      'A' -> cursorUp(parameter())
      'B' -> cursorDown(parameter())
      // VPR moves like CUD but does not stop at the bottom margin.
      'e' -> moveRow(parameter())
      'C', 'a' -> active.cursorColumn = (active.cursorColumn + parameter()).coerceAtMost(active.columns - 1)
      'D' -> active.cursorColumn = (minOf(active.cursorColumn, active.columns - 1) - parameter()).coerceAtLeast(0)
      'E' -> {
        cursorDown(parameter())
        active.cursorColumn = 0
      }
      'F' -> {
        cursorUp(parameter())
        active.cursorColumn = 0
      }
      'G', '`' -> active.cursorColumn = (parameter() - 1).coerceIn(0, active.columns - 1)
      'd' -> setCursor(parameter() - 1, active.cursorColumn)
      'H', 'f' -> setCursor(parameter(0) - 1, parameter(1) - 1)
      'J' -> eraseDisplay(parameterZero(0))
      'K' -> eraseLine(parameterZero(0))
      // IL/DL are ignored outside the scroll region; inside it they also
      // move the cursor to the first column, as in xterm.
      'L' -> {
        restrictColumn()
        if (active.cursorRow in active.scrollTop..active.scrollBottom) {
          active.insertLines(parameter(), currentForeground, currentBackground)
          active.cursorColumn = 0
        }
      }
      'M' -> {
        restrictColumn()
        if (active.cursorRow in active.scrollTop..active.scrollBottom) {
          active.deleteLines(parameter(), currentForeground, currentBackground)
          active.cursorColumn = 0
        }
      }
      'P' -> {
        restrictColumn()
        deleteCharacters(parameter())
      }
      '@' -> {
        restrictColumn()
        insertCharacters(parameter())
      }
      'X' -> {
        restrictColumn()
        eraseCharacters(parameter())
      }
      'S' -> active.scrollUp(parameter(), currentForeground, currentBackground)
      'T' -> active.scrollDown(parameter(), currentForeground, currentBackground)
      'm' -> setGraphicsRendition()
      'h', 'l' -> setMode(privateMode, final == 'h')
      'r' -> setScrollRegion()
      's' -> saveCursor()
      'u' -> restoreCursor()
      'n' -> handleDeviceStatus(parameterZero(0))
      'c' -> reply("\u001b[?1;2c")
      'I' -> if (active.cursorColumn < active.columns) {
        repeat(parameter()) { active.cursorColumn = active.nextTabStop(active.cursorColumn) }
      }
      'Z' -> if (active.cursorColumn < active.columns) {
        repeat(parameter()) { active.cursorColumn = active.previousTabStop(active.cursorColumn) }
      }
      'g' -> when (parameterZero(0)) {
        0 -> if (active.cursorColumn < active.columns) active.tabStops[active.cursorColumn] = false
        3 -> active.tabStops.fill(false)
      }
      't' -> if (parameterZero(0) == 18) reply("\u001b[8;${active.rows};${active.columns}t")
      else -> Unit
    }
  }

  /**
   * Splits csi[start, end) into `;` groups of `:` sub-parameters. Each value
   * parses as String.toIntOrNull would (optional sign, digits only, within
   * Int range); anything else is CSI_MISSING. Groups past MAX_CSI_PARAMS are
   * ignored, as in xterm.
   */
  private fun parseCsiParameters(start: Int, end: Int) {
    csiParamCount = 0
    csiGroupStarts[0] = 0
    if (start >= end) return
    var valueCount = 0
    var segmentStart = start
    var index = start
    while (true) {
      val separator = if (index == end) ';' else csi[index]
      if (separator == ';' || separator == ':') {
        csiValues[valueCount++] = parseCsiNumber(segmentStart, index)
        if (separator == ';') {
          csiParamCount += 1
          csiGroupStarts[csiParamCount] = valueCount
          if (csiParamCount == MAX_CSI_PARAMS) return
        }
        if (index == end) return
        segmentStart = index + 1
      }
      index += 1
    }
  }

  private fun parseCsiNumber(from: Int, to: Int): Long {
    if (from >= to) return CSI_MISSING
    var index = from
    val sign = csi[from]
    if (sign == '-' || sign == '+') {
      if (to - from == 1) return CSI_MISSING
      index += 1
    }
    var value = 0L
    while (index < to) {
      val digit = csi[index] - '0'
      if (digit !in 0..9) return CSI_MISSING
      value = value * 10 + digit
      if (value > -Int.MIN_VALUE.toLong()) return CSI_MISSING
      index += 1
    }
    if (sign == '-') value = -value
    return if (value in Int.MIN_VALUE..Int.MAX_VALUE) value else CSI_MISSING
  }

  private fun csiGroupSize(group: Int): Int = csiGroupStarts[group + 1] - csiGroupStarts[group]

  /** Sub-parameter [sub] of [group], or CSI_MISSING when empty, invalid or absent. */
  private fun csiValue(group: Int, sub: Int = 0): Long =
    if (group < csiParamCount && sub < csiGroupSize(group)) csiValues[csiGroupStarts[group] + sub] else CSI_MISSING

  private fun csiInt(group: Int, default: Int): Int {
    val value = csiValue(group)
    return if (value == CSI_MISSING) default else value.toInt()
  }

  /**
   * Reads the parsed groups (`4:3`, `38:2::r:g:b`). Extended colours accept
   * both the colon form and the legacy `38;2;r;g;b` form; underline colour
   * (58) is parsed and ignored so its numbers are not read as attributes.
   */
  private fun setGraphicsRendition() {
    if (csiParamCount == 0) {
      currentForeground = DEFAULT_FOREGROUND
      currentBackground = DEFAULT_BACKGROUND
      currentFlags = 0
      return
    }
    var index = 0
    while (index < csiParamCount) {
      val groupSize = csiGroupSize(index)
      when (val value = csiInt(index, 0)) {
        0 -> {
          currentForeground = DEFAULT_FOREGROUND
          currentBackground = DEFAULT_BACKGROUND
          currentFlags = 0
        }
        1 -> currentFlags = currentFlags or FLAG_BOLD
        2 -> currentFlags = currentFlags or FLAG_DIM
        3 -> currentFlags = currentFlags or FLAG_ITALIC
        4 -> currentFlags = if (groupSize > 1 && csiValue(index, 1) == 0L) {
          currentFlags and FLAG_UNDERLINE.inv()
        } else {
          currentFlags or FLAG_UNDERLINE
        }
        7 -> currentFlags = currentFlags or FLAG_INVERSE
        8 -> currentFlags = currentFlags or FLAG_INVISIBLE
        9 -> currentFlags = currentFlags or FLAG_STRIKETHROUGH
        // 21 is double underline; drawn as a plain underline.
        21 -> currentFlags = currentFlags or FLAG_UNDERLINE
        22 -> currentFlags = currentFlags and (FLAG_BOLD or FLAG_DIM).inv()
        23 -> currentFlags = currentFlags and FLAG_ITALIC.inv()
        24 -> currentFlags = currentFlags and FLAG_UNDERLINE.inv()
        27 -> currentFlags = currentFlags and FLAG_INVERSE.inv()
        28 -> currentFlags = currentFlags and FLAG_INVISIBLE.inv()
        29 -> currentFlags = currentFlags and FLAG_STRIKETHROUGH.inv()
        in 30..37 -> currentForeground = ansiColor(value - 30)
        39 -> currentForeground = DEFAULT_FOREGROUND
        in 40..47 -> currentBackground = ansiColor(value - 40)
        49 -> currentBackground = DEFAULT_BACKGROUND
        in 90..97 -> currentForeground = ansiColor(value - 90 + 8)
        in 100..107 -> currentBackground = ansiColor(value - 100 + 8)
        38, 48, 58 -> {
          // Colon form carries its arguments in this group; the legacy form
          // takes them from the following groups.
          val colon = groupSize > 1
          val argumentCount = if (colon) groupSize - 1 else csiParamCount - index - 1
          val consumed: Int
          var color = 0
          var hasColor = false
          when (sgrColorArgument(index, colon, 0)) {
            5L -> {
              consumed = 2
              val entry = sgrColorArgument(index, colon, 1)
              if (entry != CSI_MISSING) {
                color = palette(entry.toInt())
                hasColor = true
              }
            }
            2L -> {
              // Colon form may include a colour-space id: 38:2:id:r:g:b.
              val first = if (colon && argumentCount >= 5) 2 else 1
              consumed = 4
              val red = sgrColorArgument(index, colon, first)
              val green = sgrColorArgument(index, colon, first + 1)
              val blue = sgrColorArgument(index, colon, first + 2)
              if (red != CSI_MISSING && green != CSI_MISSING && blue != CSI_MISSING) {
                color = rgb(red.coerceIn(0, 255).toInt(), green.coerceIn(0, 255).toInt(), blue.coerceIn(0, 255).toInt())
                hasColor = true
              }
            }
            else -> consumed = 0
          }
          if (hasColor) {
            if (value == 38) currentForeground = color else if (value == 48) currentBackground = color
          }
          if (!colon) index += consumed.coerceAtMost(csiParamCount - index - 1)
        }
        else -> Unit
      }
      index += 1
    }
  }

  /** Argument [argument] after the 38/48/58 in [group]: a sub-parameter, or a later group's value. */
  private fun sgrColorArgument(group: Int, colon: Boolean, argument: Int): Long =
    if (colon) csiValue(group, 1 + argument) else csiValue(group + 1 + argument)

  private fun setMode(privateMode: Boolean, enabled: Boolean) {
    for (group in 0 until csiParamCount) {
      val rawMode = csiValue(group)
      if (rawMode == CSI_MISSING) continue
      val mode = rawMode.toInt()
      if (privateMode) {
        when (mode) {
          // DECOM homes the cursor (to the region top when set).
          6 -> {
            originMode = enabled
            setCursor(0, 0)
          }
          25 -> cursorVisible = enabled
          47, 1047, 1049 -> if (enabled) enterAlternate(mode) else exitAlternate(mode)
          1048 -> if (enabled) saveCursor() else restoreCursor()
          1000, 1002, 1003 -> mouseTracking = enabled
          1006 -> mouseSgr = enabled
          2004 -> bracketedPaste = enabled
          2026 -> {
            if (enabled && !synchronizedOutput) synchronizedOutputStartedAt = System.nanoTime()
            synchronizedOutput = enabled
          }
        }
      } else if (mode == 4) {
        insertMode = enabled
      } else if (mode == 20) {
        lineFeedMode = enabled
      }
    }
  }

  /**
   * 47/1047/1049 set, as xterm: 1049 first saves the cursor (DECSC) on the
   * current screen; the alternate screen is filled with the current
   * background and starts at the normal screen's cursor position.
   */
  private fun enterAlternate(mode: Int) {
    if (mode == 1049) saveCursor()
    if (inAlternate) return
    alternate.clearRows(currentForeground, currentBackground)
    alternate.cursorRow = normal.cursorRow.coerceIn(0, alternate.rows - 1)
    alternate.cursorColumn = normal.cursorColumn.coerceIn(0, alternate.columns)
    active = alternate
    inAlternate = true
  }

  /**
   * 47/1047/1049 reset, as xterm: the normal screen takes over the alternate
   * cursor, the alternate screen is cleared (with its margins), and 1049
   * then restores the cursor (DECRC), even when already on the normal screen.
   */
  private fun exitAlternate(mode: Int) {
    if (inAlternate) {
      normal.cursorRow = alternate.cursorRow.coerceIn(0, normal.rows - 1)
      normal.cursorColumn = alternate.cursorColumn.coerceIn(0, normal.columns)
      alternate.clearAll(DEFAULT_FOREGROUND, DEFAULT_BACKGROUND)
      active = normal
      inAlternate = false
    }
    if (mode == 1049) restoreCursor()
  }

  private fun setScrollRegion() {
    val top = csiInt(0, 0).let { if (it <= 0) 1 else it }
    var bottom = csiInt(1, 0)
    if (csiParamCount < 2 || bottom <= 0 || bottom > active.rows) bottom = active.rows
    if (bottom <= top) return
    active.scrollTop = top - 1
    active.scrollBottom = bottom - 1
    setCursor(0, 0)
  }

  /** DECRQM: advertise synchronized output so TUIs enable it. */
  private fun reportPrivateMode(mode: Int?) {
    if (mode == null) return
    val state = if (mode == 2026) (if (synchronizedOutput) 1 else 2) else 0
    reply("\u001b[?$mode;$state\$y")
  }

  private fun handleDeviceStatus(value: Int) {
    when (value) {
      5 -> reply("\u001b[0n")
      // A pending wrap reports the last column, as the frame shows it.
      6 -> reply("\u001b[${active.cursorRow + 1};${minOf(active.cursorColumn, active.columns - 1) + 1}R")
    }
  }

  private fun eraseDisplay(mode: Int) {
    when (mode) {
      0 -> {
        eraseLine(0)
        for (row in active.cursorRow + 1 until active.rows) active.clearRow(row, 0, active.columns, currentForeground, currentBackground)
      }
      1 -> {
        eraseLine(1)
        for (row in 0 until active.cursorRow) active.clearRow(row, 0, active.columns, currentForeground, currentBackground)
      }
      // ED 2 clears cells only; the cursor and scroll region stay put.
      2 -> for (row in 0 until active.rows) active.clearRow(row, 0, active.columns, currentForeground, currentBackground)
      // ED 3 clears scrollback, not the screen.
      3 -> if (active === normal) normalScrollback.clear()
    }
  }

  private fun eraseLine(mode: Int) {
    when (mode) {
      0 -> eraseCells(active.cursorRow, active.cursorColumn, active.columns)
      1 -> eraseCells(active.cursorRow, 0, active.cursorColumn + 1)
      2 -> eraseCells(active.cursorRow, 0, active.columns)
    }
  }

  private fun eraseCharacters(count: Int) {
    eraseCells(active.cursorRow, active.cursorColumn, active.cursorColumn + count)
  }

  /**
   * Erases [from, to) of [row] with the current background, as xterm's
   * replaceCells: a wide character cut by either edge is blanked whole so no
   * half of it is left behind.
   */
  private fun eraseCells(row: Int, from: Int, to: Int) {
    val start = from.coerceIn(0, active.columns)
    val end = to.coerceIn(start, active.columns)
    if (start > 0 && cellWidth(row, start - 1) == 2) {
      active.clearCell(row, start - 1, currentForeground, currentBackground)
    }
    if (end in 1 until active.columns && cellWidth(row, end - 1) == 2) {
      active.clearCell(row, end, currentForeground, currentBackground)
    }
    active.clearRow(row, start, end, currentForeground, currentBackground)
  }

  /**
   * ICH, and IRM printing: shifts the rest of the row right, filling the gap
   * with [fillFlags]/current colours. A wide character split at the cursor or
   * pushed half off the right edge is blanked, as in xterm's insertCells.
   */
  private fun insertCharacters(count: Int, fillFlags: Int = 0) {
    val row = active.cursorRow
    val cursor = active.cursorColumn
    val columns = active.columns
    if (cursor > 0 && cellWidth(row, cursor - 1) == 2) {
      active.clearCell(row, cursor - 1, currentForeground, currentBackground, fillFlags)
    }
    val amount = count.coerceIn(0, columns - cursor)
    for (column in columns - 1 downTo cursor + amount) copyCell(row, column - amount, row, column)
    active.clearRow(row, cursor, cursor + amount, currentForeground, currentBackground, fillFlags)
    if (cellWidth(row, columns - 1) == 2) {
      active.clearCell(row, columns - 1, currentForeground, currentBackground, fillFlags)
    }
  }

  /** DCH, blanking a wide character split by the deletion (xterm deleteCells). */
  private fun deleteCharacters(count: Int) {
    val row = active.cursorRow
    val cursor = active.cursorColumn
    val columns = active.columns
    val amount = count.coerceIn(0, columns - cursor)
    for (column in cursor until columns - amount) copyCell(row, column + amount, row, column)
    active.clearRow(row, columns - amount, columns, currentForeground, currentBackground)
    if (cursor > 0 && cellWidth(row, cursor - 1) == 2) {
      active.clearCell(row, cursor - 1, currentForeground, currentBackground)
    }
    if (cursor < columns && isOrphanContinuation(row, cursor)) {
      active.clearCell(row, cursor, currentForeground, currentBackground)
    }
  }

  private fun cellWidth(row: Int, column: Int): Int = active.widths[active.index(row, column)].toInt()

  /** A width-0 cell whose wide lead is gone (xterm: width 0 without content). */
  private fun isOrphanContinuation(row: Int, column: Int): Boolean {
    val offset = active.index(row, column)
    return active.widths[offset] == ZERO_WIDTH && active.texts[offset].isEmpty()
  }

  private fun copyCell(sourceRow: Int, sourceColumn: Int, targetRow: Int, targetColumn: Int) {
    val source = active.index(sourceRow, sourceColumn)
    val target = active.index(targetRow, targetColumn)
    active.markDirty(targetRow)
    active.texts[target] = active.texts[source]
    active.widths[target] = active.widths[source]
    active.foreground[target] = active.foreground[source]
    active.background[target] = active.background[source]
    active.flags[target] = active.flags[source]
  }

  private fun putCodePoint(printed: Int) {
    val codePoint = if (graphicsActive && printed in 0x60..0x7e) DEC_SPECIAL_GRAPHICS[printed - 0x60].code else printed
    val width = TerminalCharWidth.of(codePoint)
    if (width == 0) {
      joinPreviousCell(codePoint)
      return
    }
    if (active.cursorColumn >= active.columns) {
      active.cursorColumn = 0
      lineFeed()
    } else if (width == 2 && active.cursorColumn == active.columns - 1) {
      // A wide character that does not fit wraps; the skipped last cell is
      // blanked with the current attributes, as in xterm.
      active.clearCell(active.cursorRow, active.columns - 1, currentForeground, currentBackground, currentFlags)
      active.cursorColumn = 0
      lineFeed()
    }
    val row = active.cursorRow
    val column = active.cursorColumn
    // Overwriting the right half of a wide character blanks its left half.
    if (column > 0 && cellWidth(row, column - 1) == 2) {
      active.clearCell(row, column - 1, currentForeground, currentBackground, currentFlags)
    }
    if (insertMode) insertCharacters(width, currentFlags)
    val offset = active.index(row, column)
    active.markDirty(row)
    active.texts[offset] = cellText(codePoint)
    active.widths[offset] = width.toByte()
    active.foreground[offset] = currentForeground
    active.background[offset] = currentBackground
    active.flags[offset] = currentFlags
    if (width == 2 && column + 1 < active.columns) {
      val continuation = active.index(row, column + 1)
      active.texts[continuation] = ""
      active.widths[continuation] = 0
      active.foreground[continuation] = currentForeground
      active.background[continuation] = currentBackground
      active.flags[continuation] = currentFlags
    }
    active.cursorColumn += width
    // Overwriting the left half of a wide character blanks its right half.
    val next = column + width
    if (next < active.columns && isOrphanContinuation(row, next)) {
      active.clearCell(row, next, currentForeground, currentBackground, currentFlags)
    }
  }

  /**
   * Zero-width code points (combining marks, ZWJ, ZWSP, variation selectors)
   * join the cell before the cursor, stepping over a wide character's
   * continuation to its lead cell, as xterm does. At column 0 there is no
   * cell to join and the code point is dropped (xterm would give it a
   * zero-width cell of its own).
   */
  private fun joinPreviousCell(codePoint: Int) {
    val row = active.cursorRow
    var column = minOf(active.cursorColumn, active.columns) - 1
    if (column < 0) return
    if (column > 0 && active.widths[active.index(row, column)] == ZERO_WIDTH) column -= 1
    val offset = active.index(row, column)
    if (active.texts[offset].length >= MAX_CELL_CODE_UNITS) return
    active.markDirty(row)
    active.texts[offset] += String(Character.toChars(codePoint))
  }

  private fun cellText(codePoint: Int): String =
    if (codePoint < ASCII_CELL_TEXT.size) ASCII_CELL_TEXT[codePoint] else String(Character.toChars(codePoint))

  private fun lineFeed() {
    if (active.cursorRow == active.scrollBottom) {
      if (!inAlternate && active === normal && active.scrollTop == 0) {
        normalScrollback.addLast(normal.snapshotRow(0))
        while (normalScrollback.size > MAX_SCROLLBACK_ROWS) normalScrollback.removeFirst()
      }
      active.scrollUp(1, currentForeground, currentBackground)
    }
    else active.cursorRow = (active.cursorRow + 1).coerceAtMost(active.rows - 1)
    // LF from a pending wrap lands on the last column of the next row.
    restrictColumn()
  }

  /** IND (ESC D): a line feed that never honours LNM. */
  private fun index() {
    restrictColumn()
    lineFeed()
  }

  /** Cancels a pending wrap (cursorColumn == columns) onto the last column. */
  private fun restrictColumn() {
    if (active.cursorColumn >= active.columns) active.cursorColumn = active.columns - 1
  }

  private fun reverseIndex() {
    restrictColumn()
    if (active.cursorRow == active.scrollTop) active.scrollDown(1, currentForeground, currentBackground)
    else active.cursorRow = (active.cursorRow - 1).coerceAtLeast(0)
  }

  /** CUU: stops at the top margin when the cursor starts at or below it. */
  private fun cursorUp(count: Int) {
    val aboveTop = active.cursorRow - active.scrollTop
    moveRow(-(if (aboveTop >= 0) minOf(aboveTop, count) else count))
  }

  /** CUD: stops at the bottom margin when the cursor starts at or above it. */
  private fun cursorDown(count: Int) {
    val belowBottom = active.scrollBottom - active.cursorRow
    moveRow(if (belowBottom >= 0) minOf(belowBottom, count) else count)
  }

  private fun moveRow(delta: Int) {
    val minimum = if (originMode) active.scrollTop else 0
    val maximum = if (originMode) active.scrollBottom else active.rows - 1
    active.cursorRow = (active.cursorRow + delta).coerceIn(minimum, maximum)
    restrictColumn()
  }

  private fun setCursor(row: Int, column: Int) {
    val minimum = if (originMode) active.scrollTop else 0
    val maximum = if (originMode) active.scrollBottom else active.rows - 1
    active.cursorRow = (if (originMode) active.scrollTop + row else row).coerceIn(minimum, maximum)
    active.cursorColumn = column.coerceIn(0, active.columns - 1)
  }

  private fun saveCursor() {
    active.savedRow = active.cursorRow
    active.savedColumn = active.cursorColumn
    active.savedForeground = currentForeground
    active.savedBackground = currentBackground
    active.savedFlags = currentFlags
    active.savedGraphics = graphicsActive
  }

  private fun restoreCursor() {
    active.cursorRow = active.savedRow
    active.cursorColumn = active.savedColumn.coerceIn(0, active.columns - 1)
    currentForeground = active.savedForeground
    currentBackground = active.savedBackground
    currentFlags = active.savedFlags
    graphicsActive = active.savedGraphics
    // As xterm's _restrictCursor: origin mode keeps the row in the region.
    val minimum = if (originMode) active.scrollTop else 0
    val maximum = if (originMode) active.scrollBottom else active.rows - 1
    active.cursorRow = active.cursorRow.coerceIn(minimum, maximum)
  }

  /**
   * DECSTR (CSI ! p), as xterm's soft reset: shows the cursor, clears the
   * margins, SGR, insert/origin modes, character sets and the saved cursor,
   * and ends synchronized output. The screen and cursor position stay.
   */
  private fun softReset() {
    cursorVisible = true
    active.scrollTop = 0
    active.scrollBottom = active.rows - 1
    currentForeground = DEFAULT_FOREGROUND
    currentBackground = DEFAULT_BACKGROUND
    currentFlags = 0
    insertMode = false
    originMode = false
    synchronizedOutput = false
    resetCharsets()
    active.resetSavedCursor()
  }

  private fun resetCharsets() {
    g0Graphics = false
    g1Graphics = false
    shiftOut = false
    graphicsActive = false
  }

  private fun reset() {
    parserState = ParserState.NORMAL
    lineFeedMode = false
    resetCharsets()
    currentForeground = DEFAULT_FOREGROUND
    currentBackground = DEFAULT_BACKGROUND
    currentFlags = 0
    cursorVisible = true
    originMode = false
    insertMode = false
    mouseTracking = false
    mouseSgr = false
    bracketedPaste = false
    synchronizedOutput = false
    normal.clearAll(DEFAULT_FOREGROUND, DEFAULT_BACKGROUND)
    alternate.clearAll(DEFAULT_FOREGROUND, DEFAULT_BACKGROUND)
    normal.resetSavedCursor()
    alternate.resetSavedCursor()
    normalScrollback.clear()
    active = normal
    inAlternate = false
  }

  private fun reply(value: String) {
    runCatching { inputWriter(sessionId, value.toByteArray(StandardCharsets.UTF_8)) }
  }

  private fun isContinuation(value: Byte): Boolean = value.toInt() and 0xc0 == 0x80

  private fun ansiColor(index: Int): Int = ANSI_COLORS[index.coerceIn(0, 15)]

  private fun palette(index: Int): Int {
    val value = index.coerceIn(0, 255)
    if (value < 16) return ansiColor(value)
    if (value >= 232) {
      val channel = 8 + (value - 232) * 10
      return rgb(channel, channel, channel)
    }
    val colorIndex = value - 16
    val levels = intArrayOf(0, 95, 135, 175, 215, 255)
    return rgb(
      levels[colorIndex / 36],
      levels[(colorIndex / 6) % 6],
      levels[colorIndex % 6],
    )
  }

  companion object {
    /** Opaque ARGB colour; plain bit maths so the parser has no Android dependency. */
    private fun rgb(red: Int, green: Int, blue: Int): Int =
      (0xFF shl 24) or ((red and 0xFF) shl 16) or ((green and 0xFF) shl 8) or (blue and 0xFF)

    const val FLAG_BOLD = 1
    const val FLAG_ITALIC = 1 shl 1
    const val FLAG_DIM = 1 shl 2
    const val FLAG_UNDERLINE = 1 shl 3
    const val FLAG_STRIKETHROUGH = 1 shl 4
    const val FLAG_INVISIBLE = 1 shl 5
    const val FLAG_INVERSE = 1 shl 6
    const val DEFAULT_FOREGROUND = 0xFFF2F4F5.toInt()
    const val DEFAULT_BACKGROUND = 0xFF090C0D.toInt()
    private const val MAX_CSI_CHARS = 128
    /** CSI parameter groups kept per sequence; xterm likewise drops the rest. */
    private const val MAX_CSI_PARAMS = 32
    private const val CSI_MISSING = Long.MIN_VALUE
    private const val MULTIPLE = -1
    private const val MAX_OSC_CHARS = 65_536
    private const val MAX_CELL_CODE_UNITS = 64
    private const val ZERO_WIDTH: Byte = 0
    /** Shared single-character strings so printing ASCII allocates nothing. */
    private val ASCII_CELL_TEXT = Array(0x80) { it.toChar().toString() }
    /** DEC Special Graphics for 0x60..0x7e, as xterm's CHARSETS['0']. */
    private const val DEC_SPECIAL_GRAPHICS =
      "\u25c6\u2592\u2409\u240c\u240d\u240a\u00b0\u00b1\u2424\u240b\u2518\u2510\u250c\u2514\u253c\u23ba" +
        "\u23bb\u2500\u23bc\u23bd\u251c\u2524\u2534\u252c\u2502\u2264\u2265\u03c0\u2260\u00a3\u00b7"
    private const val MAX_SCROLLBACK_ROWS = 200
    private const val NATIVE_FRAME_BATCH_NANOS = 16_000_000L
    private const val NATIVE_FRAME_QUIET_NANOS = 3_000_000L
    private const val SYNCHRONIZED_OUTPUT_TIMEOUT_NANOS = 150_000_000L
    private val ANSI_COLORS = intArrayOf(
      0xFF000000.toInt(), 0xFFCD0000.toInt(), 0xFF00CD00.toInt(), 0xFFCDCD00.toInt(),
      0xFF0000EE.toInt(), 0xFFCD00CD.toInt(), 0xFF00CDCD.toInt(), 0xFFE5E5E5.toInt(),
      0xFF7F7F7F.toInt(), 0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFFFFFF00.toInt(),
      0xFF5C5CFF.toInt(), 0xFFFF00FF.toInt(), 0xFF00FFFF.toInt(), 0xFFFFFFFF.toInt(),
    )
  }
}
