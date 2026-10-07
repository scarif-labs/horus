package com.scariflabs.horus.terminal

import android.text.Editable
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class TerminalInputViewTest {

  @Test
  fun `native input maps control and alt modifiers`() {
    assertEquals("\u0018", applyTerminalInputModifiers("x", ctrlActive = true, altActive = false))
    assertEquals("\u001bm", applyTerminalInputModifiers("m", ctrlActive = false, altActive = true))
    assertEquals("\u001b\u0000", applyTerminalInputModifiers(" ", ctrlActive = true, altActive = true))
    assertEquals("\u007f", applyTerminalInputModifiers("?", ctrlActive = true, altActive = false))
  }

  @Test
  fun `one-shot modifiers apply to the first key of a commit only`() {
    assertEquals("\u0003", applyOneShotTerminalModifiers("c", ctrlActive = true, altActive = false))
    assertEquals("\u000cs", applyOneShotTerminalModifiers("ls", ctrlActive = true, altActive = false))
    assertEquals("\u001bb.", applyOneShotTerminalModifiers("b.", ctrlActive = false, altActive = true))
    assertEquals("🙂x", applyOneShotTerminalModifiers("🙂x", ctrlActive = true, altActive = false))
    assertEquals("ls", applyOneShotTerminalModifiers("ls", ctrlActive = false, altActive = false))
    assertEquals("", applyOneShotTerminalModifiers("", ctrlActive = true, altActive = true))
  }

  @Test
  fun `native input leaves ordinary text unchanged without modifiers`() {
    assertEquals("model", applyTerminalInputModifiers("model", ctrlActive = false, altActive = false))
    assertEquals("é🙂", applyTerminalInputModifiers("é🙂", ctrlActive = true, altActive = false))
  }

  @Test
  fun `hardware enter sends one return across key down and key up`() {
    assertEquals(true, editorReturnAction(EditorInfo.IME_ACTION_SEND, KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_DOWN))
    assertEquals(false, editorReturnAction(EditorInfo.IME_NULL, KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_UP))
  }

  @Test
  fun `ime send without a key event sends once and other actions pass through`() {
    assertEquals(true, editorReturnAction(EditorInfo.IME_ACTION_SEND, null, null))
    assertNull(editorReturnAction(EditorInfo.IME_ACTION_NEXT, null, null))
  }

  @Test
  fun `clearing committed text empties the same buffer instead of replacing it`() {
    val buffer = StringBuilder("ls")
    val calls = mutableListOf<String>()
    val editable = recordingEditable(buffer, calls)

    assertTrue(clearTerminalEditable(editable))
    assertEquals("", buffer.toString())
    assertEquals(listOf("length", "clear"), calls)

    calls.clear()
    assertFalse(clearTerminalEditable(editable))
    assertEquals(listOf("length"), calls)
  }

  private fun recordingEditable(buffer: StringBuilder, calls: MutableList<String>): Editable =
    Proxy.newProxyInstance(Editable::class.java.classLoader, arrayOf(Editable::class.java)) { _, method, _ ->
      calls += method.name
      when (method.name) {
        "length" -> buffer.length
        "clear" -> buffer.setLength(0)
        else -> throw UnsupportedOperationException(method.name)
      }
    } as Editable
}
