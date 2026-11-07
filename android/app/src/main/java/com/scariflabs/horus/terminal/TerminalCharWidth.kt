package com.scariflabs.horus.terminal

/**
 * Terminal cell width of a code point (0, 1 or 2) backed by libghostty-vt.
 */
internal object TerminalCharWidth {
  fun of(codePoint: Int): Int {
    if (codePoint in 0x20..0x7E) return 1
    if (codePoint < 0x20 || codePoint in 0x7F..0x9F) return 1
    val width = TerminalPtyJni.nativeUnicodeWidth(codePoint)
    return if (width >= 0) width else 1
  }
}
