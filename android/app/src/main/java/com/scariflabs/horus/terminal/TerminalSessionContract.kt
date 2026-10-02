package com.scariflabs.horus.terminal

/**
 * Pure contract for Phase 2 native PTY sessions. Keeps
 * every bounded-input rule, the signal table, the exit-status decoding, and
 * the session-id rules free of Android framework types so JVM unit tests
 * cover each rule exactly.
 */
object TerminalSessionContract {
  /** Native-issued session ids look like `s-<epochMillis>-<sequence>`. */
  private val SESSION_ID_PATTERN = Regex("^[a-z0-9][a-z0-9-]{1,63}$")

  const val MIN_ROWS = 2
  const val MAX_ROWS = 250
  const val MIN_COLUMNS = 2
  const val MAX_COLUMNS = 500

  /** One write request may carry at most this many decoded bytes. */
  const val MAX_INPUT_BYTES = 16 * 1024
  /** Interactive launcher commands are bounded before they cross IPC or persistence. */
  const val MAX_COMMAND_LENGTH = 4096
  /** A stalled PTY cannot hold a bridge write forever. */
  const val WRITE_DEADLINE_MS = 2_000L

  /** Worst-case base64 length of [MAX_INPUT_BYTES] (with padding). */
  const val MAX_INPUT_BASE64_CHARS = ((MAX_INPUT_BYTES + 2) / 3) * 4

  /** The lowest session limit Settings offers. */
  const val MIN_ACTIVE_SESSIONS = 1
  /** The default user-facing session limit; Settings can change it within the bounds. */
  const val DEFAULT_ACTIVE_SESSIONS = 2
  /** Hard bound for persisted session metadata and native protocol arrays. */
  const val MAX_ACTIVE_SESSIONS = 4

  /** Exited session records retained for idempotent stop/subscribe calls. */
  const val MAX_RETAINED_EXITED_SESSIONS = 8

  const val REASON_MAX_CHARS = 64
  private val REASON_PATTERN = Regex("^[a-z0-9_]{1,64}$")

  /** Bounded stop escalation windows (plan: bounded TERM/KILL escalation). */
  const val STOP_TERM_WAIT_MS = 2_000L
  const val STOP_KILL_WAIT_MS = 2_000L
  const val STOP_POLL_MS = 25L
  /** Must stay outside the negative signal range used by the JNI helper. */
  const val WAIT_ERROR_BASE = 1_000

  /** Signals the bridge may deliver to a session's process group. */
  val SIGNALS: Map<String, Int> = mapOf(
    "sighup" to 1,
    "sigint" to 2,
    "sigquit" to 3,
    "sigterm" to 15,
    "sigkill" to 9,
  )

  /**
   * Names for observed deaths, broader than the deliverable set so a death
   * report never says "unknown" for a common signal.
   */
  private val DEATH_SIGNAL_NAMES: Map<Int, String> = mapOf(
    1 to "sighup", 2 to "sigint", 3 to "sigquit", 4 to "sigill",
    6 to "sigabrt", 7 to "sigbus", 9 to "sigkill", 10 to "sigusr1",
    11 to "sigsegv", 12 to "sigusr2", 13 to "sigpipe", 15 to "sigterm",
    24 to "sigxcpu", 25 to "sigxfsz", 31 to "sigsys",
  )

  private val SIGNAL_NAMES: Map<Int, String> =
    SIGNALS.entries.associate { (name, number) -> number to name } + DEATH_SIGNAL_NAMES

  fun signalNumber(name: String?): Int? = name?.let { SIGNALS[it] }

  fun signalName(number: Int): String? = SIGNAL_NAMES[number]

  fun isValidSessionId(sessionId: String?): Boolean =
    sessionId != null && SESSION_ID_PATTERN.matches(sessionId)

  fun isValidStopReason(reason: String?): Boolean =
    reason != null && REASON_PATTERN.matches(reason)

  fun isValidRows(rows: Int): Boolean = rows in MIN_ROWS..MAX_ROWS

  fun isValidColumns(columns: Int): Boolean = columns in MIN_COLUMNS..MAX_COLUMNS

  fun isValidActiveSessionLimit(limit: Int): Boolean = limit in MIN_ACTIVE_SESSIONS..MAX_ACTIVE_SESSIONS

  /** A positive status is the waitpid exit code; negative is a signal (or errno). */
  fun isPlausibleEncodedStatus(encoded: Int): Boolean = encoded in -64..255

  /**
   * Decodes the helper's waitpid encoding: 0..255 for a normal exit, a
   * negative signal number for a signal death. Null means the value cannot
   * be a wait status (treated as an internal error by callers).
   */
  fun decodeExitStatus(encoded: Int): ExitStatus? = when {
    encoded in 0..255 -> ExitStatus(exitCode = encoded, signal = null)
    isPlausibleEncodedStatus(encoded) && signalName(-encoded) != null ->
      ExitStatus(exitCode = null, signal = signalName(-encoded))
    else -> null
  }

  data class ExitStatus(val exitCode: Int?, val signal: String?)
}
