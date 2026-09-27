package com.scariflabs.horus.terminal

import android.content.Context
import android.os.Process
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Small, app-private diagnostic journal for failures that otherwise only
 * exist in logcat. It deliberately stores structured lifecycle metadata, not
 * terminal bytes: PTY output can contain passwords, device codes, tokens, and
 * commands entered by the user.
 */
object TerminalDebugLog {
  const val MAX_BYTES = 256 * 1024L
  const val MAX_EVENT_CHARS = 512
  const val MAX_DUMP_CHARS = 32 * 1024
  private const val ROTATE_KEEP_BYTES = 128 * 1024L
  private const val FILE_NAME = "terminal-debug.log"
  private const val DIAGNOSTICS_DIR_NAME = "diagnostics"
  private const val MAX_PENDING_RECORDS = 512
  private const val SNAPSHOT_FLUSH_TIMEOUT_MS = 250L
  private val processLock = Any()
  private val writer = ThreadPoolExecutor(
    1,
    1,
    0L,
    TimeUnit.MILLISECONDS,
    ArrayBlockingQueue(MAX_PENDING_RECORDS),
    { runnable ->
      Thread(runnable, "terminal-debug-log").apply { isDaemon = true }
    },
    ThreadPoolExecutor.DiscardOldestPolicy(),
  )

  data class Snapshot(
    val path: String,
    val externalPath: String?,
    val bytes: Long,
    val tail: String,
  )

  fun record(context: Context, event: String) {
    val files = listOfNotNull(logFile(context), externalLogFile(context))
      .distinctBy(File::getAbsolutePath)
    if (files.isEmpty()) return
    val safeEvent = sanitize(event)
    if (safeEvent.isEmpty()) return
    writer.execute {
      val timestampMs = System.currentTimeMillis()
      val processId = Process.myPid()
      files.forEach { file -> append(file, safeEvent, timestampMs, processId) }
    }
  }

  fun snapshot(context: Context, maxChars: Int = MAX_DUMP_CHARS): Snapshot? {
    val file = logFile(context) ?: return null
    val externalFile = externalLogFile(context)
    return runCatching {
      // Pull/debug requests are infrequent; make the journal current without
      // putting file I/O back on the caller's hot path.
      awaitIdle(SNAPSHOT_FLUSH_TIMEOUT_MS)
      synchronized(processLock) {
        if (!file.exists()) {
          if (!file.createNewFile()) return@runCatching null
        }
        Snapshot(
          path = file.absolutePath,
          externalPath = externalFile?.absolutePath,
          bytes = file.length().coerceAtLeast(0L),
          tail = readTail(file, maxChars.coerceIn(1, MAX_DUMP_CHARS)),
        )
      }
    }.getOrNull()
  }

  /** Test hook kept separate from the Context-dependent production surface. */
  internal fun appendForTest(file: File, event: String, timestampMs: Long, processId: Int): Boolean =
    append(file, event, timestampMs, processId)

  /**
   * Test-only asynchronous path used to prove queued diagnostics are drained
   * during teardown instead of leaving pending work behind.
   */
  internal fun recordForTest(file: File, event: String, timestampMs: Long, processId: Int): Boolean {
    val safeEvent = sanitize(event)
    if (safeEvent.isEmpty()) return false
    writer.execute { append(file, safeEvent, timestampMs, processId) }
    return true
  }

  internal fun awaitIdleForTest(timeoutMs: Long = 1_000L): Boolean = awaitIdle(timeoutMs)

  private fun awaitIdle(timeoutMs: Long): Boolean = runCatching {
    writer.submit {}.get(timeoutMs, TimeUnit.MILLISECONDS)
    true
  }.getOrDefault(false)

  private fun append(file: File, event: String, timestampMs: Long, processId: Int): Boolean {
    val safeEvent = sanitize(event)
    if (safeEvent.isEmpty()) return false
    val line = "ts=$timestampMs pid=$processId event=$safeEvent\n"
      .toByteArray(StandardCharsets.UTF_8)
    return runCatching {
      synchronized(processLock) {
        val parent = file.parentFile ?: return@runCatching false
        if (!ensureDirectory(parent) || !ensureRegularFile(file)) return@runCatching false
        RandomAccessFile(file, "rw").use { access ->
          val channel = access.channel
          val lock = channel.tryLock() ?: return@runCatching false
          lock.use {
            trim(channel)
            channel.position(channel.size())
            channel.write(ByteBuffer.wrap(line))
            channel.force(false)
            if (channel.size() > MAX_BYTES) trim(channel)
          }
        }
      }
      true
    }.getOrDefault(false)
  }

  private fun readTail(file: File, maxChars: Int): String {
    if (!file.isFile || file.length() <= 0L) return ""
    RandomAccessFile(file, "r").use { access ->
      val length = access.length()
      val bytesToRead = minOf(length, (maxChars.toLong() * 4L).coerceAtMost(MAX_BYTES)).toInt()
      access.seek(length - bytesToRead)
      val bytes = ByteArray(bytesToRead)
      access.readFully(bytes)
      return String(bytes, StandardCharsets.UTF_8).takeLast(maxChars)
    }
  }

  private fun trim(channel: java.nio.channels.FileChannel) {
    val length = channel.size()
    if (length <= MAX_BYTES) return
    val keep = minOf(length, ROTATE_KEEP_BYTES).toInt()
    val start = length - keep
    val bytes = ByteArray(keep)
    channel.position(start)
    var offset = 0
    while (offset < keep) {
      val read = channel.read(ByteBuffer.wrap(bytes, offset, keep - offset))
      if (read <= 0) break
      offset += read
    }
    channel.position(0L)
    channel.write(ByteBuffer.wrap(bytes, 0, offset))
    channel.truncate(offset.toLong())
  }

  private fun logFile(context: Context): File? {
    val filesDir = context.applicationContext.filesDir
    val storageRoot = File(filesDir, TerminalRuntimeContract.STORAGE_ROOT_DIR_NAME)
    val diagnostics = File(storageRoot, DIAGNOSTICS_DIR_NAME)
    val file = File(diagnostics, FILE_NAME)
    if (isSymlink(storageRoot) || isSymlink(diagnostics) || isSymlink(file)) return null
    return file
  }

  /** App-scoped external mirror makes the safe journal pullable with ADB. */
  private fun externalLogFile(context: Context): File? {
    val diagnostics = context.applicationContext.getExternalFilesDir(DIAGNOSTICS_DIR_NAME) ?: return null
    val file = File(diagnostics, FILE_NAME)
    if (isSymlink(diagnostics) || isSymlink(file)) return null
    return file
  }

  private fun ensureDirectory(directory: File): Boolean {
    if (isSymlink(directory)) return false
    if (!directory.exists() && !directory.mkdirs()) return false
    return directory.isDirectory && !isSymlink(directory)
  }

  private fun ensureRegularFile(file: File): Boolean =
    (!file.exists() || (file.isFile && !isSymlink(file)))

  private fun isSymlink(file: File): Boolean =
    runCatching { Files.isSymbolicLink(file.toPath()) }.getOrDefault(true)

  private fun sanitize(event: String): String = event
    .take(MAX_EVENT_CHARS)
    .map { character ->
      when {
        character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' -> character
        character == '_' || character == '-' || character == '.' || character == ':' ||
          character == '/' || character == '=' || character == ' ' -> character
        else -> '_'
      }
    }
    .joinToString("")
    .trim()
}
