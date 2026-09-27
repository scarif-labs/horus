package com.scariflabs.horus.terminal

import android.content.Intent
import java.util.concurrent.atomic.AtomicReference

/**
 * Carries the session a notification tap should open from MainActivity to
 * the React Native bridge. The id is consumed once so a later relaunch or
 * configuration change does not reopen it.
 */
object LaunchSessionIntent {
  const val EXTRA_SESSION_ID = "com.scariflabs.horus.extra.SESSION_ID"
  private val pending = AtomicReference<String?>(null)

  fun record(intent: Intent?) {
    val sessionId = intent?.getStringExtra(EXTRA_SESSION_ID) ?: return
    intent.removeExtra(EXTRA_SESSION_ID)
    if (TerminalSessionContract.isValidSessionId(sessionId)) pending.set(sessionId)
  }

  fun consume(): String? = pending.getAndSet(null)
}
