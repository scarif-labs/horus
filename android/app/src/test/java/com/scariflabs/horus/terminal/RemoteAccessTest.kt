package com.scariflabs.horus.terminal

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteAccessTest {
  // Throwaway fixture keys; fingerprints are what `ssh-keygen -lf` prints.
  private val ed25519 = "AAAAC3NzaC1lZDI1NTE5AAAAIJINpCEzt+pD1+GOKdw3/TY7s14kwV8TvgKJIIiQAtDj"
  private val ed25519Fingerprint = "SHA256:F+7aOhltov2IBCyXTHR4u/ZdoL0pbUbDogAweeNEWr0"

  private fun valid(type: String = "ssh-ed25519", data: String = ed25519, label: String = "laptop"): RemoteAccess.PublicKey {
    val result = RemoteAccess.parsePublicKey(type, data, label)
    assertTrue("$result", result is RemoteAccess.KeyResult.Valid)
    return (result as RemoteAccess.KeyResult.Valid).key
  }

  @Test
  fun `fingerprints match OpenSSH`() {
    assertEquals(ed25519Fingerprint, valid().fingerprint)
  }

  @Test
  fun `rejects keys that are not a bare supported public key`() {
    fun reason(type: String?, data: String?, label: String?) =
      (RemoteAccess.parsePublicKey(type, data, label) as RemoteAccess.KeyResult.Invalid).reason
    assertEquals("unsupported_key_type", reason("ssh-dss", ed25519, "laptop"))
    assertEquals("unsupported_key_type", reason(null, ed25519, "laptop"))
    assertEquals("invalid_label", reason("ssh-ed25519", ed25519, "my laptop"))
    assertEquals("invalid_label", reason("ssh-ed25519", ed25519, "a\nb"))
    assertEquals("invalid_key", reason("ssh-ed25519", "not base64!", "laptop"))
    // The declared type must match the type inside the blob.
    assertEquals("invalid_key", reason("ssh-rsa", ed25519, "laptop"))
    // Options or a second key smuggled into the data field never parse.
    assertEquals("invalid_key", reason("ssh-ed25519", "$ed25519 command=\"sh\"", "laptop"))
    assertEquals("invalid_key", reason("ssh-ed25519", "AAAA", "laptop"))
  }

  @Test
  fun `authorized line restricts forwarding and tags the key`() {
    assertEquals(
      "no-agent-forwarding,no-X11-forwarding ssh-ed25519 $ed25519 horus:laptop",
      valid().authorizedLine(),
    )
  }

  @Test
  fun `adding a key replaces the same key and keeps hand-added ones`() {
    val handAdded = "ssh-ed25519 $ed25519 me@desk\n"
    val once = RemoteAccess.withKey("# comment\n", valid(label = "first"))
    val twice = RemoteAccess.withKey(once, valid(label = "second"))
    assertEquals(1, RemoteAccess.parseAuthorizedKeys(twice).size)
    assertEquals("second", RemoteAccess.parseAuthorizedKeys(twice).single().label)
    assertTrue(twice.startsWith("# comment\n"))

    val parsed = RemoteAccess.parseAuthorizedKeys(handAdded).single()
    assertEquals(ed25519Fingerprint, parsed.fingerprint)
    assertEquals("me@desk", parsed.label)
    assertFalse(parsed.managed)
  }

  @Test
  fun `removing a key leaves the others`() {
    val content = RemoteAccess.withKey("# keep me\n", valid())
    assertEquals("# keep me\n", RemoteAccess.withoutKey(content, ed25519Fingerprint))
    assertEquals("", RemoteAccess.withoutKey(valid().authorizedLine(), ed25519Fingerprint))
  }

  @Test
  fun `base64 codec round-trips and rejects junk`() {
    for (size in 0..10) {
      val bytes = ByteArray(size) { (it * 37).toByte() }
      assertTrue(bytes.contentEquals(Base64Codec.decode(Base64Codec.encode(bytes, padding = true))!!))
      assertTrue(bytes.contentEquals(Base64Codec.decode(Base64Codec.encode(bytes, padding = false))!!))
    }
    assertNull(Base64Codec.decode("A"))
    assertNull(Base64Codec.decode("AB=C"))
    assertNull(Base64Codec.decode("AB==="))
    assertNull(Base64Codec.decode("é123"))
  }

  @Test
  fun `store writes private authorized keys and refuses planted symlinks`() {
    val base = Files.createTempDirectory("horus-remote").toFile()
    try {
      val store = RemoteAccess.Store(base)
      store.addKey(valid())
      val keys = File(base, "home/.ssh/authorized_keys")
      assertTrue(keys.isFile)
      assertTrue(store.isAuthorized(ed25519Fingerprint))
      assertTrue(store.removeKey(ed25519Fingerprint))
      assertFalse(store.removeKey(ed25519Fingerprint))

      // A guest could replace ~/.ssh with a link to app-private files.
      val sshDir = File(base, "home/.ssh")
      sshDir.deleteRecursively()
      val target = File(base, "elsewhere").apply { mkdirs() }
      Files.createSymbolicLink(sshDir.toPath(), target.toPath())
      assertThrows(IllegalStateException::class.java) { store.addKey(valid()) }
      assertFalse(File(target, "authorized_keys").exists())
    } finally {
      base.deleteRecursively()
    }
  }

  @Test
  fun `store tracks the switch and server status`() {
    val base = Files.createTempDirectory("horus-remote-state").toFile()
    try {
      val store = RemoteAccess.Store(base)
      assertFalse(store.isEnabled())
      store.setEnabled(true)
      assertTrue(store.isEnabled())
      store.setEnabled(false)
      assertFalse(store.isEnabled())
      assertEquals(RemoteAccess.STATE_STOPPED, store.readStatus().state)
      store.writeStatus(RemoteAccess.Status(RemoteAccess.STATE_FAILED, "install_failed", 42))
      assertEquals(RemoteAccess.Status(RemoteAccess.STATE_FAILED, "install_failed", 42), store.readStatus())
    } finally {
      base.deleteRecursively()
    }
  }

  @Test
  fun `server launch is loopback-only, keys-only, and runs as the profile user`() {
    val base = Files.createTempDirectory("horus-remote-launch").toFile()
    try {
      val rootfs = File(base, "rootfs").apply { mkdirs() }
      File(rootfs, "etc").mkdirs()
      val home = File(base, "home").apply { mkdirs() }
      val workspace = File(base, "workspace").apply { mkdirs() }
      val hostProc = File(base, "proc").apply { mkdirs() }
      File(hostProc, "stat").writeText("cpu 1\n")
      File(hostProc, "loadavg").writeText("0 0 0\n")
      val hostDev = File(base, "dev").apply { mkdirs() }
      listOf("null", "zero", "random", "urandom", "tty", "ptmx").forEach { File(hostDev, it).writeText("") }
      File(hostDev, "pts").mkdirs()
      val runtime = ProotRuntimeLocator.LocatedRuntime(
        prootBin = File(base, "libproot.so"),
        loaderBin = File(base, "libproot_loader.so"),
        libraryDir = base,
        prootVersion = "5.4.0",
      )

      val launch = ProotSessionLauncher(runtime, File(base, "scratch"), hostProc, hostDev)
        .remoteAccessLaunchSpec(rootfs, home, workspace, "horus")

      assertTrue(launch.argv.contains("-0"))
      val binds = launch.argv.windowed(2).filter { it[0] == "-b" }.map { it[1] }
      assertTrue(binds.contains("${home.canonicalPath}:/home/horus"))
      assertFalse("home must not also appear as /root", binds.contains("${home.canonicalPath}:/root"))
      assertTrue(binds.contains("${File(hostDev, "ptmx").absolutePath}:/dev/ptmx"))
      assertTrue(binds.contains("${File(hostDev, "pts").absolutePath}:/dev/pts"))
      assertEquals(listOf("horus-remote-access", "horus", "1000", "1000", "8022"), launch.argv.takeLast(5))
      val script = launch.argv[launch.argv.size - 6]
      assertTrue(script.contains("-p \"127.0.0.1:"))
      assertTrue(script.contains("dropbear -F -E -s -w "))
      assertTrue(script.contains("busybox su -s /bin/sh -c"))
      assertThrows(IllegalArgumentException::class.java) {
        ProotSessionLauncher(runtime, File(base, "scratch"), hostProc, hostDev)
          .remoteAccessLaunchSpec(rootfs, home, workspace, ProotSessionLauncher.GUEST_ROOT_USER)
      }
    } finally {
      base.deleteRecursively()
    }
  }
}
