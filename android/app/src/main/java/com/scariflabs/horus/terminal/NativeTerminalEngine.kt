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
import java.util.concurrent.atomic.AtomicReference

import com.scariflabs.horus.terminal.NativeTerminalEngine.Frame
import com.scariflabs.horus.terminal.NativeTerminalEngine.FrameRow

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
  @Volatile private var defaultBackend: TerminalEngineBackend = TerminalEngineBackend.LIBGHOSTTY

  fun setDefaultBackend(backend: TerminalEngineBackend) {
    defaultBackend = backend
  }

  fun getDefaultBackend(): TerminalEngineBackend = defaultBackend

  fun configureOutputGapListener(listener: ((String) -> Unit)?) {
    outputGapListener = listener
  }

  /**
   * Writes to the session's PTY. [userInput] (typed or pasted text, keys)
   * also brings the view back to the output, like a desktop terminal.
   */
  fun writeInput(sessionId: String, bytes: ByteArray, userInput: Boolean = true): Boolean {
    if (sessionId.isEmpty() || bytes.isEmpty()) return false
    val writer = inputWriter ?: return false
    if (userInput) noteUserInput(sessionId)
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

  /** The session's engine, created at [rows] x [columns] if it has none; an existing engine keeps its size. */
  fun attach(sessionId: String, rows: Int, columns: Int): NativeTerminalEngine = obtain(sessionId, rows, columns)

  /** Input reached the session from outside the engine (for example the key bar). */
  fun noteUserInput(sessionId: String) {
    engines[sessionId]?.noteUserInput()
  }

  private fun obtain(sessionId: String, rows: Int, columns: Int): NativeTerminalEngine =
    engines.computeIfAbsent(sessionId) {
      NativeTerminalEngine(
        sessionId,
        rows,
        columns,
        clipboardWriter = { payload -> clipboardWriter?.invoke(payload) },
        onOutputGap = { outputGapListener?.invoke(sessionId) },
        backend = defaultBackend,
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
  val backend: TerminalEngineBackend = NativeTerminalEngineRegistry.getDefaultBackend(),
  private val inputWriter: (String, ByteArray) -> Unit,
) : ITerminalBackend {

  constructor(
    sessionId: String,
    rows: Int,
    columns: Int,
    inputWriter: (String, ByteArray) -> Unit,
  ) : this(sessionId, rows, columns, {}, {}, NativeTerminalEngineRegistry.getDefaultBackend(), inputWriter)

  constructor(
    sessionId: String,
    rows: Int,
    columns: Int,
    clipboardWriter: (String) -> Unit,
    onOutputGap: () -> Unit,
    inputWriter: (String, ByteArray) -> Unit,
  ) : this(sessionId, rows, columns, clipboardWriter, onOutputGap, NativeTerminalEngineRegistry.getDefaultBackend(), inputWriter)

  internal val delegate: ITerminalBackend = when (backend) {
    TerminalEngineBackend.LIBGHOSTTY -> LibghosttyTerminalBackend(
      sessionId, rows, columns, clipboardWriter, onOutputGap, inputWriter
    )
    TerminalEngineBackend.LEGACY -> LegacyTerminalEngine(
      sessionId, rows, columns, clipboardWriter, onOutputGap, inputWriter
    )
  }

  /**
   * Runs [block] with the live libghostty handle, or returns null when this
   * session uses the legacy engine or is closed. The handle cannot be freed
   * while [block] runs.
   */
  internal fun <T> withNativeHandle(block: (Long) -> T): T? =
    (delegate as? LibghosttyTerminalBackend)?.withHandle(block)

  private val native: LibghosttyTerminalBackend? get() = delegate as? LibghosttyTerminalBackend

  /**
   * Moves the viewport: follow the active area, or show screen row [topRow]
   * (0 is the top of the scrollback) first. [above]/[below] rows around the
   * viewport are captured too, so a canvas can scroll smoothly between
   * frames. The legacy engine keeps all its rows in every frame instead.
   */
  fun setViewport(follow: Boolean, topRow: Int, above: Int, below: Int) {
    native?.setViewport(follow, topRow, above, below)
  }

  /*
   * Selection lives in the terminal, so it moves with the text as output
   * arrives or the view scrolls. Cells are (column, screen row). These run
   * on the caller's thread and republish the frame so the highlight updates.
   */
  /**
   * Selects the word at a cell. A press between words takes the nearest
   * word within two cells, as Android text fields do; otherwise just the cell.
   */
  fun selectWord(column: Int, screenRow: Int): IntArray? = native?.selection { h ->
    WORD_SEARCH_OFFSETS.firstNotNullOfOrNull { offset ->
      val near = column + offset
      if (near < 0) null else TerminalPtyJni.nativeEngineSelectWord(h, near, screenRow)
    } ?: if (TerminalPtyJni.nativeEngineSelectRange(h, column, screenRow, column, screenRow)) {
      intArrayOf(column, screenRow, column, screenRow)
    } else null
  }

  fun selectRange(startColumn: Int, startRow: Int, endColumn: Int, endRow: Int): Boolean = native?.selection { h ->
    TerminalPtyJni.nativeEngineSelectRange(h, startColumn, startRow, endColumn, endRow)
  } == true

  fun selectAll(): Boolean = native?.selection(TerminalPtyJni::nativeEngineSelectAll) == true

  fun clearSelection() {
    native?.selection(TerminalPtyJni::nativeEngineClearSelection)
  }

  /** {startColumn, startRow, endColumn, endRow}, inclusive, in screen rows; or null. */
  fun selectionBounds(): IntArray? = withNativeHandle(TerminalPtyJni::nativeEngineSelectionBounds)

  fun selectionText(): String? = withNativeHandle(TerminalPtyJni::nativeEngineSelectionText)

  /** The frame to draw. */
  fun displayFrame(): Frame = delegate.currentFrame()

  /** Counts user input, so a canvas can return to the output when the user types. */
  @Volatile var inputCount = 0L
    private set

  fun noteUserInput() {
    inputCount += 1
    delegate.wakeListeners()
  }

  override val bracketedPaste: Boolean get() = delegate.bracketedPaste
  override val defaultBackground: Int get() = delegate.defaultBackground
  override val defaultForeground: Int get() = delegate.defaultForeground
  override fun currentFrame(): Frame = delegate.currentFrame()
  override fun hasListeners(): Boolean = delegate.hasListeners()
  override fun addListener(listener: () -> Unit): AutoCloseable = delegate.addListener(listener)
  override fun enqueue(seq: Long, bytes: ByteArray): Boolean = delegate.enqueue(seq, bytes)
  override fun resize(newRows: Int, newColumns: Int) = delegate.resize(newRows, newColumns)
  override fun close() = delegate.close()
  override fun recoverFromOutputGap() = delegate.recoverFromOutputGap()
  override fun wakeListeners() = delegate.wakeListeners()

  init {
    TerminalDebugLog.record("terminal_engine_init|sessionId=$sessionId|backend=${backend.name}")
    runCatching {
      android.util.Log.i("NativeTerminalEngine", "Initialized engine for session $sessionId with backend ${backend.name}")
    }
  }

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
    /** Frame rows before the viewport's first row. */
    val overscanAbove: Int = (contentRows - rows).coerceAtLeast(0),
    /** Screen row (0 is the top of the scrollback) of the viewport's first row. */
    val viewportTop: Int = overscanAbove,
    /** Rows of scrollback plus the active screen. */
    val totalRows: Int = viewportTop + rows,
    /** The viewport follows the active area rather than showing scrollback. */
    val followingActive: Boolean = true,
    /** Selected columns per frame row as [start, end) pairs, -1 when unselected; null without a selection. */
    val selection: IntArray? = null,
    /** Two longs per frame row identifying it across frames; null when rows have no identity. */
    val rowIds: LongArray? = null,
    val cursorStyle: Int = CURSOR_STYLE_BLOCK,
    val defaultForegroundColor: Int = DEFAULT_FOREGROUND,
    val defaultBackgroundColor: Int = DEFAULT_BACKGROUND,
    /** Mode 1007: the wheel sends arrow keys on the alternate screen. */
    val mouseAlternateScroll: Boolean = false,
  ) {
    /** Screen row of frame row [index]. */
    fun screenRow(index: Int): Int = viewportTop - overscanAbove + index

    /** Frame row of [screenRow], which may be outside the frame. */
    fun frameRow(screenRow: Int): Int = screenRow - viewportTop + overscanAbove

    /** Screen row of the active area's first row. */
    val activeTop: Int get() = totalRows - rows
  }

  companion object {
    const val FLAG_BOLD = 1
    const val FLAG_ITALIC = 1 shl 1
    const val FLAG_DIM = 1 shl 2
    const val FLAG_UNDERLINE = 1 shl 3
    const val FLAG_STRIKETHROUGH = 1 shl 4
    const val FLAG_INVISIBLE = 1 shl 5
    const val FLAG_INVERSE = 1 shl 6
    /** Cells tried, in order, when a long press lands between words. */
    private val WORD_SEARCH_OFFSETS = listOf(0, 1, -1, 2, -2)

    const val CURSOR_STYLE_BAR = 0
    const val CURSOR_STYLE_BLOCK = 1
    const val CURSOR_STYLE_UNDERLINE = 2
    const val CURSOR_STYLE_BLOCK_HOLLOW = 3
    const val DEFAULT_FOREGROUND = 0xFFF2F4F5.toInt()
    const val DEFAULT_BACKGROUND = 0xFF090C0D.toInt()

    val ANSI_COLORS = intArrayOf(
      0xFF000000.toInt(), 0xFFCD0000.toInt(), 0xFF00CD00.toInt(), 0xFFCDCD00.toInt(),
      0xFF0000EE.toInt(), 0xFFCD00CD.toInt(), 0xFF00CDCD.toInt(), 0xFFE5E5E5.toInt(),
      0xFF7F7F7F.toInt(), 0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFFFFFF00.toInt(),
      0xFF5C5CFF.toInt(), 0xFFFF00FF.toInt(), 0xFF00FFFF.toInt(), 0xFFFFFFFF.toInt(),
    )
  }

}

internal class LibghosttyTerminalBackend(
  private val sessionId: String,
  rows: Int,
  columns: Int,
  private val clipboardWriter: (String) -> Unit = {},
  private val onOutputGap: () -> Unit = {},
  private val inputWriter: (String, ByteArray) -> Unit,
) : ITerminalBackend {
  companion object {
    private const val DEFAULT_RENDER_BUFFER_SIZE = 1024 * 1024
    private val ASCII_CELL_TEXT = Array(0x80) { it.toChar().toString() }
    private const val NATIVE_FRAME_BATCH_NANOS = 16_000_000L
    private const val NATIVE_FRAME_QUIET_NANOS = 3_000_000L
    private const val SYNCHRONIZED_OUTPUT_TIMEOUT_NANOS = 150_000_000L
    private const val MAX_RENDER_BUFFER_GROWTHS = 4

    // Frame layout; must match terminal_pty.c.
    private const val HEADER_BYTES = 128
    private const val ROW_BYTES = 40
    private const val CELL_BYTES = 12
    private const val NO_CELLS = -1
    private const val NO_SELECTION = 0xFFFF
    private const val HDR_COLS = 1
    private const val HDR_CURSOR_ROW = 2
    private const val HDR_CURSOR_COL = 3
    private const val HDR_CURSOR_VISIBLE = 4
    private const val HDR_ALTERNATE = 5
    private const val HDR_MOUSE_TRACKING = 6
    private const val HDR_MOUSE_SGR = 7
    private const val HDR_BRACKETED_PASTE = 8
    private const val HDR_OVERSCAN_ABOVE = 9
    private const val HDR_VIEWPORT_ROWS = 10
    private const val HDR_DIRTY = 11
    private const val HDR_SCROLL_TOTAL = 14
    private const val HDR_SCROLL_OFFSET = 15
    private const val HDR_VIEWPORT_ACTIVE = 17
    private const val HDR_HAS_SELECTION = 18
    private const val HDR_CURSOR_STYLE = 19
    private const val HDR_DEFAULT_FG = 21
    private const val HDR_DEFAULT_BG = 22
    private const val HDR_MOUSE_ALTERNATE_SCROLL = 23
    private const val HDR_SLOTS = 32
    private const val DIRTY_FALSE = 0
  }

  override val defaultBackground: Int get() = NativeTerminalEngine.DEFAULT_BACKGROUND
  override val defaultForeground: Int get() = NativeTerminalEngine.DEFAULT_FOREGROUND

  private sealed interface Command {
    data class Output(val seq: Long, val bytes: ByteArray) : Command
    data class Resize(val rows: Int, val columns: Int) : Command
    /** Publish a frame now: the viewport or the selection changed. */
    object Wake : Command
  }

  private data class RowKey(val b0: Long, val b1: Long)

  private data class ViewportRequest(val follow: Boolean, val topRow: Int, val above: Int, val below: Int)

  /** Guards [nativeHandle]: every native call and the final destroy hold it. */
  private val nativeLock = Any()
  private var nativeHandle: Long = 0L

  private val commands = ArrayBlockingQueue<Command>(1024)
  private val closed = AtomicBoolean(false)
  private val highestQueuedSeq = AtomicLong(0)
  private var lastAppliedSeq = 0L
  private val pendingViewport = AtomicReference<ViewportRequest?>(null)
  private val wakeQueued = AtomicBoolean(false)

  @Volatile override var bracketedPaste = false
    private set

  private var synchronizedOutput = false
  private var synchronizedOutputStartedAt = 0L
  private var utf8Tail = ByteArray(0)
  private val listeners = CopyOnWriteArrayList<() -> Unit>()

  @Volatile private var requestedRows = rows.coerceIn(2, 250)
  @Volatile private var requestedColumns = columns.coerceIn(2, 500)

  private var renderBuffer: ByteBuffer =
    ByteBuffer.allocateDirect(DEFAULT_RENDER_BUFFER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
  private val header = IntArray(HDR_SLOTS)
  private val lastHeader = IntArray(HDR_SLOTS)
  private var lastHeaderValid = false

  private var normalRowCache = HashMap<RowKey, FrameRow>()
  private var alternateRowCache = HashMap<RowKey, FrameRow>()

  @Volatile private var publishedFrame: Frame

  private val parserThread: Thread

  init {
    val h = TerminalPtyJni.nativeEngineCreate(requestedColumns, requestedRows, -1)
    check(h != 0L) { "Failed to create libghostty-vt native terminal engine" }
    nativeHandle = h

    publishedFrame = syncRender() ?: createEmptyFrame()

    parserThread = Thread({ parseLoop() }, "opencode-native-terminal").apply {
      isDaemon = true
      start()
    }
  }

  override fun currentFrame(): Frame = publishedFrame

  override fun hasListeners(): Boolean = listeners.isNotEmpty()

  override fun wakeListeners() {
    listeners.forEach { listener -> runCatching { listener() } }
  }

  override fun addListener(listener: () -> Unit): AutoCloseable {
    listeners.add(listener)
    return AutoCloseable { listeners.remove(listener) }
  }

  override fun enqueue(seq: Long, bytes: ByteArray): Boolean {
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

  override fun resize(newRows: Int, newColumns: Int) {
    if (closed.get()) return
    val boundedRows = newRows.coerceIn(2, 250)
    val boundedColumns = newColumns.coerceIn(2, 500)
    if (requestedRows == boundedRows && requestedColumns == boundedColumns) return
    requestedRows = boundedRows
    requestedColumns = boundedColumns
    commands.offer(Command.Resize(boundedRows, boundedColumns))
  }

  /** Applied on the parser thread before the next frame, which is published at once. */
  fun setViewport(follow: Boolean, topRow: Int, above: Int, below: Int) {
    if (closed.get()) return
    pendingViewport.set(ViewportRequest(follow, topRow.coerceAtLeast(0), above.coerceIn(0, 1000), below.coerceIn(0, 1000)))
    wake()
  }

  /** Runs a selection change under the native lock, then republishes. */
  fun <T> selection(block: (Long) -> T): T? {
    val result = withHandle(block)
    wake()
    return result
  }

  private fun wake() {
    // One queued wake is enough; a full queue means a publish is coming anyway.
    if (wakeQueued.compareAndSet(false, true) && !commands.offer(Command.Wake)) wakeQueued.set(false)
  }

  override fun close() {
    if (!closed.compareAndSet(false, true)) return
    commands.clear()
    parserThread.interrupt()
    listeners.clear()
    // The parser thread may be inside a native call; wait for it before freeing.
    synchronized(nativeLock) {
      val h = nativeHandle
      nativeHandle = 0L
      if (h != 0L) {
        TerminalPtyJni.nativeEngineDestroy(h)
      }
    }
  }

  internal fun <T> withHandle(block: (Long) -> T): T? = synchronized(nativeLock) {
    val h = nativeHandle
    if (h == 0L) null else block(h)
  }

  private fun parseLoop() {
    try {
      while (!closed.get()) {
        val first = commands.take()
        apply(first)

        // Output is batched into one frame; a wake (scrolling, selecting)
        // publishes straight away so touch never waits for a batch.
        if (first !is Command.Wake) {
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
            if (next is Command.Wake && !synchronizedOutput) break
            lastAppliedAt = System.nanoTime()
          }
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
          val (reply, clip) = withHandle { h ->
            TerminalPtyJni.nativeEngineFeed(h, readyBytes, 0, readyBytes.size) to
              TerminalPtyJni.nativeEnginePollClipboard(h)
          } ?: return
          if (reply != null && reply.isNotEmpty()) {
            runCatching { inputWriter(sessionId, reply) }
          }
          if (clip != null && clip.isNotEmpty()) {
            runCatching { clipboardWriter(clip) }
          }
        }
        val syncNow = withHandle(TerminalPtyJni::nativeEngineIsSynchronizedOutput) ?: return
        if (syncNow && !synchronizedOutput) {
          synchronizedOutputStartedAt = System.nanoTime()
        }
        synchronizedOutput = syncNow
        lastAppliedSeq = command.seq
      }
      is Command.Resize -> resizeNow(command.rows, command.columns)
      Command.Wake -> wakeQueued.set(false)
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

  override fun recoverFromOutputGap() {
    val resetBytes = "\u0018\u001b[!p\u001b[m".toByteArray(StandardCharsets.UTF_8)
    withHandle { h -> TerminalPtyJni.nativeEngineFeed(h, resetBytes, 0, resetBytes.size) }
    synchronizedOutput = false
    utf8Tail = ByteArray(0)
    normalRowCache.clear()
    alternateRowCache.clear()
    lastHeaderValid = false
    runCatching { onOutputGap() }
  }

  private fun resizeNow(newRows: Int, newColumns: Int) {
    withHandle { h -> TerminalPtyJni.nativeEngineResize(h, newColumns, newRows, 0, 0) }
  }

  private fun publish() {
    pendingViewport.getAndSet(null)?.let { request ->
      withHandle { h ->
        TerminalPtyJni.nativeEngineSetViewport(h, request.follow, request.topRow.toLong(), request.above, request.below)
      }
    }
    // Nothing on screen changed (output scrolled past a pinned viewport, or
    // a no-op wake): keep the frame and skip the redraw.
    val frame = syncRender() ?: return
    publishedFrame = frame
    listeners.forEach { listener -> runCatching { listener() } }
  }

  /**
   * Reads a frame from the native engine, or null when nothing changed since
   * the last one. Clean rows arrive without cells and come from the row cache;
   * a clean row missing from the cache forces one full re-read.
   */
  private fun syncRender(): Frame? {
    val first = readFrame(forceCells = false)
    if (first !== CacheMiss) return first as Frame?
    val full = readFrame(forceCells = true)
    return if (full === CacheMiss) null else full as Frame?
  }

  /** Returned by [readFrame] when a clean row is missing from the row cache. */
  private object CacheMiss

  /** A [Frame], null when unchanged, or [CacheMiss]. */
  private fun readFrame(forceCells: Boolean): Any? {
    var res = withHandle { h -> TerminalPtyJni.nativeEngineSyncRender(h, renderBuffer, forceCells) }
      ?: return createEmptyFrame()
    // Long graphemes can outgrow the native size estimate, so allow a few doublings.
    var attempts = 0
    while (res.getOrNull(0) == -1 && attempts < MAX_RENDER_BUFFER_GROWTHS) {
      val needed = res[1]
      val nextCap = maxOf(needed, renderBuffer.capacity() * 2)
      renderBuffer = ByteBuffer.allocateDirect(nextCap).order(ByteOrder.LITTLE_ENDIAN)
      // The failed attempt did not consume the dirty state, so ask for every cell.
      res = withHandle { h -> TerminalPtyJni.nativeEngineSyncRender(h, renderBuffer, true) }
        ?: return createEmptyFrame()
      attempts += 1
    }
    if (res[0] != 0) return null

    val totalRows = res[2]
    val buf = renderBuffer
    for (slot in 0 until HDR_SLOTS) header[slot] = buf.getInt(slot * 4)
    val dirty = header[HDR_DIRTY]
    if (!forceCells && dirty == DIRTY_FALSE && lastHeaderValid && header.contentEqualsIgnoringDirty(lastHeader)) {
      return null
    }
    header.copyInto(lastHeader)
    lastHeaderValid = true

    val cols = header[HDR_COLS]
    bracketedPaste = header[HDR_BRACKETED_PASTE] != 0

    val isAlt = header[HDR_ALTERNATE] != 0
    val hasSelection = header[HDR_HAS_SELECTION] != 0
    val activeCache = if (isAlt) alternateRowCache else normalRowCache
    val nextCache = HashMap<RowKey, FrameRow>(totalRows * 2)
    val rowIds = LongArray(totalRows * 2)
    val selection = if (hasSelection) IntArray(totalRows * 2) { -1 } else null
    val lines = arrayOfNulls<FrameRow>(totalRows)
    for (r in 0 until totalRows) {
      val rowEntryOffset = HEADER_BYTES + r * ROW_BYTES
      val b0 = buf.getLong(rowEntryOffset)
      val b1 = buf.getLong(rowEntryOffset + 8)
      val rowCols = buf.getShort(rowEntryOffset + 16).toInt() and 0xFFFF
      val dirtyByte = buf.get(rowEntryOffset + 18)
      val wrapByte = buf.get(rowEntryOffset + 19)
      val padByte = buf.get(rowEntryOffset + 20)
      val cellOffset = buf.getInt(rowEntryOffset + 24)
      val textOffset = buf.getInt(rowEntryOffset + 28)
      rowIds[r * 2] = b0
      rowIds[r * 2 + 1] = b1
      if (selection != null) {
        val selStart = buf.getShort(rowEntryOffset + 32).toInt() and 0xFFFF
        val selEnd = buf.getShort(rowEntryOffset + 34).toInt() and 0xFFFF
        if (selStart != NO_SELECTION && selEnd != NO_SELECTION) {
          selection[r * 2] = selStart
          selection[r * 2 + 1] = selEnd + 1
        }
      }

      val key = RowKey(b0, b1)
      val cached = activeCache[key]
      if (cellOffset == NO_CELLS) {
        if (cached == null || cached.columns != rowCols) return CacheMiss
        nextCache[key] = cached
        lines[r] = cached
        continue
      }
      if (cached != null && cached.columns == rowCols) {
        if (dirtyByte.toInt() == 0 || rowMatches(cached, rowCols, wrapByte, padByte, cellOffset, textOffset, buf)) {
          nextCache[key] = cached
          lines[r] = cached
          continue
        }
      }

      val newRow = readRow(buf, rowCols, wrapByte, padByte, cellOffset, textOffset)
      nextCache[key] = newRow
      lines[r] = newRow
    }
    @Suppress("UNCHECKED_CAST")
    val frameLines = lines as Array<FrameRow>

    if (isAlt) {
      alternateRowCache = nextCache
    } else {
      normalRowCache = nextCache
    }

    var lastContentRow = frameLines.size - 1
    while (lastContentRow >= 0 && !frameLines[lastContentRow].hasVisibleContent) lastContentRow -= 1

    val viewportRows = header[HDR_VIEWPORT_ROWS]
    val overscanAbove = header[HDR_OVERSCAN_ABOVE]
    val cursorRow = header[HDR_CURSOR_ROW]
    return Frame(
      rows = viewportRows,
      columns = cols,
      contentRows = totalRows,
      lines = frameLines,
      cursorRow = if (cursorRow in 0 until totalRows) cursorRow else -1,
      cursorColumn = header[HDR_CURSOR_COL].coerceIn(0, (cols - 1).coerceAtLeast(0)),
      cursorVisible = header[HDR_CURSOR_VISIBLE] != 0 && cursorRow in 0 until totalRows,
      alternate = isAlt,
      hasVisibleContent = lastContentRow >= 0,
      lastContentRow = lastContentRow,
      mouseTracking = header[HDR_MOUSE_TRACKING] != 0,
      mouseSgr = header[HDR_MOUSE_SGR] != 0,
      overscanAbove = overscanAbove,
      viewportTop = header[HDR_SCROLL_OFFSET],
      totalRows = maxOf(header[HDR_SCROLL_TOTAL], header[HDR_SCROLL_OFFSET] + viewportRows),
      followingActive = header[HDR_VIEWPORT_ACTIVE] != 0,
      selection = selection,
      rowIds = rowIds,
      cursorStyle = header[HDR_CURSOR_STYLE],
      defaultForegroundColor = header[HDR_DEFAULT_FG],
      defaultBackgroundColor = header[HDR_DEFAULT_BG],
      mouseAlternateScroll = header[HDR_MOUSE_ALTERNATE_SCROLL] != 0,
    )
  }

  private fun IntArray.contentEqualsIgnoringDirty(other: IntArray): Boolean {
    for (slot in indices) {
      if (slot != HDR_DIRTY && this[slot] != other[slot]) return false
    }
    return true
  }

  private fun readRow(buf: ByteBuffer, rowCols: Int, wrapByte: Byte, padByte: Byte, cellOffset: Int, textOffset: Int): FrameRow {
    val rowTexts = Array(rowCols) { "" }
    val rowWidths = ByteArray(rowCols)
    val rowFg = IntArray(rowCols)
    val rowBg = IntArray(rowCols)
    val rowFlags = IntArray(rowCols)

    var textPos = textOffset
    for (c in 0 until rowCols) {
      val cPtr = cellOffset + c * CELL_BYTES
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

    return FrameRow(
      text = rowTexts,
      width = rowWidths,
      foreground = rowFg,
      background = rowBg,
      flags = rowFlags,
      wrapped = wrapByte.toInt() != 0,
      wrapPadding = padByte.toInt() != 0,
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
      val cPtr = cellOffset + c * CELL_BYTES
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
      foreground = IntArray(requestedColumns) { NativeTerminalEngine.DEFAULT_FOREGROUND },
      background = IntArray(requestedColumns) { NativeTerminalEngine.DEFAULT_BACKGROUND },
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


}
