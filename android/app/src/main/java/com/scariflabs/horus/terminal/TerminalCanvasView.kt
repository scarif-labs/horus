package com.scariflabs.horus.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.view.ActionMode
import android.view.HapticFeedbackConstants
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import android.widget.Toast
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.WritableMap
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.UIManagerHelper
import com.facebook.react.uimanager.events.Event
import com.facebook.react.uimanager.events.NativeGestureUtil
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private class TerminalCanvasEvent(
  surfaceId: Int,
  viewTag: Int,
  private val name: String,
  private val coalesce: Boolean,
  private val data: WritableMap,
) : Event<TerminalCanvasEvent>(surfaceId, viewTag) {
  override fun getEventName(): String = name

  override fun canCoalesce(): Boolean = coalesce

  override fun getEventData(): WritableMap = data
}

/**
 * The terminal surface. With a native session it draws libghostty frames and
 * handles touch itself, like a desktop terminal: drag and fling scroll the
 * scrollback with pixel precision, the view stays put while output arrives
 * above it, and it follows the output again once scrolled to the bottom. On
 * a full-screen app a drag becomes mouse-wheel or arrow-key input. A long
 * press selects text, with handles and the system Copy toolbar. A tap opens
 * a link or clicks for apps that track the mouse; it never opens the keyboard.
 *
 * Without a native session it draws a JS snapshot frame inside a ScrollView.
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

  private data class ViewportRequest(val follow: Boolean, val topRow: Int, val above: Int, val below: Int)

  private enum class TouchMode { NONE, PENDING, SCROLL, WHEEL, SELECT, HANDLE, IGNORED }

  private val normalTypeface = loadTypeface("fonts/FiraCode.ttf", Typeface.MONOSPACE)
  private val boldTypeface = loadTypeface("fonts/FiraCode_bold.ttf", normalTypeface, bold = true)
  private val italicTypeface = Typeface.create(normalTypeface, Typeface.ITALIC)
  private val boldItalicTypeface = Typeface.create(boldTypeface, Typeface.BOLD_ITALIC)
  private val runText = StringBuilder()
  private val wrappedLinkCache = NativeTerminalLinks.WrappedLinkCache()
  private val visibleRect = Rect()
  private val fontMetrics = Paint.FontMetrics()
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
  private var lastMetaAlternate: Boolean? = null
  private var lastMetaHasContent: Boolean? = null
  private var lastMetaMouseTracking: Boolean? = null
  private var lastMetaMouseSgr: Boolean? = null
  private var nativeCursorHiddenAt = 0L
  private var nativeCursorLastRow = -1
  private var nativeCursorLastColumn = -1
  private var loadingSweep = 0f

  // Scroll position. While [following], the view shows the end of the
  // output; otherwise [scrollTop] is the screen row (0 = top of the
  // scrollback, fractional for smooth scrolling) at the top edge.
  private var following = true
  private var scrollTop = 0.0
  // Alternate screens have no scrollback; when taller than the view (the
  // keyboard is open), this many rows are scrolled off to keep the cursor visible.
  private var alternateOffset = 0.0
  private var lastTailAnchor = 0
  private var lastFrame: NativeTerminalEngine.Frame? = null
  private var lastRequest: ViewportRequest? = null
  private var lastInputCount = 0L
  private val scroller = OverScroller(context)
  private var flingIsWheel = false
  private var flingLastY = 0
  private var velocityTracker: VelocityTracker? = null
  private val viewConfiguration = ViewConfiguration.get(context)
  private val touchSlop = viewConfiguration.scaledTouchSlop
  private val minFlingVelocity = viewConfiguration.scaledMinimumFlingVelocity
  private val maxFlingVelocity = viewConfiguration.scaledMaximumFlingVelocity
  private var touchMode = TouchMode.NONE
  private var touchActive = false
  /** The latest touch event, kept to hand React Native when a long press claims the gesture. */
  private var lastEvent: MotionEvent? = null
  private var gestureClaimed = false
  private var downX = 0f
  private var downY = 0f
  private var lastTouchX = 0f
  private var lastTouchY = 0f
  private var wheelRemainder = 0f
  private var scrollbarShownAt = 0L

  // Selection. The terminal owns the selected range; the view keeps the
  // long-pressed word to extend from, or the fixed end while a handle moves.
  private var anchorStart: IntArray? = null
  private var anchorEnd: IntArray? = null
  private var handleFixed: IntArray? = null
  /** From the finger to the centre of the cell the grabbed handle marks. */
  private var handleOffsetX = 0f
  private var handleOffsetY = 0f
  private var selectionBounds: IntArray? = null
  private var selectionBoundsFrame: NativeTerminalEngine.Frame? = null
  private var selectionActive = false
  /** The frame shown when the selection began; it predates the selection, so it has none. */
  private var selectionStartFrame: NativeTerminalEngine.Frame? = null
  private var selectionClearRequest = 0
  private var actionMode: ActionMode? = null
  private var restartingActionMode = false
  private var autoScrollRows = 0.0
  private val handleRect = RectF()
  private val longPress = Runnable { onLongPress() }
  private val autoScroll = object : Runnable {
    override fun run() {
      if (autoScrollRows == 0.0 || (touchMode != TouchMode.SELECT && touchMode != TouchMode.HANDLE)) return
      scrollByRows(autoScrollRows)
      dragSelectionTo(lastTouchX, lastTouchY)
      postOnAnimation(this)
    }
  }

  init {
    setWillNotDraw(false)
    setBackgroundColor(backgroundColor)
    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    isHapticFeedbackEnabled = true
  }

  fun setFrame(value: ReadableMap?) {
    frame = value?.let(::readFrame)
    invalidate()
  }

  fun setNativeSessionId(value: String?) {
    if (nativeSessionId == value && (value == null || nativeEngine != null)) return
    nativeSessionId = value?.takeIf(String::isNotEmpty)
    lastMetaAlternate = null
    lastMetaHasContent = null
    lastMetaMouseTracking = null
    lastMetaMouseSgr = null
    nativeCursorHiddenAt = 0L
    nativeCursorLastRow = -1
    nativeCursorLastColumn = -1
    following = true
    scrollTop = 0.0
    alternateOffset = 0.0
    lastFrame = null
    lastRequest = null
    scroller.forceFinished(true)
    endSelectionUi()
    bindNativeEngine()
  }

  fun setNativeRows(value: Int) {
    val coerced = value.coerceIn(2, 250)
    if (nativeRows == coerced) return
    nativeRows = coerced
    if (nativeSessionId != null && nativeEngine == null) bindNativeEngine() else invalidate()
  }

  fun setNativeColumns(value: Int) {
    val coerced = value.coerceIn(2, 500)
    if (nativeColumns == coerced) return
    nativeColumns = coerced
    if (nativeSessionId != null && nativeEngine == null) bindNativeEngine() else invalidate()
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

  /** React Native asks to drop the selection (Back) by bumping this counter. */
  fun setSelectionClearRequest(value: Int) {
    if (value == selectionClearRequest) return
    selectionClearRequest = value
    clearSelection()
  }

  private fun clearSelection() {
    nativeEngine?.clearSelection()
    endSelectionUi()
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    if (nativeSessionId != null) bindNativeEngine()
  }

  override fun onDetachedFromWindow() {
    nativeSubscription?.close()
    nativeSubscription = null
    removeCallbacks(longPress)
    actionMode?.finish()
    actionMode = null
    lastEvent?.recycle()
    lastEvent = null
    super.onDetachedFromWindow()
  }

  override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
    super.onSizeChanged(w, h, oldw, oldh)
    invalidate()
  }

  private val density: Float get() = resources.displayMetrics.density
  private val cellWidthPx: Float get() = cellWidthDp * density
  private val cellHeightPx: Float get() = cellHeightDp * density
  private val visibleRows: Double get() = if (cellHeightPx > 0f) height / cellHeightPx.toDouble() else 0.0

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    val cellWidth = cellWidthPx
    val cellHeight = cellHeightPx
    if (!cellWidth.isFinite() || !cellHeight.isFinite() || cellWidth <= 0f || cellHeight <= 0f) {
      canvas.drawColor(backgroundColor)
      return
    }

    paint.textSize = fontSizeDp * density
    val engine = nativeEngine
    if (engine != null) {
      val current = engine.displayFrame()
      canvas.drawColor(current.defaultBackgroundColor)
      notifyNativeFrameMeta(current)
      val loadingText = nativeLoadingText
      // During first-run provisioning the native parser is already receiving
      // the installer transcript in the normal screen. Show that bounded
      // transcript as soon as it has content; keep the spinner only while the
      // PTY is still blank or the alternate TUI has not painted yet.
      if (loadingText != null && !current.hasVisibleContent) {
        drawLoading(canvas, loadingText, density)
        postInvalidateOnAnimation()
        return
      }
      drawNativeFrame(canvas, engine, current, cellWidth, cellHeight)
      return
    }
    canvas.drawColor(backgroundColor)
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
      paint.style = Paint.Style.FILL
      paint.color = CURSOR_COLOR
      val left = currentFrame.cursorColumn.coerceAtLeast(0) * cellWidth
      val top = currentFrame.cursorRow * cellHeight
      canvas.drawRect(left, top, left + cellWidth, top + cellHeight, paint)
    }
  }

  // ---------------------------------------------------------------------------
  // Native frame layout and drawing
  // ---------------------------------------------------------------------------

  /** Screen row at the top edge while following the output. */
  private fun tailTop(frame: NativeTerminalEngine.Frame): Double {
    val activeTop = frame.activeTop
    val visible = visibleRows
    if (frame.alternate) {
      val cursor = if (frame.cursorRow >= 0) frame.screenRow(frame.cursorRow) - activeTop else -1
      alternateOffset = TerminalViewport.alternateOffset(alternateOffset, frame.rows, visible, cursor)
      return activeTop + alternateOffset
    }
    // Scrolled back, the frame may not hold the whole active screen: keep the last anchor.
    val activeCaptured = frame.frameRow(activeTop) >= 0 && frame.frameRow(activeTop + frame.rows - 1) < frame.lines.size
    var anchor = lastTailAnchor
    if (activeCaptured) {
      anchor = -1
      if (frame.lastContentRow >= 0) anchor = frame.screenRow(frame.lastContentRow) - activeTop
      if (frame.cursorRow >= 0) anchor = max(anchor, frame.screenRow(frame.cursorRow) - activeTop)
      lastTailAnchor = anchor.coerceAtLeast(0)
    }
    return activeTop + TerminalViewport.tailOffset(frame.rows, visible, anchor)
  }

  /** Screen row at the top edge of the view for [frame]. */
  private fun viewTop(frame: NativeTerminalEngine.Frame): Double {
    val tail = tailTop(frame)
    if (following || frame.alternate) return tail
    if (scrollTop >= tail) {
      following = true
      return tail
    }
    scrollTop = scrollTop.coerceAtLeast(0.0)
    return scrollTop
  }

  /**
   * Keeps the scroll position on the same text when the scrollback lost rows
   * at the top (the history limit) or rows were renumbered, using row identity.
   */
  private fun reanchor(previous: NativeTerminalEngine.Frame?, next: NativeTerminalEngine.Frame) {
    if (following || previous == null || previous === next) return
    val previousIds = previous.rowIds ?: return
    val nextIds = next.rowIds ?: return
    val previousIndex = previous.frameRow(floor(scrollTop).toInt())
    if (previousIndex !in previous.lines.indices) return
    val id0 = previousIds[previousIndex * 2]
    val id1 = previousIds[previousIndex * 2 + 1]
    if (id0 == 0L && id1 == 0L) return
    for (index in next.lines.indices) {
      if (nextIds[index * 2] == id0 && nextIds[index * 2 + 1] == id1) {
        val delta = next.screenRow(index) - previous.screenRow(previousIndex)
        if (delta != 0) scrollTop += delta
        return
      }
    }
  }

  /** Asks the engine to capture the rows around [top] that the view needs next. */
  private fun ensureViewport(engine: NativeTerminalEngine, frame: NativeTerminalEngine.Frame, top: Double) {
    val rows = frame.rows.coerceAtLeast(2)
    val request = if (following || frame.alternate) {
      // At rest only the screen is needed. A finger on the glass may start a
      // scroll, so capture a screen of scrollback above to scroll into at once.
      ViewportRequest(true, 0, if (touchActive || !scroller.isFinished) rows else FOLLOW_IDLE_ABOVE, 0)
    } else {
      val captureStart = frame.screenRow(0)
      val captureEnd = frame.screenRow(frame.lines.size)
      val margin = max(4, rows / 3)
      val visible = visibleRows
      val short = (top < captureStart + margin && captureStart > 0) ||
        (top + visible > captureEnd - margin && captureEnd < frame.totalRows) ||
        frame.followingActive && top + visible < frame.activeTop
      val previous = lastRequest
      if (!short && previous != null && !previous.follow) return
      ViewportRequest(false, floor(top).toInt().coerceAtLeast(0), rows, rows)
    }
    if (request == lastRequest) return
    lastRequest = request
    engine.setViewport(request.follow, request.topRow, request.above, request.below)
  }

  private fun drawNativeFrame(
    canvas: Canvas,
    engine: NativeTerminalEngine,
    frame: NativeTerminalEngine.Frame,
    cellWidth: Float,
    cellHeight: Float,
  ) {
    reanchor(lastFrame, frame)
    lastFrame = frame
    if (engine.inputCount != lastInputCount) {
      // Typing jumps back to the output and ends a selection, like a desktop terminal.
      lastInputCount = engine.inputCount
      if (!following) {
        following = true
        scroller.forceFinished(true)
      }
      if (selectionActive) clearSelection()
    }
    if (selectionActive && frame.selection == null && frame !== selectionStartFrame &&
      touchMode != TouchMode.SELECT && touchMode != TouchMode.HANDLE
    ) {
      // The app cleared the screen under the selection.
      endSelectionUi()
    }

    val top = viewTop(frame)
    ensureViewport(engine, frame, top)
    val background = frame.defaultBackgroundColor
    val baselineOffset = baselineOffset(cellHeight)
    val viewHeight = height.toFloat()
    for (index in frame.lines.indices) {
      val y = ((frame.screenRow(index) - top) * cellHeight).toFloat()
      if (y + cellHeight <= 0f || y >= viewHeight) continue
      val links = NativeTerminalLinks.rowLinks(frame.lines, index, wrappedLinkCache)
      drawNativeLine(canvas, frame.lines[index], links, frame.columns, background, cellWidth, cellHeight, y, y + baselineOffset)
    }
    frame.selection?.let { drawSelection(canvas, frame, it, top, cellWidth, cellHeight) }
    if (running && updateNativeCursor(frame) && nativeCursorLastRow in frame.lines.indices) {
      drawCursor(canvas, frame, ((frame.screenRow(nativeCursorLastRow) - top) * cellHeight).toFloat(), cellWidth, cellHeight)
    }
    if (selectionActive) drawHandles(canvas, engine, frame, top, cellWidth, cellHeight)
    drawScrollbar(canvas, frame, top)
  }

  private fun drawCursor(canvas: Canvas, frame: NativeTerminalEngine.Frame, top: Float, cellWidth: Float, cellHeight: Float) {
    val left = nativeCursorLastColumn.coerceAtLeast(0) * cellWidth
    paint.color = CURSOR_COLOR
    when (frame.cursorStyle) {
      NativeTerminalEngine.CURSOR_STYLE_BAR -> {
        paint.style = Paint.Style.FILL
        canvas.drawRect(left, top, left + 2f * density, top + cellHeight, paint)
      }
      NativeTerminalEngine.CURSOR_STYLE_UNDERLINE -> {
        paint.style = Paint.Style.FILL
        canvas.drawRect(left, top + cellHeight - 2f * density, left + cellWidth, top + cellHeight, paint)
      }
      NativeTerminalEngine.CURSOR_STYLE_BLOCK_HOLLOW -> {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density
        canvas.drawRect(left, top, left + cellWidth, top + cellHeight, paint)
        paint.style = Paint.Style.FILL
      }
      else -> {
        paint.style = Paint.Style.FILL
        canvas.drawRect(left, top, left + cellWidth, top + cellHeight, paint)
      }
    }
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

  /** Tints the selected cells over the drawn text, one rect per row. */
  private fun drawSelection(
    canvas: Canvas,
    frame: NativeTerminalEngine.Frame,
    selection: IntArray,
    top: Double,
    cellWidth: Float,
    cellHeight: Float,
  ) {
    paint.style = Paint.Style.FILL
    paint.color = SELECTION_COLOR
    for (index in frame.lines.indices) {
      val start = selection[index * 2]
      val end = selection[index * 2 + 1]
      if (start < 0 || end <= start) continue
      val y = ((frame.screenRow(index) - top) * cellHeight).toFloat()
      if (y + cellHeight <= 0f || y >= height) continue
      canvas.drawRect(start * cellWidth, y, end * cellWidth, y + cellHeight, paint)
    }
  }

  /** The selection's ends for [frame], read once per frame. */
  private fun boundsFor(engine: NativeTerminalEngine, frame: NativeTerminalEngine.Frame): IntArray? {
    if (selectionBoundsFrame !== frame) {
      selectionBounds = engine.selectionBounds()
      selectionBoundsFrame = frame
    }
    return selectionBounds
  }

  private fun drawHandles(
    canvas: Canvas,
    engine: NativeTerminalEngine,
    frame: NativeTerminalEngine.Frame,
    top: Double,
    cellWidth: Float,
    cellHeight: Float,
  ) {
    val bounds = boundsFor(engine, frame) ?: return
    paint.style = Paint.Style.FILL
    paint.color = HANDLE_COLOR
    val radius = HANDLE_RADIUS_DP * density
    val startX = bounds[0] * cellWidth
    val startY = ((bounds[1] + 1 - top) * cellHeight).toFloat()
    val endX = (bounds[2] + 1) * cellWidth
    val endY = ((bounds[3] + 1 - top) * cellHeight).toFloat()
    // Teardrops whose point touches the selection's outer corner.
    canvas.drawCircle(startX - radius, startY + radius, radius, paint)
    canvas.drawRect(startX - radius, startY, startX, startY + radius, paint)
    canvas.drawCircle(endX + radius, endY + radius, radius, paint)
    canvas.drawRect(endX, endY, endX + radius, endY + radius, paint)
  }

  /** A thin thumb on the right edge while scrolling, fading out after. */
  private fun drawScrollbar(canvas: Canvas, frame: NativeTerminalEngine.Frame, top: Double) {
    if (frame.alternate) return
    val visible = visibleRows
    val total = max(frame.totalRows.toDouble(), top + visible)
    if (total <= visible + 0.5) return
    val now = SystemClock.uptimeMillis()
    val active = touchMode == TouchMode.SCROLL || (!scroller.isFinished && !flingIsWheel)
    if (active) scrollbarShownAt = now
    val age = now - scrollbarShownAt
    if (scrollbarShownAt == 0L || age > SCROLLBAR_VISIBLE_MS + SCROLLBAR_FADE_MS) return
    val alpha = if (age <= SCROLLBAR_VISIBLE_MS) 1f else 1f - (age - SCROLLBAR_VISIBLE_MS) / SCROLLBAR_FADE_MS.toFloat()
    val viewHeight = height.toFloat()
    val thumb = max(SCROLLBAR_MIN_DP * density, (visible / total * viewHeight).toFloat())
    val position = (top / (total - visible)).coerceIn(0.0, 1.0).toFloat()
    val thumbTop = position * (viewHeight - thumb)
    val width = SCROLLBAR_WIDTH_DP * density
    paint.style = Paint.Style.FILL
    paint.color = withAlpha(SCROLLBAR_COLOR, (0x99 * alpha).roundToInt())
    canvas.drawRoundRect(this.width - width - 2f * density, thumbTop, this.width - 2f * density, thumbTop + thumb, width, width, paint)
    if (!active) postInvalidateOnAnimation()
  }

  // ---------------------------------------------------------------------------
  // Touch
  // ---------------------------------------------------------------------------

  override fun onTouchEvent(event: MotionEvent): Boolean {
    val engine = nativeEngine ?: return super.onTouchEvent(event)
    val current = engine.displayFrame()
    velocityTracker?.addMovement(event)
    lastEvent?.recycle()
    lastEvent = MotionEvent.obtain(event)
    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        val wasFlinging = !scroller.isFinished
        scroller.forceFinished(true)
        velocityTracker?.recycle()
        velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
        touchActive = true
        downX = event.x
        downY = event.y
        lastTouchX = event.x
        lastTouchY = event.y
        wheelRemainder = 0f
        touchMode = if (wasFlinging) TouchMode.IGNORED else TouchMode.PENDING
        val handle = if (selectionActive) handleAt(engine, current, event.x, event.y) else 0
        if (handle != 0) {
          val bounds = boundsFor(engine, current)
          if (bounds != null) {
            handleFixed = if (handle < 0) intArrayOf(bounds[2], bounds[3]) else intArrayOf(bounds[0], bounds[1])
            val top = viewTop(current)
            val column = if (handle < 0) bounds[0] else bounds[2]
            val row = if (handle < 0) bounds[1] else bounds[3]
            handleOffsetX = (column + 0.5f) * cellWidthPx - event.x
            handleOffsetY = ((row + 0.5 - top) * cellHeightPx).toFloat() - event.y
            touchMode = TouchMode.HANDLE
            claimGesture(event)
            hideActionMode()
          }
        } else if (!wasFlinging) {
          postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
        }
        // Capture scrollback above now, so a drag that starts has rows to show.
        invalidate()
        return true
      }
      MotionEvent.ACTION_MOVE -> {
        val x = event.x
        val y = event.y
        when (touchMode) {
          TouchMode.PENDING, TouchMode.IGNORED -> {
            val dx = x - downX
            val dy = y - downY
            if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
              removeCallbacks(longPress)
              if (abs(dy) >= abs(dx)) {
                touchMode = if (current.alternate) TouchMode.WHEEL else TouchMode.SCROLL
                claimGesture(event)
                // Start from the slop edge so the content does not jump.
                lastTouchY = y - (if (dy > 0) touchSlop else -touchSlop)
              } else {
                touchMode = TouchMode.IGNORED
              }
            }
          }
          else -> Unit
        }
        when (touchMode) {
          TouchMode.SCROLL -> {
            scrollByRows((lastTouchY - y) / cellHeightPx.toDouble())
            lastTouchY = y
          }
          TouchMode.WHEEL -> {
            wheel(engine, current, y - lastTouchY, x, y)
            lastTouchY = y
          }
          TouchMode.SELECT, TouchMode.HANDLE -> {
            lastTouchX = x
            lastTouchY = y
            dragSelectionTo(x, y)
            updateAutoScroll(y)
          }
          else -> Unit
        }
        return true
      }
      MotionEvent.ACTION_UP -> {
        removeCallbacks(longPress)
        touchActive = false
        autoScrollRows = 0.0
        val mode = touchMode
        touchMode = TouchMode.NONE
        when (mode) {
          TouchMode.PENDING -> onTap(engine, current, event.x, event.y)
          TouchMode.SCROLL, TouchMode.WHEEL -> {
            val tracker = velocityTracker
            tracker?.computeCurrentVelocity(1000, maxFlingVelocity.toFloat())
            val velocity = tracker?.yVelocity ?: 0f
            if (abs(velocity) > minFlingVelocity) fling(current, velocity, mode == TouchMode.WHEEL)
          }
          TouchMode.SELECT, TouchMode.HANDLE -> {
            handleFixed = null
            showActionMode()
          }
          else -> Unit
        }
        releaseGesture(event)
        velocityTracker?.recycle()
        velocityTracker = null
        invalidate()
        return true
      }
      MotionEvent.ACTION_CANCEL -> {
        removeCallbacks(longPress)
        touchActive = false
        autoScrollRows = 0.0
        if (touchMode == TouchMode.SELECT || touchMode == TouchMode.HANDLE) showActionMode()
        touchMode = TouchMode.NONE
        handleFixed = null
        releaseGesture(event)
        velocityTracker?.recycle()
        velocityTracker = null
        invalidate()
        return true
      }
    }
    return true
  }

  /** Takes the gesture from React Native's responders and any scrolling parent. */
  private fun claimGesture(event: MotionEvent) {
    parent?.requestDisallowInterceptTouchEvent(true)
    if (gestureClaimed) return
    gestureClaimed = true
    NativeGestureUtil.notifyNativeGestureStarted(this, event)
  }

  private fun releaseGesture(event: MotionEvent) {
    if (!gestureClaimed) return
    gestureClaimed = false
    NativeGestureUtil.notifyNativeGestureEnded(this, event)
  }

  private fun scrollByRows(rows: Double) {
    val current = nativeEngine?.displayFrame() ?: return
    if (current.alternate || rows == 0.0) return
    val top = viewTop(current)
    val tail = tailTop(current)
    val next = (top + rows).coerceIn(0.0, max(0.0, tail))
    following = next >= tail
    scrollTop = next
    invalidate()
  }

  private fun fling(frame: NativeTerminalEngine.Frame, velocityY: Float, wheel: Boolean) {
    val cellHeight = cellHeightPx
    flingIsWheel = wheel
    if (wheel) {
      flingLastY = 0
      scroller.fling(0, 0, 0, velocityY.roundToInt(), 0, 0, Int.MIN_VALUE / 2, Int.MAX_VALUE / 2)
    } else {
      val top = viewTop(frame)
      val start = (top * cellHeight).roundToInt()
      val max = (tailTop(frame) * cellHeight).roundToInt()
      scroller.fling(0, start, 0, -velocityY.roundToInt(), 0, 0, 0, max)
    }
    postInvalidateOnAnimation()
  }

  override fun computeScroll() {
    if (!scroller.computeScrollOffset()) return
    val engine = nativeEngine ?: return
    val current = engine.displayFrame()
    if (flingIsWheel) {
      val y = scroller.currY
      wheel(engine, current, (y - flingLastY).toFloat(), lastTouchX, lastTouchY)
      flingLastY = y
      if (abs(scroller.currVelocity) < minFlingVelocity) scroller.forceFinished(true)
    } else if (current.alternate) {
      scroller.forceFinished(true)
    } else {
      val tail = tailTop(current)
      val next = (scroller.currY / cellHeightPx.toDouble()).coerceIn(0.0, max(0.0, tail))
      following = next >= tail - FOLLOW_EPSILON_ROWS
      scrollTop = next
      if (following) scroller.forceFinished(true)
    }
    postInvalidateOnAnimation()
  }

  /**
   * A drag on a full-screen app: wheel events when it tracks the mouse, arrow
   * keys when it asked for them (mode 1007), otherwise Page Up/Down. [deltaY]
   * is the finger's movement; down shows earlier content.
   */
  private fun wheel(engine: NativeTerminalEngine, frame: NativeTerminalEngine.Frame, deltaY: Float, x: Float, y: Float) {
    val lineStep = cellHeightPx * WHEEL_ROWS_PER_TICK
    val step = if (frame.mouseTracking || frame.mouseAlternateScroll) lineStep else max(lineStep, height / 2f)
    wheelRemainder += deltaY
    var ticks = (wheelRemainder / step).toInt()
    if (ticks == 0) return
    wheelRemainder -= ticks * step
    ticks = ticks.coerceIn(-MAX_TICKS_PER_FRAME, MAX_TICKS_PER_FRAME)
    val up = ticks > 0
    val count = abs(ticks)
    val bytes = engine.withNativeHandle { h ->
      val column = (x / cellWidthPx).toInt().coerceIn(0, frame.columns - 1)
      val row = (y / cellHeightPx + (tailTop(frame) - frame.activeTop)).toInt().coerceIn(0, frame.rows - 1)
      val one = when {
        frame.mouseTracking -> TerminalPtyJni.nativeEngineEncodeMouse(
          h, column.toFloat(), row.toFloat(), if (up) MOUSE_WHEEL_UP else MOUSE_WHEEL_DOWN, MOUSE_PRESS, 0,
        )
        frame.mouseAlternateScroll -> TerminalPtyJni.nativeEngineEncodeKey(h, if (up) KEY_ARROW_UP else KEY_ARROW_DOWN, 0, KEY_PRESS, null)
        else -> TerminalPtyJni.nativeEngineEncodeKey(h, if (up) KEY_PAGE_UP else KEY_PAGE_DOWN, 0, KEY_PRESS, null)
      } ?: return@withNativeHandle null
      ByteArray(one.size * count) { one[it % one.size] }
    } ?: return
    nativeSessionId?.let { NativeTerminalEngineRegistry.writeInput(it, bytes, userInput = false) }
  }

  private fun onTap(engine: NativeTerminalEngine, frame: NativeTerminalEngine.Frame, x: Float, y: Float) {
    if (selectionActive) {
      clearSelection()
      return
    }
    val (column, screenRow) = cellAt(frame, x, y)
    val index = frame.frameRow(screenRow)
    val url = if (index in frame.lines.indices) NativeTerminalLinks.urlAt(engine, frame, index, column) else null
    if (!url.isNullOrEmpty()) {
      emit(EVENT_LINK, false, Arguments.createMap().apply { putString("url", url) })
      return
    }
    if (frame.mouseTracking) {
      // Apps that track the mouse (opencode, htop) get a click, as on a desktop.
      val row = screenRow - frame.viewportTop
      if (row in 0 until frame.rows) {
        val bytes = engine.withNativeHandle { h ->
          val press = TerminalPtyJni.nativeEngineEncodeMouse(h, column.toFloat(), row.toFloat(), MOUSE_LEFT, MOUSE_PRESS, 0)
          val release = TerminalPtyJni.nativeEngineEncodeMouse(h, column.toFloat(), row.toFloat(), MOUSE_LEFT, MOUSE_RELEASE, 0)
          (press ?: ByteArray(0)) + (release ?: ByteArray(0))
        }
        if (bytes != null && bytes.isNotEmpty()) {
          nativeSessionId?.let { NativeTerminalEngineRegistry.writeInput(it, bytes, userInput = false) }
        }
      }
    }
    emit(EVENT_TAP, false, Arguments.createMap())
  }

  /** (column, screen row) under a point of the view. */
  private fun cellAt(frame: NativeTerminalEngine.Frame, x: Float, y: Float): Pair<Int, Int> {
    val column = floor(x / cellWidthPx).toInt().coerceIn(0, (frame.columns - 1).coerceAtLeast(0))
    val screenRow = floor(viewTop(frame) + y / cellHeightPx).toInt().coerceIn(0, (frame.totalRows - 1).coerceAtLeast(0))
    return column to screenRow
  }

  // ---------------------------------------------------------------------------
  // Selection
  // ---------------------------------------------------------------------------

  private fun onLongPress() {
    if (touchMode != TouchMode.PENDING) return
    val engine = nativeEngine ?: return
    val current = engine.displayFrame()
    val (column, screenRow) = cellAt(current, downX, downY)
    // Hold the view still while selecting, even if output keeps arriving.
    if (!current.alternate) {
      scrollTop = viewTop(current)
      following = false
    }
    selectionStartFrame = current
    val word = engine.selectWord(column, screenRow) ?: return
    anchorStart = intArrayOf(word[0], word[1])
    anchorEnd = intArrayOf(word[2], word[3])
    selectionBoundsFrame = null
    touchMode = TouchMode.SELECT
    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    lastEvent?.let(::claimGesture)
    setSelectionActive(true)
    invalidate()
  }

  private fun dragSelectionTo(x: Float, y: Float) {
    val engine = nativeEngine ?: return
    val current = engine.displayFrame()
    val fixed = handleFixed
    val handleDrag = touchMode == TouchMode.HANDLE && fixed != null
    val targetX = if (handleDrag) x + handleOffsetX else x
    val targetY = if (handleDrag) y + handleOffsetY else y
    val (column, screenRow) = cellAt(current, targetX.coerceAtLeast(0f), targetY.coerceIn(0f, height - 1f))
    if (handleDrag) {
      engine.selectRange(fixed[0], fixed[1], column, screenRow)
    } else {
      val start = anchorStart ?: return
      val end = anchorEnd ?: return
      when {
        before(column, screenRow, start[0], start[1]) -> engine.selectRange(end[0], end[1], column, screenRow)
        before(end[0], end[1], column, screenRow) -> engine.selectRange(start[0], start[1], column, screenRow)
        else -> engine.selectRange(start[0], start[1], end[0], end[1])
      }
    }
    selectionBoundsFrame = null
  }

  private fun before(column: Int, row: Int, otherColumn: Int, otherRow: Int): Boolean =
    row < otherRow || (row == otherRow && column < otherColumn)

  /** Scrolls while a selection drag sits near the top or bottom edge. */
  private fun updateAutoScroll(y: Float) {
    val edge = cellHeightPx * 1.5f
    val next = when {
      y < edge -> -AUTO_SCROLL_ROWS_PER_FRAME
      y > height - edge -> AUTO_SCROLL_ROWS_PER_FRAME
      else -> 0.0
    }
    val wasIdle = autoScrollRows == 0.0
    autoScrollRows = next
    if (next != 0.0 && wasIdle) postOnAnimation(autoScroll)
  }

  /** -1 for the start handle under the point, 1 for the end handle, else 0. */
  private fun handleAt(engine: NativeTerminalEngine, frame: NativeTerminalEngine.Frame, x: Float, y: Float): Int {
    val bounds = boundsFor(engine, frame) ?: return 0
    val top = viewTop(frame)
    val radius = HANDLE_RADIUS_DP * density
    val slop = HANDLE_TOUCH_DP * density
    val startX = bounds[0] * cellWidthPx - radius
    val startY = ((bounds[1] + 1 - top) * cellHeightPx).toFloat() + radius
    val endX = (bounds[2] + 1) * cellWidthPx + radius
    val endY = ((bounds[3] + 1 - top) * cellHeightPx).toFloat() + radius
    val toStart = hypot(x - startX, y - startY)
    val toEnd = hypot(x - endX, y - endY)
    return when {
      toEnd <= slop && toEnd <= toStart -> 1
      toStart <= slop -> -1
      else -> 0
    }
  }

  private fun setSelectionActive(active: Boolean) {
    if (selectionActive == active) return
    selectionActive = active
    emit(EVENT_SELECTION, false, Arguments.createMap().apply { putBoolean("active", active) })
  }

  /** Forgets the selection UI; the caller clears the engine's selection if needed. */
  private fun endSelectionUi() {
    anchorStart = null
    anchorEnd = null
    handleFixed = null
    selectionStartFrame = null
    selectionBounds = null
    selectionBoundsFrame = null
    autoScrollRows = 0.0
    if (touchMode == TouchMode.SELECT || touchMode == TouchMode.HANDLE) touchMode = TouchMode.IGNORED
    setSelectionActive(false)
    actionMode?.let {
      actionMode = null
      it.finish()
    }
    invalidate()
  }

  private fun hideActionMode() {
    val mode = actionMode ?: return
    restartingActionMode = true
    actionMode = null
    mode.finish()
    restartingActionMode = false
  }

  private fun showActionMode() {
    if (!selectionActive || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
    actionMode?.let {
      it.invalidateContentRect()
      return
    }
    actionMode = startActionMode(selectionToolbar, ActionMode.TYPE_FLOATING)
  }

  private val selectionToolbar = object : ActionMode.Callback2() {
    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
      menu.add(Menu.NONE, MENU_COPY, 0, android.R.string.copy).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
      menu.add(Menu.NONE, MENU_SELECT_ALL, 1, android.R.string.selectAll).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
      return true
    }

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
      val engine = nativeEngine ?: return false
      when (item.itemId) {
        MENU_COPY -> {
          copyToClipboard(engine.selectionText().orEmpty())
          clearSelection()
        }
        MENU_SELECT_ALL -> {
          engine.selectAll()
          selectionBoundsFrame = null
          mode.invalidateContentRect()
          invalidate()
        }
        else -> return false
      }
      return true
    }

    override fun onDestroyActionMode(mode: ActionMode) {
      if (actionMode === mode) actionMode = null
      // Dismissing the toolbar (a tap elsewhere, Back) ends the selection.
      if (!restartingActionMode && selectionActive && touchMode != TouchMode.SELECT && touchMode != TouchMode.HANDLE) {
        clearSelection()
      }
    }

    override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
      val engine = nativeEngine
      val current = engine?.displayFrame()
      val bounds = if (engine != null && current != null) boundsFor(engine, current) else null
      if (current == null || bounds == null) {
        outRect.set(0, 0, width, height / 2)
        return
      }
      val top = viewTop(current)
      val startY = ((bounds[1] - top) * cellHeightPx).toInt().coerceIn(0, height)
      val endY = ((bounds[3] + 1 - top) * cellHeightPx).toInt().coerceIn(startY, height)
      val left = if (bounds[1] == bounds[3]) (bounds[0] * cellWidthPx).toInt() else 0
      val right = if (bounds[1] == bounds[3]) ((bounds[2] + 1) * cellWidthPx).toInt() else width
      outRect.set(left, startY, right.coerceAtLeast(left + 1), endY.coerceAtLeast(startY + 1))
    }
  }

  private fun copyToClipboard(text: String) {
    if (text.isEmpty()) return
    val copied = runCatching {
      val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
      clipboard.setPrimaryClip(ClipData.newPlainText("Terminal", text))
    }.isSuccess
    // Android 13+ shows its own copied-text preview.
    if (copied && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
      Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
    }
  }

  // ---------------------------------------------------------------------------
  // Events to React Native
  // ---------------------------------------------------------------------------

  private fun emit(name: String, coalesce: Boolean, data: WritableMap) {
    if (id == NO_ID) return
    val reactContext = context as? ThemedReactContext ?: return
    UIManagerHelper.getEventDispatcher(reactContext)?.dispatchEvent(
      TerminalCanvasEvent(UIManagerHelper.getSurfaceId(this), id, name, coalesce, data),
    )
  }

  /** Tells React Native when the kind of screen changes; never per output line. */
  private fun notifyNativeFrameMeta(frame: NativeTerminalEngine.Frame) {
    val hasContent = frame.hasVisibleContent
    if (lastMetaAlternate == frame.alternate && lastMetaHasContent == hasContent &&
      lastMetaMouseTracking == frame.mouseTracking && lastMetaMouseSgr == frame.mouseSgr) return
    lastMetaAlternate = frame.alternate
    lastMetaHasContent = hasContent
    lastMetaMouseTracking = frame.mouseTracking
    lastMetaMouseSgr = frame.mouseSgr
    emit(EVENT_FRAME_META, true, Arguments.createMap().apply {
      putBoolean("alternate", frame.alternate)
      putInt("contentRows", frame.contentRows)
      putInt("rows", frame.rows)
      putInt("cursorRow", frame.cursorRow)
      putInt("lastContentRow", if (hasContent) frame.lastContentRow else -1)
      putBoolean("mouseTracking", frame.mouseTracking)
      putBoolean("mouseSgr", frame.mouseSgr)
    })
  }

  // ---------------------------------------------------------------------------
  // JS snapshot frames (no native session)
  // ---------------------------------------------------------------------------

  /**
   * Updates [visibleRect] to the part of this view that can be on screen,
   * padded by one viewport on each side. Falls back to the full view.
   */
  private fun updateVisibleRect() {
    if (!getLocalVisibleRect(visibleRect) || visibleRect.height() <= 0) {
      visibleRect.set(0, 0, width, height)
    } else {
      val pad = visibleRect.height()
      visibleRect.top -= pad
      visibleRect.bottom += pad
    }
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

  /**
   * Paints a row's non-default backgrounds before its text, one rect per run
   * of adjacent same-colored cells instead of one per cell (TUI status bars
   * and selections are usually a single run).
   */
  private fun drawNativeBackgrounds(
    canvas: Canvas,
    line: NativeTerminalEngine.FrameRow,
    columns: Int,
    defaultBackground: Int,
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
      val color = if (column < columns) line.background[column] else defaultBackground
      if (runStart >= 0 && (color != runColor || column != runEnd)) {
        paint.color = runColor
        canvas.drawRect(runStart * cellWidth, top, runEnd * cellWidth, bottom, paint)
        runStart = -1
      }
      if (column < columns && color != defaultBackground) {
        if (runStart < 0) {
          runStart = column
          runColor = color
        }
        runEnd = column + width
      }
    }
  }

  /**
   * Draws one row with at most one text draw per run of same-styled ASCII
   * cells. Styles are compared as (foreground, flags) primitives so the per
   * cell loop does not allocate. Inverse video is already resolved in the
   * row snapshot, so its colours are drawn as they are.
   */
  private fun drawNativeLine(
    canvas: Canvas,
    line: NativeTerminalEngine.FrameRow,
    rowLinks: IntArray,
    frameColumns: Int,
    defaultBackground: Int,
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
    drawNativeBackgrounds(canvas, line, columns, defaultBackground, cellWidth, rowTop, rowTop + cellHeight)
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
    // The PTY's size is owned by the session: binding must not resize the
    // engine to this view, which is shorter while the keyboard is open.
    val engine = NativeTerminalEngineRegistry.attach(sessionId, nativeRows, nativeColumns)
    nativeEngine = engine
    lastInputCount = engine.inputCount
    lastRequest = null
    nativeSubscription = engine.addListener { postInvalidateOnAnimation() }
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
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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

  internal companion object {
    const val EVENT_FRAME_META = "topNativeFrameMeta"
    const val EVENT_LINK = "topTerminalLink"
    const val EVENT_TAP = "topTerminalTap"
    const val EVENT_SELECTION = "topTerminalSelection"

    private const val NATIVE_CURSOR_HIDE_GRACE_MS = 120L
    private const val SYMBOLS_FONT_ASSET = "fonts/TerminalSymbols.ttf"
    private const val CURSOR_COLOR = 0x73F2F4F5
    /** Brand green (#80EB12) at about 35% so the text stays readable. */
    private const val SELECTION_COLOR = 0x5980EB12
    private const val HANDLE_COLOR = 0xFF80EB12.toInt()
    private const val SCROLLBAR_COLOR = 0xFFF2F4F5.toInt()
    private const val HANDLE_RADIUS_DP = 10f
    private const val HANDLE_TOUCH_DP = 28f
    private const val SCROLLBAR_WIDTH_DP = 3f
    private const val SCROLLBAR_MIN_DP = 32f
    private const val SCROLLBAR_VISIBLE_MS = 900L
    private const val SCROLLBAR_FADE_MS = 300L
    /** Scrollback rows captured above the screen while following and untouched. */
    private const val FOLLOW_IDLE_ABOVE = 2
    private const val FOLLOW_EPSILON_ROWS = 0.01
    private const val AUTO_SCROLL_ROWS_PER_FRAME = 0.5
    private const val WHEEL_ROWS_PER_TICK = 1.5f
    private const val MAX_TICKS_PER_FRAME = 8
    private const val MENU_COPY = 1
    private const val MENU_SELECT_ALL = 2

    // libghostty input enums (mouse/event.h, key/event.h).
    private const val MOUSE_PRESS = 0
    private const val MOUSE_RELEASE = 1
    private const val MOUSE_LEFT = 1
    private const val MOUSE_WHEEL_UP = 4
    private const val MOUSE_WHEEL_DOWN = 5
    private const val KEY_PRESS = 1
    private const val KEY_PAGE_DOWN = 73
    private const val KEY_PAGE_UP = 74
    private const val KEY_ARROW_DOWN = 75
    private const val KEY_ARROW_UP = 78

    private const val NATIVE_STYLE_FLAGS = NativeTerminalEngine.FLAG_BOLD or NativeTerminalEngine.FLAG_ITALIC or
      NativeTerminalEngine.FLAG_DIM or NativeTerminalEngine.FLAG_UNDERLINE or NativeTerminalEngine.FLAG_STRIKETHROUGH
    private const val NATIVE_LINE_FLAGS = NativeTerminalEngine.FLAG_UNDERLINE or NativeTerminalEngine.FLAG_STRIKETHROUGH
  }
}
