package com.scariflabs.horus.terminal

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProotSessionLauncherTest {
  @Test
  fun interactiveZshExitAndEofNormalizeBuildFailuresButPreserveKillsAndCommandStatus() {
    val zsh = System.getenv("HORUS_TEST_ZSH") ?: "/usr/bin/zsh"
    org.junit.Assume.assumeTrue("zsh required for shell behavior test", File(zsh).canExecute())
    fun checkStatus(expected: Int, commands: String, interactive: Boolean) {
      val process = ProcessBuilder(zsh, if (interactive) "-dfi" else "-df")
        .redirectErrorStream(true).start()
      process.outputStream.bufferedWriter().use {
        // Startup files accept comments; enable them for the stdin fixture too.
        if (interactive) it.write("setopt interactivecomments\n")
        it.write(ProotSessionLauncher.ZSH_EXIT_SCRIPT + "\n" + commands)
      }
      assertTrue("zsh fixture timed out", process.waitFor(10, TimeUnit.SECONDS))
      val output = process.inputStream.bufferedReader().readText()
      assertEquals("commands=$commands\n$output", expected, process.exitValue())
    }
    checkStatus(0, "false\nexit\n", true)
    checkStatus(0, "false\n", true)
    // Ctrl-C leaves $? at 130; a bare exit afterwards is still a clean close.
    checkStatus(0, "sh -c 'kill -INT ${'$'}${'$'}'\nexit\n", true)
    checkStatus(0, "sh -c 'kill -INT ${'$'}${'$'}'\n", true)
    checkStatus(23, "exit 23\n", true)
    checkStatus(137, "exit 137\n", true)
    checkStatus(137, "kill -KILL ${'$'}${'$'}\n", true)
    checkStatus(23, "exit 23\n", false)
    val commandShell = ProcessBuilder(zsh, "-dfi", "-c", ProotSessionLauncher.ZSH_EXIT_SCRIPT + "\nfalse")
      .redirectErrorStream(true).start()
    assertTrue(commandShell.waitFor(10, TimeUnit.SECONDS))
    assertEquals("interactive -c must retain CLI failures", 1, commandShell.exitValue())
  }

  @Test
  fun loginZshOnATerminalExitsCleanlyAfterCtrlCEvenWithAZlogoutFile() {
    // Debian's login shell sources /etc/zsh/zlogout, which makes zsh ignore an
    // exit inside zshexit. It only shows on a real terminal, so drive zsh
    // through a pty: as in the app, a login shell with the line editor on.
    val zsh = System.getenv("HORUS_TEST_ZSH") ?: "/usr/bin/zsh"
    org.junit.Assume.assumeTrue("zsh required for shell behavior test", File(zsh).canExecute())
    val python = listOf("/usr/bin/python3", "/usr/local/bin/python3", "/opt/homebrew/bin/python3")
      .firstOrNull { File(it).canExecute() }
    org.junit.Assume.assumeTrue("python3 required for the pty driver", python != null)
    val zdotdir = Files.createTempDirectory("horus-zdot").toFile()
    try {
      File(zdotdir, ".zshrc").writeText(ProotSessionLauncher.ZSH_EXIT_SCRIPT + "\n")
      File(zdotdir, ".zlogout").writeText("# like Debian's /etc/zsh/zlogout\n")
      val driver = File(zdotdir, "drive_zsh.py").apply {
        writeText(
          """
          import os, pty, select, sys, time
          pid, fd = pty.fork()
          if pid == 0:
              os.execv(sys.argv[1], ['zsh', '-l'])
          def drain(seconds):
              end = time.time() + seconds
              while time.time() < end:
                  try:
                      if select.select([fd], [], [], 0.05)[0]: os.read(fd, 4096)
                  except OSError:
                      return
          drain(1.0)
          for key in sys.argv[2:]:
              os.write(fd, {'^C': b'\x03', '^D': b'\x04'}.get(key) or key.encode() + b'\r')
              drain(0.6)
          drain(1.0)
          print(os.waitstatus_to_exitcode(os.waitpid(pid, 0)[1]))
          """.trimIndent(),
        )
      }
      fun checkStatus(expected: Int, vararg keys: String) {
        val process = ProcessBuilder(listOf(python!!, driver.absolutePath, zsh) + keys)
          .redirectErrorStream(true)
          .apply { environment()["ZDOTDIR"] = zdotdir.absolutePath }
          .start()
        assertTrue("zsh pty fixture timed out", process.waitFor(30, TimeUnit.SECONDS))
        val output = process.inputStream.bufferedReader().readText().trim()
        assertEquals("keys=${keys.toList()}", expected.toString(), output.lineSequence().last())
      }
      checkStatus(0, "exit")
      checkStatus(0, "sleep 100", "^C", "exit")
      checkStatus(0, "sleep 100", "^C", "^D")
      checkStatus(0, "false", "^D")
      checkStatus(0, "false", "logout")
      checkStatus(23, "exit 23")
      checkStatus(-9, "kill -KILL ${'$'}${'$'}")
    } finally {
      zdotdir.deleteRecursively()
    }
  }

  @Test
  fun shortTmpBindFitsClaudeSocketAndPrunesOnlyOrphanedProotFiles() {
    val base = Files.createTempDirectory("horus-tmp").toFile()
    try {
      val paths = DistroStorePaths(base)
      val scratch = paths.guestTmp.apply { mkdirs() }
      val proc = File(base, "proc").apply { mkdirs() }
      File(proc, "123").mkdirs()
      val live = File(scratch, "proot-123-ABC123").apply { writeText("keep") }
      val dead = File(scratch, "proot-456-ABC123").apply { writeText("orphan") }
      val deadTree = File(scratch, "proot-tmpfs--456-ABC123").apply { mkdirs() }
      File(deadTree, "payload").writeText("orphan")
      val userFile = File(scratch, "proot-user-data").apply { writeText("keep") }
      val outside = File(base, "outside").apply { mkdirs() }
      File(outside, "keep").writeText("user data")
      Files.createSymbolicLink(File(deadTree, "link").toPath(), outside.toPath())
      val rootfs = File(base, "rootfs").apply { mkdirs() }
      val home = File(base, "home").apply { mkdirs() }
      val launch = launcher(base, proc, scratch).interactiveShellLaunchSpec(rootfs, home)
      assertTrue(launch.argv.windowed(2).contains(listOf("-b", "${scratch.canonicalPath}:/tmp")))
      assertTrue(launch.environment.contains("PROOT_TMP_DIR=${scratch.canonicalPath}"))
      assertTrue(launch.environment.contains("PROOT_L2S_DIR=${paths.hardlinks.canonicalPath}"))
      assertTrue(launch.argv.windowed(2).contains(listOf("-b", "${paths.hardlinks.canonicalPath}:${paths.hardlinks.canonicalPath}")))
      assertFalse(paths.hardlinks.canonicalPath.startsWith(scratch.canonicalPath + File.separator))
      assertTrue(live.exists())
      assertFalse(dead.exists())
      assertFalse(deadTree.exists())
      assertTrue(userFile.exists())
      assertTrue(File(outside, "keep").exists())
      val androidTmp = DistroStorePaths(File("/data/user/0/com.scariflabs.horus/files/horus")).guestTmp
      val socketPath = "${androidTmp.path}/claude-messaging-61001/12264.sock"
      assertTrue("translated socket must fit sun_path with NUL", socketPath.toByteArray(Charsets.UTF_8).size < 108)
    } finally {
      base.deleteRecursively()
    }
  }

  @Test
  fun prootCleanupDoesNotFollowATmpDirectorySymlink() {
    val base = Files.createTempDirectory("horus-tmp-link").toFile()
    try {
      val outside = File(base, "outside").apply { mkdirs() }
      val keep = File(outside, "proot-456-ABC123").apply { writeText("keep") }
      val link = File(base, "tmp")
      Files.createSymbolicLink(link.toPath(), outside.toPath())
      DistroStoreCore.pruneProotTemps(link, File(base, "proc"))
      assertTrue(keep.exists())
    } finally {
      base.deleteRecursively()
    }
  }

  @Test
  fun bindsWorkspaceAndSetsPersistentGuestPaths() {
    val base = Files.createTempDirectory("horus-launcher").toFile()
    try {
      val rootfs = File(base, "rootfs").apply { mkdirs() }
      File(rootfs, "etc").mkdirs()
      val home = File(base, "home").apply { mkdirs() }
      val workspace = File(base, "workspace").apply { mkdirs() }
      val hostProc = File(base, "proc").apply { mkdirs() }
      File(hostProc, "stat").mkdirs()
      File(hostProc, "meminfo").writeText("MemTotal: 2 kB\n")
      val scratch = File(base, "scratch")
      val runtime = ProotRuntimeLocator.LocatedRuntime(
        prootBin = File(base, "libproot.so"),
        loaderBin = File(base, "libproot_loader.so"),
        libraryDir = base,
        prootVersion = "5.4.0",
      )

      val launch = ProotSessionLauncher(runtime, scratch, hostProc, bootTimeSeconds = { 1_759_390_000L })
        .interactiveShellLaunchSpec(rootfs, home, workspace)

      assertTrue(launch.argv.windowed(2).any { it == listOf("-b", "${home.canonicalPath}:/root") })
      assertTrue(launch.argv.windowed(2).any { it == listOf("-b", "${workspace.canonicalPath}:/workspace") })
      assertTrue(launch.argv.windowed(2).any { it == listOf("-b", "${hostProc.canonicalPath}:${ProotSessionLauncher.GUEST_PROC}") })
      for (device in listOf("null", "zero", "random", "urandom", "tty")) {
        assertTrue(
          "missing /dev/$device bind",
          launch.argv.windowed(2).any { it == listOf("-b", "/dev/$device:/dev/$device") },
        )
      }
      val statBind = launch.argv.windowed(2).firstOrNull {
        it[0] == "-b" && it[1].endsWith(":${ProotSessionLauncher.GUEST_PROC}/stat")
      }
      assertTrue("unreadable /proc/stat was not overlaid", statBind != null)
      val fallbackStat = statBind!![1].removeSuffix(":${ProotSessionLauncher.GUEST_PROC}/stat")
      assertTrue(File(fallbackStat).isFile)
      assertEquals(
        "cpu 0 0 0 0 0 0 0 0 0 0\ncpu0 0 0 0 0 0 0 0 0 0 0\nintr 0\nctxt 0\nbtime 1759390000\n" +
          "processes 0\nprocs_running 1\nprocs_blocked 0\nsoftirq 0 0 0 0 0 0 0 0 0 0 0\n",
        File(fallbackStat).readText(),
      )
      val loadavgBind = launch.argv.windowed(2).firstOrNull {
        it[0] == "-b" && it[1].endsWith(":${ProotSessionLauncher.GUEST_PROC}/loadavg")
      }
      assertTrue("missing /proc/loadavg was not overlaid", loadavgBind != null)
      assertEquals("0.00 0.00 0.00 0/0 0\n", File(loadavgBind!![1].substringBefore(":${ProotSessionLauncher.GUEST_PROC}/loadavg")).readText())
      assertFalse(launch.argv.any { it.contains("compat-bin") })
      assertEquals("/workspace", launch.argv[launch.argv.indexOf("-w") + 1])
      assertTrue(launch.environment.contains("PWD=/workspace"))
      assertTrue(launch.environment.contains("DOTNET_gcServer=0"))
      assertTrue(launch.environment.contains("DOTNET_GCHeapHardLimit=0x40000000"))
      assertTrue(launch.environment.contains("DOTNET_CLI_DO_NOT_USE_MSBUILD_SERVER=1"))
      assertTrue(launch.environment.contains("DOTNET_CLI_USE_MSBUILD_SERVER=0"))
      assertTrue(launch.environment.contains("UseSharedCompilation=false"))
      assertTrue(launch.environment.contains("PROOT_NO_SECCOMP=1"))
      assertTrue(launch.environment.contains("PATH=${ProotSessionLauncher.GUEST_PATH}"))
      assertFalse(launch.argv.any { it.endsWith(":${ProotSessionLauncher.GUEST_PROC}/meminfo") && it.contains("proc-compat") })
      assertEquals(
        listOf(
          "${ProotSessionLauncher.GUEST_PROC}/stat",
          "${ProotSessionLauncher.GUEST_PROC}/loadavg",
        ),
        launch.argv.windowed(2)
          .filter { it[0] == "-b" && it[1].contains("proc-compat") }
          .map { it[1].substringAfter(":") },
      )
      assertEquals(listOf(ProotSessionLauncher.GUEST_SHELL, "-c", "exec zsh -l"), launch.argv.takeLast(3))
      assertTrue(launch.environment.contains("XDG_CONFIG_HOME=/root/.config"))
      assertTrue(launch.environment.contains("XDG_DATA_HOME=/root/.local/share"))
      assertTrue(launch.environment.contains("XDG_CACHE_HOME=/root/.cache"))
      assertTrue(ProotSessionLauncher.GUEST_PATH.split(':').contains("/root/.local/bin"))
      assertTrue(ProotSessionLauncher.GUEST_PATH.split(':').contains("/usr/local/bin"))
      val profile = File(home, ".profile")
      assertTrue(profile.isFile)
      assertTrue(profile.readText().contains(ProotSessionLauncher.CODEX_PROFILE_BEGIN))
      assertTrue(profile.readText().contains("command codex --sandbox danger-full-access --no-daemon \"\$@\""))
      assertTrue(profile.readText().contains(ProotSessionLauncher.ZSH_PROFILE_BEGIN))
      val zshrc = File(home, ".zshrc")
      assertTrue(zshrc.isFile)
      assertTrue(zshrc.readText().contains(ProotSessionLauncher.ZSHRC_BEGIN))
      assertTrue(zshrc.readText().contains("PROMPT=$'%{\\e[0;34m%}%B┌─["))
      assertTrue(zshrc.readText().contains("export PATH=\"\$HOME/.local/bin:/usr/local/bin:"))
      assertTrue(zshrc.readText().contains("umask 0002"))
      assertTrue(zshrc.readText().contains(ProotSessionLauncher.ZSH_EXIT_SCRIPT))
      // A stray ^S must not freeze the terminal.
      assertTrue(zshrc.readText().contains("unsetopt flow_control\nstty -ixon"))
      assertFalse(zshrc.readText().contains("RPROMPT"))
      assertTrue(zshrc.readText().contains("duellj theme"))
      assertFalse(zshrc.readText().contains("vcs_info"))
    } finally {
      base.deleteRecursively()
    }
  }

  @Test
  fun preservesUserProfileAndRepairsOneCodexPolicyBlockIdempotently() {
    val base = Files.createTempDirectory("horus-codex-profile").toFile()
    try {
      val rootfs = File(base, "rootfs").apply { mkdirs() }
      File(rootfs, "etc").mkdirs()
      val home = File(base, "home").apply { mkdirs() }
      val profile = File(home, ".profile").apply {
        writeText("export HORUS_TEST=keep\n${ProotSessionLauncher.CODEX_PROFILE_BEGIN}\nold\n${ProotSessionLauncher.CODEX_PROFILE_END}\n")
      }
      val hostProc = File(base, "proc").apply { mkdirs() }
      File(hostProc, "stat").writeText("live stat\n")
      File(hostProc, "loadavg").writeText("0.01 0.02 0.03 1/2 3\n")
      val launcher = launcher(base, hostProc)

      launcher.interactiveShellLaunchSpec(rootfs, home)
      val first = profile.readText()
      launcher.interactiveShellLaunchSpec(rootfs, home)
      val second = profile.readText()

      assertEquals(first, second)
      assertTrue(first.startsWith("export HORUS_TEST=keep\n"))
      assertTrue(first.contains("command codex --sandbox danger-full-access --no-daemon \"\$@\""))
      assertEquals(1, first.split(ProotSessionLauncher.CODEX_PROFILE_BEGIN).size - 1)
      assertEquals(1, first.split(ProotSessionLauncher.ZSH_PROFILE_BEGIN).size - 1)
      val zshrc = File(home, ".zshrc")
      val firstZshrc = zshrc.readText()
      launcher.interactiveShellLaunchSpec(rootfs, home)
      assertEquals(firstZshrc, zshrc.readText())
      assertEquals(1, firstZshrc.split(ProotSessionLauncher.ZSHRC_BEGIN).size - 1)
    } finally {
      base.deleteRecursively()
    }
  }

  @Test
  fun launchesPersistedProfileAsNonRootGuestIdentity() {
    val base = Files.createTempDirectory("horus-guest-user").toFile()
    try {
      val rootfs = File(base, "rootfs").apply { mkdirs() }
      File(rootfs, "etc").mkdirs()
      File(rootfs, "etc/passwd").writeText("root:x:0:0:root:/root:/bin/sh\n")
      File(rootfs, "etc/group").writeText("root:x:0:\n")
      File(rootfs, "etc/shadow").writeText("root:!:19000:0:99999:7:::\n")
      val home = File(base, "home").apply { mkdirs() }
      val hostProc = File(base, "proc").apply { mkdirs() }
      File(hostProc, "stat").writeText("live stat\n")
      File(hostProc, "loadavg").writeText("0.01 0.02 0.03 1/2 3\n")

      val launch = launcher(base, hostProc).interactiveShellLaunchSpec(rootfs, home, guestUsername = "luna")

      assertTrue(launch.argv.windowed(2).any { it == listOf("-i", "1000:1000") })
      assertFalse(launch.argv.contains("-0"))
      assertTrue(launch.argv.windowed(2).any { it == listOf("-b", "${home.canonicalPath}:/home/luna") })
      assertTrue(launch.environment.contains("HOME=/home/luna"))
      assertTrue(launch.environment.contains("PATH=/home/luna/.local/bin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"))
      assertTrue(launch.environment.contains("XDG_CONFIG_HOME=/home/luna/.config"))
      assertTrue(File(rootfs, "etc/passwd").readText().contains("luna:x:1000:1000:luna:/home/luna:/bin/sh"))
      assertTrue(File(rootfs, "etc/group").readText().contains("luna:x:1000:"))
      assertTrue(File(rootfs, "etc/shadow").readText().contains("luna:!:") )
    } finally {
      base.deleteRecursively()
    }
  }

  @Test
  fun migratesOneLegacyProfileAccountToHorusAndIsIdempotent() {
    val base = Files.createTempDirectory("horus-profile-migration").toFile()
    try {
      val rootfs = File(base, "rootfs").apply { mkdirs() }
      val etc = File(rootfs, "etc").apply { mkdirs() }
      val passwd = File(etc, "passwd").apply {
        writeText("root:x:0:0:root:/root:/bin/sh\noldprofile:x:1000:1000:oldprofile:/home/oldprofile:/bin/sh\n")
      }
      val group = File(etc, "group").apply { writeText("root:x:0:\noldprofile:x:1000:\n") }
      val shadow = File(etc, "shadow").apply {
        writeText("root:!:19000:0:99999:7:::\noldprofile:!:19000:0:99999:7:::\n")
      }
      val home = File(base, "home").apply { mkdirs() }
      val hostProc = File(base, "proc").apply { mkdirs() }
      File(hostProc, "stat").writeText("live stat\n")
      File(hostProc, "loadavg").writeText("0.01 0.02 0.03 1/2 3\n")
      val launcher = launcher(base, hostProc)

      val launch = launcher.interactiveShellLaunchSpec(rootfs, home, guestUsername = "horus")
      val first = listOf(passwd.readText(), group.readText(), shadow.readText())
      launcher.interactiveShellLaunchSpec(rootfs, home, guestUsername = "horus")

      assertTrue(launch.argv.windowed(2).any { it == listOf("-i", "1000:1000") })
      assertEquals("root:x:0:0:root:/root:/bin/sh\nhorus:x:1000:1000:horus:/home/horus:/bin/sh\n", first[0])
      assertEquals("root:x:0:\nhorus:x:1000:\n", first[1])
      assertEquals("root:!:19000:0:99999:7:::\nhorus:!:19000:0:99999:7:::\n", first[2])
      assertEquals(first, listOf(passwd.readText(), group.readText(), shadow.readText()))
    } finally {
      base.deleteRecursively()
    }
  }

  @Test
  fun renamesTheProfileAccountToTheConfiguredUsername() {
    // A rootfs set up as "horus" whose user is now configured as "vishesh":
    // the profile uid is renamed in place, never duplicated.
    val base = Files.createTempDirectory("horus-profile-rename").toFile()
    try {
      val rootfs = File(base, "rootfs").apply { mkdirs() }
      val etc = File(rootfs, "etc").apply { mkdirs() }
      val passwd = File(etc, "passwd").apply {
        writeText("root:x:0:0:root:/root:/bin/sh\nhorus:x:1000:1000:horus:/home/horus:/bin/sh\n")
      }
      val group = File(etc, "group").apply { writeText("root:x:0:\nhorus:x:1000:\n") }
      val shadow = File(etc, "shadow").apply {
        writeText("root:!:19000:0:99999:7:::\nhorus:!:19000:0:99999:7:::\n")
      }
      val home = File(base, "home").apply { mkdirs() }
      val hostProc = File(base, "proc").apply { mkdirs() }
      File(hostProc, "stat").writeText("live stat\n")
      File(hostProc, "loadavg").writeText("0.01 0.02 0.03 1/2 3\n")

      val launch = launcher(base, hostProc).interactiveShellLaunchSpec(rootfs, home, guestUsername = "vishesh")

      assertTrue(launch.argv.windowed(2).any { it == listOf("-b", "${home.canonicalPath}:/home/vishesh") })
      assertEquals("root:x:0:0:root:/root:/bin/sh\nvishesh:x:1000:1000:vishesh:/home/vishesh:/bin/sh\n", passwd.readText())
      assertEquals("root:x:0:\nvishesh:x:1000:\n", group.readText())
      assertEquals("root:!:19000:0:99999:7:::\nvishesh:!:19000:0:99999:7:::\n", shadow.readText())

      // Renamed again from Account after SSH gave the user its login shell.
      passwd.writeText(passwd.readText().replace("/home/vishesh:/bin/sh", "/home/vishesh:/usr/local/bin/horus-ssh-shell"))
      launcher(base, hostProc).interactiveShellLaunchSpec(rootfs, home, guestUsername = "nefer")
      assertEquals("root:x:0:0:root:/root:/bin/sh\nnefer:x:1000:1000:nefer:/home/nefer:/usr/local/bin/horus-ssh-shell\n", passwd.readText())
      assertEquals("root:x:0:\nnefer:x:1000:\n", group.readText())

      // Saved absolute paths keep working: former names link to the current one.
      val homes = File(rootfs, "home")
      fun link(name: String) = Files.readSymbolicLink(File(homes, name).toPath()).toString()
      assertEquals("nefer", link("vishesh"))
      assertEquals("nefer", link("horus"))
      assertTrue(File(homes, "nefer").isDirectory && !Files.isSymbolicLink(File(homes, "nefer").toPath()))

      // Renaming back reuses the former name; its alias is replaced by a real mount point.
      launcher(base, hostProc).interactiveShellLaunchSpec(rootfs, home, guestUsername = "vishesh")
      assertFalse(Files.isSymbolicLink(File(homes, "vishesh").toPath()))
      assertEquals("vishesh", link("nefer"))
      assertEquals("vishesh", link("horus"))

      // A directory with content is never replaced by a link.
      File(homes, "keep").mkdirs(); File(homes, "keep/file").writeText("x")
      passwd.writeText(passwd.readText().replace("vishesh:x:1000:1000:vishesh:/home/vishesh", "keep:x:1000:1000:keep:/home/keep"))
      group.writeText(group.readText().replace("vishesh:x:1000", "keep:x:1000"))
      shadow.writeText(shadow.readText().replace("vishesh:!", "keep:!"))
      launcher(base, hostProc).interactiveShellLaunchSpec(rootfs, home, guestUsername = "ra")
      assertEquals("x", File(homes, "keep/file").readText())
    } finally {
      base.deleteRecursively()
    }
  }

  @Test
  fun resumesAnInterruptedLegacyProfileMigration() {
    val base = Files.createTempDirectory("horus-profile-migration-retry").toFile()
    try {
      val rootfs = File(base, "rootfs").apply { mkdirs() }
      val etc = File(rootfs, "etc").apply { mkdirs() }
      val passwd = File(etc, "passwd").apply {
        writeText("root:x:0:0:root:/root:/bin/sh\noldprofile:x:1000:1000:oldprofile:/home/oldprofile:/bin/sh\n")
      }
      val group = File(etc, "group").apply { writeText("root:x:0:\nhorus:x:1000:\n") }
      val shadow = File(etc, "shadow").apply {
        writeText("root:!:19000:0:99999:7:::\nhorus:!:19000:0:99999:7:::\n")
      }
      val home = File(base, "home").apply { mkdirs() }
      val hostProc = File(base, "proc").apply { mkdirs() }
      File(hostProc, "stat").writeText("live stat\n")
      File(hostProc, "loadavg").writeText("0.01 0.02 0.03 1/2 3\n")

      launcher(base, hostProc).interactiveShellLaunchSpec(rootfs, home, guestUsername = "horus")

      assertTrue(passwd.readText().contains("horus:x:1000:1000:horus:/home/horus:/bin/sh"))
      assertEquals("root:x:0:\nhorus:x:1000:\n", group.readText())
      assertEquals("root:!:19000:0:99999:7:::\nhorus:!:19000:0:99999:7:::\n", shadow.readText())
    } finally {
      base.deleteRecursively()
    }
  }

  @Test
  fun rejectsAmbiguousOrUnexpectedUid1000AccountWithoutRewritingAccountFiles() {
    val base = Files.createTempDirectory("horus-profile-migration-reject").toFile()
    try {
      val rootfs = File(base, "rootfs").apply { mkdirs() }
      val etc = File(rootfs, "etc").apply { mkdirs() }
      val passwdText = "root:x:0:0:root:/root:/bin/sh\nother:x:1000:1000:other:/home/other:/bin/sh\n"
      val groupText = "root:x:0:\nother:x:1000:\n"
      val shadowText = "root:!:19000:0:99999:7:::\nother:\$6\$unexpected:19000:0:99999:7:::\n"
      val passwd = File(etc, "passwd").apply { writeText(passwdText) }
      val group = File(etc, "group").apply { writeText(groupText) }
      val shadow = File(etc, "shadow").apply { writeText(shadowText) }
      val home = File(base, "home").apply { mkdirs() }
      val hostProc = File(base, "proc").apply { mkdirs() }
      File(hostProc, "stat").writeText("live stat\n")
      File(hostProc, "loadavg").writeText("0.01 0.02 0.03 1/2 3\n")

      val failure = assertThrows(IllegalStateException::class.java) {
        launcher(base, hostProc).interactiveShellLaunchSpec(rootfs, home, guestUsername = "horus")
      }

      assertEquals("legacy profile shadow entry does not match the prior format", failure.message)
      assertEquals(passwdText, passwd.readText())
      assertEquals(groupText, group.readText())
      assertEquals(shadowText, shadow.readText())
      assertFalse(File(rootfs, "home/horus").exists())
    } finally {
      base.deleteRecursively()
    }
  }

  @Test
  fun rejectsLegacyProfileSupplementalGroupMembershipWithoutRewritingAccountFiles() {
    val base = Files.createTempDirectory("horus-profile-migration-groups").toFile()
    try {
      val rootfs = File(base, "rootfs").apply { mkdirs() }
      val etc = File(rootfs, "etc").apply { mkdirs() }
      val passwdText = "root:x:0:0:root:/root:/bin/sh\nother:x:1000:1000:other:/home/other:/bin/sh\n"
      val groupText = "root:x:0:\nother:x:1000:\nwheel:x:10:other\n"
      val shadowText = "root:!:19000:0:99999:7:::\nother:!:19000:0:99999:7:::\n"
      val passwd = File(etc, "passwd").apply { writeText(passwdText) }
      val group = File(etc, "group").apply { writeText(groupText) }
      val shadow = File(etc, "shadow").apply { writeText(shadowText) }
      val home = File(base, "home").apply { mkdirs() }
      val hostProc = File(base, "proc").apply { mkdirs() }
      File(hostProc, "stat").writeText("live stat\n")
      File(hostProc, "loadavg").writeText("0.01 0.02 0.03 1/2 3\n")

      val failure = assertThrows(IllegalStateException::class.java) {
        launcher(base, hostProc).interactiveShellLaunchSpec(rootfs, home, guestUsername = "horus")
      }

      assertEquals("legacy profile has unexpected supplemental group memberships", failure.message)
      assertEquals(passwdText, passwd.readText())
      assertEquals(groupText, group.readText())
      assertEquals(shadowText, shadow.readText())
      assertFalse(File(rootfs, "home/horus").exists())
    } finally {
      base.deleteRecursively()
    }
  }

  @Test
  fun provisionsHarnessesAsRootBeforeTheUserSession() {
    val base = Files.createTempDirectory("horus-harness-provision").toFile()
    try {
      val rootfs = File(base, "rootfs").apply { mkdirs() }
      File(rootfs, "etc").mkdirs()
      val home = File(base, "home").apply { mkdirs() }
      val hostProc = File(base, "proc").apply { mkdirs() }
      File(hostProc, "stat").writeText("live stat\n")
      File(hostProc, "loadavg").writeText("0.01 0.02 0.03 1/2 3\n")

      val launch = launcher(base, hostProc).toolchainProvisionLaunchSpec(
        rootfs,
        home,
        TerminalRuntimeContract.TOOLCHAIN_TARGET_CLAUDE,
      )

      assertTrue(launch.argv.contains("-0"))
      assertFalse(launch.argv.contains("-i"))
      assertEquals(listOf("/bin/sh", "-c"), launch.argv.takeLast(3).dropLast(1))
      assertTrue(launch.argv.last().contains("@openai/codex"))
      assertTrue(launch.argv.last().contains("opencode-ai"))
      assertTrue(launch.argv.last().contains("https://claude.ai/install.sh"))
      assertTrue(launch.argv.last().contains("github-cli"))
      assertTrue(launch.argv.last().contains("claude_failed"))
      assertTrue(launch.argv.last().contains("head -n 1"))
      assertTrue(launch.argv.last().contains("npm install --global --force"))
      assertTrue(launch.argv.last().contains("--include=optional --no-package-lock --no-audit --no-fund --no-progress --loglevel=error @openai/codex"))
      assertTrue(launch.argv.last().contains("--no-progress --loglevel=error opencode-ai"))
      assertTrue(launch.argv.last().contains("stat -L -c '%s:%Y:%i:%d'"))
      assertTrue(launch.argv.last().contains("curl --connect-timeout 15 --max-time 300 -fsSL"))
      assertTrue(launch.argv.last().contains("[ -L \"${'$'}codex_launcher\" ]"))
      assertTrue(launch.argv.last().contains("[ -L \"${'$'}opencode_launcher\" ]"))
      assertTrue(launch.argv.last().contains("opencode_entry\" --version"))
      assertTrue(launch.argv.last().contains("provision-stage"))
      assertTrue(launch.environment.contains("HOME=/root"))
      assertTrue(launch.environment.contains("HORUS_TOOLCHAIN_TARGET=claude"))
      assertFalse(launch.environment.contains("HORUS_BOOTSTRAP=1"))
    } finally {
      base.deleteRecursively()
    }
  }

  @Test
  fun provisionsHarnessIntoItsPrivateHomeAndHandsOffToItsOwnLockedUser() {
    val base = Files.createTempDirectory("horus-private-harness").toFile()
    try {
      val rootfs = File(base, "rootfs").apply { mkdirs() }
      File(rootfs, "etc").mkdirs()
      File(rootfs, "etc/passwd").writeText("root:x:0:0:root:/root:/bin/sh\nprofile:x:1000:1000:profile:/home/profile:/bin/sh\n")
      File(rootfs, "etc/group").writeText("root:x:0:\nprofile:x:1000:\n")
      File(rootfs, "etc/shadow").writeText("root:!:19000:0:99999:7:::\n")
      val home = File(base, "harness-homes/codex").apply { mkdirs() }
      val workspace = File(base, "workspace").apply { mkdirs() }
      val hostProc = File(base, "proc").apply { mkdirs() }
      File(hostProc, "stat").writeText("live stat\n")
      File(hostProc, "loadavg").writeText("0.01 0.02 0.03 1/2 3\n")

      val launch = launcher(base, hostProc).toolchainSessionLaunchSpec(
        rootfsDir = rootfs,
        guestHomeDir = home,
        workspaceDir = workspace,
        guestUsername = "harness_codex",
        guestUid = 61_002,
        guestGid = 1_000,
        target = TerminalRuntimeContract.TOOLCHAIN_TARGET_CODEX,
        sessionCommand = "exec codex",
      )
      val command = launch.argv.takeLast(9)

      assertTrue(launch.argv.contains("-0"))
      assertTrue(launch.argv.windowed(2).any { it == listOf("-b", "${home.canonicalPath}:/root") })
      assertTrue(launch.argv.windowed(2).any { it == listOf("-b", "${home.canonicalPath}:/home/harness_codex") })
      assertTrue(launch.argv.windowed(2).any { it == listOf("-b", "${workspace.canonicalPath}:/workspace") })
      assertEquals("harness_codex", command[4])
      assertEquals("61002", command[5])
      assertEquals("1000", command[6])
      assertEquals("exec codex", command[7])
      assertTrue(command[2].contains("chown \"${'$'}session_uid:${'$'}session_gid\" /root"))
      assertTrue(command[2].contains("chmod 700 /root"))
      assertTrue(command[2].contains("chgrp \"${'$'}session_gid\" /workspace"))
      assertTrue(command[2].contains("chmod 2770 /workspace"))
      assertTrue(command[2].contains("umask 0002"))
      assertTrue(command[2].contains("export NPM_CONFIG_PREFIX=\"${'$'}HOME/.local\""))
      assertTrue(command[2].contains("export npm_config_prefix=\"${'$'}HOME/.local\""))
      assertTrue(File(rootfs, "etc/passwd").readText().contains("harness_codex:x:61002:1000:harness_codex:/home/harness_codex:/bin/sh"))
      assertTrue(File(rootfs, "etc/shadow").readText().contains("harness_codex:!:"))

      val readyLaunch = launcher(base, hostProc).toolchainSessionLaunchSpec(
        rootfsDir = rootfs,
        guestHomeDir = home,
        workspaceDir = workspace,
        guestUsername = "harness_codex",
        guestUid = 61_002,
        guestGid = 1_000,
        target = TerminalRuntimeContract.TOOLCHAIN_TARGET_CODEX,
        sessionCommand = "exec codex",
        provision = false,
      )
      assertTrue(readyLaunch.environment.contains("HORUS_SKIP_TOOLCHAIN_PROVISION=1"))
      assertTrue(readyLaunch.argv.any { it.contains("HORUS_SKIP_TOOLCHAIN_PROVISION") })
    } finally {
      base.deleteRecursively()
    }
  }

  @Test
  fun seedsCodexMobileEnvironmentInstructionsBeforeLazyInstallAndPreservesUserGuidance() {
    val base = Files.createTempDirectory("horus-codex-agent-guidance").toFile()
    try {
      val rootfs = File(base, "rootfs").apply { mkdirs() }
      File(rootfs, "etc").mkdirs()
      val home = File(base, "harness-homes/codex").apply { mkdirs() }
      val agentsFile = File(home, ".codex/AGENTS.md").apply {
        requireNotNull(parentFile).mkdirs()
        writeText("# My Codex defaults\n\n- Keep my project instructions.\n")
      }
      val hostProc = File(base, "proc").apply { mkdirs() }
      File(hostProc, "stat").writeText("live stat\n")
      File(hostProc, "loadavg").writeText("0.01 0.02 0.03 1/2 3\n")
      val launcher = launcher(base, hostProc)

      val launch = launcher.toolchainProvisionLaunchSpec(
        rootfsDir = rootfs,
        guestHomeDir = home,
        target = TerminalRuntimeContract.TOOLCHAIN_TARGET_CODEX,
      )
      val first = agentsFile.readText()

      assertTrue(first.contains("# My Codex defaults"))
      assertTrue(first.contains("Keep my project instructions."))
      assertTrue(first.contains("Linux ARM64 guest through PRoot. The distribution is Alpine or Debian"))
      assertTrue(first.contains("Alpine uses `apk`") && first.contains("Debian uses `apt`"))
      assertTrue(first.contains("`harness_codex` user"))
      assertTrue(first.contains("`--sandbox danger-full-access`"))
      assertTrue(first.indexOf("# My Codex defaults") < first.indexOf(ProotSessionLauncher.CODEX_AGENT_INSTRUCTIONS_BEGIN))
      assertTrue(launch.argv.last().contains("npm install --global --force"))
      assertTrue(launch.argv.last().contains("@openai/codex"))

      agentsFile.appendText("\n## User addition\nKeep this addition too.\n")
      val withUserAddition = agentsFile.readText()
      launcher.toolchainProvisionLaunchSpec(
        rootfsDir = rootfs,
        guestHomeDir = home,
        target = TerminalRuntimeContract.TOOLCHAIN_TARGET_CODEX,
      )
      val second = agentsFile.readText()

      assertTrue(second.contains("Keep this addition too."))
      assertEquals(1, second.split(ProotSessionLauncher.CODEX_AGENT_INSTRUCTIONS_BEGIN).size - 1)
      assertEquals(1, second.split(ProotSessionLauncher.CODEX_AGENT_INSTRUCTIONS_END).size - 1)
      assertEquals(withUserAddition, second)

      val freshHome = File(base, "harness-homes/codex-fresh").apply { mkdirs() }
      launcher.toolchainProvisionLaunchSpec(
        rootfsDir = rootfs,
        guestHomeDir = freshHome,
        target = TerminalRuntimeContract.TOOLCHAIN_TARGET_CODEX,
      )
      assertTrue(File(freshHome, ".codex/AGENTS.md").isFile)

      val claudeHome = File(base, "harness-homes/claude").apply { mkdirs() }
      launcher.toolchainProvisionLaunchSpec(
        rootfsDir = rootfs,
        guestHomeDir = claudeHome,
        target = TerminalRuntimeContract.TOOLCHAIN_TARGET_CLAUDE,
      )
      assertFalse(File(claudeHome, ".codex/AGENTS.md").exists())
    } finally {
      base.deleteRecursively()
    }
  }

  @Test
  fun refusesToFollowCodexAgentInstructionsDirectorySymlink() {
    val base = Files.createTempDirectory("horus-codex-agent-symlink").toFile()
    try {
      val rootfs = File(base, "rootfs").apply { mkdirs() }
      File(rootfs, "etc").mkdirs()
      val home = File(base, "harness-homes/codex").apply { mkdirs() }
      val redirected = File(base, "redirected").apply { mkdirs() }
      val sentinel = File(redirected, "AGENTS.md").apply { writeText("keep this file\n") }
      Files.createSymbolicLink(File(home, ".codex").toPath(), redirected.toPath())
      val hostProc = File(base, "proc").apply { mkdirs() }
      File(hostProc, "stat").writeText("live stat\n")
      File(hostProc, "loadavg").writeText("0.01 0.02 0.03 1/2 3\n")

      val failure = assertThrows(IllegalStateException::class.java) {
        launcher(base, hostProc).toolchainProvisionLaunchSpec(
          rootfsDir = rootfs,
          guestHomeDir = home,
          target = TerminalRuntimeContract.TOOLCHAIN_TARGET_CODEX,
        )
      }

      assertEquals("Codex config directory is unavailable", failure.message)
      assertEquals("keep this file\n", sentinel.readText())
    } finally {
      base.deleteRecursively()
    }
  }

  @Test
  fun seedsClaudeCodeAndOpenCodeGlobalInstructionsBeforeTheirLazyInstallers() {
    data class HarnessCase(
      val target: String,
      val homeName: String,
      val instructionsPath: String,
      val beginMarker: String,
      val endMarker: String,
      val userName: String,
      val installerNeedle: String,
      val otherInstructionsPath: String,
    )

    val harnesses = listOf(
      HarnessCase(
        TerminalRuntimeContract.TOOLCHAIN_TARGET_CLAUDE,
        "claude",
        ".claude/CLAUDE.md",
        ProotSessionLauncher.CLAUDE_AGENT_INSTRUCTIONS_BEGIN,
        ProotSessionLauncher.CLAUDE_AGENT_INSTRUCTIONS_END,
        "harness_claude",
        "https://claude.ai/install.sh",
        ".config/opencode/AGENTS.md",
      ),
      HarnessCase(
        TerminalRuntimeContract.TOOLCHAIN_TARGET_OPENCODE,
        "opencode",
        ".config/opencode/AGENTS.md",
        ProotSessionLauncher.OPENCODE_AGENT_INSTRUCTIONS_BEGIN,
        ProotSessionLauncher.OPENCODE_AGENT_INSTRUCTIONS_END,
        "harness_opencode",
        "opencode-ai",
        ".claude/CLAUDE.md",
      ),
    )

    val base = Files.createTempDirectory("horus-agent-global-guidance").toFile()
    try {
      val rootfs = File(base, "rootfs").apply { mkdirs() }
      File(rootfs, "etc").mkdirs()
      val hostProc = File(base, "proc").apply { mkdirs() }
      File(hostProc, "stat").writeText("live stat\n")
      File(hostProc, "loadavg").writeText("0.01 0.02 0.03 1/2 3\n")
      val launcher = launcher(base, hostProc)

      harnesses.forEach { harness ->
        val home = File(base, "harness-homes/${harness.homeName}").apply { mkdirs() }
        val instructionsFile = File(home, harness.instructionsPath).apply {
          requireNotNull(parentFile).mkdirs()
          writeText("# My ${harness.homeName} defaults\n\n- Keep my existing guidance.\n")
        }
        val launch = launcher.toolchainProvisionLaunchSpec(rootfs, home, harness.target)
        val first = instructionsFile.readText()

        assertTrue(first.contains("# My ${harness.homeName} defaults"))
        assertTrue(first.contains("Keep my existing guidance."))
        assertTrue(first.contains("Linux ARM64 guest through PRoot. The distribution is Alpine or Debian"))
        assertTrue(first.contains("Alpine uses `apk`") && first.contains("Debian uses `apt`"))
        assertTrue(first.contains(harness.userName))
        assertTrue(first.contains("not a hard security boundary"))
        assertTrue(first.indexOf("# My ${harness.homeName} defaults") < first.indexOf(harness.beginMarker))
        assertTrue(launch.argv.last().contains(harness.installerNeedle))
        assertFalse(File(home, harness.otherInstructionsPath).exists())

        instructionsFile.appendText("\n## User addition\nKeep this addition too.\n")
        val withUserAddition = instructionsFile.readText()
        launcher.toolchainProvisionLaunchSpec(rootfs, home, harness.target)
        val second = instructionsFile.readText()

        assertTrue(second.contains("Keep this addition too."))
        assertEquals(1, second.split(harness.beginMarker).size - 1)
        assertEquals(1, second.split(harness.endMarker).size - 1)
        assertEquals(withUserAddition, second)

        val freshHome = File(base, "harness-homes/${harness.homeName}-fresh").apply { mkdirs() }
        launcher.toolchainProvisionLaunchSpec(rootfs, freshHome, harness.target)
        assertTrue(File(freshHome, harness.instructionsPath).isFile)
      }
    } finally {
      base.deleteRecursively()
    }
  }

  @Test
  fun refusesToFollowClaudeCodeAndOpenCodeInstructionDirectorySymlinks() {
    data class SymlinkCase(
      val target: String,
      val homeName: String,
      val symlinkPart: String,
      val toolName: String,
    )

    val cases = listOf(
      SymlinkCase(TerminalRuntimeContract.TOOLCHAIN_TARGET_CLAUDE, "claude", ".claude", "Claude Code"),
      SymlinkCase(TerminalRuntimeContract.TOOLCHAIN_TARGET_OPENCODE, "opencode", ".config", "OpenCode"),
    )
    val base = Files.createTempDirectory("horus-agent-guidance-symlink").toFile()
    try {
      val rootfs = File(base, "rootfs").apply { mkdirs() }
      File(rootfs, "etc").mkdirs()
      val hostProc = File(base, "proc").apply { mkdirs() }
      File(hostProc, "stat").writeText("live stat\n")
      File(hostProc, "loadavg").writeText("0.01 0.02 0.03 1/2 3\n")
      val launcher = launcher(base, hostProc)

      cases.forEach { case ->
        val home = File(base, "harness-homes/${case.homeName}").apply { mkdirs() }
        val redirected = File(base, "redirected-${case.homeName}").apply { mkdirs() }
        val sentinel = File(redirected, "sentinel").apply { writeText("keep this file\n") }
        Files.createSymbolicLink(File(home, case.symlinkPart).toPath(), redirected.toPath())

        val failure = assertThrows(IllegalStateException::class.java) {
          launcher.toolchainProvisionLaunchSpec(rootfs, home, case.target)
        }

        assertEquals("${case.toolName} config directory is unavailable", failure.message)
        assertEquals("keep this file\n", sentinel.readText())
      }
    } finally {
      base.deleteRecursively()
    }
  }

  @Test
  fun installsAndValidatesBaseAlpineToolsForVisibleWorkspaceCloneSessions() {
    val base = Files.createTempDirectory("horus-shell-clone-provision").toFile()
    try {
      val rootfs = File(base, "rootfs").apply { mkdirs() }
      File(rootfs, "etc").mkdirs()
      val home = File(base, "home").apply { mkdirs() }
      val hostProc = File(base, "proc").apply { mkdirs() }
      File(hostProc, "stat").writeText("live stat\n")
      File(hostProc, "loadavg").writeText("0.01 0.02 0.03 1/2 3\n")

      val launch = launcher(base, hostProc).toolchainProvisionLaunchSpec(
        rootfs,
        home,
        TerminalRuntimeContract.TOOLCHAIN_TARGET_SHELL,
      )

      val script = launch.argv.last()
      // One script for both distributions: the guest picks apk or apt.
      assertTrue(script.contains("pkg_install /root/.cache/horus/alpine-bootstrap.log \$pkgs_shell"))
      assertTrue(script.contains("pkgs_shell=\"ca-certificates curl gcompat git jq less openssh-client-default python3 ripgrep tzdata zsh\""))
      assertTrue(script.contains("apk add --no-cache --no-progress"))
      assertTrue(script.contains("force-unsafe-io"))
      assertTrue(script.contains("pkgs_shell=\"ca-certificates curl git jq less openssh-client python3 python3-venv ripgrep tzdata zsh\""))
      // Installs from before python3-venv joined the list get it on their next shell.
      assertTrue(script.contains("|| ! has_python_venv; then"))
      assertTrue(script.contains("apt_with_status install -y --fix-broken --no-install-recommends"))
      assertTrue(script.contains("APT::Sandbox::User \"root\";"))
      assertTrue(launch.argv.last().contains("command -v git"))
      assertTrue(launch.argv.last().contains("git --version"))
      for (utility in listOf("rg", "curl", "jq", "python3")) {
        assertTrue(launch.argv.last().contains("/usr/bin/$utility"))
      }
      assertTrue(launch.argv.last().contains("/lib/ld-linux-aarch64.so.1"))
    } finally {
      base.deleteRecursively()
    }
  }

  @Test
  fun packageProgressTurnsAptAndApkStatusIntoPercentLines() {
    val base = Files.createTempDirectory("horus-pkg-progress").toFile()
    try {
      val status = File(base, "status")
      val progress = File(base, "progress")
      val log = File(base, "log")
      fun watch(manager: String, vararg lines: String): List<String> {
        status.writeText("")
        log.writeText("")
        val target = if (manager == "apt") "\$pkg_status" else "\$pkg_log"
        // Guest configuration must stay in the fixture, even on hosts with apt.
        val script = ProotSessionLauncher.PACKAGE_MANAGER_SCRIPT
          .replace("/etc/apt", File(base, "etc/apt").absolutePath)
          .replace("/etc/dpkg", File(base, "etc/dpkg").absolutePath) +
          "\nhorus_pkg=$manager; pkg_status='${status.path}'; pkg_progress='${progress.path}'; pkg_log='${log.path}'\n" +
          "watch_packages & pid=\$!\n" +
          lines.joinToString("") { "printf '%s\\n' '$it' >> \"$target\"; sleep 1.3\n" } +
          "kill \$pid\n"
        val process = ProcessBuilder("/bin/sh", "-c", script).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        return output.lines().filter { it.isNotBlank() }
      }

      // apt: download status, then dpkg status whose package may carry an arch.
      assertEquals(
        listOf("Downloading packages… 37%", "Installing packages… 64%"),
        watch("apt", "dlstatus:12:37.5000:Retrieving file 12 of 40", "pmstatus:libc6:arm64:64.2000:Unpacking libc6 (arm64)"),
      )
      assertEquals("Installing packages… 64%", progress.readText().trim())
      if (File("/usr/bin/apt-get").canExecute()) {
        assertTrue(File(base, "etc/apt/apt.conf.d/99horus").readText().contains("APT::Sandbox::User \"root\";"))
        assertTrue(File(base, "etc/dpkg/dpkg.cfg.d/99horus").readText().contains("force-unsafe-io"))
      }
      // apk: its own "(n/total)" lines in the install log.
      assertEquals(
        listOf("Installing packages… 25%"),
        watch("apk", "fetch https://dl-cdn.alpinelinux.org/alpine/v3.24/main/aarch64/APKINDEX.tar.gz", "(12/48) Installing libcurl (8.22.0-r0)"),
      )
      // Anything else is ignored.
      assertEquals(emptyList<String>(), watch("apt", "pmerror:foo:1:bad", "noise"))
    } finally {
      base.deleteRecursively()
    }
  }

  /**
   * PACKAGE_MANAGER_SCRIPT with every guest path moved into [base], fake
   * apt-get and dpkg first on PATH, and a `flock` (the host's, or an fcntl
   * stand-in on hosts without one, e.g. macOS).
   */
  private fun packageFixture(base: File, aptBody: String, dpkgBody: String = "exit 0"): String {
    val bin = File(base, "bin").apply { mkdirs() }
    fun command(name: String, body: String) = File(bin, name).apply {
      writeText("#!/bin/sh\n$body\n")
      setExecutable(true)
    }
    command("apt-get", aptBody)
    command("dpkg", dpkgBody)
    if (ProcessBuilder("/bin/sh", "-c", "command -v flock").start().waitFor() != 0) {
      val python = listOf("/usr/bin/python3", "/opt/homebrew/bin/python3", "/usr/local/bin/python3").firstOrNull { File(it).canExecute() }
      org.junit.Assume.assumeTrue("flock or python3 required for the package lock test", python != null)
      File(bin, "flock").apply {
        writeText(
          "#!$python\nimport fcntl, sys\nargs = sys.argv[1:]\nflags = fcntl.LOCK_EX\n" +
            "if args[0] == '-n':\n    flags |= fcntl.LOCK_NB\n    args = args[1:]\n" +
            "try:\n    fcntl.flock(int(args[0]), flags)\nexcept OSError:\n    sys.exit(1)\n",
        )
        setExecutable(true)
      }
    }
    val cache = File(base, "cache").apply { mkdirs() }
    return "PATH='${bin.path}':\$PATH\n" +
      ProotSessionLauncher.PACKAGE_MANAGER_SCRIPT
        .replace("/var/lib/horus", File(base, "var/lib/horus").absolutePath)
        .replace("/var/lib/dpkg", File(base, "var/lib/dpkg").absolutePath)
        .replace("/var/lib/apt/lists", File(base, "var/lib/apt/lists").absolutePath)
        .replace("/etc/apt", File(base, "etc/apt").absolutePath)
        .replace("/etc/dpkg", File(base, "etc/dpkg").absolutePath)
        .replace("/usr/bin/apt-get", File(bin, "apt-get").absolutePath)
        .replace("/usr/bin/dpkg", File(bin, "dpkg").absolutePath)
        .replace("/root/.cache/horus", cache.absolutePath) +
      "\nrun_logged() { run_log=\$1; shift; \"\$@\" >>\"\$run_log\" 2>&1; }\n"
  }

  @Test
  fun packageInstallsQueueBehindOneRootfsLock() {
    val base = Files.createTempDirectory("horus-pkg-lock").toFile()
    try {
      val events = File(base, "events")
      // Two sessions' installs must never overlap, as apt and apk fail on a held lock.
      val script = packageFixture(
        base,
        "[ \"\$1\" = update ] && exit 0\nprintf 'start %s\\n' \"\$HORUS_TEST_NAME\" >> '${events.path}'\nsleep 1\n" +
          "printf 'end %s\\n' \"\$HORUS_TEST_NAME\" >> '${events.path}'",
      ) + "pkg_install '${File(base, "log").path}' zsh\n" +
        // Released on return: the last session finds it free at once.
        "exec 7>>'${File(base, "var/lib/horus/packages.lock").path}'; flock -n 7 || echo still-locked\n"
      fun start(name: String) = ProcessBuilder("/bin/sh", "-c", script)
        .redirectErrorStream(true)
        .apply { environment()["HORUS_TEST_NAME"] = name }
        .start()
      val first = start("first")
      while (!events.isFile) Thread.sleep(20)
      val second = start("second")
      val outputs = listOf(first, second).map { process ->
        val output = process.inputStream.bufferedReader().readText()
        assertTrue("package install did not finish", process.waitFor(20, TimeUnit.SECONDS))
        assertEquals(output, 0, process.exitValue())
        output
      }

      assertEquals(listOf("start first", "end first", "start second", "end second"), events.readLines())
      assertFalse(outputs[0].contains("Waiting for another install to finish…"))
      assertTrue(outputs[1], outputs[1].contains("Waiting for another install to finish…"))
      assertFalse(outputs[1], outputs[1].contains("still-locked"))
      assertTrue(File(base, "etc/apt/apt.conf.d/99horus").readText().contains("DPkg::Lock::Timeout \"120\";"))
    } finally {
      base.deleteRecursively()
    }
  }

  @Test
  fun packageInstallFinishesAnInterruptedDpkgFirst() {
    val base = Files.createTempDirectory("horus-dpkg-recover").toFile()
    try {
      val calls = File(base, "calls")
      val script = packageFixture(
        base,
        aptBody = "printf 'apt-get %s\\n' \"\$*\" >> '${calls.path}'",
        dpkgBody = "printf 'dpkg %s\\n' \"\$*\" >> '${calls.path}'\n[ \"\$1\" != --configure ] || rm -f '${File(base, "var/lib/dpkg/updates").path}'/*",
      ) + "pkg_install '${File(base, "log").path}' zsh\n"
      fun run(): List<String> {
        calls.delete()
        val process = ProcessBuilder("/bin/sh", "-c", script).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(20, TimeUnit.SECONDS))
        assertEquals(output, 0, process.exitValue())
        return calls.readLines().map { it.substringBefore(" -o APT::Status-Fd") + if (it.contains("install")) " " + it.substringAfter("install") else "" }
      }

      // A dpkg killed mid-run leaves a journal entry in updates/.
      File(base, "var/lib/dpkg/updates/0001").apply { parentFile!!.mkdirs(); writeText("journal") }
      val recovered = run()
      assertEquals("dpkg --configure -a", recovered.first { it.startsWith("dpkg --configure") })
      assertTrue(recovered.indexOf("dpkg --configure -a") < recovered.indexOfFirst { it.contains("install") })
      assertTrue(recovered.joinToString(), recovered.any { it.contains("-y --fix-broken --no-install-recommends zsh") })
      assertTrue(File(base, "log").readText().contains("Finishing an earlier install that was interrupted…"))

      // A clean dpkg database is left alone.
      assertFalse(run().any { it.startsWith("dpkg --configure") })
    } finally {
      base.deleteRecursively()
    }
  }

  @Test
  fun remoteAccessInstallTakesThePackageLockAndDropsItBeforeDropbear() {
    val base = Files.createTempDirectory("horus-remote-lock").toFile()
    try {
      val rootfs = File(base, "rootfs").apply { mkdirs() }
      File(rootfs, "etc").mkdirs()
      val home = File(base, "home").apply { mkdirs() }
      val hostDev = File(base, "dev").apply { mkdirs() }
      listOf("null", "zero", "random", "urandom", "tty", "ptmx").forEach { File(hostDev, it).writeText("") }
      File(hostDev, "pts").mkdirs()
      val argv = launcher(base, File(base, "proc").apply { mkdirs() }, hostDev = hostDev)
        .remoteAccessLaunchSpec(rootfs, home, workspaceDir = null, guestUsername = "nefer").argv
      val lines = argv[argv.indexOf("horus-remote-access") - 1].lines().map { it.trim() }
      val take = lines.indexOf("pkg_lock_take")
      assertTrue(take > 0)
      assertTrue(take < lines.indexOf("if ! remote_install; then"))
      assertTrue(lines.indexOf("pkg_lock_release") in take..lines.indexOfFirst { it.startsWith("dropbear_script=") })
      assertTrue(lines.contains("if dpkg_interrupted; then dpkg_recover || true; fi"))
    } finally {
      base.deleteRecursively()
    }
  }

  @Test
  fun productionLoggerWatchdogDoesNotRetainThePackageLock() {
    val base = Files.createTempDirectory("horus-logger-lock").toFile()
    try {
      val rootfs = File(base, "rootfs").apply { mkdirs() }
      File(rootfs, "etc").mkdirs()
      val home = File(base, "home").apply { mkdirs() }
      val generated = launcher(base, File(base, "proc").apply { mkdirs() })
        .toolchainProvisionLaunchSpec(rootfs, home, "shell").argv.last()
      val logger = generated.substring(generated.indexOf("run_logged() {"), generated.indexOf("# The installers download silently"))
      // Let the watchdog actually start sleep before killing its shell. This
      // deterministically exercises the descendant that otherwise races startup.
      val script = packageFixture(base, "exit 0") + "\n" + logger + "\n" +
        "kill() { /bin/sleep 0.2; command kill \"\$@\"; }\n" +
        "pkg_install '${File(base, "install.log").path}' gzip\n" +
        "exec 7>>'${File(base, "var/lib/horus/packages.lock").path}'; flock -n 7\n"
      val process = ProcessBuilder("/bin/sh", "-c", script).redirectErrorStream(true).start()
      assertTrue("logger fixture timed out", process.waitFor(15, TimeUnit.SECONDS))
      val output = process.inputStream.bufferedReader().readText()
      assertEquals("watchdog's sleep kept the lock open: $output", 0, process.exitValue())
    } finally { base.deleteRecursively() }
  }

  @Test
  fun sshLazySystemPackagesEmbedAndReleaseTheSharedRootfsLock() {
    val base = Files.createTempDirectory("horus-ssh-lazy-lock").toFile()
    try {
      val rootfs = File(base, "rootfs").apply { mkdirs() }
      File(rootfs, "etc").mkdirs()
      val home = File(base, "home").apply { mkdirs() }
      val dev = File(base, "dev").apply { mkdirs() }
      listOf("null", "zero", "random", "urandom", "tty", "ptmx").forEach { File(dev, it).writeText("") }
      File(dev, "pts").mkdirs()
      val argv = launcher(base, File(base, "proc").apply { mkdirs() }, hostDev = dev)
        .remoteAccessLaunchSpec(rootfs, home, null, "validation").argv
      val script = argv[argv.indexOf("horus-remote-access") - 1]
      val helperStart = script.indexOf("system_packages() {")
      val lockStart = script.lastIndexOf("pkg_lock_file=/var/lib/horus/packages.lock", helperStart)
      assertTrue("shared lock must be inside the generated installer", lockStart > script.lastIndexOf("HORUS_INSTALLER", helperStart))
      val helper = script.substring(lockStart, script.indexOf("install_claude() {"))
        .replace("/var/lib/horus", File(base, "var/lib/horus").path)
      val bin = File(base, "bin").apply { mkdirs() }
      // Success and distinct package failures must all release the lock and
      // retain the package status, including when the caller enables errexit.
      for (packageStatus in listOf(0, 23, 127)) {
        File(bin, "apk").apply { writeText("#!/bin/sh\nexit $packageStatus\n"); setExecutable(true) }
        val process = ProcessBuilder("/bin/sh", "-c",
          "set -eu\nPATH='${bin.path}':\$PATH\n$helper\n" +
            "if system_packages busybox; then status=0; else status=\$?; fi\n" +
            "[ \"\$status\" -eq $packageStatus ] || exit 99\n" +
            "exec 7>>\"\$pkg_lock_file\"; flock -n 7\n",
        ).redirectErrorStream(true).start()
        try {
          assertTrue("package status $packageStatus timed out", process.waitFor(10, TimeUnit.SECONDS))
          assertEquals(process.inputStream.bufferedReader().readText(), 0, process.exitValue())
        } finally {
          if (process.isAlive) process.destroyForcibly()
        }
      }
    } finally { base.deleteRecursively() }
  }

  @Test
  fun runLoggedShowsTheLastLinesBeforeTheCommandExits() {
    val base = Files.createTempDirectory("horus-run-logged").toFile()
    try {
      val rootfs = File(base, "rootfs").apply { mkdirs() }
      File(rootfs, "etc").mkdirs()
      val home = File(base, "home").apply { mkdirs() }
      val hostProc = File(base, "proc").apply { mkdirs() }
      File(hostProc, "stat").writeText("live stat\n")
      File(hostProc, "loadavg").writeText("0.01 0.02 0.03 1/2 3\n")
      val launcher = ProotSessionLauncher(
        runtime = ProotRuntimeLocator.LocatedRuntime(
          prootBin = File(base, "libproot.so"),
          loaderBin = File(base, "libproot_loader.so"),
          libraryDir = base,
          prootVersion = "5.4.0",
        ),
        scratchDir = File(base, "scratch"),
        hostProcDir = hostProc,
      )
      val script = launcher.toolchainProvisionLaunchSpec(rootfs, home, TerminalRuntimeContract.TOOLCHAIN_TARGET_OPENCODE)
        .argv.first { it.contains("run_logged() {") }
      val start = script.indexOf("run_logged() {")
      val end = script.indexOf("\n", script.indexOf("return \"\$command_status\"", start) + 1)
      val runLogged = script.substring(start, script.indexOf("}", end) + 1)
      val log = File(base, "install.log")
      // The error lands just before the exit, as npm's does.
      val process = ProcessBuilder(
        "/bin/sh", "-c",
        "set -eu\n$runLogged\nrun_logged '${log.path}' sh -c 'echo first; sleep 0.3; echo \"npm error ENOTFOUND\"; exit 7' || echo status=\$?",
      ).redirectErrorStream(true).start()
      val output = process.inputStream.bufferedReader().readText()
      assertEquals(output, 0, process.waitFor())

      assertEquals(listOf("first", "npm error ENOTFOUND", "HORUS_INSTALL_COMMAND_EXIT=7", "status=7"), output.lines().filter { it.isNotBlank() })
      assertEquals("first\nnpm error ENOTFOUND\n", log.readText())
      assertFalse(File(base, "install.log.pipe").exists())
    } finally {
      base.deleteRecursively()
    }
  }

  @Test
  fun everyGeneratedGuestScriptIsValidShell() {
    val base = Files.createTempDirectory("horus-script-syntax").toFile()
    try {
      val rootfs = File(base, "rootfs").apply { mkdirs() }
      File(rootfs, "etc").mkdirs()
      val home = File(base, "home").apply { mkdirs() }
      val hostProc = File(base, "proc").apply { mkdirs() }
      File(hostProc, "stat").writeText("live stat\n")
      File(hostProc, "loadavg").writeText("0.01 0.02 0.03 1/2 3\n")
      // Remote access binds /dev/ptmx and /dev/pts, which the JVM test host may lack.
      val hostDev = File(base, "dev").apply { mkdirs() }
      listOf("null", "zero", "random", "urandom", "tty", "ptmx").forEach { File(hostDev, it).writeText("") }
      File(hostDev, "pts").mkdirs()
      val launcher = ProotSessionLauncher(
        runtime = ProotRuntimeLocator.LocatedRuntime(
          prootBin = File(base, "libproot.so"),
          loaderBin = File(base, "libproot_loader.so"),
          libraryDir = base,
          prootVersion = "5.4.0",
        ),
        scratchDir = File(base, "scratch"),
        hostProcDir = hostProc,
        hostDevDir = hostDev,
      )
      val launches = TerminalRuntimeContract.let { contract ->
        listOf(
          contract.TOOLCHAIN_TARGET_SHELL, contract.TOOLCHAIN_TARGET_GITHUB, contract.TOOLCHAIN_TARGET_CLAUDE,
          contract.TOOLCHAIN_TARGET_CODEX, contract.TOOLCHAIN_TARGET_OPENCODE,
        ).flatMap { target ->
          listOf(
            launcher.toolchainProvisionLaunchSpec(rootfs, home, target),
            launcher.toolchainSessionLaunchSpec(rootfs, home, guestUsername = "vishesh", target = target),
          )
        }
      } + launcher.remoteAccessLaunchSpec(rootfs, home, workspaceDir = null, guestUsername = "vishesh")
      for (launch in launches) {
        val script = launch.argv.first { it.contains("set -eu") }
        val process = ProcessBuilder("/bin/sh", "-n", "-c", script).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(output, 0, process.waitFor())
      }
    } finally {
      base.deleteRecursively()
    }
  }

  @Test
  fun shellReadinessRequiresEveryNativeUtilityAndTheGcompatLoader() {
    val base = Files.createTempDirectory("horus-shell-readiness").toFile()
    try {
      val rootfs = File(base, "rootfs").apply { mkdirs() }
      File(rootfs, "usr/bin/zsh").apply {
        parentFile!!.mkdirs()
        writeText("#!/bin/sh\nexit 0\n")
        setExecutable(true)
      }
      val home = File(base, "home").apply { mkdirs() }
      val hostProc = File(base, "proc").apply { mkdirs() }
      File(hostProc, "stat").writeText("live stat\n")
      File(hostProc, "loadavg").writeText("0.01 0.02 0.03 1/2 3\n")
      val launcher = launcher(base, hostProc)

      assertFalse(
        launcher.hasProvisionedToolchain(rootfs, home, TerminalRuntimeContract.TOOLCHAIN_TARGET_SHELL),
      )
      listOf("rg", "curl", "jq", "python3").forEach { name ->
        File(rootfs, "usr/bin/$name").apply {
          writeText("#!/bin/sh\nexit 0\n")
          setExecutable(true)
        }
      }
      assertFalse(
        "shell must repair a missing glibc interpreter even when every requested tool exists",
        launcher.hasProvisionedToolchain(rootfs, home, TerminalRuntimeContract.TOOLCHAIN_TARGET_SHELL),
      )

      File(rootfs, "lib/ld-linux-aarch64.so.1").apply {
        parentFile!!.mkdirs()
        writeText("gcompat loader placeholder\n")
      }
      assertTrue(
        launcher.hasProvisionedToolchain(rootfs, home, TerminalRuntimeContract.TOOLCHAIN_TARGET_SHELL),
      )
      File(rootfs, "usr/bin/rg").delete()
      assertFalse(
        "a user-home rg must not make the Alpine package set look ready",
        launcher.hasProvisionedToolchain(rootfs, home, TerminalRuntimeContract.TOOLCHAIN_TARGET_SHELL),
      )
    } finally {
      base.deleteRecursively()
    }
  }

  @Test
  fun reusesValidatedOpenCodeAndOnlyRepairsBrokenLauncherOnRepeatStarts() {
    val base = Files.createTempDirectory("horus-opencode-fast-path").toFile()
    try {
      val rootfs = File(base, "rootfs").apply { mkdirs() }
      File(rootfs, "etc").mkdirs()
      val home = File(base, "home").apply { mkdirs() }
      val hostProc = File(base, "proc").apply { mkdirs() }
      File(hostProc, "stat").writeText("live stat\n")
      File(hostProc, "loadavg").writeText("0.01 0.02 0.03 1/2 3\n")

      val launch = launcher(base, hostProc).toolchainProvisionLaunchSpec(
        rootfs,
        home,
        TerminalRuntimeContract.TOOLCHAIN_TARGET_OPENCODE,
      )
      val guestRoot = File(base, "guest-root").apply { mkdirs() }
      val guestBin = File(guestRoot, ".local/bin").apply { mkdirs() }
      val payloadDir = File(guestRoot, ".local/lib/node_modules/opencode-ai/bin").apply { mkdirs() }
      val cacheDir = File(guestRoot, ".cache/horus").apply { mkdirs() }
      val versionLog = File(base, "version.log")
      val npmLog = File(base, "npm.log")
      val packageLog = File(base, "package-manager.log")
      val entry = File(payloadDir, "opencode")
      val entryPath = entry.absolutePath
      entry.writeText("#!/bin/sh\nif [ \"${'$'}1\" = --version ]; then printf 'version\\n' >> '${versionLog.absolutePath}'; fi\n")
      entry.setExecutable(true)
      fun command(name: String, body: String) {
        File(guestBin, name).apply {
          writeText("#!/bin/sh\n$body\n")
          setExecutable(true)
        }
      }
      command("zsh", "exit 0")
      command("node", "exit 0")
      // This fast-path fixture has a ready Node/npm toolchain on either host
      // distribution. Never let a missing fixture invoke the host's apt/apk.
      for (manager in listOf("apt-get", "apk")) {
        command(manager, "printf '%s\\n' '$manager' >> '${packageLog.absolutePath}'; exit 93")
      }
      val systemPrefix = File(base, "usr/local")
      File(systemPrefix, "bin/npm").apply {
        parentFile!!.mkdirs()
        writeText("#!/bin/sh\nexit 0\n")
        setExecutable(true)
      }
      File(systemPrefix, "lib/node_modules/npm/package.json").apply {
        parentFile!!.mkdirs()
        writeText("{\"version\": \"${ProotSessionLauncher.UPSTREAM_NPM_VERSION}\"}\n")
      }
      command(
        "npm",
        "printf 'npm\\n' >> '${npmLog.absolutePath}'\n" +
          "[ ! -e '${entryPath}' ] || exit 92\nmkdir -p '${payloadDir.absolutePath}'\n" +
          "cat > '${entryPath}' <<'HORUS_TEST_ENTRY'\n#!/bin/sh\nif [ \"${'$'}1\" = --version ]; then printf 'version\\n' >> '${versionLog.absolutePath}'; fi\nHORUS_TEST_ENTRY\nchmod u+rx '${entryPath}'",
      )
      val launcher = File(guestBin, "opencode")
      val expectedLauncher = "#!/bin/sh\nexec \"${'$'}HOME/.local/lib/node_modules/opencode-ai/bin/opencode\" \"${'$'}@\"\n"
      launcher.writeText(expectedLauncher)
      launcher.setExecutable(true)
      Files.setLastModifiedTime(launcher.toPath(), FileTime.fromMillis(1_000_000_000_000L))
      File(guestBin, "stat").apply {
        writeText(
          "#!/bin/sh\n" +
            "[ \"${'$'}1\" = -L ] && [ \"${'$'}2\" = -c ] && [ \"${'$'}3\" = '%s:%Y:%i:%d' ] || exit 97\n" +
            "printf '%s\\n' \"${'$'}HORUS_TEST_STAMP\"\n",
        )
        setExecutable(true)
      }
      File(cacheDir, "opencode.ready").writeText("ready:123:456:789:1\n")

      val script = launch.argv.last().replace("/root", guestRoot.absolutePath)
        .replace("/etc/apt", File(base, "etc/apt").absolutePath)
        .replace("/etc/dpkg", File(base, "etc/dpkg").absolutePath)
        .replace("/usr/local", systemPrefix.absolutePath)
        // Exercise Debian/npm detection even when the test host uses apk/pacman.
        .replace("/usr/bin/apt-get", File(guestBin, "apt-get").absolutePath)
      fun runProvision(): String {
        val output = File.createTempFile("provision-", ".log", base)
        val process = ProcessBuilder("/bin/sh", "-c", script)
          .redirectErrorStream(true)
          .redirectOutput(output)
          .apply {
            environment()["HORUS_TOOLCHAIN_TARGET"] = "opencode"
            environment()["HORUS_TEST_STAMP"] = "123:456:789:1"
            environment()["HORUS_TEST_ENTRY"] = entryPath
          }
          .start()
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
          process.destroyForcibly()
          throw AssertionError("OpenCode provisioning exceeded the bounded host test timeout")
        }
        assertEquals(output.readText(), 0, process.exitValue())
        assertFalse("fast-path provisioning must not invoke apt/apk", packageLog.exists())
        return output.readText()
      }

      val firstOutput = runProvision()
      assertTrue(firstOutput.contains("HORUS_INSTALL_TARGET=opencode"))
      assertTrue(firstOutput.contains("HORUS_INSTALL_STAGE=ready"))
      assertTrue(firstOutput.contains("HORUS_TOOLCHAIN_READY"))
      for (internalName in listOf("HORUS_TOOLCHAIN_TARGET", "HORUS_BOOTSTRAP", "HORUS_SKIP_TOOLCHAIN_PROVISION", "HORUS_TEST_")) {
        assertFalse(internalName, firstOutput.contains(internalName))
      }
      assertEquals("ready:123:456:789:1\n", File(cacheDir, "opencode.ready").readText())
      assertEquals(0L, versionLog.length())
      assertEquals(0L, npmLog.length())
      assertEquals(expectedLauncher, launcher.readText())
      assertEquals(1_000_000_000_000L, Files.getLastModifiedTime(launcher.toPath()).toMillis())

      launcher.writeText("#!/bin/sh\nexit 1\n")
      Files.setLastModifiedTime(launcher.toPath(), FileTime.fromMillis(1_000_000_000_000L))
      val repairOutput = runProvision()
      assertTrue(repairOutput.contains("HORUS_TOOLCHAIN_READY"))
      assertEquals(expectedLauncher, launcher.readText())
      assertEquals(0L, versionLog.length())
      assertEquals(0L, npmLog.length())
      assertTrue(Files.getLastModifiedTime(launcher.toPath()).toMillis() > 1_000_000_000_000L)

      File(cacheDir, "opencode.ready").delete()
      entry.delete()
      val installOutput = runProvision()
      assertTrue(installOutput.contains("HORUS_TOOLCHAIN_READY"))
      assertEquals("npm\n", npmLog.readText())
      assertEquals("version\n", versionLog.readText())
      assertTrue(entry.isFile && entry.canExecute())
      val config = File(guestRoot, ".config/opencode/user.json").apply {
        parentFile!!.mkdirs(); writeText("preserve user config")
      }
      entry.writeText("#!/bin/sh\nexit 91\n")
      File(cacheDir, "opencode.ready").writeText("ready:old-payload\n")
      assertTrue(runProvision().contains("HORUS_TOOLCHAIN_READY"))
      assertEquals("npm\nnpm\n", npmLog.readText())
      assertEquals("version\nversion\n", versionLog.readText())
      assertEquals("preserve user config", config.readText())

      // A working payload without its marker is restamped offline, never removed.
      File(cacheDir, "opencode.ready").delete()
      command("npm", "printf 'npm\\n' >> '${npmLog.absolutePath}'\nexit 1")
      assertTrue(runProvision().contains("HORUS_TOOLCHAIN_READY"))
      assertEquals("npm\nnpm\n", npmLog.readText())
      assertTrue(entry.isFile && entry.canExecute())
      assertEquals("ready:123:456:789:1\n", File(cacheDir, "opencode.ready").readText())
    } finally {
      base.deleteRecursively()
    }
  }

  @Test
  fun rejectsOpenCodeReadinessWhenPayloadMetadataChanges() {
    val base = Files.createTempDirectory("horus-opencode-readiness").toFile()
    try {
      val rootfs = File(base, "rootfs").apply { mkdirs() }
      File(rootfs, "bin/zsh").apply { requireNotNull(parentFile).mkdirs(); writeText("zsh"); setExecutable(true) }
      File(rootfs, "usr/bin/node").apply { requireNotNull(parentFile).mkdirs(); writeText("node"); setExecutable(true) }
      File(rootfs, "usr/bin/npm").apply { writeText("npm"); setExecutable(true) }
      val home = File(base, "home").apply { mkdirs() }
      val entry = File(home, ".local/lib/node_modules/opencode-ai/bin/opencode")
      val payloadTarget = File(home, ".local/lib/node_modules/opencode-ai/bin/opencode-payload").apply {
        requireNotNull(parentFile).mkdirs()
        writeBytes(byteArrayOf(0x7f, 0x45, 0x4c, 0x46, 0x01))
        setExecutable(true)
      }
      Files.createSymbolicLink(entry.toPath(), payloadTarget.toPath())
      File(home, ".local/bin/opencode").apply {
        requireNotNull(parentFile).mkdirs()
        writeText("#!/bin/sh\nexec \"\$HOME/.local/lib/node_modules/opencode-ai/bin/opencode\" \"\$@\"\n")
        setExecutable(true)
      }
      val attributes = Files.readAttributes(entry.toPath(), "unix:size,lastModifiedTime,ino,dev")
      val stamp = "${attributes["size"]}:${(attributes["lastModifiedTime"] as FileTime).toMillis() / 1000}:${attributes["ino"]}:${attributes["dev"]}"
      File(home, ".cache/horus/opencode.ready").apply {
        requireNotNull(parentFile).mkdirs()
        writeText("ready:$stamp\n")
      }
      val hostProc = File(base, "proc").apply { mkdirs() }
      File(hostProc, "stat").writeText("live stat\n")
      File(hostProc, "loadavg").writeText("0.01 0.02 0.03 1/2 3\n")
      val launcher = launcher(base, hostProc)

      assertTrue(launcher.hasProvisionedToolchain(rootfs, home, TerminalRuntimeContract.TOOLCHAIN_TARGET_OPENCODE))
      // Debian's npm is npm's own release in /usr/local/bin.
      val debianNpm = File(rootfs, "usr/local/bin/npm").apply { requireNotNull(parentFile).mkdirs() }
      assertTrue(File(rootfs, "usr/bin/npm").renameTo(debianNpm))
      assertTrue(launcher.hasProvisionedToolchain(rootfs, home, TerminalRuntimeContract.TOOLCHAIN_TARGET_OPENCODE))
      assertTrue(debianNpm.delete())
      assertFalse(launcher.hasProvisionedToolchain(rootfs, home, TerminalRuntimeContract.TOOLCHAIN_TARGET_OPENCODE))
      File(rootfs, "usr/bin/npm").apply { writeText("npm"); setExecutable(true) }
      val currentMtime = Files.getLastModifiedTime(payloadTarget.toPath()).toMillis()
      Files.setLastModifiedTime(payloadTarget.toPath(), FileTime.fromMillis(currentMtime + 2_000))
      assertFalse(launcher.hasProvisionedToolchain(rootfs, home, TerminalRuntimeContract.TOOLCHAIN_TARGET_OPENCODE))
    } finally {
      base.deleteRecursively()
    }
  }

  @Test
  fun provisionsGitHubCliAsItsOwnLazyTarget() {
    val base = Files.createTempDirectory("horus-github-provision").toFile()
    try {
      val rootfs = File(base, "rootfs").apply { mkdirs() }
      File(rootfs, "etc").mkdirs()
      val home = File(base, "home").apply { mkdirs() }
      val hostProc = File(base, "proc").apply { mkdirs() }
      File(hostProc, "stat").writeText("live stat\n")
      File(hostProc, "loadavg").writeText("0.01 0.02 0.03 1/2 3\n")
      val launcher = launcher(base, hostProc)
      val launch = launcher.toolchainProvisionLaunchSpec(
        rootfs,
        home,
        TerminalRuntimeContract.TOOLCHAIN_TARGET_GITHUB,
      )

      assertTrue(launch.argv.last().contains("github-cli"))
      assertTrue(launch.argv.last().contains("github_failed"))
      assertTrue(launch.environment.contains("HORUS_TOOLCHAIN_TARGET=github"))

      File(rootfs, "usr/bin").mkdirs()
      File(rootfs, "usr/bin/zsh").apply { writeText("#!/bin/sh\n"); setExecutable(true) }
      File(rootfs, "usr/bin/gh").apply { writeText("#!/bin/sh\n"); setExecutable(true) }
      assertFalse(launcher.hasProvisionedToolchain(rootfs, home, TerminalRuntimeContract.TOOLCHAIN_TARGET_GITHUB))
      File(home, ".cache/horus").mkdirs()
      File(home, ".cache/horus/github.ready").writeText("ready\n")
      assertTrue(launcher.hasProvisionedToolchain(rootfs, home, TerminalRuntimeContract.TOOLCHAIN_TARGET_GITHUB))
    } finally {
      base.deleteRecursively()
    }
  }

  @Test
  fun streamsHarnessInstallBeforeHandingTheSessionToTheConfiguredUser() {
    val base = Files.createTempDirectory("horus-visible-install").toFile()
    try {
      val rootfs = File(base, "rootfs").apply { mkdirs() }
      File(rootfs, "etc").mkdirs()
      val home = File(base, "home").apply { mkdirs() }
      val workspace = File(base, "workspace").apply { mkdirs() }
      val hostProc = File(base, "proc").apply { mkdirs() }
      File(hostProc, "stat").writeText("live stat\n")
      File(hostProc, "loadavg").writeText("0.01 0.02 0.03 1/2 3\n")

      val launch = launcher(base, hostProc).toolchainSessionLaunchSpec(
        rootfsDir = rootfs,
        guestHomeDir = home,
        workspaceDir = workspace,
        guestUsername = "luna",
        target = TerminalRuntimeContract.TOOLCHAIN_TARGET_GITHUB,
        sessionCommand = "stty echo; exec zsh -lic 'gh auth login'",
      )
      val command = launch.argv.takeLast(9)

      assertTrue(launch.argv.contains("-0"))
      assertFalse(launch.argv.contains("-i"))
      assertTrue(launch.argv.windowed(2).any { it == listOf("-b", "${home.canonicalPath}:/root") })
      assertTrue(launch.argv.windowed(2).any { it == listOf("-b", "${home.canonicalPath}:/home/luna") })
      assertEquals("/workspace", launch.argv[launch.argv.indexOf("-w") + 1])
      assertEquals(ProotSessionLauncher.GUEST_SHELL, command[0])
      assertEquals("-c", command[1])
      assertTrue(command[2].contains("run_logged"))
      assertTrue(command[2].contains("HORUS_TOOLCHAIN_READY"))
      assertTrue(command[2].contains("su -p -c"))
      assertEquals("luna", command[4])
      assertEquals("1000", command[5])
      assertEquals("1000", command[6])
      assertEquals("stty echo; exec zsh -lic 'gh auth login'", command[7])
      assertEquals("/workspace", command[8])
      assertTrue(launch.environment.contains("HOME=/root"))
      assertTrue(launch.environment.contains("HORUS_TOOLCHAIN_TARGET=github"))
    } finally {
      base.deleteRecursively()
    }
  }

  @Test
  fun rejectsClaudeRootAbsoluteSymlinkForNonRootSessions() {
    val base = Files.createTempDirectory("horus-claude-launcher").toFile()
    try {
      val rootfs = File(base, "rootfs").apply { mkdirs() }
      File(rootfs, "etc").mkdirs()
      File(rootfs, "bin/zsh").apply { requireNotNull(parentFile).mkdirs(); writeText("zsh"); setExecutable(true) }
      val home = File(base, "home").apply { mkdirs() }
      val claudeBin = File(home, ".local/bin/claude").apply { requireNotNull(parentFile).mkdirs() }
      Files.createSymbolicLink(
        claudeBin.toPath(),
        File("/root/.local/share/claude/versions/2.1.267/claude").toPath(),
      )
      File(home, ".cache/horus/claude.ready").apply { requireNotNull(parentFile).mkdirs(); writeText("ready\n") }
      val hostProc = File(base, "proc").apply { mkdirs() }
      File(hostProc, "stat").writeText("live stat\n")
      File(hostProc, "loadavg").writeText("0.01 0.02 0.03 1/2 3\n")

      assertFalse(
        launcher(base, hostProc).hasProvisionedToolchain(
          rootfs,
          home,
          TerminalRuntimeContract.TOOLCHAIN_TARGET_CLAUDE,
        ),
      )
      claudeBin.delete()
      claudeBin.writeText("#!/bin/sh\nexec \"\$HOME/.local/share/claude/versions/2.1.267/claude\" \"\$@\"\n")
      claudeBin.setExecutable(true)
      assertTrue(
        launcher(base, hostProc).hasProvisionedToolchain(
          rootfs,
          home,
          TerminalRuntimeContract.TOOLCHAIN_TARGET_CLAUDE,
        ),
      )
    } finally {
      base.deleteRecursively()
    }
  }

  @Test
  fun rejectsCodexLauncherWhenPersistentPayloadIsMissingOrCorrupt() {
    val base = Files.createTempDirectory("horus-codex-readiness").toFile()
    try {
      val rootfs = File(base, "rootfs").apply { mkdirs() }
      File(rootfs, "bin/zsh").apply { requireNotNull(parentFile).mkdirs(); writeText("zsh"); setExecutable(true) }
      File(rootfs, "usr/bin/node").apply { requireNotNull(parentFile).mkdirs(); writeText("node"); setExecutable(true) }
      File(rootfs, "usr/bin/npm").apply { writeText("npm"); setExecutable(true) }
      val home = File(base, "home").apply { mkdirs() }
      File(home, ".local/bin/codex").apply {
        requireNotNull(parentFile).mkdirs()
        writeText("#!/bin/sh\nexec /usr/bin/node \"\$HOME/.local/lib/node_modules/@openai/codex/bin/codex.js\" \"\$@\"\n")
        setExecutable(true)
      }
      File(home, ".cache/horus/codex.ready").apply { requireNotNull(parentFile).mkdirs(); writeText("ready\n") }
      val entry = File(home, ".local/lib/node_modules/@openai/codex/bin/codex.js").apply {
        requireNotNull(parentFile).mkdirs()
        writeText("#!/bin/sh\n")
        setExecutable(true)
      }
      val launcher = launcher(base, File(base, "proc").apply { mkdirs() })

      assertFalse(launcher.hasProvisionedToolchain(rootfs, home, TerminalRuntimeContract.TOOLCHAIN_TARGET_CODEX))
      entry.writeText("#!/usr/bin/env node\n")
      // BusyBox ps (a symlink) cannot report start times for Codex's server.
      val ps = File(rootfs, "bin/ps")
      Files.createSymbolicLink(ps.toPath(), File(rootfs, "bin/busybox").toPath())
      assertFalse(launcher.hasProvisionedToolchain(rootfs, home, TerminalRuntimeContract.TOOLCHAIN_TARGET_CODEX))
      ps.delete()
      ps.writeText("procps")
      assertFalse(launcher.hasProvisionedToolchain(rootfs, home, TerminalRuntimeContract.TOOLCHAIN_TARGET_CODEX))
      val native = File(home, ".local/lib/node_modules/@openai/codex/node_modules/@openai/codex-linux-arm64/vendor/aarch64-unknown-linux-musl/bin/codex")
      requireNotNull(native.parentFile).mkdirs()
      File(home, ".local/lib/node_modules/@openai/codex/package.json").writeText("""{"version":"0.115.0","optionalDependencies":{"@openai/codex-linux-arm64":"npm:@openai/codex@0.115.0-linux-arm64"}}""")
      File(home, ".local/lib/node_modules/@openai/codex/node_modules/@openai/codex-linux-arm64/package.json").writeText("""{"version":"0.115.0-linux-arm64"}""")
      native.writeText("broken")
      native.setExecutable(true)
      assertFalse(launcher.hasProvisionedToolchain(rootfs, home, TerminalRuntimeContract.TOOLCHAIN_TARGET_CODEX))
      val header = ByteArray(64)
      header[0] = 0x7f; header[1] = 0x45; header[2] = 0x4c; header[3] = 0x46
      header[4] = 2; header[5] = 1; header[18] = 0xb7.toByte()
      native.writeBytes(header)
      val marker = File(home, ".cache/horus/codex.ready")
      // A legacy marker never proves the native binary was run successfully.
      assertFalse(launcher.hasProvisionedToolchain(rootfs, home, TerminalRuntimeContract.TOOLCHAIN_TARGET_CODEX))
      marker.writeText("ready:${launcher.codexPayloadStamp(home)}\n")
      assertTrue(launcher.hasProvisionedToolchain(rootfs, home, TerminalRuntimeContract.TOOLCHAIN_TARGET_CODEX))
      native.appendBytes(byteArrayOf(0))
      assertFalse(launcher.hasProvisionedToolchain(rootfs, home, TerminalRuntimeContract.TOOLCHAIN_TARGET_CODEX))
      native.delete()
      assertFalse(launcher.hasProvisionedToolchain(rootfs, home, TerminalRuntimeContract.TOOLCHAIN_TARGET_CODEX))
    } finally {
      base.deleteRecursively()
    }
  }

  @Test
  fun putsTheFixedSessionCommandInTheNativeShellArgv() {
    val base = Files.createTempDirectory("horus-session-command").toFile()
    try {
      val rootfs = File(base, "rootfs").apply { mkdirs() }
      File(rootfs, "etc").mkdirs()
      val home = File(base, "home").apply { mkdirs() }
      val hostProc = File(base, "proc").apply { mkdirs() }
      File(hostProc, "stat").writeText("live stat\n")
      File(hostProc, "loadavg").writeText("0.01 0.02 0.03 1/2 3\n")

      val launch = launcher(base, hostProc).interactiveShellLaunchSpec(
        rootfs,
        home,
        sessionCommand = "stty echo; exec zsh -lic 'codex'",
      )

      assertEquals(
        listOf(
          ProotSessionLauncher.GUEST_SHELL,
          "-c",
          "stty echo 2>/dev/null || true; unset HORUS_BOOTSTRAP; exec ${ProotSessionLauncher.GUEST_SHELL} -c \"\$1\"",
          "horus-session",
          "stty echo; exec zsh -lic 'codex'",
        ),
        launch.argv.takeLast(5),
      )
    } finally {
      base.deleteRecursively()
    }
  }

  @Test
  fun codexProfileRequestsUnsandboxedModeWithoutChangingApprovalArguments() {
    val base = Files.createTempDirectory("horus-codex-wrapper").toFile()
    try {
      val rootfs = File(base, "rootfs").apply { mkdirs() }
      File(rootfs, "etc").mkdirs()
      val home = File(base, "home").apply { mkdirs() }
      val hostProc = File(base, "proc").apply { mkdirs() }
      File(hostProc, "stat").writeText("live stat\n")
      File(hostProc, "loadavg").writeText("0.01 0.02 0.03 1/2 3\n")
      val bin = File(base, "bin").apply { mkdirs() }
      File(bin, "codex").apply {
        writeText("#!/bin/sh\nprintf '%s\\n' \"${'$'}@\"\n")
        assertTrue(setExecutable(true))
      }
      val launcher = launcher(base, hostProc)
      launcher.interactiveShellLaunchSpec(rootfs, home)

      val process = ProcessBuilder("/bin/sh", "-c", ". ./.profile; codex exec inspect")
        .directory(home)
        .apply { environment()["PATH"] = bin.absolutePath }
        .redirectErrorStream(true)
        .start()
      val output = process.inputStream.bufferedReader().readLines()
      assertEquals(0, process.waitFor())
      assertEquals(listOf("--sandbox", "danger-full-access", "--no-daemon", "exec", "inspect"), output)
    } finally {
      base.deleteRecursively()
    }
  }

  @Test
  fun readableProcFilesStayLiveWithoutCompatibilityBinds() {
    val base = Files.createTempDirectory("horus-readable-proc").toFile()
    try {
      val rootfs = File(base, "rootfs").apply { mkdirs() }
      val home = File(base, "home").apply { mkdirs() }
      val hostProc = File(base, "proc").apply { mkdirs() }
      File(hostProc, "stat").writeText("live stat\n")
      File(hostProc, "meminfo").writeText("live meminfo\n")
      File(hostProc, "loadavg").writeText("0.01 0.02 0.03 1/2 3\n")

      val launch = launcher(base, hostProc).interactiveShellLaunchSpec(rootfs, home)

      assertTrue(
        launch.argv.windowed(2).none { it[0] == "-b" && it[1].contains("proc-compat") },
      )
      assertEquals("live stat\n", File(hostProc, "stat").readText())
      assertEquals("0.01 0.02 0.03 1/2 3\n", File(hostProc, "loadavg").readText())
    } finally {
      base.deleteRecursively()
    }
  }

  @Test
  fun bindsAndroidDnsSnapshotIntoGuestResolverPath() {
      val base = Files.createTempDirectory("horus-launcher-dns").toFile()
    try {
      val rootfs = File(base, "rootfs").apply { mkdirs() }
      File(rootfs, "etc").mkdirs()
      val home = File(base, "home").apply { mkdirs() }
      val hostProc = File(base, "proc").apply { mkdirs() }
      File(hostProc, "stat").writeText("live stat\n")
      File(hostProc, "loadavg").writeText("0.01 0.02 0.03 1/2 3\n")
      val launch = ProotSessionLauncher(
        runtime = ProotRuntimeLocator.LocatedRuntime(
          prootBin = File(base, "libproot.so"),
          loaderBin = File(base, "libproot_loader.so"),
          libraryDir = base,
          prootVersion = "5.4.0",
        ),
        scratchDir = File(base, "scratch"),
        hostProcDir = hostProc,
        dnsServersProvider = { listOf("192.0.2.53", "2001:db8::53", "192.0.2.53") },
      ).interactiveShellLaunchSpec(rootfs, home)

      assertEquals(
        "nameserver 192.0.2.53\nnameserver 2001:db8::53\n",
        File(rootfs, "etc/resolv.conf").readText(),
      )
    } finally {
      base.deleteRecursively()
    }
  }

  @Test
  fun networkChangeRewritesResolverAndEmptyListKeepsIt() {
    val base = Files.createTempDirectory("horus-resolver-refresh").toFile()
    try {
      val rootfs = File(base, "rootfs").apply { mkdirs() }
      val resolver = File(rootfs, "etc/resolv.conf")
      File(rootfs, "etc").mkdirs()
      resolver.writeText("nameserver 192.168.1.1\n")

      ProotSessionLauncher.writeResolverFile(rootfs, listOf("2001:db8::53", " 192.0.2.53 "))
      assertEquals("nameserver 2001:db8::53\nnameserver 192.0.2.53\n", resolver.readText())

      ProotSessionLauncher.writeResolverFile(rootfs, emptyList())
      assertEquals("nameserver 2001:db8::53\nnameserver 192.0.2.53\n", resolver.readText())
    } finally {
      base.deleteRecursively()
    }
  }

  @Test
  fun repeatedLaunchesRepairStaleFallbacksAndDropOverlayWhenHostFileBecomesReadable() {
    val base = Files.createTempDirectory("horus-repair-proc").toFile()
    try {
      val rootfs = File(base, "rootfs").apply { mkdirs() }
      val home = File(base, "home").apply { mkdirs() }
      val hostProc = File(base, "proc").apply { mkdirs() }
      File(hostProc, "stat").mkdirs()
      val scratch = File(base, "scratch")
      val launcher = launcher(base, hostProc, scratch)
      val first = launcher.interactiveShellLaunchSpec(rootfs, home)
      val statBind = first.argv.windowed(2).single { it[0] == "-b" && it[1].endsWith(":/proc/stat") }
      val fallback = File(statBind[1].removeSuffix(":/proc/stat"))
      fallback.writeText("stale\n")

      launcher.interactiveShellLaunchSpec(rootfs, home)
      assertEquals(
        ProotSessionLauncher.procGlobalFiles(0L).first().second,
        fallback.readText(),
      )

      assertTrue(File(hostProc, "stat").delete())
      File(hostProc, "stat").writeText("live stat\n")
      val readable = launcher.interactiveShellLaunchSpec(rootfs, home)
      assertTrue(
        readable.argv.windowed(2).none {
          it[0] == "-b" && it[1].endsWith(":/proc/stat") && it[1].contains("proc-compat")
        },
      )
      assertEquals("live stat\n", File(hostProc, "stat").readText())
    } finally {
      base.deleteRecursively()
    }
  }

  @Test
  fun seccompAccelerationIsOptInAndOnlyDropsTheNoSeccompVariable() {
    val base = Files.createTempDirectory("horus-launcher-seccomp").toFile()
    try {
      val rootfs = File(base, "rootfs").apply { mkdirs() }
      File(rootfs, "etc").mkdirs()
      val home = File(base, "home").apply { mkdirs() }
      val hostProc = File(base, "proc").apply { mkdirs() }
      val runtime = ProotRuntimeLocator.LocatedRuntime(
        prootBin = File(base, "libproot.so"),
        loaderBin = File(base, "libproot_loader.so"),
        libraryDir = base,
        prootVersion = "5.4.0",
      )
      val stable = ProotSessionLauncher(runtime, File(base, "scratch"), hostProc)
        .interactiveShellLaunchSpec(rootfs, home)
      val accelerated = ProotSessionLauncher(runtime, File(base, "scratch"), hostProc, seccompAcceleration = true)
        .interactiveShellLaunchSpec(rootfs, home)

      assertTrue(stable.environment.contains("PROOT_NO_SECCOMP=1"))
      assertFalse(accelerated.environment.any { it.startsWith("PROOT_NO_SECCOMP") })
      assertEquals(stable.argv, accelerated.argv)
      assertEquals(stable.environment - "PROOT_NO_SECCOMP=1", accelerated.environment)
    } finally {
      base.deleteRecursively()
    }
  }

  @Test
  fun givesOnlyTheProfileUsersSessionsARootHelperForSudo() {
    val base = Files.createTempDirectory("horus-root-helper").toFile()
    try {
      val rootfs = File(base, "rootfs").apply { mkdirs() }
      File(rootfs, "etc").mkdirs()
      val home = File(base, "home").apply { mkdirs() }
      val hostProc = File(base, "proc").apply { mkdirs() }
      File(hostProc, "stat").writeText("cpu 1\n")
      File(hostProc, "loadavg").writeText("0 0 0\n")
      val hostDev = File(base, "dev").apply { mkdirs() }
      listOf("null", "zero", "random", "urandom", "tty", "ptmx").forEach { File(hostDev, it).writeText("") }
      File(hostDev, "pts").mkdirs()
      val launcher = ProotSessionLauncher(
        runtime = ProotRuntimeLocator.LocatedRuntime(
          prootBin = File(base, "libproot.so"),
          loaderBin = File(base, "libproot_loader.so"),
          libraryDir = base,
          prootVersion = "5.4.0",
        ),
        scratchDir = File(base, "scratch"),
        hostProcDir = hostProc,
        hostDevDir = hostDev,
      )
      val sudo = File(rootfs, ProotSessionLauncher.GUEST_SUDO_PATH.trimStart('/'))
      val helper = File(rootfs, ProotSessionLauncher.GUEST_ROOT_HELPER_PATH.trimStart('/'))

      val harness = launcher.toolchainSessionLaunchSpec(
        rootfs, home, guestUsername = "harness_codex", guestUid = 61_002, target = TerminalRuntimeContract.TOOLCHAIN_TARGET_CODEX,
      )
      assertFalse(harness.environment.contains("HORUS_ROOT_HELPER=1"))
      assertFalse(harness.argv.contains("${File(hostDev, "pts").absolutePath}:/dev/pts"))
      assertFalse(sudo.exists())

      val claudeHome = File(base, "harness-homes/claude").apply { mkdirs() }
      val own = launcher.toolchainSessionLaunchSpec(
        rootfs, home, guestUsername = "nefer", target = TerminalRuntimeContract.TOOLCHAIN_TARGET_SHELL, rootHelper = true, provision = false,
        harnessHomes = listOf(ProotSessionLauncher.HarnessHome(TerminalRuntimeContract.TOOLCHAIN_TARGET_CLAUDE, "harness_claude", claudeHome)),
      )
      // The user's own terminal runs the app's harnesses, as SSH does.
      assertTrue(own.argv.windowed(2).any { it == listOf("-b", "${claudeHome.canonicalPath}:/home/harness_claude") })
      assertEquals("claude=/home/harness_claude\n", File(rootfs, ProotSessionLauncher.GUEST_AGENT_HOMES_PATH.trimStart('/')).readText())
      assertTrue(own.environment.contains("HORUS_ROOT_HELPER=1"))
      assertTrue(own.environment.contains("HORUS_SKIP_TOOLCHAIN_PROVISION=1"))
      val binds = own.argv.windowed(2).filter { it[0] == "-b" }.map { it[1] }
      assertTrue(binds.contains("${File(hostDev, "ptmx").absolutePath}:/dev/ptmx"))
      assertTrue(binds.contains("${File(hostDev, "pts").absolutePath}:/dev/pts"))
      val script = own.argv[own.argv.size - 7]
      assertTrue(script.contains("${ProotSessionLauncher.GUEST_ROOT_HELPER_PATH} \"${'$'}HORUS_SUDO_SOCKET\" \"${'$'}session_uid\""))
      assertTrue(script.indexOf("HORUS_SUDO_SOCKET") < script.indexOf("setpriv --reuid"))
      for (file in listOf(sudo, helper)) {
        assertTrue("${file.name} is not executable", file.isFile && file.canExecute())
        assertTrue(file.readText().startsWith("#!/usr/bin/python3\n"))
      }

      val remote = launcher.remoteAccessLaunchSpec(rootfs, home, workspaceDir = null, guestUsername = "nefer").argv
      assertTrue(remote[remote.indexOf("horus-remote-access") - 1].contains("${ProotSessionLauncher.GUEST_ROOT_HELPER_PATH} /run/horus-sudo/ssh"))

      // A stale copy is replaced; a symlink planted on the way is never followed.
      sudo.writeText("old")
      launcher.toolchainSessionLaunchSpec(rootfs, home, guestUsername = "nefer", target = TerminalRuntimeContract.TOOLCHAIN_TARGET_SHELL, rootHelper = true)
      assertTrue(sudo.readText().startsWith("#!/usr/bin/python3\n"))
      val outside = File(base, "outside").apply { mkdirs() }
      File(rootfs, "usr/local/bin").deleteRecursively()
      Files.createSymbolicLink(File(rootfs, "usr/local/bin").toPath(), outside.toPath())
      assertThrows(IllegalStateException::class.java) {
        launcher.toolchainSessionLaunchSpec(rootfs, home, guestUsername = "nefer", target = TerminalRuntimeContract.TOOLCHAIN_TARGET_SHELL, rootHelper = true)
      }
      assertTrue(outside.listFiles().isNullOrEmpty())
    } finally {
      base.deleteRecursively()
    }
  }

  @Test
  fun remoteAccessHeredocsTerminateBeforeFollowingCommands() {
    val base = Files.createTempDirectory("horus-remote-heredocs").toFile()
    try {
      val rootfs = File(base, "rootfs").apply { mkdirs() }
      File(rootfs, "etc").mkdirs()
      // SSH needs /dev/pts, which a macOS host lacks.
      val hostDev = File(base, "dev").apply { mkdirs() }
      listOf("null", "zero", "random", "urandom", "tty", "ptmx").forEach { File(hostDev, it).writeText("") }
      File(hostDev, "pts").mkdirs()
      val launch = launcher(base, File(base, "proc").apply { mkdirs() }, hostDev = hostDev).remoteAccessLaunchSpec(
        rootfs,
        File(base, "home").apply { mkdirs() },
        null,
        "luna",
      )
      val script = launch.argv[launch.argv.indexOf("horus-remote-access") - 1]
      val lines = script.lines()
      val followingCommands = mapOf(
        "HORUS_SHELL" to listOf("chmod 755 \"\$login_shell.tmp\"", "mv -f \"\$login_shell.tmp\" \"\$login_shell\""),
        "HORUS_INSTALLER" to listOf("chmod 755 \"\$agent_installer.tmp\"", "mv -f \"\$agent_installer.tmp\" \"\$agent_installer\""),
        "HORUS_ZSH" to listOf("mv -f /etc/zsh/zshrc.d/horus-ssh.zsh.tmp /etc/zsh/zshrc.d/horus-ssh.zsh"),
      )
      for ((delimiter, following) in followingCommands) {
        val opener = lines.indices.filter { lines[it].endsWith("<<'$delimiter'") }
        assertEquals("one opener for $delimiter", 1, opener.size)
        val terminator = lines.indices.filter { lines[it].trim() == delimiter }
        assertEquals("one terminator for $delimiter", 1, terminator.size)
        val start = opener.single()
        val end = terminator.single()
        assertEquals("$delimiter must close at column zero", delimiter, lines[end])
        assertTrue("$delimiter closes after its opener", end > start)
        assertEquals(following, lines.subList(end + 1, end + 1 + following.size))
        val body = lines.subList(start + 1, end).joinToString("\n") + "\n"
        if (delimiter == "HORUS_INSTALLER") {
          assertTrue("shared Codex functions must survive interpolation", body.contains(ProotSessionLauncher.CODEX_INSTALL_SCRIPT))
          assertTrue(body.contains("install_codex() {"))
          assertTrue(body.contains("exec \"\$launcher\" \"\$@\""))
        }
        // Execute only the rendered cat/chmod/mv fragment in a temporary directory.
        // Never run remote provisioning or the generated installers themselves.
        val outputFile = File(base, delimiter)
        val fragment = lines.subList(start, end + 1 + following.size).joinToString("\n")
          .replace("/etc/zsh/zshrc.d/horus-ssh.zsh", outputFile.absolutePath)
        val fixture = "set -eu\nlogin_shell='${outputFile.absolutePath}'\n" +
          "agent_installer='${outputFile.absolutePath}'\n$fragment\nprintf 'after:$delimiter\\n'\n"
        val output = File(base, "$delimiter.log")
        val process = ProcessBuilder("/bin/sh", "-c", fixture)
          .redirectErrorStream(true).redirectOutput(output).start()
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
          process.destroyForcibly()
          throw AssertionError("$delimiter fixture exceeded the bounded host test timeout")
        }
        assertEquals(output.readText(), 0, process.exitValue())
        assertEquals("after:$delimiter\n", output.readText())
        assertEquals("subsequent commands must not enter $delimiter payload", body, outputFile.readText())
        if (delimiter != "HORUS_ZSH") {
          val syntax = ProcessBuilder("/bin/sh", "-n", outputFile.absolutePath)
            .redirectErrorStream(true).redirectOutput(output).start()
          assertTrue(syntax.waitFor(10, TimeUnit.SECONDS))
          assertEquals(output.readText(), 0, syntax.exitValue())
        }
      }
    } finally {
      base.deleteRecursively()
    }
  }

  private fun launcher(base: File, hostProc: File, scratch: File = File(base, "scratch"), hostDev: File = File("/dev")): ProotSessionLauncher =
    ProotSessionLauncher(
      runtime = ProotRuntimeLocator.LocatedRuntime(
        prootBin = File(base, "libproot.so"),
        loaderBin = File(base, "libproot_loader.so"),
        libraryDir = base,
        prootVersion = "5.4.0",
      ),
      scratchDir = scratch,
      hostProcDir = hostProc,
      hostDevDir = hostDev,
    )
}
