package com.scariflabs.horus.terminal

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** The persisted key-value view the user store needs: SharedPreferences in the app, a map in tests. */
interface ProfileKeyValues {
  fun string(key: String): String?
  fun int(key: String, default: Int): Int
  fun long(key: String, default: Long): Long

  /** Applies every change durably before returning; a null value removes the key. */
  fun commit(changes: Map<String, Any?>)
}

/** One Linux user: at most one per distribution. */
data class ProfileUser(val distro: String, val username: String, val avatar: String)

/**
 * The local users and their password verifiers. There is at most one user
 * per distribution, each with its own password, and one shared attempt
 * limit, so a second user never doubles the guesses an attacker gets.
 *
 * Alpine's verifier keeps the keys Horus 1.0.x wrote, so an upgrade keeps
 * the existing password and a downgrade can still unlock. An Alpine user
 * from before usernames existed reads as [DEFAULT_USERNAME] with
 * [DEFAULT_AVATAR], which is also the Linux account those installs already
 * have.
 */
class ProfileUsersCore(
  private val store: ProfileKeyValues,
  private val clock: () -> Long = System::currentTimeMillis,
  private val iterations: Int = PASSWORD_ITERATIONS,
) {
  sealed interface Check {
    data object Success : Check
    data object Incorrect : Check
    data class Locked(val retryAfterMs: Long) : Check
    data object Unavailable : Check
  }

  enum class CreateResult { CREATED, EXISTS, INVALID_DISTRO, INVALID_USERNAME, INVALID_AVATAR, INVALID_PASSWORD }
  enum class UpdateResult { UPDATED, NOT_FOUND, INVALID_USERNAME, INVALID_AVATAR }

  fun user(distro: String): ProfileUser? {
    if (distro !in DISTROS || !hasVerifier(distro)) return null
    val username = store.string(usernameKey(distro))?.takeIf(::isValidUsername) ?: DEFAULT_USERNAME
    val avatar = store.string(avatarKey(distro))?.takeIf { it in AVATARS } ?: DEFAULT_AVATAR
    return ProfileUser(distro, username, avatar)
  }

  /** Configured users, Alpine first. */
  fun users(): List<ProfileUser> = DISTROS.mapNotNull(::user)

  fun isConfigured(): Boolean = DISTROS.any(::hasVerifier)

  /**
   * True for an install from before Linux users: Alpine has a password but
   * no stored username, so it silently became [DEFAULT_USERNAME]. Home
   * points such a user at Account once; editing or dismissing ends it.
   */
  fun needsAccountIntro(): Boolean =
    hasVerifier(RootfsCatalog.DISTRO_ALPINE) &&
      store.string(usernameKey(RootfsCatalog.DISTRO_ALPINE)) == null &&
      store.int(KEY_ACCOUNT_INTRO_SEEN, 0) == 0

  fun dismissAccountIntro() = store.commit(mapOf(KEY_ACCOUNT_INTRO_SEEN to 1))

  /** Adds the user for [distro]. Never replaces an existing one, or the lock could be reset without its password. */
  @Synchronized
  fun create(distro: String, username: String, avatar: String, password: String): CreateResult {
    if (distro !in DISTROS) return CreateResult.INVALID_DISTRO
    if (hasVerifier(distro)) return CreateResult.EXISTS
    if (!isValidUsername(username)) return CreateResult.INVALID_USERNAME
    if (avatar !in AVATARS) return CreateResult.INVALID_AVATAR
    if (!isValidPassword(password)) return CreateResult.INVALID_PASSWORD
    val salt = ByteArray(PASSWORD_SALT_BYTES).also(SecureRandom()::nextBytes)
    val verifier = derive(password, salt, iterations)
    val prefix = passwordPrefix(distro)
    val changes = mutableMapOf<String, Any?>(
      prefix + KEY_PASSWORD_SALT to Base64Codec.encode(salt, padding = true),
      prefix + KEY_PASSWORD_VERIFIER to Base64Codec.encode(verifier, padding = true),
      prefix + KEY_PASSWORD_ITERATIONS to iterations,
      usernameKey(distro) to username,
      avatarKey(distro) to avatar,
    )
    if (distro == RootfsCatalog.DISTRO_ALPINE) {
      changes[KEY_HAS_PASSWORD] = true
      changes[KEY_LEGACY_USERNAME] = null
      changes[KEY_LEGACY_EMOJI] = null
    }
    store.commit(changes)
    return CreateResult.CREATED
  }

  /**
   * Renames an existing user or changes their avatar. The password stays;
   * it belongs to the distribution, not the name. The guest account follows
   * on its next launch, which renames the profile uid's row.
   */
  fun update(distro: String, username: String, avatar: String): UpdateResult {
    if (distro !in DISTROS || !hasVerifier(distro)) return UpdateResult.NOT_FOUND
    if (!isValidUsername(username)) return UpdateResult.INVALID_USERNAME
    if (avatar !in AVATARS) return UpdateResult.INVALID_AVATAR
    val changes = mutableMapOf<String, Any?>(usernameKey(distro) to username, avatarKey(distro) to avatar)
    if (distro == RootfsCatalog.DISTRO_ALPINE) {
      changes[KEY_LEGACY_USERNAME] = null
      changes[KEY_LEGACY_EMOJI] = null
    }
    store.commit(changes)
    return UpdateResult.UPDATED
  }

  /**
   * Checks [password] against [distro]'s user, or against every user when
   * [distro] is null (pairing a computer accepts either). Either way it is
   * one attempt against the shared limit.
   */
  @Synchronized
  fun verify(distro: String?, password: String?): Check {
    val now = clock()
    val lockedUntil = store.long(KEY_LOCKED_UNTIL, 0L)
    val lastFailureAt = store.long(KEY_LAST_FAILURE_AT, 0L)
    val retryAfterMs = when {
      // A clock moved backwards must not end a lockout early.
      now < lastFailureAt -> passwordLockoutMs(store.int(KEY_FAILED_ATTEMPTS, 0))
      else -> lockedUntil - now
    }
    if (retryAfterMs > 0) return Check.Locked(retryAfterMs)
    val candidates = if (distro == null) DISTROS.filter(::hasVerifier) else listOf(distro).filter(::hasVerifier)
    if (candidates.isEmpty()) return Check.Unavailable
    if (password == null || !isValidPassword(password)) return Check.Incorrect
    // Check every candidate so the time taken does not say which user exists.
    val valid = candidates.map { matches(it, password) }.any { it }
    if (valid) {
      store.commit(mapOf(KEY_FAILED_ATTEMPTS to null, KEY_LOCKED_UNTIL to null, KEY_LAST_FAILURE_AT to null))
      return Check.Success
    }
    val failures = store.int(KEY_FAILED_ATTEMPTS, 0) + 1
    // Committed before returning: killing the app after each guess must not reset the count.
    store.commit(
      mapOf(
        KEY_FAILED_ATTEMPTS to failures,
        KEY_LAST_FAILURE_AT to now,
        KEY_LOCKED_UNTIL to now + passwordLockoutMs(failures),
      ),
    )
    return Check.Incorrect
  }

  private fun hasVerifier(distro: String): Boolean {
    val prefix = passwordPrefix(distro)
    return store.string(prefix + KEY_PASSWORD_SALT) != null && store.string(prefix + KEY_PASSWORD_VERIFIER) != null
  }

  private fun matches(distro: String, password: String): Boolean {
    val prefix = passwordPrefix(distro)
    return try {
      // Base64Codec writes the same padded, unwrapped form as the android.util.Base64 1.0.x used.
      val salt = Base64Codec.decode(store.string(prefix + KEY_PASSWORD_SALT) ?: return false) ?: return false
      val expected = Base64Codec.decode(store.string(prefix + KEY_PASSWORD_VERIFIER) ?: return false) ?: return false
      val rounds = store.int(prefix + KEY_PASSWORD_ITERATIONS, PASSWORD_ITERATIONS)
      if (rounds !in MIN_PASSWORD_ITERATIONS..MAX_PASSWORD_ITERATIONS) return false
      MessageDigest.isEqual(expected, derive(password, salt, rounds))
    } catch (_: IllegalArgumentException) {
      false
    }
  }

  companion object {
    val DISTROS = listOf(RootfsCatalog.DISTRO_ALPINE, RootfsCatalog.DISTRO_DEBIAN)

    /** Avatar ids; each names a hieroglyph in assets/avatars. */
    val AVATARS = listOf("falcon", "ibis", "jackal", "cat", "cobra", "scarab", "lotus", "papyrus", "sun-disk")

    const val DEFAULT_USERNAME = "horus"
    const val DEFAULT_AVATAR = "falcon"

    const val KEY_LEGACY_USERNAME = "username"
    const val KEY_LEGACY_EMOJI = "emoji"
    const val KEY_HAS_PASSWORD = "has_password"
    private const val KEY_ACCOUNT_INTRO_SEEN = "account_intro_seen"
    const val KEY_PASSWORD_SALT = "password_salt"
    const val KEY_PASSWORD_VERIFIER = "password_verifier"
    private const val KEY_PASSWORD_ITERATIONS = "password_iterations"
    private const val KEY_FAILED_ATTEMPTS = "password_failed_attempts"
    private const val KEY_LOCKED_UNTIL = "password_locked_until_ms"
    private const val KEY_LAST_FAILURE_AT = "password_last_failure_ms"

    private const val PASSWORD_SALT_BYTES = 16
    private const val PASSWORD_KEY_BITS = 256
    const val PASSWORD_ITERATIONS = 120_000
    private const val MIN_PASSWORD_ITERATIONS = 50_000
    private const val MAX_PASSWORD_ITERATIONS = 500_000

    private val USERNAME_PATTERN = Regex("^[a-z_][a-z0-9_-]{0,31}$")

    /**
     * Names that already exist in the Alpine or Debian rootfs, or that Horus
     * uses for harness accounts. Taking one would collide with a system
     * account inside the guest.
     */
    private val RESERVED_USERNAMES = setOf(
      "root", "daemon", "bin", "sys", "sync", "games", "man", "lp", "mail", "news", "uucp", "proxy",
      "www-data", "backup", "list", "irc", "gnats", "nobody", "nogroup", "_apt", "messagebus",
      "adm", "shutdown", "halt", "operator", "postmaster", "cron", "ftp", "sshd", "at", "squid",
      "xfs", "ntp", "smmsp", "guest", "utmp", "staff", "users", "wheel", "tty", "disk", "kmem",
      "audio", "video", "input", "kvm", "dialout", "floppy", "cdrom", "tape", "abuild", "ping",
      "shadow", "sudo", "src", "plugdev", "netdev", "systemd-network", "systemd-timesync",
    )

    /** Lowercase Linux login names that cannot collide with a guest system account. */
    fun isValidUsername(username: String): Boolean =
      USERNAME_PATTERN.matches(username) && username !in RESERVED_USERNAMES && !username.startsWith("harness_")

    fun isValidPassword(password: String): Boolean =
      password.length in 4..128 && password.all { character ->
        character >= ' ' && character != '\u007f' && character != '\n' && character != '\r'
      }

    /** Alpine keeps the unprefixed keys from 1.0.x. */
    private fun passwordPrefix(distro: String): String =
      if (distro == RootfsCatalog.DISTRO_ALPINE) "" else "${distro}_"

    private fun usernameKey(distro: String) = "${distro}_username"
    private fun avatarKey(distro: String) = "${distro}_avatar"

    private fun derive(password: String, salt: ByteArray, rounds: Int): ByteArray {
      val spec = PBEKeySpec(password.toCharArray(), salt, rounds, PASSWORD_KEY_BITS)
      return try {
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
      } finally {
        spec.clearPassword()
      }
    }
  }
}
