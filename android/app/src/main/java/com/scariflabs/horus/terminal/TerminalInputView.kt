package com.scariflabs.horus.terminal

import android.graphics.Color
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.graphics.Rect
import android.os.Build
import android.view.View
import android.view.KeyEvent
import com.facebook.react.uimanager.PointerEvents
import com.facebook.react.uimanager.ReactPointerEventsView
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.widget.EditText
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.UIManagerHelper
import com.facebook.react.uimanager.events.Event
import java.nio.charset.StandardCharsets

internal fun applyTerminalInputModifiers(value: String, ctrlActive: Boolean, altActive: Boolean): String {
  if (!ctrlActive && !altActive) return value
  val output = StringBuilder(value.length + if (altActive) value.length else 0)
  value.codePoints().forEach { codePoint ->
    val modified = if (ctrlActive) controlCodePoint(codePoint) else codePoint
    if (altActive) output.appendCodePoint(0x1b)
    output.appendCodePoint(modified)
  }
  return output.toString()
}

/**
 * CTRL and ALT are one-shot, like Termux: they modify the first key of the
 * next commit only. A latched CTRL turned the next command into control codes,
 * and its ^S froze the terminal with no visible way out.
 */
internal fun applyOneShotTerminalModifiers(value: String, ctrlActive: Boolean, altActive: Boolean): String {
  if ((!ctrlActive && !altActive) || value.isEmpty()) return value
  val firstEnd = value.offsetByCodePoints(0, 1)
  return applyTerminalInputModifiers(value.substring(0, firstEnd), ctrlActive, altActive) + value.substring(firstEnd)
}

private class ModifiersConsumedEvent(surfaceId: Int, viewTag: Int) : Event<ModifiersConsumedEvent>(surfaceId, viewTag) {
  override fun getEventName(): String = EVENT_NAME

  override fun getEventData(): WritableMap = Arguments.createMap()

  companion object {
    const val EVENT_NAME = "topModifiersConsumed"
  }
}

private fun controlCodePoint(codePoint: Int): Int = when {
  codePoint == '?'.code -> 0x7f
  codePoint == ' '.code -> 0
  codePoint == '\t'.code -> '\t'.code
  codePoint in 0x40..0x5f || codePoint in 0x60..0x7e -> codePoint and 0x1f
  else -> codePoint
}

/**
 * Decides one editor action: null leaves it unhandled; otherwise it is
 * consumed and true means send a carriage return. A hardware Enter reaches
 * the listener on key down (as IME_ACTION_SEND) and again on key up, so only
 * the down event sends; an IME send without a key event sends once.
 */
internal fun editorReturnAction(actionId: Int, keyCode: Int?, keyAction: Int?): Boolean? = when {
  keyCode == KeyEvent.KEYCODE_ENTER -> keyAction == KeyEvent.ACTION_DOWN
  actionId == EditorInfo.IME_ACTION_SEND -> true
  else -> null
}

/** Bytes that bring the terminal line from [sent] to the editor's text. */
internal data class TerminalLineEdit(val deletes: Int, val insert: String)

/**
 * One DEL per code point after the longest shared prefix, then the editor's
 * new tail. A keyboard edit anywhere in the line (a swiped word, a tapped
 * suggestion, a correction) therefore reaches the shell as backspaces and
 * retyped text, which is right while the shell cursor is at the line end.
 */
internal fun terminalLineEdit(sent: String, current: String): TerminalLineEdit {
  var prefix = 0
  while (prefix < sent.length && prefix < current.length) {
    val codePoint = sent.codePointAt(prefix)
    if (codePoint != current.codePointAt(prefix)) break
    prefix += Character.charCount(codePoint)
  }
  return TerminalLineEdit(sent.codePointCount(prefix, sent.length), current.substring(prefix))
}

/**
 * Where to cut the front of a long line so [keep] chars stay, or 0 to leave it.
 * The cut never splits a surrogate pair.
 */
internal fun terminalLineTrimStart(line: CharSequence, max: Int, keep: Int): Int {
  if (line.length <= max) return 0
  val start = line.length - keep
  return if (Character.isLowSurrogate(line[start])) start + 1 else start
}

/**
 * Empties the editor buffer in place. TextView.setText swaps the buffer and
 * restarts the IME connection, so doing that after every committed key makes
 * the keyboard's next commit land on a stale session and get dropped.
 */
internal fun clearTerminalEditable(editable: Editable): Boolean {
  if (editable.isEmpty()) return false
  editable.clear()
  return true
}

/**
 * Keyboard-only terminal input. Text is committed straight to the native PTY
 * writer so a busy React Native output path cannot delay individual keys.
 *
 * The editor keeps the line typed since the last Return, and every change is
 * mirrored to the shell with [terminalLineEdit]. Keyboards need that text:
 * swipe typing, suggestions, and auto-spacing between swiped words all read
 * the words before the cursor. Composing text is sent as it changes, so a
 * swiped word shows up at once and a tapped suggestion replaces it.
 */
class TerminalInputView(context: ThemedReactContext) : EditText(context), ReactPointerEventsView {
  private var sessionId: String? = null
  private var terminalEnabled = false
  private var wantsAutoFocus = false
  private var ctrlActive = false
  private var altActive = false
  private var lastKeyboardShowRequest = 0
  private var lastKeyboardHideRequest = 0
  private var lastLineResetRequest = 0
  private var suppressChanges = false
  /** The editor text the shell has received since the line was last reset. */
  private var sentLine = ""

  private val watcher = object : TextWatcher {
    override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit

    override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit

    override fun afterTextChanged(editable: Editable?) {
      if (suppressChanges || !terminalEnabled || editable == null) return
      syncLine(editable)
    }
  }

  init {
    setBackgroundColor(Color.TRANSPARENT)
    setTextColor(Color.TRANSPARENT)
    setHintTextColor(Color.TRANSPARENT)
    setCursorVisible(false)
    setSingleLine(true)
    maxLines = 1
    filters = arrayOf(InputFilter.LengthFilter(MAX_INPUT_CHARS))
    // Plain single-line text, so keyboards offer swipe typing and
    // suggestions. Without TYPE_TEXT_FLAG_AUTO_CORRECT a single-line field
    // gets no autocorrect, and without the CAP flags no auto-capitals, so
    // typed commands arrive as typed. The password variations are avoided:
    // they turn swipe typing off and make Gboard show a "Passwords" chip.
    // Autofill and keyboard learning stay off, so typed commands never reach
    // a password manager or the dictionary.
    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
    imeOptions = EditorInfo.IME_ACTION_SEND or
      EditorInfo.IME_FLAG_NO_EXTRACT_UI or
      EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    }
    showSoftInputOnFocus = true
    addTextChangedListener(watcher)
    setOnEditorActionListener { _, actionId, event ->
      val sendReturn = editorReturnAction(actionId, event?.keyCode, event?.action)
      if (sendReturn == null) return@setOnEditorActionListener false
      if (sendReturn && terminalEnabled) write("\r")
      clearSilently()
      true
    }
    setOnKeyListener { _, keyCode, event ->
      if (terminalEnabled && keyCode == KeyEvent.KEYCODE_DEL && event.action == KeyEvent.ACTION_DOWN && text.isNullOrEmpty()) {
        write("\u007f")
        true
      } else false
    }
  }

  fun setTerminalSessionId(value: String?) {
    val next = value?.takeIf(String::isNotEmpty)
    if (sessionId == next) return
    sessionId = next
    clearSilently()
    requestAutoFocusIfReady()
  }

  fun setTerminalEnabled(value: Boolean) {
    terminalEnabled = value
    isEnabled = value
    isFocusableInTouchMode = value
    if (!value) clearSilently()
    requestAutoFocusIfReady()
  }

  fun setTerminalAutoFocus(value: Boolean) {
    wantsAutoFocus = value
    requestAutoFocusIfReady()
  }

  fun setCtrlActive(value: Boolean) {
    ctrlActive = value
  }

  fun setAltActive(value: Boolean) {
    altActive = value
  }

  fun setKeyboardShowRequest(value: Int) {
    if (value == lastKeyboardShowRequest) return
    lastKeyboardShowRequest = value
    requestKeyboardShow()
  }

  fun setKeyboardHideRequest(value: Int) {
    if (value == lastKeyboardHideRequest) return
    lastKeyboardHideRequest = value
    requestKeyboardHide()
  }

  /** Forgets the typed line after keys the editor did not see, such as Tab or arrows. */
  fun setLineResetRequest(value: Int) {
    if (value == lastLineResetRequest) return
    lastLineResetRequest = value
    clearSilently()
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    requestAutoFocusIfReady()
  }

  // The editor is invisible and only ever focused from code (a tap on the
  // terminal calls focus()). React Native picks touch targets itself and
  // ignores the JS pointerEvents prop on a custom native view, so declare it
  // here; otherwise this strip near the bottom of the screen swallows taps
  // meant for buttons drawn over it.
  override val pointerEvents: PointerEvents = PointerEvents.NONE

  override fun onFocusChanged(focused: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
    super.onFocusChanged(focused, direction, previouslyFocusedRect)
    if (!focused || !wantsAutoFocus || !terminalEnabled || sessionId == null) return
    // Keyboard.dismiss() can leave the native editor focused. The next focus
    // request therefore needs to explicitly ask Android to show the IME.
    post {
      if (isAttachedToWindow && isFocused && terminalEnabled && sessionId != null) {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
          ?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
      }
    }
  }

  private fun requestAutoFocusIfReady() {
    if (!wantsAutoFocus || !terminalEnabled || sessionId == null) return
    requestKeyboardShow()
  }

  private fun requestKeyboardShow() {
    if (!terminalEnabled || sessionId == null) return
    post {
      if (!isAttachedToWindow || !terminalEnabled || sessionId == null) return@post
      requestFocus()
      (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
          ?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }
  }

  private fun requestKeyboardHide() {
    post {
      if (!isAttachedToWindow) return@post
      (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
        ?.hideSoftInputFromWindow(windowToken, InputMethodManager.HIDE_NOT_ALWAYS)
    }
  }

  private fun syncLine(editable: Editable) {
    val edit = terminalLineEdit(sentLine, editable.toString())
    sentLine = editable.toString()
    val modified = edit.insert.isNotEmpty() && (ctrlActive || altActive)
    val insert = if (modified) applyOneShotTerminalModifiers(edit.insert, ctrlActive, altActive) else edit.insert
    write("\u007f".repeat(edit.deletes) + insert)
    if (modified) {
      releaseModifiers()
      // The shell got a control key instead of the typed text, so the line
      // in the editor no longer matches it.
      clearSilently()
    } else {
      trimLine(editable)
    }
  }

  /** Drops the front of a long line; keyboards only read the last few words. */
  private fun trimLine(editable: Editable) {
    if (hasComposingText(editable)) return
    val start = terminalLineTrimStart(editable, MAX_LINE_CHARS, KEPT_LINE_CHARS)
    if (start == 0) return
    suppressChanges = true
    editable.delete(0, start)
    suppressChanges = false
    sentLine = editable.toString()
  }

  private fun write(value: String) {
    val id = sessionId ?: return
    if (value.isEmpty()) return
    NativeTerminalEngineRegistry.writeInput(id, value.toByteArray(StandardCharsets.UTF_8))
  }

  /** Clears CTRL/ALT here at once and tells React Native to clear its buttons. */
  private fun releaseModifiers() {
    ctrlActive = false
    altActive = false
    if (id == NO_ID) return
    val reactContext = context as? ThemedReactContext ?: return
    UIManagerHelper.getEventDispatcher(reactContext)?.dispatchEvent(
      ModifiersConsumedEvent(UIManagerHelper.getSurfaceId(this), id),
    )
  }

  private fun clearSilently() {
    sentLine = ""
    val editable = text ?: return
    suppressChanges = true
    if (clearTerminalEditable(editable)) setSelection(0)
    suppressChanges = false
  }

  private fun hasComposingText(value: Editable): Boolean {
    val start = android.view.inputmethod.BaseInputConnection.getComposingSpanStart(value)
    val end = android.view.inputmethod.BaseInputConnection.getComposingSpanEnd(value)
    return start >= 0 && end > start
  }

  private companion object {
    const val MAX_INPUT_CHARS = 4096
    const val MAX_LINE_CHARS = 512
    const val KEPT_LINE_CHARS = 64
  }
}
