package com.scariflabs.horus.terminal

/**
 * Infers when an AI harness finishes a turn from PTY traffic alone.
 *
 * A turn starts when the user submits input (a carriage return). Claude Code,
 * Codex, and OpenCode redraw a spinner/elapsed timer continuously while they
 * work, so once output has been quiet for [quietMs] after at least
 * [minTurnMs] of work, the harness has either finished or stopped to ask for
 * approval. Both mean "the user is needed". Terminal replies (cursor reports,
 * device attributes) carry no carriage return and never start a turn.
 *
 * Not thread-safe on its own; the service calls it under its own lock.
 */
internal class HarnessTurnTracker(
  private val clock: () -> Long,
  private val quietMs: Long = QUIET_MS,
  private val minTurnMs: Long = MIN_TURN_MS,
) {
  enum class State { IDLE, WORKING }

  private class Turn(val startedAt: Long) {
    var lastOutputAt = 0L
  }

  private val turns = HashMap<String, Turn>()

  fun state(sessionId: String): State = if (turns.containsKey(sessionId)) State.WORKING else State.IDLE

  /** Returns true when this input started a new turn. */
  fun onInput(sessionId: String, bytes: ByteArray): Boolean {
    if (!bytes.contains(CARRIAGE_RETURN)) return false
    val existing = turns[sessionId]
    if (existing != null) return false
    turns[sessionId] = Turn(clock())
    return true
  }

  fun onOutput(sessionId: String) {
    turns[sessionId]?.lastOutputAt = clock()
  }

  /** Removes and returns the sessions whose turn has ended. */
  fun pollFinished(): List<String> {
    val now = clock()
    val finished = turns.filter { (_, turn) ->
      turn.lastOutputAt > 0L &&
        now - turn.lastOutputAt >= quietMs &&
        turn.lastOutputAt - turn.startedAt >= minTurnMs
    }.keys.toList()
    // A submit that produced only a brief echo (a slash-command menu, a
    // typo) is not a turn worth reporting; stop tracking it once quiet.
    val abandoned = turns.filter { (sessionId, turn) ->
      sessionId !in finished &&
        now - maxOf(turn.startedAt, turn.lastOutputAt) >= quietMs &&
        turn.lastOutputAt - turn.startedAt < minTurnMs
    }.keys
    finished.forEach(turns::remove)
    abandoned.forEach(turns::remove)
    return finished
  }

  fun hasActiveTurns(): Boolean = turns.isNotEmpty()

  fun remove(sessionId: String) {
    turns.remove(sessionId)
  }

  companion object {
    private const val CARRIAGE_RETURN = '\r'.code.toByte()
    const val QUIET_MS = 6_000L
    const val MIN_TURN_MS = 3_000L
  }
}
