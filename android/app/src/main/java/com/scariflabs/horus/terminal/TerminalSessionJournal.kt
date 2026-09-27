package com.scariflabs.horus.terminal

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject

/**
 * Small app-private journal for sessions that should be recoverable after the
 * UI process is recreated. It stores launcher metadata only; PTY output,
 * terminal input, one-time codes, and tokens are never written here.
 */
class TerminalSessionJournal(
  private val file: File,
) {

  sealed interface UpsertOutcome {
    data object Stored : UpsertOutcome
    data class CapacityReached(val activeCount: Int, val limit: Int) : UpsertOutcome
  }

  data class Record(
    val sessionId: String,
    val rows: Int,
    val columns: Int,
    val command: String?,
    val startedAtMs: Long,
    val toolchainTarget: String? = null,
  )

  private val lock = Any()

  fun read(): List<Record> = synchronized(lock) { readUnsafe() }

  @Throws(IOException::class)
  fun upsert(record: Record): UpsertOutcome {
    requireValid(record)
    return synchronized(lock) {
      val records = readUnsafe().toMutableList()
      val existingIndex = records.indexOfFirst { it.sessionId == record.sessionId }
      if (existingIndex >= 0) {
        records[existingIndex] = record
      } else {
        val activeCount = records.count(::countsAgainstSessionLimit)
        if (countsAgainstSessionLimit(record) && activeCount >= TerminalSessionContract.MAX_ACTIVE_SESSIONS) {
          return@synchronized UpsertOutcome.CapacityReached(
            activeCount = activeCount,
            limit = TerminalSessionContract.MAX_ACTIVE_SESSIONS,
          )
        }
        records += record
      }
      writeUnsafe(records)
      UpsertOutcome.Stored
    }
  }

  @Throws(IOException::class)
  fun remove(sessionId: String) {
    if (!TerminalSessionContract.isValidSessionId(sessionId)) return
    synchronized(lock) {
      val records = readUnsafe().filterNot { it.sessionId == sessionId }
      writeUnsafe(records)
    }
  }

  @Throws(IOException::class)
  fun clear() {
    synchronized(lock) {
      if (Files.isSymbolicLink(file.toPath())) {
        throw IOException("session journal is a symbolic link")
      }
      if (file.exists() && !file.delete()) {
        throw IOException("cannot remove session journal")
      }
    }
  }

  private fun readUnsafe(): List<Record> {
    if (Files.isSymbolicLink(file.toPath()) || !file.isFile || file.length() > MAX_JOURNAL_BYTES) return emptyList()
    val root = try {
      JSONObject(file.readText(Charsets.UTF_8))
    } catch (_: Exception) {
      return emptyList()
    }
    val schemaVersion = root.optInt(KEY_SCHEMA_VERSION, -1)
    if (schemaVersion != LEGACY_SCHEMA_VERSION && schemaVersion != SCHEMA_VERSION) return emptyList()
    val values = root.optJSONArray(KEY_SESSIONS) ?: return emptyList()
    val records = ArrayList<Record>(minOf(values.length(), MAX_JOURNAL_RECORDS))
    for (index in 0 until values.length()) {
      if (records.size >= MAX_JOURNAL_RECORDS) break
      val value = values.opt(index)
      if (value !is JSONObject) continue
      val sessionId = value.optString(KEY_SESSION_ID)
      val rows = value.optInt(KEY_ROWS, -1)
      val columns = value.optInt(KEY_COLUMNS, -1)
      val startedAtMs = value.optLong(KEY_STARTED_AT_MS, -1L)
      val commandValue = value.opt(KEY_COMMAND)
      val command = when {
        commandValue == null || commandValue == JSONObject.NULL -> null
        commandValue is String -> commandValue
        else -> continue
      }
      val targetValue = value.opt(KEY_TOOLCHAIN_TARGET)
      val toolchainTarget = when {
        schemaVersion == LEGACY_SCHEMA_VERSION || targetValue == null || targetValue == JSONObject.NULL -> null
        targetValue is String -> targetValue
        else -> continue
      }
      val record = Record(sessionId, rows, columns, command, startedAtMs, toolchainTarget)
      if (isValid(record) && records.none { it.sessionId == record.sessionId }) {
        records += record
      }
    }
    return records
  }

  @Throws(IOException::class)
  private fun writeUnsafe(records: List<Record>) {
    if (records.isEmpty()) {
      if (Files.isSymbolicLink(file.toPath())) {
        throw IOException("session journal is a symbolic link")
      }
      if (file.exists() && !file.delete()) {
        throw IOException("cannot remove empty session journal")
      }
      return
    }
    if (records.size > MAX_JOURNAL_RECORDS || records.count(::countsAgainstSessionLimit) > TerminalSessionContract.MAX_ACTIVE_SESSIONS || records.any { !isValid(it) }) {
      throw IOException("session journal record is invalid")
    }
    val parent = file.parentFile ?: throw IOException("session journal has no parent")
    if (Files.isSymbolicLink(parent.toPath())) {
      throw IOException("session journal parent is a symbolic link")
    }
    if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
      throw IOException("cannot create session journal parent")
    }
    if (Files.isSymbolicLink(file.toPath())) {
      throw IOException("session journal is a symbolic link")
    }
    val json = JSONObject().put(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
    val values = JSONArray()
    records.forEach { record ->
      values.put(
        JSONObject()
          .put(KEY_SESSION_ID, record.sessionId)
          .put(KEY_ROWS, record.rows)
          .put(KEY_COLUMNS, record.columns)
          .put(KEY_COMMAND, record.command ?: JSONObject.NULL)
          .put(KEY_STARTED_AT_MS, record.startedAtMs)
          .put(KEY_TOOLCHAIN_TARGET, record.toolchainTarget ?: JSONObject.NULL),
      )
    }
    json.put(KEY_SESSIONS, values)
    val temporary = File(parent, ".${file.name}.tmp")
    if (Files.isSymbolicLink(temporary.toPath())) {
      throw IOException("temporary session journal is a symbolic link")
    }
    temporary.writeText(json.toString(), Charsets.UTF_8)
    if (!temporary.renameTo(file)) {
      temporary.delete()
      throw IOException("cannot atomically replace session journal")
    }
  }

  private fun requireValid(record: Record) {
    require(isValid(record)) { "invalid session journal record" }
  }

  private fun isValid(record: Record): Boolean =
    TerminalSessionContract.isValidSessionId(record.sessionId) &&
      TerminalSessionContract.isValidRows(record.rows) &&
      TerminalSessionContract.isValidColumns(record.columns) &&
      (record.command == null || record.command.isNotEmpty() && record.command.length <= TerminalSessionContract.MAX_COMMAND_LENGTH) &&
      (record.toolchainTarget == null || TerminalRuntimeContract.isValidToolchainTarget(record.toolchainTarget)) &&
      record.startedAtMs >= 0L

  private fun countsAgainstSessionLimit(record: Record): Boolean =
    record.toolchainTarget != TerminalRuntimeContract.TOOLCHAIN_TARGET_GITHUB

  private companion object {
    const val LEGACY_SCHEMA_VERSION = 1
    const val SCHEMA_VERSION = 2
    const val MAX_JOURNAL_BYTES = 32 * 1024L
    // GitHub utility PTYs are intentionally not recoverable, but retain one
    // legacy record during migration so it cannot consume a user slot or make
    // the journal reject the fourth user session.
    const val MAX_JOURNAL_RECORDS = TerminalSessionContract.MAX_ACTIVE_SESSIONS + 1
    const val KEY_SCHEMA_VERSION = "schemaVersion"
    const val KEY_SESSIONS = "sessions"
    const val KEY_SESSION_ID = "sessionId"
    const val KEY_ROWS = "rows"
    const val KEY_COLUMNS = "columns"
    const val KEY_COMMAND = "command"
    const val KEY_STARTED_AT_MS = "startedAtMs"
    const val KEY_TOOLCHAIN_TARGET = "toolchainTarget"
  }
}
