package com.scariflabs.horus.terminal

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Native terminal state backed by libghostty-vt.
 *
 * Ordered output arrives in the RN process through the Messenger boundary,
 * enqueued into this bounded parser queue. Native libghostty-vt processes
 * VT sequences, state transitions, Unicode cluster layouts, and render sync.
 * The Canvas view reads immutable published frames.
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
   * Receives the session id when an engine skips output it never saw. The caller should make the app redraw.
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
   * frames until the row changes. Inverse video is resolved in native code:
   * foreground and background are already swapped and FLAG_INVERSE is cleared.
   */
  internal class FrameRow(
    val text: Array<String>,
    val width: ByteArray,
    val foreground: IntArray,
    val background: IntArray,
    val flags: IntArray,
    /** True when this row continues a line that soft-wrapped from above. */
    val wrapped: Boolean,
    /** Continuation follows a wide character that skipped the preceding last cell. */
    val wrapPadding: Boolean = false,
  ) {
    val columns: Int get() = text.size
    val hasVisibleContent: Boolean = text.any { it.isNotBlank() }
    val links: IntArray = NativeTerminalLinks.detect(text, width)
  }

  /**
   * [lines] holds scrollback rows followed by the active screen rows.
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
    val lastContentRow: Int,
    val mouseTracking: Boolean,
    val mouseSgr: Boolean,
  )

  private sealed interface Command {
    data class Output(val seq: Long, val bytes: ByteArray) : Command
    data class Resize(val rows: Int, val columns: Int) : Command
  }

  private data class RowKey(val b0: Long, val b1: Long)

  var nativeHandle: Long = 0L
    private set

  private val commands = ArrayBlockingQueue<Command>(1024)
  private val closed = AtomicBoolean(false)
  private val highestQueuedSeq = AtomicLong(0)
  private var lastAppliedSeq = 0L

  @Volatile var bracketedPaste = false
    private set

  private var synchronizedOutput = false
  private var synchronizedOutputStartedAt = 0L
  private var utf8Tail = ByteArray(0)
  private val listeners = CopyOnWriteArrayList<() -> Unit>()

  @Volatile private var requestedRows = rows.coerceIn(2, 250)
  @Volatile private var requestedColumns = columns.coerceIn(2, 500)

  private var renderBuffer: ByteBuffer =
    ByteBuffer.allocateDirect(DEFAULT_RENDER_BUFFER_SIZE).order(ByteOrder.LITTLE_ENDIAN)

  private var normalRowCache = HashMap<RowKey, FrameRow>()
  private var alternateRowCache = HashMap<RowKey, FrameRow>()

  @Volatile private var publishedFrame: Frame

  private val parserThread: Thread

  init {
    val h = TerminalPtyJni.nativeEngineCreate(requestedColumns, requestedRows, -1)
    check(h != 0L) { "Failed to create libghostty-vt native terminal engine" }
    nativeHandle = h

    publishedFrame = syncRender()

    parserThread = Thread({ parseLoop() }, "opencode-native-terminal").apply {
      isDaemon = true
      start()
    }
  }

  fun currentFrame(): Frame = publishedFrame

  fun hasListeners(): Boolean = listeners.isNotEmpty()

  fun addListener(listener: () -> Unit): AutoCloseable {
    listeners.add(listener)
    return AutoCloseable { listeners.remove(listener) }
  }

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
    val h = nativeHandle
    nativeHandle = 0L
    if (h != 0L) {
      TerminalPtyJni.nativeEngineDestroy(h)
    }
  }

  private fun parseLoop() {
    try {
      while (!closed.get()) {
        apply(commands.take())

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
    } catch (_: Throwable) {
      if (command is Command.Output) lastAppliedSeq = maxOf(lastAppliedSeq, command.seq)
    }
  }

  private fun applyUnchecked(command: Command) {
    when (command) {
      is Command.Output -> {
        if (command.seq <= lastAppliedSeq) return
        if (command.seq != lastAppliedSeq + 1L) recoverFromOutputGap()
        val readyBytes = prepareUtf8Bytes(command.bytes)
        if (readyBytes.isNotEmpty()) {
          val reply = TerminalPtyJni.nativeEngineFeed(nativeHandle, readyBytes, 0, readyBytes.size)
          if (reply != null && reply.isNotEmpty()) {
            runCatching { inputWriter(sessionId, reply) }
          }
          val clip = TerminalPtyJni.nativeEnginePollClipboard(nativeHandle)
          if (clip != null && clip.isNotEmpty()) {
            runCatching { clipboardWriter(clip) }
          }
        }
        val syncNow = TerminalPtyJni.nativeEngineIsSynchronizedOutput(nativeHandle)
        if (syncNow && !synchronizedOutput) {
          synchronizedOutputStartedAt = System.nanoTime()
        }
        synchronizedOutput = syncNow
        lastAppliedSeq = command.seq
      }
      is Command.Resize -> resizeNow(command.rows, command.columns)
    }
  }

  private fun prepareUtf8Bytes(bytes: ByteArray): ByteArray {
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
    return if (carryStart == merged.size) merged else merged.copyOfRange(0, carryStart)
  }

  private fun isContinuation(value: Byte): Boolean = value.toInt() and 0xc0 == 0x80

  private fun recoverFromOutputGap() {
    if (nativeHandle != 0L) {
      val resetBytes = "\u0018\u001b[!p\u001b[m".toByteArray(StandardCharsets.UTF_8)
      TerminalPtyJni.nativeEngineFeed(nativeHandle, resetBytes, 0, resetBytes.size)
    }
    synchronizedOutput = false
    utf8Tail = ByteArray(0)
    normalRowCache.clear()
    alternateRowCache.clear()
    runCatching { onOutputGap() }
  }

  private fun resizeNow(newRows: Int, newColumns: Int) {
    if (nativeHandle != 0L) {
      TerminalPtyJni.nativeEngineResize(nativeHandle, newColumns, newRows, 0, 0)
    }
  }

  private fun publish() {
    publishedFrame = syncRender()
    listeners.forEach { listener -> runCatching { listener() } }
  }

  private fun syncRender(): Frame {
    if (nativeHandle == 0L) return createEmptyFrame()

    var res = TerminalPtyJni.nativeEngineSyncRender(nativeHandle, renderBuffer)
    if (res?.getOrNull(0) == -1) {
      val needed = res[1]
      val nextCap = maxOf(needed, renderBuffer.capacity() * 2)
      renderBuffer = ByteBuffer.allocateDirect(nextCap).order(ByteOrder.LITTLE_ENDIAN)
      res = TerminalPtyJni.nativeEngineSyncRender(nativeHandle, renderBuffer)
    }
    if (res == null || res[0] != 0) {
      return runCatching { publishedFrame }.getOrNull() ?: createEmptyFrame()
    }

    val totalRows = res[2]
    val buf = renderBuffer

    val cols = buf.getInt(4)
    val cursorRowHdr = buf.getInt(8)
    val cursorColHdr = buf.getInt(12)
    val cursorVisHdr = buf.getInt(16)
    val altHdr = buf.getInt(20)
    val mouseTrackingHdr = buf.getInt(24)
    val mouseSgrHdr = buf.getInt(28)
    val bracketedPasteHdr = buf.getInt(32)
    val scrollbackRowsHdr = buf.getInt(36)
    val viewportRowsHdr = buf.getInt(40)

    bracketedPaste = bracketedPasteHdr != 0

    val isAlt = altHdr != 0
    val activeCache = if (isAlt) alternateRowCache else normalRowCache
    val nextCache = HashMap<RowKey, FrameRow>(totalRows * 2)
    val lines = Array(totalRows) { r ->
      val rowEntryOffset = 64 + r * 32
      val b0 = buf.getLong(rowEntryOffset)
      val b1 = buf.getLong(rowEntryOffset + 8)
      val rowCols = buf.getShort(rowEntryOffset + 16).toInt() and 0xFFFF
      val dirtyByte = buf.get(rowEntryOffset + 18)
      val wrapByte = buf.get(rowEntryOffset + 19)
      val padByte = buf.get(rowEntryOffset + 20)
      val cellOffset = buf.getInt(rowEntryOffset + 24)
      val textOffset = buf.getInt(rowEntryOffset + 28)

      val key = RowKey(b0, b1)
      val cached = activeCache[key]
      if (cached != null && cached.columns == rowCols) {
        if (dirtyByte.toInt() == 0 || rowMatches(cached, rowCols, wrapByte, padByte, cellOffset, textOffset, buf)) {
          nextCache[key] = cached
          return@Array cached
        }
      }

      val rowTexts = Array(rowCols) { "" }
      val rowWidths = ByteArray(rowCols)
      val rowFg = IntArray(rowCols)
      val rowBg = IntArray(rowCols)
      val rowFlags = IntArray(rowCols)

      var textPos = textOffset
      for (c in 0 until rowCols) {
        val cPtr = cellOffset + c * 12
        rowWidths[c] = buf.get(cPtr)
        val textLen = buf.get(cPtr + 1).toInt() and 0xFF
        rowFlags[c] = buf.getShort(cPtr + 2).toInt() and 0xFFFF
        rowFg[c] = buf.getInt(cPtr + 4)
        rowBg[c] = buf.getInt(cPtr + 8)

        if (textLen == 0) {
          rowTexts[c] = ""
        } else if (textLen == 1) {
          val b = buf.get(textPos).toInt() and 0xFF
          rowTexts[c] = if (b < ASCII_CELL_TEXT.size) ASCII_CELL_TEXT[b] else String(byteArrayOf(b.toByte()), StandardCharsets.UTF_8)
          textPos += 1
        } else {
          val strBytes = ByteArray(textLen)
          buf.position(textPos)
          buf.get(strBytes, 0, textLen)
          rowTexts[c] = String(strBytes, StandardCharsets.UTF_8)
          textPos += textLen
        }
      }

      val newRow = FrameRow(
        text = rowTexts,
        width = rowWidths,
        foreground = rowFg,
        background = rowBg,
        flags = rowFlags,
        wrapped = wrapByte.toInt() != 0,
        wrapPadding = padByte.toInt() != 0,
      )
      nextCache[key] = newRow
      newRow
    }

    if (isAlt) {
      alternateRowCache = nextCache
    } else {
      normalRowCache = nextCache
    }

    var lastContentRow = lines.size - 1
    while (lastContentRow >= 0 && !lines[lastContentRow].hasVisibleContent) lastContentRow -= 1

    return Frame(
      rows = viewportRowsHdr,
      columns = cols,
      contentRows = totalRows,
      lines = lines,
      cursorRow = cursorRowHdr.coerceIn(0, (totalRows - 1).coerceAtLeast(0)),
      cursorColumn = cursorColHdr.coerceIn(0, (cols - 1).coerceAtLeast(0)),
      cursorVisible = cursorVisHdr != 0,
      alternate = altHdr != 0,
      hasVisibleContent = lastContentRow >= 0,
      lastContentRow = lastContentRow,
      mouseTracking = mouseTrackingHdr != 0,
      mouseSgr = mouseSgrHdr != 0,
    )
  }

  private fun rowMatches(
    cached: FrameRow,
    cols: Int,
    wrapByte: Byte,
    padByte: Byte,
    cellOffset: Int,
    textOffset: Int,
    buf: ByteBuffer,
  ): Boolean {
    if (cached.columns != cols) return false
    if (cached.wrapped != (wrapByte.toInt() != 0)) return false
    if (cached.wrapPadding != (padByte.toInt() != 0)) return false

    var textPos = textOffset
    for (c in 0 until cols) {
      val cPtr = cellOffset + c * 12
      if (cached.width[c] != buf.get(cPtr)) return false
      val textLen = buf.get(cPtr + 1).toInt() and 0xFF
      if (cached.flags[c] != (buf.getShort(cPtr + 2).toInt() and 0xFFFF)) return false
      if (cached.foreground[c] != buf.getInt(cPtr + 4)) return false
      if (cached.background[c] != buf.getInt(cPtr + 8)) return false

      if (textLen == 0) {
        if (cached.text[c].isNotEmpty()) return false
      } else if (textLen == 1) {
        val b = buf.get(textPos).toInt() and 0xFF
        textPos += 1
        val expected = if (b < ASCII_CELL_TEXT.size) ASCII_CELL_TEXT[b] else String(byteArrayOf(b.toByte()), StandardCharsets.UTF_8)
        if (cached.text[c] != expected) return false
      } else {
        val strBytes = ByteArray(textLen)
        buf.position(textPos)
        buf.get(strBytes, 0, textLen)
        textPos += textLen
        if (cached.text[c] != String(strBytes, StandardCharsets.UTF_8)) return false
      }
    }
    return true
  }

  private fun createEmptyFrame(): Frame {
    val emptyRow = FrameRow(
      text = Array(requestedColumns) { " " },
      width = ByteArray(requestedColumns) { 1 },
      foreground = IntArray(requestedColumns) { DEFAULT_FOREGROUND },
      background = IntArray(requestedColumns) { DEFAULT_BACKGROUND },
      flags = IntArray(requestedColumns),
      wrapped = false,
    )
    val lines = Array(requestedRows) { emptyRow }
    return Frame(
      rows = requestedRows,
      columns = requestedColumns,
      contentRows = requestedRows,
      lines = lines,
      cursorRow = 0,
      cursorColumn = 0,
      cursorVisible = true,
      alternate = false,
      hasVisibleContent = false,
      lastContentRow = -1,
      mouseTracking = false,
      mouseSgr = false,
    )
  }

  companion object {
    const val FLAG_BOLD = 1
    const val FLAG_ITALIC = 1 shl 1
    const val FLAG_DIM = 1 shl 2
    const val FLAG_UNDERLINE = 1 shl 3
    const val FLAG_STRIKETHROUGH = 1 shl 4
    const val FLAG_INVISIBLE = 1 shl 5
    const val FLAG_INVERSE = 1 shl 6
    const val DEFAULT_FOREGROUND = 0xFFF2F4F5.toInt()
    const val DEFAULT_BACKGROUND = 0xFF090C0D.toInt()

    private const val DEFAULT_RENDER_BUFFER_SIZE = 2 * 1024 * 1024
    private val ASCII_CELL_TEXT = Array(0x80) { it.toChar().toString() }
    private const val NATIVE_FRAME_BATCH_NANOS = 16_000_000L
    private const val NATIVE_FRAME_QUIET_NANOS = 3_000_000L
    private const val SYNCHRONIZED_OUTPUT_TIMEOUT_NANOS = 150_000_000L

    val ANSI_COLORS = intArrayOf(
      0xFF000000.toInt(), 0xFFCD0000.toInt(), 0xFF00CD00.toInt(), 0xFFCDCD00.toInt(),
      0xFF0000EE.toInt(), 0xFFCD00CD.toInt(), 0xFF00CDCD.toInt(), 0xFFE5E5E5.toInt(),
      0xFF7F7F7F.toInt(), 0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFFFFFF00.toInt(),
      0xFF5C5CFF.toInt(), 0xFFFF00FF.toInt(), 0xFF00FFFF.toInt(), 0xFFFFFFFF.toInt(),
    )
  }
}
