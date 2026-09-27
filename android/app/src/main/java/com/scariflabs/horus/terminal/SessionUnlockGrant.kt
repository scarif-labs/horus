package com.scariflabs.horus.terminal

/**
 * Remembers, in the terminal service's memory only, that the user unlocked
 * Horus. Android can reclaim the UI process on low-memory devices while the
 * `:terminal` service keeps the user's harness running; a recreated UI asks
 * here instead of demanding the password again. Nothing is persisted: if the
 * service process dies too, the grant is gone and the next launch locks.
 *
 * The background timeout mirrors the UI's own lock (15 minutes) and is
 * measured on the monotonic clock.
 */
internal class SessionUnlockGrant(
  private val timeoutMs: Long = BACKGROUND_TIMEOUT_MS,
  private val clock: () -> Long,
) {
  private var unlocked = false
  private var backgroundedAt: Long? = null

  /**
   * Applies [op] and returns whether Horus is unlocked afterwards. `resume`
   * means the UI is in the foreground now: it expires a grant whose
   * background time ran out and otherwise clears the background mark.
   */
  @Synchronized
  fun apply(op: String): Boolean {
    when (op) {
      OP_GRANT -> {
        unlocked = true
        backgroundedAt = null
      }
      OP_REVOKE -> {
        unlocked = false
        backgroundedAt = null
      }
      OP_BACKGROUND -> if (unlocked && backgroundedAt == null) backgroundedAt = clock()
      OP_RESUME -> {
        val since = backgroundedAt
        if (unlocked && since != null && clock() - since >= timeoutMs) unlocked = false
        backgroundedAt = null
      }
    }
    return unlocked
  }

  companion object {
    const val OP_GRANT = "grant"
    const val OP_REVOKE = "revoke"
    const val OP_BACKGROUND = "background"
    const val OP_RESUME = "resume"
    const val BACKGROUND_TIMEOUT_MS = 15L * 60L * 1000L
    private val OPS = setOf(OP_GRANT, OP_REVOKE, OP_BACKGROUND, OP_RESUME)

    fun isValidOp(op: String?): Boolean = op != null && op in OPS
  }
}
