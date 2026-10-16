package com.scariflabs.horus.terminal

import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileUsersCoreTest {
  private class MapValues : ProfileKeyValues {
    val values = mutableMapOf<String, Any>()
    override fun string(key: String): String? = values[key] as? String
    override fun int(key: String, default: Int): Int = values[key] as? Int ?: default
    override fun long(key: String, default: Long): Long = values[key] as? Long ?: default
    override fun commit(changes: Map<String, Any?>) {
      for ((key, value) in changes) if (value == null) values.remove(key) else values[key] = value
    }
  }

  private var now = 1_000_000L
  private val values = MapValues()
  // The lowest iteration count the store accepts, to keep the tests quick.
  private val users = ProfileUsersCore(values, clock = { now }, iterations = 50_000)

  private val alpine = RootfsCatalog.DISTRO_ALPINE
  private val debian = RootfsCatalog.DISTRO_DEBIAN

  @Test
  fun `an install from horus 1_0_4 reads as the alpine user horus and keeps its password`() {
    // Exactly what 1.0.4 stored: the unprefixed verifier keys, no username.
    val salt = ByteArray(16) { it.toByte() }
    val spec = PBEKeySpec("old-secret".toCharArray(), salt, 120_000, 256)
    val verifier = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    // android.util.Base64 NO_WRAP output, which is what 1.0.4 wrote.
    values.values["password_salt"] = Base64.getEncoder().encodeToString(salt)
    values.values["password_verifier"] = Base64.getEncoder().encodeToString(verifier)
    values.values["password_iterations"] = 120_000
    values.values["has_password"] = true

    assertEquals(listOf(ProfileUser(alpine, "horus", "falcon")), users.users())
    assertTrue(users.isConfigured())
    assertEquals(ProfileUsersCore.Check.Success, users.verify(alpine, "old-secret"))
    assertEquals(ProfileUsersCore.Check.Unavailable, users.verify(debian, "old-secret"))
  }

  @Test
  fun `the alpine user is stored where an older horus can still verify it`() {
    assertEquals(ProfileUsersCore.CreateResult.CREATED, users.create(alpine, "vishesh", "ibis", "horus"))
    assertTrue(values.values.containsKey("password_salt"))
    assertTrue(values.values.containsKey("password_verifier"))
    assertEquals(true, values.values["has_password"])
    assertEquals(ProfileUser(alpine, "vishesh", "ibis"), users.user(alpine))
  }

  @Test
  fun `each distribution has its own user and password`() {
    users.create(alpine, "vishesh", "falcon", "alpine-pass")
    users.create(debian, "deb", "scarab", "debian-pass")

    assertEquals(
      listOf(ProfileUser(alpine, "vishesh", "falcon"), ProfileUser(debian, "deb", "scarab")),
      users.users(),
    )
    assertEquals(ProfileUsersCore.Check.Success, users.verify(debian, "debian-pass"))
    assertEquals(ProfileUsersCore.Check.Incorrect, users.verify(debian, "alpine-pass"))
    assertEquals(ProfileUsersCore.Check.Success, users.verify(alpine, "alpine-pass"))
  }

  @Test
  fun `only an upgrade from before users is pointed at account, once`() {
    assertFalse(users.needsAccountIntro())
    // What 1.0.4 left: an Alpine password and no username.
    values.values["password_salt"] = "c2FsdHNhbHRzYWx0c2FsdA=="
    values.values["password_verifier"] = "dmVyaWZpZXI="
    assertTrue(users.needsAccountIntro())
    users.dismissAccountIntro()
    assertFalse(users.needsAccountIntro())

    // A user who chose a name at onboarding never sees it.
    val fresh = ProfileUsersCore(MapValues(), clock = { now }, iterations = 50_000)
    assertEquals(ProfileUsersCore.CreateResult.CREATED, fresh.create(alpine, "ra", "falcon", "secret"))
    assertFalse(fresh.needsAccountIntro())
  }

  @Test
  fun `the signed-in user can change name and avatar and keeps their password`() {
    assertEquals(ProfileUsersCore.UpdateResult.NOT_FOUND, users.update(debian, "deb", "ibis"))
    assertEquals(ProfileUsersCore.CreateResult.CREATED, users.create(debian, "deb", "ibis", "secret"))
    assertEquals(ProfileUsersCore.UpdateResult.INVALID_USERNAME, users.update(debian, "Root!", "ibis"))
    assertEquals(ProfileUsersCore.UpdateResult.INVALID_AVATAR, users.update(debian, "deb", "unicorn"))
    assertEquals(ProfileUsersCore.UpdateResult.UPDATED, users.update(debian, "nefer", "scarab"))
    assertEquals(ProfileUser(debian, "nefer", "scarab"), users.user(debian))
    assertEquals(ProfileUsersCore.Check.Success, users.verify(debian, "secret"))
  }

  @Test
  fun `a user is never replaced, so its password cannot be reset`() {
    users.create(debian, "deb", "scarab", "first")
    assertEquals(ProfileUsersCore.CreateResult.EXISTS, users.create(debian, "other", "cat", "second"))
    assertEquals(ProfileUsersCore.Check.Success, users.verify(debian, "first"))
    assertEquals("deb", users.user(debian)!!.username)
  }

  @Test
  fun `pairing accepts either password and counts one guess per attempt`() {
    users.create(alpine, "vishesh", "falcon", "alpine-pass")
    users.create(debian, "deb", "scarab", "debian-pass")
    assertEquals(ProfileUsersCore.Check.Success, users.verify(null, "alpine-pass"))
    assertEquals(ProfileUsersCore.Check.Success, users.verify(null, "debian-pass"))

    assertEquals(ProfileUsersCore.Check.Incorrect, users.verify(null, "wrong"))
    assertEquals(1, values.values["password_failed_attempts"])
  }

  @Test
  fun `wrong guesses against either user share one lockout`() {
    users.create(alpine, "vishesh", "falcon", "alpine-pass")
    users.create(debian, "deb", "scarab", "debian-pass")
    repeat(3) { assertEquals(ProfileUsersCore.Check.Incorrect, users.verify(alpine, "nope")) }
    repeat(3) { users.verify(debian, "nope") }
    // The sixth failure overall locks every user, including the right password.
    val locked = users.verify(alpine, "alpine-pass")
    assertTrue(locked.toString(), locked is ProfileUsersCore.Check.Locked)
    assertTrue(users.verify(debian, "debian-pass") is ProfileUsersCore.Check.Locked)

    now += 31_000
    assertEquals(ProfileUsersCore.Check.Success, users.verify(debian, "debian-pass"))
    assertNull(values.values["password_failed_attempts"])
  }

  @Test
  fun `usernames must be safe linux logins that no guest account already uses`() {
    for (name in listOf("vishesh", "horus", "dev_1", "_x", "a-b")) {
      assertTrue(name, ProfileUsersCore.isValidUsername(name))
    }
    for (name in listOf("", "Vishesh", "1abc", "root", "nobody", "www-data", "_apt", "harness_claude", "a b", "x".repeat(33), "../x")) {
      assertFalse(name, ProfileUsersCore.isValidUsername(name))
    }
    assertEquals(ProfileUsersCore.CreateResult.INVALID_USERNAME, users.create(alpine, "root", "falcon", "pass"))
    assertEquals(ProfileUsersCore.CreateResult.INVALID_AVATAR, users.create(alpine, "vishesh", "eye", "pass"))
    assertEquals(ProfileUsersCore.CreateResult.INVALID_PASSWORD, users.create(alpine, "vishesh", "falcon", "abc"))
    assertEquals(ProfileUsersCore.CreateResult.INVALID_DISTRO, users.create("ubuntu", "vishesh", "falcon", "pass"))
    assertFalse(users.isConfigured())
  }

  @Test
  fun `every avatar id has an image in the app`() {
    val avatarsDir = java.io.File("../../assets/avatars").takeIf { it.isDirectory } ?: java.io.File("../assets/avatars")
    for (avatar in ProfileUsersCore.AVATARS) {
      assertTrue(avatar, java.io.File(avatarsDir, "$avatar.png").isFile)
    }
  }
}
