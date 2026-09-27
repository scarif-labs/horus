package com.scariflabs.horus.terminal

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The single owner of the local password verifier and its attempt limit.
 * The login screen and computer pairing both verify through here, so every
 * guess counts against the same persisted lockout.
 */
object PasswordGate {

  sealed interface Check {
    data object Success : Check
    data object Incorrect : Check
    data class Locked(val retryAfterMs: Long) : Check
    data object Unavailable : Check
  }

  fun isConfigured(context: Context): Boolean {
    val preferences = preferences(context)
    return preferences.getString(KEY_PASSWORD_SALT, null) != null &&
      preferences.getString(KEY_PASSWORD_VERIFIER, null) != null
  }

  /** Stores the first verifier. Never overwrites a configured one. */
  @Synchronized
  fun create(context: Context, password: String): Boolean {
    val preferences = preferences(context)
    if (preferences.getString(KEY_PASSWORD_VERIFIER, null) != null || !isValidPassword(password)) return false
    val salt = ByteArray(PASSWORD_SALT_BYTES).also(SecureRandom()::nextBytes)
    val verifier = derivePasswordVerifier(password, salt)
    preferences.edit()
      .remove(KEY_LEGACY_USERNAME)
      .remove(KEY_LEGACY_EMOJI)
      .putString(KEY_PASSWORD_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
      .putString(KEY_PASSWORD_VERIFIER, Base64.encodeToString(verifier, Base64.NO_WRAP))
      .putInt(KEY_PASSWORD_ITERATIONS, PASSWORD_ITERATIONS)
      .putBoolean(KEY_HAS_PASSWORD, true)
      .apply()
    return true
  }

  @Synchronized
  fun verify(context: Context, password: String?): Check {
    val preferences = preferences(context)
    // Refuse to evaluate guesses during a lockout so the attempt limit holds
    // no matter which caller asks.
    val now = System.currentTimeMillis()
    val lockedUntil = preferences.getLong(KEY_PASSWORD_LOCKED_UNTIL, 0L)
    val lastFailureAt = preferences.getLong(KEY_PASSWORD_LAST_FAILURE_AT, 0L)
    val retryAfterMs = when {
      // A clock moved backwards must not end a lockout early.
      now < lastFailureAt -> passwordLockoutMs(preferences.getInt(KEY_PASSWORD_FAILED_ATTEMPTS, 0))
      else -> lockedUntil - now
    }
    if (retryAfterMs > 0) return Check.Locked(retryAfterMs)
    val saltText = preferences.getString(KEY_PASSWORD_SALT, null)
    val verifierText = preferences.getString(KEY_PASSWORD_VERIFIER, null)
    if (saltText == null || verifierText == null) return Check.Unavailable
    if (password == null || !isValidPassword(password)) return Check.Incorrect
    val valid = try {
      val salt = Base64.decode(saltText, Base64.NO_WRAP)
      val expected = Base64.decode(verifierText, Base64.NO_WRAP)
      val actual = derivePasswordVerifier(
        password,
        salt,
        preferences.getInt(KEY_PASSWORD_ITERATIONS, PASSWORD_ITERATIONS),
      )
      MessageDigest.isEqual(expected, actual)
    } catch (_: IllegalArgumentException) {
      false
    }
    if (valid) {
      preferences.edit()
        .remove(KEY_PASSWORD_FAILED_ATTEMPTS)
        .remove(KEY_PASSWORD_LOCKED_UNTIL)
        .remove(KEY_PASSWORD_LAST_FAILURE_AT)
        .commit()
      return Check.Success
    }
    val failures = preferences.getInt(KEY_PASSWORD_FAILED_ATTEMPTS, 0) + 1
    // commit(), not apply(): the count must be on disk before the result is
    // visible, or killing the app after each guess would reset it.
    preferences.edit()
      .putInt(KEY_PASSWORD_FAILED_ATTEMPTS, failures)
      .putLong(KEY_PASSWORD_LAST_FAILURE_AT, now)
      .putLong(KEY_PASSWORD_LOCKED_UNTIL, now + passwordLockoutMs(failures))
      .commit()
    return Check.Incorrect
  }

  fun isValidPassword(password: String): Boolean =
    password.length in 4..128 && password.all { character ->
      character >= ' ' && character != '\u007f' && character != '\n' && character != '\r'
    }

  private fun preferences(context: Context) =
    context.applicationContext.getSharedPreferences(PROFILE_PREFERENCES, Context.MODE_PRIVATE)

  private fun derivePasswordVerifier(
    password: String,
    salt: ByteArray,
    iterations: Int = PASSWORD_ITERATIONS,
  ): ByteArray {
    require(iterations in MIN_PASSWORD_ITERATIONS..MAX_PASSWORD_ITERATIONS)
    val spec = PBEKeySpec(password.toCharArray(), salt, iterations, PASSWORD_KEY_BITS)
    return try {
      SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    } finally {
      spec.clearPassword()
    }
  }

  const val PROFILE_PREFERENCES = "horus_profile"
  const val KEY_LEGACY_USERNAME = "username"
  const val KEY_LEGACY_EMOJI = "emoji"
  const val KEY_HAS_PASSWORD = "has_password"
  const val KEY_PASSWORD_SALT = "password_salt"
  const val KEY_PASSWORD_VERIFIER = "password_verifier"
  private const val KEY_PASSWORD_ITERATIONS = "password_iterations"
  private const val KEY_PASSWORD_FAILED_ATTEMPTS = "password_failed_attempts"
  private const val KEY_PASSWORD_LOCKED_UNTIL = "password_locked_until_ms"
  private const val KEY_PASSWORD_LAST_FAILURE_AT = "password_last_failure_ms"
  private const val PASSWORD_SALT_BYTES = 16
  private const val PASSWORD_KEY_BITS = 256
  private const val PASSWORD_ITERATIONS = 120_000
  private const val MIN_PASSWORD_ITERATIONS = 50_000
  private const val MAX_PASSWORD_ITERATIONS = 500_000
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
