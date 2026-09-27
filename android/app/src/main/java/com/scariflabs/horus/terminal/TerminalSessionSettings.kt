package com.scariflabs.horus.terminal

import java.io.File
import java.nio.file.Files
import org.json.JSONObject

/**
 * Small cross-process setting store for the user-facing concurrent-session
 * limit. The service and the React Native process do not share a reliable
 * SharedPreferences cache, so reads are fresh and writes replace the file
 * atomically. The file contains no credentials or terminal data.
 */
class TerminalSessionSettings(
  private val file: File,
) {

  @Synchronized
  fun readLimit(): Int {
    if (Files.isSymbolicLink(file.toPath()) || !file.isFile || file.length() > MAX_FILE_BYTES) {
      return TerminalSessionContract.DEFAULT_ACTIVE_SESSIONS
    }
    val value = runCatching {
      when (val raw = JSONObject(file.readText(Charsets.UTF_8)).opt(KEY_LIMIT)) {
        is Int -> raw
        is Long -> raw.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt()
        else -> null
      }
    }.getOrNull()
    return value?.takeIf(TerminalSessionContract::isValidActiveSessionLimit)
      ?: TerminalSessionContract.DEFAULT_ACTIVE_SESSIONS
  }

  @Synchronized
  fun writeLimit(limit: Int): Boolean {
    if (!TerminalSessionContract.isValidActiveSessionLimit(limit)) return false
    val parent = file.parentFile ?: return false
    if (Files.isSymbolicLink(parent.toPath())) return false
    if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) return false
    if (Files.isSymbolicLink(file.toPath())) return false
    val temporary = File(parent, ".${file.name}.tmp")
    if (Files.isSymbolicLink(temporary.toPath())) return false
    return runCatching {
      temporary.writeText(JSONObject().put(KEY_LIMIT, limit).toString(), Charsets.UTF_8)
      if (!temporary.renameTo(file)) {
        temporary.delete()
        false
      } else {
        true
      }
    }.getOrElse {
      temporary.delete()
      false
    }
  }

  private companion object {
    const val KEY_LIMIT = "maxConcurrentSessions"
    const val MAX_FILE_BYTES = 1024L
  }
}
