package com.scariflabs.horus.terminal

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Executes the shared production OpenCode shell with disposable npm/native fixtures, no network. */
class OpenCodeRecoveryTest {
  @get:Rule val temp = TemporaryFolder()
  private val home get() = File(temp.root, "home").apply { mkdirs() }
  private val bin get() = File(temp.root, "bin").apply { mkdirs() }
  private val packageRoot get() = File(home, ".local/lib/node_modules/opencode-ai")
  private val marker get() = File(home, ".cache/horus/opencode.ready")
  private val script get() = ProotSessionLauncher.OPENCODE_INSTALL_SCRIPT

  private val wrapperJson =
    "{\"version\":\"1.19.0\",\"optionalDependencies\":{\"opencode-linux-x64\":\"1.19.0\",\"opencode-linux-x64-musl\":\"1.19.0\",\"opencode-linux-arm64\":\"1.19.0\",\"opencode-linux-arm64-musl\":\"1.19.0\"}}"

  private fun executable(file: File, body: String) {
    file.parentFile!!.mkdirs(); file.writeText(body); assertTrue(file.setExecutable(true))
  }

  private fun wrapper() {
    packageRoot.mkdirs()
    File(packageRoot, "package.json").writeText(wrapperJson)
  }

  private fun fixture() {
    marker.parentFile!!.mkdirs()
    File(home, ".config/opencode").mkdirs()
    File(home, ".config/opencode/AGENTS.md").writeText("user configuration")
    executable(File(bin, "timeout"), "#!/bin/sh\nexit 0\n")
    executable(File(bin, "npm"), """
      #!/bin/sh
      printf '%s\n' "${'$'}*" >> "${'$'}HOME/npm.log"
      [ "${'$'}{NPM_FAIL:-0}" != 1 ] || exit 8
      prefix=
      while [ "${'$'}#" -gt 0 ]; do
        if [ "${'$'}1" = --prefix ]; then shift; prefix=${'$'}1; fi
        last=${'$'}1; shift
      done
      case "${'$'}last" in
        opencode-ai)
          root="${'$'}prefix/lib/node_modules/opencode-ai"; mkdir -p "${'$'}root/bin"
          printf '%s\n' '$wrapperJson' > "${'$'}root/package.json"
          [ "${'$'}{NPM_BAD_PAYLOAD:-0}" != 1 ] ||
            { printf '%s\n' '#!/bin/sh' 'exit 0' > "${'$'}root/bin/opencode"; chmod u+rx "${'$'}root/bin/opencode"; }
          ;;
        opencode-linux-*@1.19.0)
          name=${'$'}{last%@*}; root="${'$'}prefix/lib/node_modules/${'$'}name"; mkdir -p "${'$'}root/bin"
          printf '%s\n' '{"version":"1.19.0"}' > "${'$'}root/package.json"
          printf '\177ELF\002\001\001\000' > "${'$'}root/bin/opencode"; chmod u+rx "${'$'}root/bin/opencode"
          ;;
        *) exit 99 ;;
      esac
    """.trimIndent())
  }

  private fun run(body: String, env: Map<String, String> = emptyMap()): Pair<Int, String> {
    val log = File(temp.root, "output-${System.nanoTime()}")
    val process = ProcessBuilder("/bin/sh", "-c", body).redirectErrorStream(true).redirectOutput(log)
    process.environment().putAll(mapOf("HOME" to home.absolutePath, "PATH" to "${bin.absolutePath}:${System.getenv("PATH")}"))
    process.environment().putAll(env)
    val running = process.start()
    if (!running.waitFor(15, TimeUnit.SECONDS)) {
      running.destroyForcibly(); throw AssertionError("bounded fixture timed out: ${log.readText()}")
    }
    return running.exitValue() to log.readText()
  }

  private fun launcher() = ProotSessionLauncher(ProotRuntimeLocator.LocatedRuntime(
    File(temp.root, "proot"), File(temp.root, "loader"), temp.root, "5.4.0"), File(temp.root, "scratch"))

  private fun preserved() {
    assertEquals("user configuration", File(home, ".config/opencode/AGENTS.md").readText())
  }

  @Test fun recoversMissingPlatformPayloadAndStampsKotlinIdentity() {
    fixture()
    val result = run("$script\ninstall_opencode_package")
    assertEquals(result.second, 0, result.first)
    val requests = File(home, "npm.log").readLines()
    assertEquals(2, requests.size)
    assertTrue(requests.all { it.contains("--include=optional") })
    assertTrue(requests.first().endsWith("opencode-ai"))
    assertTrue(requests.last().contains("opencode-linux-"))
    val entry = File(packageRoot, "bin/opencode.exe")
    assertTrue(entry.isFile && entry.canExecute())
    assertArrayEquals(byteArrayOf(0x7f, 0x45, 0x4c, 0x46), entry.readBytes().take(4).toByteArray())
    assertEquals("ready:${launcher().openCodePayloadStamp(home)}\n", marker.readText())
    assertNotNull(launcher().openCodePayloadStamp(home))
    preserved()
  }

  @Test fun stampedInstallStartsNoNpmOrPayloadProbe() {
    fixture()
    assertEquals(0, run("$script\ninstall_opencode_package").first)
    File(home, "npm.log").delete()
    val result = run("$script\ninstall_opencode_package")
    assertEquals(result.second, 0, result.first)
    assertFalse(File(home, "npm.log").exists()); preserved()
  }

  @Test fun offlineWorkingPayloadIsRestampedWithoutInstalling() {
    fixture()
    assertEquals(0, run("$script\ninstall_opencode_package").first)
    File(home, "npm.log").delete()
    marker.delete()
    val result = run("$script\ninstall_opencode_package", mapOf("NPM_FAIL" to "1"))
    assertEquals(result.second, 0, result.first)
    assertFalse(File(home, "npm.log").exists())
    assertEquals("ready:${launcher().openCodePayloadStamp(home)}\n", marker.readText()); preserved()
  }

  @Test fun nonElfPayloadTriggersRecoveryInstall() {
    fixture()
    val result = run("$script\ninstall_opencode_package", mapOf("NPM_BAD_PAYLOAD" to "1"))
    assertEquals(result.second, 0, result.first)
    assertEquals(2, File(home, "npm.log").readLines().size)
    assertFalse("the non-runnable bin must be replaced", File(packageRoot, "bin/opencode").exists())
    val entry = File(packageRoot, "bin/opencode.exe")
    assertArrayEquals(byteArrayOf(0x7f, 0x45, 0x4c, 0x46), entry.readBytes().take(4).toByteArray())
    preserved()
  }

  @Test fun failedDownloadPreservesPreviousPackageAndDoesNotStamp() {
    fixture()
    wrapper()
    val before = File(packageRoot, "package.json").readText()
    val result = run("$script\ninstall_opencode_package", mapOf("NPM_FAIL" to "1"))
    assertNotEquals(result.second, 0, result.first)
    assertTrue(result.second.contains("preserved"))
    assertEquals(before, File(packageRoot, "package.json").readText())
    assertFalse(marker.exists()); preserved()
  }

  @Test fun sshLauncherReusesStampedInstallWithoutInstalling() {
    fixture()
    assertEquals(0, run("$script\ninstall_opencode_package").first)
    File(home, "npm.log").delete()
    val rootfs = File(temp.root, "rootfs").apply { mkdirs(); File(this, "etc").mkdirs() }
    val remote = launcher().remoteAccessLaunchSpec(rootfs, home, null, "horus").argv.takeLast(8)[2]
    val installer = remote.substringAfter("<<'HORUS_INSTALLER'\n").substringBefore("\nHORUS_INSTALLER")
    executable(File(home, ".local/bin/opencode"), "#!/bin/sh\nprintf 'launched:%s\\n' \"${'$'}1\"\n")
    val ssh = run("set -- opencode --help\n$installer", mapOf("NPM_FAIL" to "1"))
    assertEquals(ssh.second, 0, ssh.first); assertTrue(ssh.second.contains("launched:--help"))
    assertFalse(File(home, "npm.log").exists()); preserved()
  }
}
