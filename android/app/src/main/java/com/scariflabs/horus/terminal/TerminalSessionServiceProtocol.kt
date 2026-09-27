package com.scariflabs.horus.terminal

/**
 * Private Messenger protocol between the React Native bridge process and the
 * terminal foreground-service process. The wire surface deliberately carries
 * only bounded primitive values; no React Native or Parcelable application
 * objects cross the process boundary.
 */
object TerminalSessionServiceProtocol {
  const val ACTION = "com.scariflabs.horus.action.TERMINAL_SESSION_SERVICE"

  const val MSG_REGISTER_CLIENT = 1
  const val MSG_UNREGISTER_CLIENT = 2
  const val MSG_START_SESSION = 3
  const val MSG_WRITE_SESSION = 4
  const val MSG_RESIZE_SESSION = 5
  const val MSG_SIGNAL_SESSION = 6
  const val MSG_STOP_SESSION = 7
  const val MSG_SUBSCRIBE_SESSION = 8
  const val MSG_ACKNOWLEDGE_OUTPUT = 9
  const val MSG_STOP_ALL = 10
  const val MSG_PROVISION_TOOLCHAIN = 11
  const val MSG_LIST_SESSIONS = 12
  const val MSG_DETACH_SESSION = 13
  const val MSG_UNLOCK_GRANT = 14
  const val MSG_REDRAW_SESSION = 15
  /** Fire-and-forget: the UI activity became visible (true) or hidden (false). */
  const val MSG_UI_VISIBILITY = 16

  const val MSG_SESSION_RESPONSE = 100
  const val MSG_SESSION_EVENT = 101

  const val EVENT_OUTPUT = "output"
  const val EVENT_EXIT = "exit"

  const val STATUS_SUCCESS = "success"
  const val STATUS_ERROR = "error"

  const val KEY_REQUEST_ID = "requestId"
  const val KEY_STATUS = "status"
  const val KEY_ERROR_CODE = "errorCode"
  const val KEY_SESSION_ID = "sessionId"
  const val KEY_PID = "pid"
  const val KEY_ROWS = "rows"
  const val KEY_COLUMNS = "columns"
  const val KEY_COMMAND = "command"
  const val KEY_TARGET = "target"
  const val KEY_COUNTS_AGAINST_SESSION_LIMIT = "countsAgainstSessionLimit"
  const val KEY_SESSIONS = "sessions"
  const val KEY_STARTED_AT_MS = "startedAtMs"
  const val KEY_BYTES = "bytes"
  const val KEY_BYTES_WRITTEN = "bytesWritten"
  const val KEY_SIGNAL = "signal"
  const val KEY_REASON = "reason"
  const val KEY_EXIT_CODE = "exitCode"
  const val KEY_EXIT_SIGNAL = "exitSignal"
  const val KEY_EXIT_REASON = "exitReason"
  const val KEY_REMAINING_PROCESS_COUNT = "remainingProcessCount"
  const val KEY_STOPPED_WITHIN_DEADLINE = "stoppedWithinDeadline"
  const val KEY_SESSION_STATE = "sessionState"
  const val KEY_FIRST_AVAILABLE_SEQ = "firstAvailableSeq"
  const val KEY_LAST_EMITTED_SEQ = "lastEmittedSeq"
  const val KEY_REPLAY_AVAILABLE = "replayAvailable"
  const val KEY_ACKNOWLEDGED_SEQ = "acknowledgedSeq"
  const val KEY_OUTSTANDING_CHUNKS = "outstandingChunks"
  const val KEY_EVENT_TYPE = "eventType"
  const val KEY_SEQ = "seq"
  const val KEY_AFTER_SEQ = "afterSeq"
  const val KEY_UNLOCK_OP = "unlockOp"
  const val KEY_UNLOCKED = "unlocked"
  const val KEY_VISIBLE = "visible"
}
