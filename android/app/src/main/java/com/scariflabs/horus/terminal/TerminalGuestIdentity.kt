package com.scariflabs.horus.terminal

import android.content.Context

/** RN-free fixed Alpine identity for service launch when a local profile exists. */
object TerminalGuestIdentity {
  private const val PROFILE_PREFERENCES = "horus_profile"
  private const val KEY_PASSWORD_SALT = "password_salt"
  private const val KEY_PASSWORD_VERIFIER = "password_verifier"
  const val PROFILE_USERNAME = "horus"

  fun readStoredUsername(context: Context): String? {
    // The :terminal process reads what the UI process wrote. Android caches
    // preferences per process, so without a reload a service that started
    // during onboarding would keep seeing "no profile" and launch sessions
    // as the guest root. MODE_MULTI_PROCESS re-reads the file when it
    // changed on disk; this side only reads.
    @Suppress("DEPRECATION")
    val preferences = context.getSharedPreferences(PROFILE_PREFERENCES, Context.MODE_PRIVATE or Context.MODE_MULTI_PROCESS)
    val configured = preferences.getString(KEY_PASSWORD_SALT, null) != null &&
      preferences.getString(KEY_PASSWORD_VERIFIER, null) != null
    return PROFILE_USERNAME.takeIf { configured }
  }
}
