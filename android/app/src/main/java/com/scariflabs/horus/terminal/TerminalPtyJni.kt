package com.scariflabs.horus.terminal

import java.io.File

/**
 * JNI surface of the app-owned PTY helper (cpp/terminal_pty.c). All native
 * methods return errno-encoded failures; the Kotlin wrapper converts them to
 * exceptions so the supervisor deals only in outcomes. Raw-fd I/O also lives
 * in the helper because android.system.Os speaks FileDescriptor, not ints.
 */
object TerminalPtyJni {
  init {
    System.loadLibrary("horus_pty")
  }

  /** Returns {pid, master_fd}; on failure {-(errno), -1}. */
  external fun createSubprocess(
    argv: Array<String>,
    cwd: String?,
    environment: Array<String>,
    rows: Int,
    columns: Int,
  ): IntArray

  /** TIOCSWINSZ on the pty master; 0 on success, -(errno) on failure. */
  external fun setWindowSize(masterFd: Int, rows: Int, columns: Int): Int

  /**
   * Blocking waitpid for one direct child. Decoded by
   * [TerminalSessionContract.decodeExitStatus]; -(1000 + errno) on failure.
   */
  external fun waitFor(pid: Int): Int

  /** close(2); 0 on success, -(errno) on failure. */
  external fun closeFd(fd: Int): Int

  /** Polling read; 0 timeout, >0 byte count, -1 EOF, -(errno) on failure. */
  external fun readMaster(masterFd: Int, buffer: ByteArray): Int

  /** Polling write; 0 not-ready, >0 accepted byte count, -(errno) on failure. */
  external fun writeMaster(masterFd: Int, buffer: ByteArray, offset: Int, count: Int): Int
}

/** Raised when a PTY backend call fails; `errno` is preserved for evidence. */
class PtyBackendException(
  val operation: String,
  val errno: Int,
  detail: String,
) : IllegalStateException("$operation failed: errno=$errno ($detail)")

/**
 * The PTY process boundary the supervisor talks to. Kept as an interface so
 * JVM unit tests drive the supervisor deterministically with a fake while
 * production uses [JniPtyBackend] over the app-owned helper.
 */
interface PtyBackend {
  data class SpawnedProcess(val pid: Int, val masterFd: Int)

  /** Spawns argv[0] in a fresh session with a real controlling terminal. */
  fun createSubprocess(
    argv: List<String>,
    cwd: File?,
    environment: List<String>,
    rows: Int,
    columns: Int,
  ): SpawnedProcess

  fun setWindowSize(masterFd: Int, rows: Int, columns: Int)

  /** Blocking read on the pty master; returns -1 on EOF, >= 0 byte count. */
  fun read(masterFd: Int, buffer: ByteArray): Int

  /** Writes up to count bytes; returns the byte count accepted. */
  fun write(masterFd: Int, bytes: ByteArray, offset: Int, count: Int): Int

  /** Blocking waitpid for the direct child; returns the encoded wait status. */
  fun waitFor(pid: Int): Int

  fun close(masterFd: Int)
}

/** Production backend over [TerminalPtyJni]; every fd operation stays in the helper. */
class JniPtyBackend : PtyBackend {

  override fun createSubprocess(
    argv: List<String>,
    cwd: File?,
    environment: List<String>,
    rows: Int,
    columns: Int,
  ): PtyBackend.SpawnedProcess {
    val result = TerminalPtyJni.createSubprocess(
      argv.toTypedArray(),
      cwd?.absolutePath,
      environment.toTypedArray(),
      rows,
      columns,
    )
    val pid = result.getOrElse(0) { -1 }
    val masterFd = result.getOrElse(1) { -1 }
    if (pid <= 0 || masterFd < 0) {
      throw PtyBackendException("createSubprocess", -pid, "cannot spawn ${argv.firstOrNull() ?: "?"}")
    }
    return PtyBackend.SpawnedProcess(pid = pid, masterFd = masterFd)
  }

  override fun setWindowSize(masterFd: Int, rows: Int, columns: Int) {
    val code = TerminalPtyJni.setWindowSize(masterFd, rows, columns)
    if (code != 0) throw PtyBackendException("setWindowSize", -code, "TIOCSWINSZ failed")
  }

  override fun read(masterFd: Int, buffer: ByteArray): Int {
    val result = TerminalPtyJni.readMaster(masterFd, buffer)
    if (result >= 0 || result == -1) return result
    throw PtyBackendException("read", -result, "pty master read failed")
  }

  override fun write(masterFd: Int, bytes: ByteArray, offset: Int, count: Int): Int {
    val result = TerminalPtyJni.writeMaster(masterFd, bytes, offset, count)
    if (result >= 0) return result
    throw PtyBackendException("write", -result, "pty master write failed")
  }

  override fun waitFor(pid: Int): Int = TerminalPtyJni.waitFor(pid)

  override fun close(masterFd: Int) {
    TerminalPtyJni.closeFd(masterFd)
  }
}
