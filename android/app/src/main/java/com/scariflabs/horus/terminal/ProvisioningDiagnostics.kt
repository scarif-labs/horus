package com.scariflabs.horus.terminal

import java.util.concurrent.TimeUnit

/** Fixed summaries only: arbitrary installer output, argv, URLs and paths never cross the bridge. */
internal object ProvisioningText {
  const val MAX_LOG_CHARS = 4096
  const val MAX_LINE_CHARS = 2048
  const val EMPTY = "No diagnostic output was captured."
  const val OMITTED = "Installer output was omitted because it contained no recognized safe diagnostic message."
  val stages = setOf("start", "apk", "apk_failed", "base_ready", "github", "github_failed", "claude", "claude_failed", "codex", "codex_failed", "opencode", "opencode_failed", "normalize", "normalize_failed", "verify", "verify_failed", "ready")
  private val rules = listOf(
    "no space left on device|ENOSPC" to "No space left on device.",
    "could not get lock|unable to (acquire.*lock|lock database)|failed to open apk database" to "Another package installation holds the package database lock.",
    "dpkg was interrupted" to "Debian package configuration was interrupted.",
    "permission denied|EACCES" to "Permission denied while installing packages.",
    "could not resolve|ENOTFOUND|EAI_AGAIN" to "Could not resolve a download host.",
    "timed out|ETIMEDOUT" to "A download connection timed out.",
    "ENETUNREACH|ECONN|failed to fetch|unable to fetch|fetch failed|network is unreachable" to "Network is unreachable while downloading packages.",
    "EBADENGINE|unsupported engine" to "EBADENGINE: incompatible Node.js version.",
    "EBADPLATFORM|unsupported platform|missing optional dependency" to "A required platform package is unavailable.",
    "web page instead of.*installer" to "The download returned a web page instead of an installer.",
    "curl: \\(22\\)|HTTP[^\\n]*(401|403|404|429|500|502|503)" to "The download server returned an HTTP error.",
    "command not found|no such file or directory" to "A required installation command or file was not found.",
    "^(E:|ERROR:|npm (error|ERR!)|dpkg: error|apk: error)" to "The package manager reported an installation error.",
  ).map { (pattern, summary) -> Regex(pattern, RegexOption.IGNORE_CASE) to summary }
  private val safe = rules.map { it.second }.toSet() + setOf(EMPTY, OMITTED)
  fun summary(line: String): String? = if (line in safe) line else rules.firstOrNull { it.first.containsMatchIn(line) }?.second
}

/** All fields are bounded and request-free, including fallback diagnostics for a pre-spawn failure. */
internal data class ProvisioningResult(
  val outcome: String,
  val exitCode: Int? = null,
  val stage: String? = null,
  val commandExitCode: Int? = null,
  val log: String = ProvisioningText.EMPTY,
) {
  val succeeded: Boolean get() = outcome == "succeeded"
  fun errorCode(): String = when {
    log.contains("EBADENGINE") -> "toolchain_node_version"
    log.contains("No space left on device") -> "toolchain_install_failed"
    log.contains("Permission denied") -> "toolchain_permissions"
    log.contains("download host") || log.contains("Network is unreachable") || log.contains("connection timed out") -> "toolchain_network"
    log.contains("platform package is unavailable") -> "toolchain_install_failed"
    stage == "github_failed" -> "toolchain_github_install_failed"
    stage == "codex_failed" -> "toolchain_codex_install_failed"
    stage == "opencode_failed" -> "toolchain_opencode_install_failed"
    stage == "normalize_failed" -> "toolchain_normalization_failed"
    stage == "verify_failed" || outcome == "incomplete" -> "toolchain_incomplete"
    else -> "toolchain_install_failed"
  }
  fun fields(): Map<String, Any> = buildMap {
    put("outcome", outcome.takeIf { it in setOf("failed", "timed_out", "cancelled", "spawn_failed", "not_started", "incomplete") } ?: "failed")
    stage?.takeIf { it in ProvisioningText.stages }?.let { put("stage", it) }
    exitCode?.takeIf { it in 0..255 }?.let { put("exitCode", it) }
    commandExitCode?.takeIf { it in 0..255 }?.let { put("commandExitCode", it) }
    val safeOutput = ProvisioningOutput().apply { append(log); finish() }
    put("log", safeOutput.result(outcome, exitCode).log)
  }
  fun withPrivateFallback(stageText: String, privateTail: String?): ProvisioningResult {
    val output = ProvisioningOutput()
    privateTail?.let { output.append(it); output.finish() }
    val fallback = output.result(outcome, exitCode)
    val currentStage = stageText.takeIf { it in ProvisioningText.stages } ?: stage ?: fallback.stage
    return copy(
      outcome = if (succeeded && currentStage?.endsWith("_failed") == true) "incomplete" else outcome,
      stage = currentStage,
      commandExitCode = commandExitCode ?: fallback.commandExitCode,
      log = if ((log == ProvisioningText.EMPTY || log == ProvisioningText.OMITTED) && !privateTail.isNullOrBlank()) fallback.log else log,
    )
  }
  companion object {
    val STRING_FIELDS = listOf("outcome", "stage", "log")
    val INT_FIELDS = listOf("exitCode", "commandExitCode")
  }
}

/** Bounded line parser; oversized lines are dropped whole, never exported as a truncated secret. */
internal class ProvisioningOutput {
  private val pending = StringBuilder()
  private var oversized = false
  private var observed = false
  private var stage: String? = null
  private var commandExit: Int? = null
  private val summaries = linkedSetOf<String>()
  @Synchronized fun append(text: String) {
    text.forEach { ch ->
      if (ch == '\n' || ch == '\r') {
        if (!oversized) line(pending.toString())
        pending.setLength(0); oversized = false
      } else if (!oversized) {
        if (pending.length < ProvisioningText.MAX_LINE_CHARS) pending.append(ch)
        else { pending.setLength(0); oversized = true; observed = true }
      }
    }
  }
  @Synchronized fun finish() {
    if (!oversized) line(pending.toString())
    pending.setLength(0); oversized = false
  }
  private fun line(raw: String) {
    val text = raw.replace(Regex("\\u001b\\[[0-?]*[ -/]*[@-~]"), "").trim()
    if (text.startsWith("HORUS_INSTALL_STAGE=")) {
      text.substringAfter('=').takeIf { it in ProvisioningText.stages }?.let { stage = it }
    } else if (text.startsWith("HORUS_INSTALL_COMMAND_EXIT=")) {
      text.substringAfter('=').toIntOrNull()?.takeIf { it in 0..255 }?.let { commandExit = it }
    } else if (text.isNotEmpty()) {
      observed = true
      ProvisioningText.summary(text)?.let { summaries.add(it) }
    }
  }
  @Synchronized fun result(outcome: String, exit: Int?): ProvisioningResult = ProvisioningResult(
    outcome, exit, stage, commandExit,
    summaries.joinToString("\n").take(ProvisioningText.MAX_LOG_CHARS).ifEmpty { if (observed) ProvisioningText.OMITTED else ProvisioningText.EMPTY },
  )
}

/** Same process/stop policy as the service; extraction permits host regression tests without Android. */
internal object ProvisioningProcess {
  fun run(
    launch: ProotSessionLauncher.LaunchSpec,
    timeoutMs: Long,
    started: (Process) -> Unit = {},
    finished: (Process) -> Unit = {},
    stop: (Process) -> Unit = ::stop,
  ): ProvisioningResult {
    val output = ProvisioningOutput()
    val process = try {
      ProcessBuilder(launch.argv).apply {
        launch.workingDirectory?.let(::directory)
        environment().clear()
        launch.environment.forEach { entry ->
          val separator = entry.indexOf('=')
          if (separator > 0) environment()[entry.substring(0, separator)] = entry.substring(separator + 1)
        }
        redirectErrorStream(true)
      }.start()
    } catch (_: Exception) { return output.result("spawn_failed", null) }
    started(process)
    val drain = Thread {
      try {
        val reader = process.inputStream.reader(Charsets.UTF_8)
        val buffer = CharArray(1024)
        while (true) {
          val count = reader.read(buffer)
          if (count < 0) break
          output.append(String(buffer, 0, count))
        }
      } catch (_: Exception) {
        // Closing the process stream during bounded teardown interrupts the drain.
      } finally { output.finish() }
    }.apply { name = "horus-toolchain-output"; isDaemon = true }
    drain.start()
    var outcome = "failed"
    try {
      outcome = if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
        stop(process); "timed_out"
      } else if (process.exitValue() == 0) "succeeded" else "failed"
    } catch (_: InterruptedException) {
      stop(process)
      outcome = "cancelled"
      Thread.currentThread().interrupt()
    } finally {
      finished(process)
      // Give already-exited output a bounded drain window; descendants may keep the pipe open.
      if (!Thread.currentThread().isInterrupted) runCatching { drain.join(500) }
      runCatching { process.inputStream.close() }
      if (drain.isAlive) { drain.interrupt(); runCatching { drain.join(500) } }
    }
    val result = output.result(outcome, runCatching { process.exitValue() }.getOrNull())
    return if (result.succeeded && result.stage?.endsWith("_failed") == true) result.copy(outcome = "incomplete") else result
  }
  private fun stop(process: Process) {
    runCatching { process.destroy() }
    if (runCatching { process.waitFor(2000, TimeUnit.MILLISECONDS) }.getOrDefault(false)) return
    runCatching { process.destroyForcibly() }
    runCatching { process.waitFor(2000, TimeUnit.MILLISECONDS) }
  }
}
