package com.scariflabs.horus.terminal

import android.content.Context
import android.content.SharedPreferences

/**
 * The app's handle on [ProfileUsersCore]: users, password verifiers, and
 * the attempt limit, stored in the profile SharedPreferences. The login
 * screen and computer pairing both verify through here, so every guess
 * counts against the same persisted lockout.
 */
object PasswordGate {
  /** SharedPreferences as [ProfileKeyValues]; commit() so a counted guess is on disk before it is answered. */
  class PreferenceValues(private val preferences: SharedPreferences) : ProfileKeyValues {
    override fun string(key: String): String? = preferences.getString(key, null)
    override fun int(key: String, default: Int): Int = preferences.getInt(key, default)
    override fun long(key: String, default: Long): Long = preferences.getLong(key, default)
    override fun commit(changes: Map<String, Any?>) {
      val editor = preferences.edit()
      for ((key, value) in changes) {
        when (value) {
          null -> editor.remove(key)
          is String -> editor.putString(key, value)
          is Int -> editor.putInt(key, value)
          is Long -> editor.putLong(key, value)
          is Boolean -> editor.putBoolean(key, value)
          else -> throw IllegalArgumentException("unsupported profile value for $key")
        }
      }
      if (!editor.commit()) throw IllegalStateException("profile preferences were not saved")
    }
  }

  private var core: ProfileUsersCore? = null

  /** One shared core per process, so its synchronized create and verify serialize every caller. */
  @Synchronized
  fun users(context: Context): ProfileUsersCore =
    core ?: ProfileUsersCore(PreferenceValues(preferences(context))).also { core = it }

  fun isConfigured(context: Context): Boolean = users(context).isConfigured()

  fun create(context: Context, distro: String, username: String, avatar: String, password: String): ProfileUsersCore.CreateResult =
    users(context).create(distro, username, avatar, password)

  fun update(context: Context, distro: String, username: String, avatar: String): ProfileUsersCore.UpdateResult =
    users(context).update(distro, username, avatar)

  /** Checks one user's password, or any user's when [distro] is null. */
  fun verify(context: Context, distro: String?, password: String?): ProfileUsersCore.Check =
    users(context).verify(distro, password)

  private fun preferences(context: Context) =
    context.applicationContext.getSharedPreferences(PROFILE_PREFERENCES, Context.MODE_PRIVATE)

  const val PROFILE_PREFERENCES = "horus_profile"
}

private const val FREE_PASSWORD_ATTEMPTS = 5
private const val FIRST_PASSWORD_LOCKOUT_MS = 30_000L
private const val MAX_PASSWORD_LOCKOUT_MS = 15 * 60_000L

/**
 * Lockout after [failures] consecutive wrong passwords: none for the first
 * five, then 30 seconds doubling per failure up to 15 minutes.
 */
internal fun passwordLockoutMs(failures: Int): Long {
  if (failures <= FREE_PASSWORD_ATTEMPTS) return 0L
  val doublings = (failures - FREE_PASSWORD_ATTEMPTS - 1).coerceAtMost(10)
  return (FIRST_PASSWORD_LOCKOUT_MS shl doublings).coerceAtMost(MAX_PASSWORD_LOCKOUT_MS)
}
