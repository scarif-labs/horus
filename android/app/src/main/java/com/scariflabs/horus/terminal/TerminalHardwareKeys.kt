package com.scariflabs.horus.terminal

import android.view.KeyEvent

/**
 * Maps Android key events from a physical keyboard onto libghostty's key
 * encoder (key/event.h), which then speaks whatever the app asked for:
 * cursor-key mode, the kitty keyboard protocol (Shift+Enter in Claude Code),
 * or plain VT sequences.
 */
internal object TerminalHardwareKeys {
  const val ACTION_RELEASE = 0
  const val ACTION_PRESS = 1
  const val ACTION_REPEAT = 2

  const val MOD_SHIFT = 1
  const val MOD_CTRL = 1 shl 1
  const val MOD_ALT = 1 shl 2
  const val MOD_SUPER = 1 shl 3
  const val MOD_CAPS_LOCK = 1 shl 4
  const val MOD_NUM_LOCK = 1 shl 5

  /** A key the encoder knows, its modifiers, and the text it types. */
  data class Key(
    val key: Int,
    val mods: Int,
    val consumedMods: Int,
    val unshifted: Int,
    val text: String?,
  )

  /** libghostty's key for an Android key code, or 0 for keys the terminal does not take. */
  fun ghosttyKey(keyCode: Int): Int = when (keyCode) {
    in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> 20 + (keyCode - KeyEvent.KEYCODE_A)
    in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> 6 + (keyCode - KeyEvent.KEYCODE_0)
    in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> 80 + (keyCode - KeyEvent.KEYCODE_NUMPAD_0)
    in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> 121 + (keyCode - KeyEvent.KEYCODE_F1)
    KeyEvent.KEYCODE_GRAVE -> 1
    KeyEvent.KEYCODE_BACKSLASH -> 2
    KeyEvent.KEYCODE_LEFT_BRACKET -> 3
    KeyEvent.KEYCODE_RIGHT_BRACKET -> 4
    KeyEvent.KEYCODE_COMMA -> 5
    KeyEvent.KEYCODE_EQUALS -> 16
    KeyEvent.KEYCODE_MINUS -> 46
    KeyEvent.KEYCODE_PERIOD -> 47
    KeyEvent.KEYCODE_APOSTROPHE -> 48
    KeyEvent.KEYCODE_SEMICOLON -> 49
    KeyEvent.KEYCODE_SLASH -> 50
    KeyEvent.KEYCODE_DEL -> 53
    KeyEvent.KEYCODE_ENTER -> 58
    KeyEvent.KEYCODE_SPACE -> 63
    KeyEvent.KEYCODE_TAB -> 64
    KeyEvent.KEYCODE_FORWARD_DEL -> 68
    KeyEvent.KEYCODE_MOVE_END -> 69
    KeyEvent.KEYCODE_MOVE_HOME -> 71
    KeyEvent.KEYCODE_INSERT -> 72
    KeyEvent.KEYCODE_PAGE_DOWN -> 73
    KeyEvent.KEYCODE_PAGE_UP -> 74
    KeyEvent.KEYCODE_DPAD_DOWN -> 75
    KeyEvent.KEYCODE_DPAD_LEFT -> 76
    KeyEvent.KEYCODE_DPAD_RIGHT -> 77
    KeyEvent.KEYCODE_DPAD_UP -> 78
    KeyEvent.KEYCODE_NUMPAD_ADD -> 90
    KeyEvent.KEYCODE_NUMPAD_COMMA -> 94
    KeyEvent.KEYCODE_NUMPAD_DOT -> 95
    KeyEvent.KEYCODE_NUMPAD_DIVIDE -> 96
    KeyEvent.KEYCODE_NUMPAD_ENTER -> 97
    KeyEvent.KEYCODE_NUMPAD_EQUALS -> 98
    KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> 104
    KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> 107
    KeyEvent.KEYCODE_ESCAPE -> 120
    else -> 0
  }

  /** libghostty's modifier bits for an Android meta state. */
  fun ghosttyMods(metaState: Int): Int {
    var mods = 0
    if (metaState and KeyEvent.META_SHIFT_ON != 0) mods = mods or MOD_SHIFT
    if (metaState and KeyEvent.META_CTRL_ON != 0) mods = mods or MOD_CTRL
    if (metaState and KeyEvent.META_ALT_ON != 0) mods = mods or MOD_ALT
    if (metaState and KeyEvent.META_META_ON != 0) mods = mods or MOD_SUPER
    if (metaState and KeyEvent.META_CAPS_LOCK_ON != 0) mods = mods or MOD_CAPS_LOCK
    if (metaState and KeyEvent.META_NUM_LOCK_ON != 0) mods = mods or MOD_NUM_LOCK
    return mods
  }

  /**
   * The encoder's view of a key: [typed] is the character the layout gives
   * with only Shift/Caps applied (0 for none), [unshifted] the one with no
   * modifiers. Control characters are not text; the encoder uses the key.
   */
  fun key(keyCode: Int, metaState: Int, typed: Int, unshifted: Int): Key? {
    val key = ghosttyKey(keyCode)
    if (key == 0) return null
    val text = if (typed >= 0x20 && typed != 0x7f && typed and KeyCharacterMapCombining == 0) {
      String(Character.toChars(typed))
    } else null
    val mods = ghosttyMods(metaState)
    // Shift that changed the character was used up making it ("A", "!").
    val consumed = if (text != null && typed != unshifted) mods and (MOD_SHIFT or MOD_CAPS_LOCK) else 0
    return Key(key, mods, consumed, if (unshifted >= 0x20) unshifted else 0, text)
  }

  /** KeyCharacterMap.COMBINING_ACCENT: a dead key, which types nothing by itself. */
  private const val KeyCharacterMapCombining = 0x80000000.toInt()
}
