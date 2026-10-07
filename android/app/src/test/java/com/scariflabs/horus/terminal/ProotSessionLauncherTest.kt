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
      assertTrue(launch.argv.last().contains("--no-progress --loglevel=error @openai/codex"))
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
      assertTrue(first.contains("Alpine Linux ARM64 guest through PRoot"))
      assertTrue(first.contains("`apk` package manager"))
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
        assertTrue(first.contains("Alpine Linux ARM64 guest through PRoot"))
        assertTrue(first.contains("`apk` package manager"))
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

      assertTrue(
        launch.argv.last().contains(
          "apk add --no-cache --no-progress ca-certificates curl gcompat git jq less openssh-client-default python3 ripgrep tzdata zsh",
        ),
      )
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
      command(
        "npm",
        "printf 'npm\\n' >> '${npmLog.absolutePath}'\n" +
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
      assertTrue(launcher.hasProvisionedToolchain(rootfs, home, TerminalRuntimeContract.TOOLCHAIN_TARGET_CODEX))
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

  private fun launcher(base: File, hostProc: File, scratch: File = File(base, "scratch")): ProotSessionLauncher =
    ProotSessionLauncher(
      runtime = ProotRuntimeLocator.LocatedRuntime(
        prootBin = File(base, "libproot.so"),
        loaderBin = File(base, "libproot_loader.so"),
        libraryDir = base,
        prootVersion = "5.4.0",
      ),
      scratchDir = scratch,
      hostProcDir = hostProc,
    )
}
