package com.scariflabs.horus.terminal

/**
 * Decides when to force one repaint after a harness TUI starts.
 *
 * Claude Code (a Bun binary) reads the PTY size wrongly at startup under
 * PRoot and draws at 80 columns until its first SIGWINCH, even though the
 * PTY was resized before it launched. The fix is a resize nudge, but it must
 * arrive after the TUI installed its resize handler: a SIGWINCH before that
 * is ignored. A TUI enables bracketed paste (`ESC [?2004h`) while setting up
 * its input, so the nudge waits for that and for the first frame to settle,
 * with a fallback deadline if the mode switch is never seen.
 *
 * Not thread-safe on its own; the service calls it under its own lock.
 */
internal class HarnessStartupRedraw(
  private val clock: () -> Long,
  private val settleMs: Long = SETTLE_MS,
  private val fallbackMs: Long = FALLBACK_MS,
) {
  private class Pending(val handoffAt: Long) {
    var drawing = false
    var lastOutputAt = 0L
    var tail = ""
  }

  private val pending = HashMap<String, Pending>()
  private val handoffTails = HashMap<String, String>()
  private val finished = HashSet<String>()

  fun onOutput(sessionId: String, bytes: ByteArray) {
    if (sessionId in finished) return
    val text = String(bytes, Charsets.ISO_8859_1)
    val now = clock()
    val current = pending[sessionId]
    if (current == null) {
      val scan = (handoffTails[sessionId] ?: "") + text
      val index = scan.indexOf(HANDOFF_MARKER)
      if (index < 0) {
        handoffTails[sessionId] = scan.takeLast(HANDOFF_MARKER.length)
        return
      }
      handoffTails.remove(sessionId)
      val next = Pending(now)
      pending[sessionId] = next
      observe(next, scan.substring(index + HANDOFF_MARKER.length), now)
      return
    }
    observe(current, text, now)
  }

  private fun observe(state: Pending, text: String, now: Long) {
    if (!state.drawing) {
      val scan = state.tail + text
      if (!scan.contains(BRACKETED_PASTE_ON)) {
        state.tail = scan.takeLast(BRACKETED_PASTE_ON.length)
        return
      }
      state.drawing = true
      state.tail = ""
    }
    state.lastOutputAt = now
  }

  /** Removes and returns the sessions that should be repainted now. */
  fun pollDue(): List<String> {
    val now = clock()
    val due = pending.filter { (_, state) ->
      (state.drawing && now - state.lastOutputAt >= settleMs) || now - state.handoffAt >= fallbackMs
    }.keys.toList()
    due.forEach { sessionId ->
      pending.remove(sessionId)
      finished += sessionId
    }
    return due
  }

  fun hasPending(): Boolean = pending.isNotEmpty()

  fun remove(sessionId: String) {
    pending.remove(sessionId)
    handoffTails.remove(sessionId)
    finished.remove(sessionId)
  }

  companion object {
    const val HANDOFF_MARKER = "HORUS_INSTALL_HANDOFF="
    const val BRACKETED_PASTE_ON = "\u001b[?2004h"
    const val SETTLE_MS = 700L
    const val FALLBACK_MS = 30_000L
  }
}
