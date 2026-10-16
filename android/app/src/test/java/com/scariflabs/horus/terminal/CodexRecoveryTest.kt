package com.scariflabs.horus.terminal

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Executes the shared production shell with disposable npm/native fixtures, no network. */
class CodexRecoveryTest {
  @get:Rule val temp = TemporaryFolder()
  private val home get() = File(temp.root, "home").apply { mkdirs() }
  private val bin get() = File(temp.root, "bin").apply { mkdirs() }
  private val packageRoot get() = File(home, ".local/lib/node_modules/@openai/codex")
  private val marker get() = File(home, ".cache/horus/codex.ready")
  private val node = System.getenv("PATH")!!.split(File.pathSeparator)
    .map { File(it, "node") }.first { it.isFile && it.canExecute() }
  private val script get() = ProotSessionLauncher.CODEX_INSTALL_SCRIPT.replace("/usr/bin/node", node.absolutePath)

  private fun executable(file: File, body: String) {
    file.parentFile!!.mkdirs(); file.writeText(body); assertTrue(file.setExecutable(true))
  }

  private fun wrapper(root: File, version: String = "0.115.0") {
    root.mkdirs()
    File(root, "package.json").writeText("""{"version":"$version","optionalDependencies":{"@openai/codex-linux-arm64":"npm:@openai/codex@$version-linux-arm64"}}""")
    executable(File(root, "bin/codex.js"), """
      #!/usr/bin/env node
      const fs = require('fs');
      const p = require(require.resolve('@openai/codex-linux-arm64/package.json', {paths: [__dirname]}));
      fs.appendFileSync(process.env.HOME + '/probe.log', p.version + '\n');
      if (process.env.PROBE_FAIL === '1') process.exit(7);
      console.log('codex-cli ' + p.version.replace('-linux-arm64', ''));
    """.trimIndent())
  }

  private fun native(root: File, version: String = "0.115.0-linux-arm64") {
    root.mkdirs(); File(root, "package.json").writeText("""{"version":"$version"}""")
    val file = File(root, "vendor/aarch64-unknown-linux-musl/bin/codex")
    val header = ByteArray(64)
    header[0] = 0x7f; header[1] = 0x45; header[2] = 0x4c; header[3] = 0x46
    header[4] = 2; header[5] = 1; header[18] = 0xb7.toByte()
    file.parentFile!!.mkdirs(); file.writeBytes(header); assertTrue(file.setExecutable(true))
  }

  private fun fixture() {
    marker.parentFile!!.mkdirs()
    File(home, ".codex").mkdirs()
    File(home, ".codex/auth.json").writeText("credentials")
    File(home, ".codex/config.toml").writeText("configuration")
    File(home, "workspace").mkdirs(); File(home, "workspace/code").writeText("work")
    wrapper(packageRoot)
    native(File(packageRoot, "node_modules/@openai/codex-linux-arm64"))
    executable(File(bin, "timeout"), """
      #!/bin/sh
      if [ "${'$'}{PROBE_TIMEOUT:-0}" = 1 ]; then exit 124; fi
      shift 3
      exec "${'$'}@"
    """.trimIndent())
    val source = File(temp.root, "new-wrapper"); wrapper(source, "0.116.0")
    val payload = File(temp.root, "new-native"); native(payload, "0.116.0-linux-arm64")
    executable(File(bin, "npm"), """
      #!/bin/sh
      printf '%s\n' "${'$'}*" >> "${'$'}HOME/npm.log"
      [ "${'$'}{NPM_FAIL:-0}" != 1 ] || exit 8
      prefix=
      while [ "${'$'}#" -gt 0 ]; do
        if [ "${'$'}1" = --prefix ]; then shift; prefix=${'$'}1; fi
        last=${'$'}1; shift
      done
      root="${'$'}prefix/lib/node_modules/@openai"
      mkdir -p "${'$'}root"
      case "${'$'}last" in
        @openai/codex) cp -R '${source.absolutePath}' "${'$'}root/codex" ;;
        @openai/codex-linux-arm64@npm:@openai/codex@0.116.0-linux-arm64) cp -R '${payload.absolutePath}' "${'$'}root/codex-linux-arm64" ;;
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

  private fun preserved() {
    assertEquals("credentials", File(home, ".codex/auth.json").readText())
    assertEquals("configuration", File(home, ".codex/config.toml").readText())
    assertEquals("work", File(home, "workspace/code").readText())
  }

  @Test fun offlineStampedLaunchStartsNeitherProbeNorNpmIncludingSsh() {
    fixture()
    val stamped = run("$script\nmark_codex_ready")
    assertEquals(stamped.second, 0, stamped.first)
    val result = run("$script\ninstall_codex_package", mapOf("NPM_FAIL" to "1", "PROBE_TIMEOUT" to "1"))
    assertEquals(result.second, 0, result.first)
    assertFalse(File(home, "probe.log").exists()); assertFalse(File(home, "npm.log").exists())

    // A fake /proc and /dev, so the rendered SSH script needs no Linux host.
    val hostProc = File(temp.root, "proc").apply { mkdirs(); File(this, "stat").writeText("cpu 1\n"); File(this, "loadavg").writeText("0 0 0\n") }
    val hostDev = File(temp.root, "dev").apply {
      mkdirs(); listOf("null", "zero", "random", "urandom", "tty", "ptmx").forEach { File(this, it).writeText("") }; File(this, "pts").mkdirs()
    }
    val launcher = ProotSessionLauncher(ProotRuntimeLocator.LocatedRuntime(
      File(temp.root, "proot"), File(temp.root, "loader"), temp.root, "5.4.0"), File(temp.root, "scratch"), hostProc, hostDev)
    val rootfs = File(temp.root, "rootfs").apply { mkdirs(); File(this, "etc").mkdirs() }
    val remote = launcher.remoteAccessLaunchSpec(rootfs, home, null, "horus").argv.let { it[it.indexOf("horus-remote-access") - 1] }
    val installer = remote.substringAfter("<<'HORUS_INSTALLER'\n").substringBefore("\nHORUS_INSTALLER")
      .replace("/usr/bin/node", node.absolutePath)
    executable(File(home, ".local/bin/codex"), "#!/bin/sh\nprintf 'launched:%s\\n' \"${'$'}1\"\n")
    val ssh = run("set -- codex --help\n$installer", mapOf("NPM_FAIL" to "1", "PROBE_TIMEOUT" to "1"))
    assertEquals(ssh.second, 0, ssh.first); assertTrue(ssh.second.contains("launched:--help"))
    assertFalse(File(home, "probe.log").exists()); assertFalse(File(home, "npm.log").exists())
    preserved()
  }

  @Test fun missingStampProbesExistingInstallationOnceOfflineAndThenReusesStamp() {
    fixture()
    repeat(2) {
      val result = run("$script\ninstall_codex_package", mapOf("NPM_FAIL" to "1"))
      assertEquals(result.second, 0, result.first)
    }
    assertEquals(listOf("0.115.0-linux-arm64"), File(home, "probe.log").readLines())
    assertFalse(File(home, "npm.log").exists()); preserved()
  }

  @Test fun probeTimeoutPreservesOriginalWithoutAttemptingReplacement() {
    fixture()
    val before = File(packageRoot, "package.json").readText()
    val result = run("$script\ninstall_codex_package", mapOf("PROBE_TIMEOUT" to "1"))
    assertEquals(result.second, 124, result.first); assertTrue(result.second.contains("preserved"))
    assertEquals(before, File(packageRoot, "package.json").readText())
    assertFalse(File(home, "npm.log").exists()); preserved()
  }

  @Test fun failedDownloadOrValidationPreservesOriginalAndRecoveryIsIdempotent() {
    fixture()
    val before = File(packageRoot, "package.json").readText()
    for (env in listOf(mapOf("PROBE_FAIL" to "1", "NPM_FAIL" to "1"), mapOf("PROBE_FAIL" to "1"))) {
      val result = run("$script\ninstall_codex_package", env)
      assertNotEquals(result.second, 0, result.first)
      assertEquals(before, File(packageRoot, "package.json").readText()); preserved()
    }
    // An interruption between the two promotion renames is rolled back on retry.
    val previous = File(packageRoot.parentFile, ".codex.previous")
    assertTrue(packageRoot.renameTo(previous))
    val result = run("$script\ninstall_codex_package", mapOf("NPM_FAIL" to "1"))
    assertEquals(result.second, 0, result.first)
    assertEquals(before, File(packageRoot, "package.json").readText()); preserved()
    assertFalse(previous.exists())
  }

  @Test fun shellAndKotlinStampsAgreeAndRejectStaleMetadataInBothLayouts() {
    fixture()
    val launcher = ProotSessionLauncher(ProotRuntimeLocator.LocatedRuntime(
      File(temp.root, "proot"), File(temp.root, "loader"), temp.root, "5.4.0"), File(temp.root, "scratch"))
    val nested = File(packageRoot, "node_modules/@openai/codex-linux-arm64")
    val top = File(packageRoot.parentFile, "codex-linux-arm64")
    repeat(2) { index ->
      if (index == 1) assertTrue(nested.renameTo(top))
      val result = run("$script\nmark_codex_ready")
      assertEquals(result.second, 0, result.first)
      assertEquals("ready:${launcher.codexPayloadStamp(home)}\n", marker.readText())
      assertNotNull(launcher.codexPayloadStamp(home))
    }
    File(top, "package.json").writeText("""{"version":"0.114.0-linux-arm64"}""")
    assertNull(launcher.codexPayloadStamp(home))
    val result = run("$script\ncodex_stamped")
    assertNotEquals(result.second, 0, result.first)
  }

  @Test fun stampFailureDoesNotReportReadinessOrRemovePackage() {
    fixture(); marker.writeText("ready:stale\n")
    executable(File(bin, "stat"), "#!/bin/sh\nexit 91\n")
    val result = run("$script\ninstall_codex_package")
    assertNotEquals(result.second, 0, result.first)
    assertFalse(marker.exists()); assertTrue(File(packageRoot, "package.json").exists())
    assertEquals(listOf("0.115.0-linux-arm64"), File(home, "probe.log").readLines())
    assertFalse(File(home, "npm.log").exists()); preserved()
  }

  @Test fun concurrentRecoveryProbesAndStampsOnlyOnce() {
    fixture()
    val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
    try {
      val results = (1..2).map { executor.submit<Pair<Int, String>> { run("$script\ninstall_codex_package", mapOf("NPM_FAIL" to "1")) } }
      results.forEach { val result = it.get(20, TimeUnit.SECONDS); assertEquals(result.second, 0, result.first) }
      assertEquals(listOf("0.115.0-linux-arm64"), File(home, "probe.log").readLines())
      assertFalse(File(home, "npm.log").exists()); preserved()
    } finally { executor.shutdownNow() }
  }

  @Test fun staleTopLevelAliasCannotValidateNewWrapperAndReplacementIsSelfContained() {
    fixture()
    File(packageRoot, "node_modules").deleteRecursively()
    native(File(packageRoot.parentFile, "codex-linux-arm64"), "0.114.0-linux-arm64")
    val invalid = run("$script\ncodex_metadata_valid")
    assertNotEquals(invalid.second, 0, invalid.first)
    val result = run("$script\ninstall_codex_package\ncodex_metadata_valid && codex_stamped")
    assertEquals(result.second, 0, result.first)
    assertTrue(File(packageRoot, "package.json").readText().contains("0.116.0"))
    assertTrue(File(packageRoot, "node_modules/@openai/codex-linux-arm64/package.json").readText().contains("0.116.0-linux-arm64"))
    assertEquals(listOf("0.116.0-linux-arm64"), File(home, "probe.log").readLines())
    val requests = File(home, "npm.log").readLines()
    assertEquals(2, requests.size); assertTrue(requests.all { it.contains("--include=optional") })
    assertTrue(requests.last().endsWith("@openai/codex-linux-arm64@npm:@openai/codex@0.116.0-linux-arm64"))
    preserved()
  }
}
