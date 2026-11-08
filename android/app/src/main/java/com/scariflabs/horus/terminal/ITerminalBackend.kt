package com.scariflabs.horus.terminal

/**
 * Common contract for terminal VT engines (libghostty-vt vs legacy Kotlin parser).
 */
internal interface ITerminalBackend : AutoCloseable {
  val bracketedPaste: Boolean
  val defaultBackground: Int
  val defaultForeground: Int
  fun currentFrame(): NativeTerminalEngine.Frame
  fun hasListeners(): Boolean
  fun addListener(listener: () -> Unit): AutoCloseable
  fun enqueue(seq: Long, bytes: ByteArray): Boolean
  fun resize(newRows: Int, newColumns: Int)
  override fun close()
  fun recoverFromOutputGap()
}

/**
 * Selectable terminal engine backend for rollout gates, diagnostics, and side-by-side comparison.
 */
enum class TerminalEngineBackend {
  /** High-performance standalone C virtual terminal library from Ghostty. */
  LIBGHOSTTY,

  /** Legacy pure-Kotlin VT emulation engine preserved for rollout gate comparison. */
  LEGACY;

  companion object {
    fun fromName(name: String?): TerminalEngineBackend = when (name?.uppercase()) {
      "LEGACY" -> LEGACY
      else -> LIBGHOSTTY
    }
  }
}
