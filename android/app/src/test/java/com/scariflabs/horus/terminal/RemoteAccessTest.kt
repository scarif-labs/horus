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
  fun `paired keys reach every distribution's user home and the server pid is found in either`() {
    val base = Files.createTempDirectory("horus-remote-homes").toFile()
    try {
      val store = RemoteAccess.Store(base)
      store.addKey(valid())
      val debianKeys = File(base, "homes/debian/home/.ssh/authorized_keys")
      // No Debian user yet, so nothing is created for it.
      assertFalse(debianKeys.exists())

      File(base, "homes/debian/home").mkdirs()
      store.syncKeysToHomes()
      assertEquals(File(base, "home/.ssh/authorized_keys").readText(), debianKeys.readText())
      assertTrue(store.removeKey(ed25519Fingerprint))
      assertFalse(debianKeys.readText().contains("ssh-ed25519"))

      // A Debian ~/.ssh link planted by the guest is refused too.
      File(base, "homes/debian/home/.ssh").deleteRecursively()
      val target = File(base, "elsewhere").apply { mkdirs() }
      Files.createSymbolicLink(File(base, "homes/debian/home/.ssh").toPath(), target.toPath())
      assertThrows(IllegalStateException::class.java) { store.addKey(valid()) }
      assertFalse(File(target, "authorized_keys").exists())

      File(base, "homes/debian/home/.cache/horus").mkdirs()
      File(base, "homes/debian/home/.cache/horus/dropbear.pid").writeText("4242\n")
      assertEquals(4242L, store.readServerPid())
      store.deleteServerPidFiles()
      assertEquals(0L, store.readServerPid())
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

      val codexHome = File(base, "harness-homes/codex").apply { mkdirs() }
      val launch = ProotSessionLauncher(runtime, File(base, "scratch"), hostProc, hostDev)
        .remoteAccessLaunchSpec(
          rootfs, home, workspace, "horus",
          harnessHomes = listOf(ProotSessionLauncher.HarnessHome(TerminalRuntimeContract.TOOLCHAIN_TARGET_CODEX, "harness_codex", codexHome)),
        )

      assertTrue(launch.argv.contains("-0"))
      val binds = launch.argv.windowed(2).filter { it[0] == "-b" }.map { it[1] }
      assertTrue(binds.contains("${home.canonicalPath}:/home/horus"))
      assertFalse("home must not also appear as /root", binds.contains("${home.canonicalPath}:/root"))
      assertTrue(binds.contains("${File(hostDev, "ptmx").absolutePath}:/dev/ptmx"))
      assertTrue(binds.contains("${File(hostDev, "pts").absolutePath}:/dev/pts"))
      assertEquals(listOf("horus-remote-access", "horus", "1000", "1000", "8022", ""), launch.argv.takeLast(6))
      val script = launch.argv[launch.argv.indexOf("horus-remote-access") - 1]
      assertTrue(script.contains(ProotSessionLauncher.DOTNET_ENV_SCRIPT))
      assertTrue(script.contains(ProotSessionLauncher.ZSH_EXIT_SCRIPT))
      for (terminator in listOf("HORUS_SHELL", "HORUS_INSTALLER", "HORUS_ZSH")) {
        assertEquals("$terminator must terminate its heredoc at column zero", 1, script.lines().count { it == terminator })
        assertFalse(script.lines().any { it.trim() == terminator && it != terminator })
      }
      val syntax = ProcessBuilder("/bin/sh", "-n", "-c", script).redirectErrorStream(true).start()
      val syntaxOutput = syntax.inputStream.bufferedReader().readText()
      assertEquals(syntaxOutput, 0, syntax.waitFor())
      assertTrue(script.contains("-p \"127.0.0.1:"))
      assertTrue(script.contains("dropbear -F -E -s -w "))
      assertTrue(script.contains("busybox su -s /bin/sh -c"))
      // SSH runs the app's own harness installs, in their homes.
      assertTrue(binds.contains("${codexHome.canonicalPath}:/home/harness_codex"))
      assertEquals("codex=/home/harness_codex\n", File(rootfs, ProotSessionLauncher.GUEST_AGENT_HOMES_PATH.trimStart('/')).readText())
      assertTrue(script.contains(ProotSessionLauncher.GUEST_AGENT_HOMES_PATH))
      assertTrue(script.contains("exec \"${'$'}app_home/.local/bin/${'$'}tool\""))
      // Debian's zsh is pointed at the zshrc.d snippets (the Claude wrapper).
      assertTrue(script.contains("/etc/zsh/zshrc.d/*.zsh(N)"))
      assertThrows(IllegalArgumentException::class.java) {
        ProotSessionLauncher(runtime, File(base, "scratch"), hostProc, hostDev).remoteAccessLaunchSpec(
          rootfs, home, workspace, "horus",
          harnessHomes = listOf(ProotSessionLauncher.HarnessHome("shell", "harness_shell", codexHome)),
        )
      }
      assertThrows(IllegalArgumentException::class.java) {
        ProotSessionLauncher(runtime, File(base, "scratch"), hostProc, hostDev)
          .remoteAccessLaunchSpec(rootfs, home, workspace, ProotSessionLauncher.GUEST_ROOT_USER)
      }
    } finally {
      base.deleteRecursively()
    }
  }

  @Test
  fun onePhoneHostKeyAdoptsTheActiveUsersKeyAndOnlyTheServerSeesIt() {
    val base = Files.createTempDirectory("horus-host-key").toFile()
    try {
      val paths = DistroStorePaths(base)
      val alpine = paths.homeFor(RootfsCatalog.DISTRO_ALPINE)
      val debian = paths.homeFor(RootfsCatalog.DISTRO_DEBIAN)
      fun legacy(home: File) = File(home, ".config/horus/${RemoteAccess.HOST_KEY_NAME}")
      // What 1.0.4 left: dropbearkey's link(2) as PRoot's source ->
      // intermediate -> backing chain beside the key.
      fun emulatedKey(home: File, content: String) {
        val key = legacy(home)
        val dir = key.parentFile!!.apply { mkdirs() }
        val intermediate = File(dir, ".l2s.${key.name}.tmp1230001")
        val backing = File(dir, "${intermediate.name}.0001").apply { writeText(content) }
        Files.createSymbolicLink(intermediate.toPath(), backing.toPath())
        Files.createSymbolicLink(key.toPath(), intermediate.toPath())
      }
      emulatedKey(alpine, "alpine key")
      emulatedKey(debian, "debian key")
      val store = RemoteAccess.Store(base)

      // Computers paired while Debian was active keep trusting the phone.
      val dir = store.prepareHostKey(debian)
      val shared = File(dir, RemoteAccess.HOST_KEY_NAME)
      assertEquals("debian key", shared.readText())
      assertFalse(Files.isSymbolicLink(shared.toPath()))
      for (home in listOf(alpine, debian)) {
        assertTrue(legacy(home).parentFile!!.listFiles()!!.isEmpty())
      }
      assertFalse(dir.canonicalPath.startsWith(alpine.canonicalPath))

      // Switching users keeps the identity; a key a guest plants is removed, not adopted.
      legacy(alpine).writeText("planted")
      assertEquals(dir, store.prepareHostKey(alpine))
      assertEquals("debian key", shared.readText())
      assertFalse(legacy(alpine).exists())

      // A key generated through PRoot's shared store becomes a plain file.
      store.deleteHostKey()
      val backing = File(paths.hardlinks.apply { mkdirs() }, ".l2s.${RemoteAccess.HOST_KEY_NAME}.new0001.0001").apply { writeText("generated") }
      Files.createSymbolicLink(shared.toPath(), backing.toPath())
      store.prepareHostKey(alpine)
      assertFalse(Files.isSymbolicLink(shared.toPath()))
      assertEquals("generated", shared.readText())

      // A link the guest plants to another app file is never adopted.
      store.deleteHostKey()
      val secret = File(base, "remote-access/status").apply { parentFile!!.mkdirs(); writeText("app private") }
      legacy(alpine).parentFile!!.mkdirs()
      Files.createSymbolicLink(legacy(alpine).toPath(), secret.toPath())
      store.prepareHostKey(alpine)
      assertFalse(shared.exists())
      assertFalse(Files.isSymbolicLink(legacy(alpine).toPath()))
      assertEquals("app private", secret.readText())

      val hostProc = File(base, "proc").apply { mkdirs() }
      File(hostProc, "stat").writeText("cpu 1\n")
      File(hostProc, "loadavg").writeText("0 0 0\n")
      val hostDev = File(base, "dev").apply { mkdirs() }
      listOf("null", "zero", "random", "urandom", "tty", "ptmx").forEach { File(hostDev, it).writeText("") }
      File(hostDev, "pts").mkdirs()
      val rootfs = File(base, "rootfs").apply { mkdirs() }
      File(rootfs, "etc").mkdirs()
      val runtime = ProotRuntimeLocator.LocatedRuntime(
        prootBin = File(base, "libproot.so"),
        loaderBin = File(base, "libproot_loader.so"),
        libraryDir = base,
        prootVersion = "5.4.0",
      )
      val launcher = ProotSessionLauncher(runtime, File(base, "scratch"), hostProc, hostDev)
      val remote = launcher.remoteAccessLaunchSpec(rootfs, alpine, null, "horus", hostKeyDir = dir).argv
      assertEquals(RemoteAccess.GUEST_HOST_KEY_DIR, remote.last())
      assertTrue(remote.windowed(2).any { it == listOf("-b", "${dir.canonicalPath}:${RemoteAccess.GUEST_HOST_KEY_DIR}") })
      val shell = launcher.interactiveShellLaunchSpec(rootfs, alpine).argv
      assertFalse(shell.any { it.contains(RemoteAccess.GUEST_HOST_KEY_DIR) })
    } finally {
      base.deleteRecursively()
    }
  }
}
