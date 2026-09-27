package com.scariflabs.horus.terminal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.UIManagerHelper
import com.facebook.react.uimanager.events.Event
import com.facebook.react.bridge.WritableMap
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

private class NativeFrameMetaEvent(
  surfaceId: Int,
  viewTag: Int,
  private val frame: NativeTerminalEngine.Frame,
) : Event<NativeFrameMetaEvent>(surfaceId, viewTag) {
  override fun getEventName(): String = EVENT_NAME

  override fun canCoalesce(): Boolean = true

  override fun getEventData(): WritableMap = Arguments.createMap().apply {
    putBoolean("alternate", frame.alternate)
    putInt("contentRows", frame.contentRows)
    putInt("cursorRow", frame.cursorRow)
    putInt("lastContentRow", frame.lastContentRow)
    putBoolean("mouseTracking", frame.mouseTracking)
    putBoolean("mouseSgr", frame.mouseSgr)
  }

  companion object {
    const val EVENT_NAME = "topNativeFrameMeta"
  }
}

/**
 * A compact Canvas renderer for both the JS fallback snapshot and the native
 * interactive terminal engine. The native path keeps PTY parsing and frame
 * publication outside the React Native heap.
 */
class TerminalCanvasView(context: Context) : View(context) {
  private data class Cell(
    val text: String,
    val column: Int,
    val width: Int,
    val foreground: Int,
    val background: Int,
    val bold: Boolean,
    val italic: Boolean,
    val dim: Boolean,
    val underline: Boolean,
    val strikethrough: Boolean,
    val invisible: Boolean,
  )

  private data class Line(
    val cells: List<Cell>,
  )

  private data class Frame(
    val lines: List<Line>,
    val cursorRow: Int,
    val cursorColumn: Int,
    val cursorVisible: Boolean,
  )

  private data class LinkRange(
    val row: Int,
    val startColumn: Int,
    val endColumn: Int,
  )

  private data class TextStyle(
    val foreground: Int,
    val bold: Boolean,
    val italic: Boolean,
    val dim: Boolean,
    val underline: Boolean,
    val strikethrough: Boolean,
  )

  private val normalTypeface = loadTypeface("fonts/FiraCode.ttf", Typeface.MONOSPACE)
  private val boldTypeface = loadTypeface("fonts/FiraCode_bold.ttf", normalTypeface, bold = true)
  private val italicTypeface = Typeface.create(normalTypeface, Typeface.ITALIC)
  private val boldItalicTypeface = Typeface.create(boldTypeface, Typeface.BOLD_ITALIC)
  private val runText = StringBuilder()
  private val wrappedLinkCache = NativeTerminalLinks.WrappedLinkCache()
  private val visibleRect = Rect()
  private val fontMetrics = Paint.FontMetrics()
  // The canvas is as tall as scrollback + screen inside a ScrollView. Only
  // the rows in (or near) the viewport are recorded, so re-record on scroll.
  private val scrollListener = ViewTreeObserver.OnScrollChangedListener { invalidate() }
  private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
    isLinearText = true
  }
  private val backgroundColor = Color.parseColor("#090C0D")
  private val linkForeground = Color.parseColor("#80EB12")
  private val parsedColorCache = HashMap<String, Int>()
  private var frame: Frame? = null
  private var linksByRow: Map<Int, List<LinkRange>> = emptyMap()
  private var cellWidthDp = 8f
  private var cellHeightDp = 19f
  private var fontSizeDp = 13f
  private var running = false
  private var nativeSessionId: String? = null
  private var nativeRows = 24
  private var nativeColumns = 80
  private var nativeEngine: NativeTerminalEngine? = null
  private var nativeSubscription: AutoCloseable? = null
  private var nativeLoadingText: String? = null
  private var lastNativeFrameAlternate: Boolean? = null
  private var lastNativeFrameContentRows = -1
  private var lastNativeFrameCursorRow = -1
  private var lastNativeFrameLastContentRow = -1
  private var lastNativeFrameMouseTracking: Boolean? = null
  private var lastNativeFrameMouseSgr: Boolean? = null
  private var nativeCursorHiddenAt = 0L
  private var nativeCursorLastRow = -1
  private var nativeCursorLastColumn = -1
  private var loadingSweep = 0f

  init {
    setWillNotDraw(false)
    setBackgroundColor(backgroundColor)
    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
  }

  fun setFrame(value: ReadableMap?) {
    frame = value?.let(::readFrame)
    invalidate()
  }

  fun setNativeSessionId(value: String?) {
    if (nativeSessionId == value && (value == null || nativeEngine != null)) return
    nativeSessionId = value?.takeIf(String::isNotEmpty)
    lastNativeFrameAlternate = null
    lastNativeFrameContentRows = -1
    lastNativeFrameCursorRow = -1
    lastNativeFrameLastContentRow = -1
    lastNativeFrameMouseTracking = null
    lastNativeFrameMouseSgr = null
    nativeCursorHiddenAt = 0L
    nativeCursorLastRow = -1
    nativeCursorLastColumn = -1
    bindNativeEngine()
  }

  fun setNativeRows(value: Int) {
    nativeRows = value.coerceIn(2, 250)
    if (nativeSessionId != null) bindNativeEngine()
  }

  fun setNativeColumns(value: Int) {
    nativeColumns = value.coerceIn(2, 500)
    if (nativeSessionId != null) bindNativeEngine()
  }

  fun setNativeLoadingText(value: String?) {
    nativeLoadingText = value?.takeIf(String::isNotEmpty)
    invalidate()
  }

  fun setLinks(value: ReadableArray?) {
    if (value == null) {
      linksByRow = emptyMap()
      invalidate()
      return
    }
    val grouped = HashMap<Int, MutableList<LinkRange>>()
    for (index in 0 until value.size()) {
      val item = value.getMap(index) ?: continue
      val row = item.getIntOrNull("row") ?: continue
      val startColumn = item.getIntOrNull("startColumn") ?: continue
      val endColumn = item.getIntOrNull("endColumn") ?: continue
      if (row < 0 || startColumn < 0 || endColumn <= startColumn) continue
      grouped.getOrPut(row) { ArrayList() }.add(LinkRange(row, startColumn, endColumn))
    }
    linksByRow = grouped
    invalidate()
  }

  fun setCellWidth(value: Float) {
    if (value.isFinite() && value > 0f) {
      cellWidthDp = value
      invalidate()
    }
  }

  fun setCellHeight(value: Float) {
    if (value.isFinite() && value > 0f) {
      cellHeightDp = value
      invalidate()
    }
  }

  fun setFontSize(value: Float) {
    if (value.isFinite() && value > 0f) {
      fontSizeDp = value
      invalidate()
    }
  }

  fun setRunning(value: Boolean) {
    running = value
    invalidate()
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    viewTreeObserver.addOnScrollChangedListener(scrollListener)
    if (nativeSessionId != null) bindNativeEngine()
  }

  override fun onDetachedFromWindow() {
    viewTreeObserver.removeOnScrollChangedListener(scrollListener)
    nativeSubscription?.close()
    nativeSubscription = null
    super.onDetachedFromWindow()
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    canvas.drawColor(backgroundColor)
    val density = resources.displayMetrics.density
    val cellWidth = cellWidthDp * density
    val cellHeight = cellHeightDp * density
    if (!cellWidth.isFinite() || !cellHeight.isFinite() || cellWidth <= 0f || cellHeight <= 0f) return

    paint.textSize = fontSizeDp * density
    val currentNativeFrame = nativeEngine?.currentFrame()
    if (currentNativeFrame != null) {
      notifyNativeFrameMeta(currentNativeFrame)
      val loadingText = nativeLoadingText
      // During first-run provisioning the native parser is already receiving
      // the installer transcript in the normal screen. Show that bounded
      // transcript as soon as it has content; keep the spinner only while the
      // PTY is still blank or the alternate TUI has not painted yet.
      if (loadingText != null && !currentNativeFrame.hasVisibleContent) {
        drawLoading(canvas, loadingText, density)
        postInvalidateOnAnimation()
        return
      }
      drawNativeFrame(canvas, currentNativeFrame, cellWidth, cellHeight)
      if (running && updateNativeCursor(currentNativeFrame) && nativeCursorLastRow in 0 until currentNativeFrame.contentRows) {
        drawCursor(canvas, nativeCursorLastRow, nativeCursorLastColumn, cellWidth, cellHeight)
      }
      return
    }
    val loadingText = nativeLoadingText
    if (nativeSessionId != null && loadingText != null) {
      drawLoading(canvas, loadingText, density)
      postInvalidateOnAnimation()
      return
    }
    val currentFrame = frame ?: return
    val baselineOffset = baselineOffset(cellHeight)
    updateVisibleRect()
    val firstRow = max(0, floor(visibleRect.top / cellHeight).toInt())
    val lastRow = min(currentFrame.lines.size, ceil(visibleRect.bottom / cellHeight).toInt())
    for (rowIndex in firstRow until lastRow) {
      val line = currentFrame.lines[rowIndex]
      val rowTop = rowIndex * cellHeight
      drawLine(canvas, line, rowIndex, cellWidth, cellHeight, rowTop, rowTop + baselineOffset)
    }

    if (running && currentFrame.cursorVisible && currentFrame.cursorRow in currentFrame.lines.indices) {
      drawCursor(canvas, currentFrame.cursorRow, currentFrame.cursorColumn, cellWidth, cellHeight)
    }
  }

  private fun drawCursor(canvas: Canvas, row: Int, column: Int, cellWidth: Float, cellHeight: Float) {
    val cursorLeft = column.coerceAtLeast(0) * cellWidth
    paint.style = Paint.Style.FILL
    paint.color = CURSOR_COLOR
    canvas.drawRect(cursorLeft, row * cellHeight, cursorLeft + cellWidth, (row + 1) * cellHeight, paint)
  }

  /**
   * Full-screen TUIs briefly hide and restore the VT cursor around redraws.
   * Draw a visible cursor immediately at its current position, and ride out
   * short hides at the last visible position so a redraw does not flash the
   * caret. A hide that outlasts the grace period is honoured.
   */
  private fun updateNativeCursor(frame: NativeTerminalEngine.Frame): Boolean {
    if (frame.cursorVisible) {
      nativeCursorHiddenAt = 0L
      nativeCursorLastRow = frame.cursorRow
      nativeCursorLastColumn = frame.cursorColumn
      return true
    }
    if (nativeCursorLastRow < 0) return false
    val now = SystemClock.uptimeMillis()
    if (nativeCursorHiddenAt == 0L) nativeCursorHiddenAt = now
    val hiddenFor = now - nativeCursorHiddenAt
    if (hiddenFor < NATIVE_CURSOR_HIDE_GRACE_MS) {
      postInvalidateDelayed(NATIVE_CURSOR_HIDE_GRACE_MS - hiddenFor)
      return true
    }
    nativeCursorLastRow = -1
    nativeCursorLastColumn = -1
    return false
  }

  /**
   * Updates [visibleRect] to the part of this view that can be on screen,
   * padded by one viewport on each side so a fling reveals already-recorded
   * rows before the scroll listener re-records. Falls back to the full view.
   */
  private fun updateVisibleRect() {
    if (!getLocalVisibleRect(visibleRect) || visibleRect.height() <= 0) {
      visibleRect.set(0, 0, width, height)
      return
    }
    val pad = visibleRect.height()
    visibleRect.top -= pad
    visibleRect.bottom += pad
  }

  private fun drawLoading(canvas: Canvas, text: String, density: Float) {
    val centerX = width / 2f
    val centerY = height / 2f
    val spinnerRadius = 10f * density
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 2f * density
    paint.strokeCap = Paint.Cap.ROUND
    paint.color = Color.parseColor("#F2F4F5")
    canvas.drawArc(
      centerX - spinnerRadius,
      centerY - 22f * density - spinnerRadius,
      centerX + spinnerRadius,
      centerY - 22f * density + spinnerRadius,
      loadingSweep,
      275f,
      false,
      paint,
    )
    paint.style = Paint.Style.FILL
    paint.strokeCap = Paint.Cap.BUTT
    paint.typeface = normalTypeface
    paint.textSize = fontSizeDp * density
    paint.textAlign = Paint.Align.CENTER
    paint.isUnderlineText = false
    paint.isStrikeThruText = false
    paint.color = Color.parseColor("#F2F4F5")
    canvas.drawText(text, centerX, centerY + 12f * density, paint)
    paint.textAlign = Paint.Align.LEFT
    loadingSweep = (loadingSweep + 18f) % 360f
  }

  private fun drawLine(canvas: Canvas, line: Line, row: Int, cellWidth: Float, cellHeight: Float, rowTop: Float, baseline: Float) {
    runText.setLength(0)
    var runStartColumn = -1
    var runColumns = 0
    var runStyle: TextStyle? = null

    fun flushRun() {
      val style = runStyle
      if (style != null && runText.isNotEmpty()) drawText(canvas, runText.toString(), runStartColumn * cellWidth, baseline, style)
      runText.setLength(0)
      runStartColumn = -1
      runColumns = 0
      runStyle = null
    }

    for (cell in line.cells) {
      val linked = linksByRow[row]?.any { cell.column < it.endColumn && cell.column + cell.width > it.startColumn } == true
      val background = cell.background
      if (background != backgroundColor) {
        paint.style = Paint.Style.FILL
        paint.color = background
        canvas.drawRect(cell.column * cellWidth, rowTop, (cell.column + cell.width) * cellWidth, rowTop + cellHeight, paint)
      }
      val visibleText = if (cell.invisible) " ".repeat(cell.width.coerceAtLeast(1)) else cell.text.ifEmpty { " " }
      if (visibleText.isEmpty()) continue
      val textStyle = TextStyle(
        foreground = if (linked) linkForeground else cell.foreground,
        bold = cell.bold,
        italic = cell.italic,
        dim = cell.dim,
        underline = cell.underline || linked,
        strikethrough = cell.strikethrough,
      )
      if (visibleText == " " && background == backgroundColor && !textStyle.underline && !textStyle.strikethrough) continue
      val canBatch = cell.width == 1 && visibleText.length == 1 && visibleText[0].code in 0x20..0x7e &&
        runStyle == textStyle && runStartColumn + runColumns == cell.column
      if (canBatch) {
        runText.append(visibleText)
        runColumns += 1
      } else {
        flushRun()
        if (cell.width == 1 && visibleText.length == 1 && visibleText[0].code in 0x20..0x7e) {
          runText.append(visibleText)
          runStartColumn = cell.column
          runColumns = 1
          runStyle = textStyle
        } else {
          drawText(canvas, visibleText, cell.column * cellWidth, baseline, textStyle)
        }
      }
    }
    flushRun()
  }

  private fun drawNativeFrame(
    canvas: Canvas,
    frame: NativeTerminalEngine.Frame,
    cellWidth: Float,
    cellHeight: Float,
  ) {
    val baselineOffset = baselineOffset(cellHeight)
    updateVisibleRect()
    val firstRow = max(0, floor(visibleRect.top / cellHeight).toInt())
    val lastRow = min(frame.contentRows, ceil(visibleRect.bottom / cellHeight).toInt())
    for (row in firstRow until lastRow) {
      val rowTop = row * cellHeight
      val links = NativeTerminalLinks.rowLinks(frame.lines, row, wrappedLinkCache)
      drawNativeLine(canvas, frame.lines[row], links, frame.columns, cellWidth, cellHeight, rowTop, rowTop + baselineOffset)
    }
  }

  /**
   * Draws one row with at most one text draw per run of same-styled ASCII
   * cells. Styles are compared as (foreground, flags) primitives so the per
   * cell loop does not allocate. Inverse video is already resolved in the
   * row snapshot, so its colours are drawn as they are.
   */
  /**
   * Paints a row's non-default backgrounds before its text, one rect per run
   * of adjacent same-colored cells instead of one per cell (TUI status bars
   * and selections are usually a single run).
   */
  private fun drawNativeBackgrounds(
    canvas: Canvas,
    line: NativeTerminalEngine.FrameRow,
    columns: Int,
    cellWidth: Float,
    top: Float,
    bottom: Float,
  ) {
    var runStart = -1
    var runEnd = -1
    var runColor = 0
    paint.style = Paint.Style.FILL
    for (column in 0..columns) {
      val width = if (column < columns) line.width[column].toInt() else 0
      // Zero-width cells are the trailing halves of wide glyphs, already
      // covered by the lead cell's width.
      if (column < columns && width <= 0) continue
      val color = if (column < columns) line.background[column] else backgroundColor
      if (runStart >= 0 && (color != runColor || column != runEnd)) {
        paint.color = runColor
        canvas.drawRect(runStart * cellWidth, top, runEnd * cellWidth, bottom, paint)
        runStart = -1
      }
      if (column < columns && color != backgroundColor) {
        if (runStart < 0) {
          runStart = column
          runColor = color
        }
        runEnd = column + width
      }
    }
  }

  private fun drawNativeLine(
    canvas: Canvas,
    line: NativeTerminalEngine.FrameRow,
    rowLinks: IntArray,
    frameColumns: Int,
    cellWidth: Float,
    cellHeight: Float,
    rowTop: Float,
    baseline: Float,
  ) {
    runText.setLength(0)
    var runStartColumn = -1
    var runForeground = 0
    var runFlags = 0
    // Plain spaces inside a run paint nothing, so they only join the run
    // when more same-styled text follows; trailing spaces are never drawn.
    var pendingSpaces = 0

    fun flushRun() {
      if (runText.isNotEmpty()) drawNativeText(canvas, runText, runStartColumn * cellWidth, baseline, runForeground, runFlags)
      runText.setLength(0)
      runStartColumn = -1
      pendingSpaces = 0
    }

    // Link ranges are sorted [start, end) column pairs cached on the row, so
    // one cursor walks them alongside the cells. A linked cell only swaps its
    // (foreground, flags) style key, keeping links and plain text batched.
    var linkIndex = 0
    // Scrollback rows keep the width they were captured at.
    val columns = min(frameColumns, line.columns)
    drawNativeBackgrounds(canvas, line, columns, cellWidth, rowTop, rowTop + cellHeight)
    for (column in 0 until columns) {
      val width = line.width[column].toInt()
      if (width <= 0) continue
      while (linkIndex < rowLinks.size && rowLinks[linkIndex + 1] <= column) linkIndex += 2
      val linked = linkIndex < rowLinks.size && rowLinks[linkIndex] <= column
      val cellFlags = if (linked) {
        (line.flags[column] and NATIVE_STYLE_FLAGS) or NativeTerminalEngine.FLAG_UNDERLINE
      } else {
        line.flags[column] and NATIVE_STYLE_FLAGS
      }
      val cellForeground = if (linked) linkForeground else line.foreground[column]
      val invisible = line.flags[column] and NativeTerminalEngine.FLAG_INVISIBLE != 0
      val text = line.text[column]
      val blank = invisible || text.isEmpty() || text == " "
      if (blank && cellFlags and NATIVE_LINE_FLAGS == 0) {
        if (runText.isNotEmpty() && runFlags and NATIVE_LINE_FLAGS == 0 &&
          runStartColumn + runText.length + pendingSpaces == column
        ) {
          pendingSpaces += width
        } else {
          flushRun()
        }
        continue
      }
      val ascii = width == 1 && text.length == 1 && text[0].code in 0x20..0x7e
      if (blank || ascii) {
        if (runText.isNotEmpty() && (runForeground != cellForeground || runFlags != cellFlags ||
            runStartColumn + runText.length + pendingSpaces != column)
        ) flushRun()
        repeat(pendingSpaces) { runText.append(' ') }
        pendingSpaces = 0
        if (runText.isEmpty()) {
          runStartColumn = column
          runForeground = cellForeground
          runFlags = cellFlags
        }
        if (blank) repeat(width) { runText.append(' ') } else runText.append(text[0])
        if (width != 1) flushRun()
      } else {
        flushRun()
        drawNativeText(canvas, text, column * cellWidth, baseline, cellForeground, cellFlags)
      }
    }
    flushRun()
  }

  private fun drawNativeText(canvas: Canvas, text: CharSequence, x: Float, baseline: Float, foreground: Int, flags: Int) {
    val bold = flags and NativeTerminalEngine.FLAG_BOLD != 0
    val italic = flags and NativeTerminalEngine.FLAG_ITALIC != 0
    paint.style = Paint.Style.FILL
    paint.color = if (flags and NativeTerminalEngine.FLAG_DIM != 0) withAlpha(foreground, 0x80) else foreground
    paint.typeface = typefaceFor(bold, italic)
    paint.isUnderlineText = flags and NativeTerminalEngine.FLAG_UNDERLINE != 0
    paint.isStrikeThruText = flags and NativeTerminalEngine.FLAG_STRIKETHROUGH != 0
    canvas.drawText(text, 0, text.length, x, baseline, paint)
  }

  private fun notifyNativeFrameMeta(frame: NativeTerminalEngine.Frame) {
    if (lastNativeFrameAlternate == frame.alternate && lastNativeFrameContentRows == frame.contentRows &&
      lastNativeFrameCursorRow == frame.cursorRow && lastNativeFrameLastContentRow == frame.lastContentRow &&
      lastNativeFrameMouseTracking == frame.mouseTracking && lastNativeFrameMouseSgr == frame.mouseSgr) return
    lastNativeFrameAlternate = frame.alternate
    lastNativeFrameContentRows = frame.contentRows
    lastNativeFrameCursorRow = frame.cursorRow
    lastNativeFrameLastContentRow = frame.lastContentRow
    lastNativeFrameMouseTracking = frame.mouseTracking
    lastNativeFrameMouseSgr = frame.mouseSgr
    if (id == NO_ID) return
    val reactContext = context as? ThemedReactContext ?: return
    UIManagerHelper.getEventDispatcher(reactContext)?.dispatchEvent(
      NativeFrameMetaEvent(UIManagerHelper.getSurfaceId(this), id, frame),
    )
  }

  private fun drawText(canvas: Canvas, text: String, x: Float, baseline: Float, style: TextStyle) {
    paint.style = Paint.Style.FILL
    paint.color = if (style.dim) withAlpha(style.foreground, 0x80) else style.foreground
    paint.typeface = typefaceFor(style.bold, style.italic)
    paint.isUnderlineText = style.underline
    paint.isStrikeThruText = style.strikethrough
    canvas.drawText(text, x, baseline, paint)
  }

  private fun typefaceFor(bold: Boolean, italic: Boolean): Typeface = when {
    bold && italic -> boldItalicTypeface
    bold -> boldTypeface
    italic -> italicTypeface
    else -> normalTypeface
  }

  /** Offset from a row's top to its text baseline; paint.textSize must be set. */
  private fun baselineOffset(rowHeight: Float): Float {
    paint.typeface = normalTypeface
    paint.getFontMetrics(fontMetrics)
    return (rowHeight - fontMetrics.bottom + fontMetrics.top) / 2f - fontMetrics.top
  }

  private fun readFrame(value: ReadableMap): Frame {
    val lines = ArrayList<Line>()
    val lineArray = value.getArray("lines")
    if (lineArray != null) {
      for (lineIndex in 0 until lineArray.size()) {
        val line = lineArray.getMap(lineIndex) ?: continue
        val cells = ArrayList<Cell>()
        val cellArray = line.getArray("cells")
        if (cellArray != null) {
          for (cellIndex in 0 until cellArray.size()) {
            val cell = cellArray.getMap(cellIndex) ?: continue
            val column = cell.getIntOrNull("column") ?: continue
            val width = (cell.getIntOrNull("width") ?: 1).coerceIn(1, 240)
            cells.add(Cell(
              text = cell.getStringOrNull("text") ?: " ",
              column = column.coerceIn(0, 240),
              width = width,
              foreground = parseColor(cell.getStringOrNull("foreground"), Color.parseColor("#F2F4F5")),
              background = parseColor(cell.getStringOrNull("background"), backgroundColor),
              bold = cell.getBooleanOrFalse("bold"),
              italic = cell.getBooleanOrFalse("italic"),
              dim = cell.getBooleanOrFalse("dim"),
              underline = cell.getBooleanOrFalse("underline"),
              strikethrough = cell.getBooleanOrFalse("strikethrough"),
              invisible = cell.getBooleanOrFalse("invisible"),
            ))
          }
        }
        lines.add(Line(cells))
      }
    }
    val cursor = value.getMap("cursor")
    return Frame(
      lines = lines,
      cursorRow = cursor?.getIntOrNull("row") ?: -1,
      cursorColumn = cursor?.getIntOrNull("column") ?: -1,
      cursorVisible = cursor?.getBooleanOrFalse("visible") ?: false,
    )
  }

  private fun bindNativeEngine() {
    nativeSubscription?.close()
    nativeSubscription = null
    val sessionId = nativeSessionId
    if (sessionId == null) {
      nativeEngine = null
      invalidate()
      return
    }
    nativeEngine = NativeTerminalEngineRegistry.ensure(sessionId, nativeRows, nativeColumns)
    nativeSubscription = nativeEngine?.addListener { postInvalidateOnAnimation() }
    invalidate()
  }

  /**
   * Fira Code lacks the TUI symbols Claude Code and other harnesses draw
   * (⏵ ⏺ ⏸ ✻ ❯ ...), and Android either has no glyph for them or only a colour
   * emoji. On API 29+ a bundled Noto Sans Symbols 2 subset sits between Fira
   * Code and the system fallback so they render as monochrome text glyphs.
   * Emoji-presentation characters are not in the subset and stay colour emoji.
   */
  private fun loadTypeface(assetPath: String, fallback: Typeface, bold: Boolean = false): Typeface = try {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
      val primary = android.graphics.fonts.Font.Builder(context.assets, assetPath).build()
      val symbols = android.graphics.fonts.Font.Builder(context.assets, SYMBOLS_FONT_ASSET)
        .setWeight(if (bold) 700 else 400)
        .build()
      Typeface.CustomFallbackBuilder(android.graphics.fonts.FontFamily.Builder(primary).build())
        .addCustomFallback(android.graphics.fonts.FontFamily.Builder(symbols).build())
        .setSystemFallback("monospace")
        .build()
    } else {
      Typeface.createFromAsset(context.assets, assetPath)
    }
  } catch (_: Exception) {
    runCatching { Typeface.createFromAsset(context.assets, assetPath) }.getOrDefault(fallback)
  }

  private fun parseColor(value: String?, fallback: Int): Int {
    if (value == null || value.length != 7 || value[0] != '#' || value.any { it != '#' && it.digitToIntOrNull(16) == null }) return fallback
    if (!parsedColorCache.containsKey(value) && parsedColorCache.size >= 4096) parsedColorCache.clear()
    return parsedColorCache.getOrPut(value) {
      try {
        Color.parseColor(value)
      } catch (_: IllegalArgumentException) {
        fallback
      }
    }
  }

  private fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or ((alpha and 0xFF) shl 24)

  private fun ReadableMap.getIntOrNull(key: String): Int? = if (hasKey(key) && !isNull(key)) {
    when (getType(key)) {
      com.facebook.react.bridge.ReadableType.Number -> getDouble(key).toInt()
      else -> null
    }
  } else null

  private fun ReadableMap.getStringOrNull(key: String): String? = if (hasKey(key) && !isNull(key) && getType(key) == com.facebook.react.bridge.ReadableType.String) getString(key) else null

  private fun ReadableMap.getBooleanOrFalse(key: String): Boolean = hasKey(key) && !isNull(key) && getType(key) == com.facebook.react.bridge.ReadableType.Boolean && getBoolean(key)

  private companion object {
    const val NATIVE_CURSOR_HIDE_GRACE_MS = 120L
    const val SYMBOLS_FONT_ASSET = "fonts/TerminalSymbols.ttf"
    const val CURSOR_COLOR = 0x73F2F4F5
    const val NATIVE_STYLE_FLAGS = NativeTerminalEngine.FLAG_BOLD or NativeTerminalEngine.FLAG_ITALIC or
      NativeTerminalEngine.FLAG_DIM or NativeTerminalEngine.FLAG_UNDERLINE or NativeTerminalEngine.FLAG_STRIKETHROUGH
    const val NATIVE_LINE_FLAGS = NativeTerminalEngine.FLAG_UNDERLINE or NativeTerminalEngine.FLAG_STRIKETHROUGH
  }
}
