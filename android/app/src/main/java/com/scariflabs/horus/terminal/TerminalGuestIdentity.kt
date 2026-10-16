package com.scariflabs.horus.terminal

import android.content.Context

/** RN-free guest identity for service launch: the configured user of a distribution. */
object TerminalGuestIdentity {
  /** The Linux account name existing 1.0.x installs already have; still Alpine's default. */
  const val PROFILE_USERNAME = ProfileUsersCore.DEFAULT_USERNAME

  /** The Linux username for [distro]'s user, or null when that distribution has no user yet. */
  fun readStoredUsername(context: Context, distro: String): String? {
    // The :terminal process reads what the UI process wrote. Android caches
    // preferences per process, so without a reload a service that started
    // during onboarding would keep seeing "no profile" and launch sessions
    // as the guest root. MODE_MULTI_PROCESS re-reads the file when it
    // changed on disk; this side only reads.
    @Suppress("DEPRECATION")
    val preferences = context.getSharedPreferences(
      PasswordGate.PROFILE_PREFERENCES,
      Context.MODE_PRIVATE or Context.MODE_MULTI_PROCESS,
    )
    return ProfileUsersCore(PasswordGate.PreferenceValues(preferences))
      .user(distro)
      ?.username
  }
}
