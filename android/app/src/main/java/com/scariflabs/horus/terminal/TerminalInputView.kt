package com.scariflabs.horus.terminal

import android.graphics.Color
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.graphics.Rect
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.widget.EditText
import com.facebook.react.uimanager.ThemedReactContext
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
 */
class TerminalInputView(context: ThemedReactContext) : EditText(context) {
  private var sessionId: String? = null
  private var terminalEnabled = false
  private var wantsAutoFocus = false
  private var ctrlActive = false
  private var altActive = false
  private var lastKeyboardShowRequest = 0
  private var lastKeyboardHideRequest = 0
  private var suppressChanges = false

  private val watcher = object : TextWatcher {
    override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit

    override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit

    override fun afterTextChanged(editable: Editable?) {
      if (suppressChanges || !terminalEnabled || editable == null || editable.isEmpty()) return
      if (hasComposingText(editable)) return
      val value = editable.toString()
      send(value)
      clearSilently()
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
    inputType = InputType.TYPE_CLASS_TEXT or
      InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
      InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
    imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI
    showSoftInputOnFocus = true
    addTextChangedListener(watcher)
    setOnEditorActionListener { _, actionId, event ->
      val sendReturn = editorReturnAction(actionId, event?.keyCode, event?.action)
      if (sendReturn == null) return@setOnEditorActionListener false
      if (sendReturn && terminalEnabled) send("\r", applyModifiers = false)
      clearSilently()
      true
    }
    setOnKeyListener { _, keyCode, event ->
      if (terminalEnabled && keyCode == KeyEvent.KEYCODE_DEL && event.action == KeyEvent.ACTION_DOWN && text.isNullOrEmpty()) {
        send("\u007f", applyModifiers = false)
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

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    requestAutoFocusIfReady()
  }

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

  private fun send(value: String, applyModifiers: Boolean = true) {
    val id = sessionId ?: return
    if (value.isEmpty()) return
    val output = if (applyModifiers) applyTerminalInputModifiers(value, ctrlActive, altActive) else value
    NativeTerminalEngineRegistry.writeInput(id, output.toByteArray(StandardCharsets.UTF_8))
  }

  private fun clearSilently() {
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
  }
}
