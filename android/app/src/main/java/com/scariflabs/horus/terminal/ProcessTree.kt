package com.scariflabs.horus.terminal

import java.io.File

/**
 * Bounded observation helpers for the /proc process tree (process
 * ownership must be explicit; stop targets the whole process group, and a
 * stop that misses descendants is a leak). The proc root is injectable so
 * JVM unit tests can exercise the traversal against a synthetic tree.
 *
 * The traversal is bounded in depth and breadth: a fork loop inside the
 * guest cannot make supervision allocate without limit.
 */
open class ProcessTree(private val procRoot: File = File("/proc")) {

  open fun isAlive(pid: Long): Boolean {
    if (pid <= 0) return false
    val entry = File(procRoot, pid.toString())
    if (!entry.isDirectory) return false
    // A waitable zombie still has a /proc directory, but it no longer owns
    // resources and must not make a bounded teardown report a leaked process.
    val stat = runCatching { File(entry, "stat").readText().take(2048) }.getOrNull()
    return stat == null || parseStateField(stat) !in setOf('Z', 'X')
  }

  /**
   * Returns the descendant pids of `rootPid` discovered through
   * /proc/<pid>/task/<pid>/children, breadth-first, bounded by
   * [MAX_DESCENDANT_DEPTH] and [MAX_DESCENDANTS]. Missing or unreadable
   * entries mean "no observation", never an exception.
   */
  open fun descendants(rootPid: Long): List<Long> {
    val seen = linkedSetOf<Long>()
    data class Entry(val pid: Long, val depth: Int)
    val queue = ArrayDeque<Entry>()
    queue += Entry(rootPid, 0)
    while (queue.isNotEmpty() && seen.size < MAX_DESCENDANTS) {
      val (pid, depth) = queue.removeFirst()
      if (depth >= MAX_DESCENDANT_DEPTH) continue
      val childrenFile = File(procRoot, "$pid/task/$pid/children")
      val children = runCatching { childrenFile.readText().take(4096).trim() }
        .getOrDefault("")
        .split(Regex("\\s+"))
        .mapNotNull { it.toLongOrNull() }
        .filter { it > 0L }
      for (childPid in children) {
        if (seen.add(childPid)) queue += Entry(childPid, depth + 1)
      }
    }
    return seen.toList()
  }

  /** Alive pids from `candidates` after the bounded wait window, for teardown evidence. */
  fun survivors(candidates: List<Long>): List<Long> = candidates.filter(::isAlive)

  /** Session id of a pid from /proc/<pid>/stat, or null when unreadable. */
  fun sessionOfPid(pid: Long): Long? {
    val stat = readStat(pid) ?: return null
    return parseSessionField(stat)
  }

  /**
   * Every process whose session id equals `sessionLeaderPid`, bounded by
   * [MAX_SESSION_SCAN_PIDS]. This is the safety net for teardown: guest job
   * control places running commands in their own process groups, and orphans
   * reparent away from the leader, but their session id still names them.
   */
  open fun sessionMembers(sessionLeaderPid: Long): List<Long> {
    val entries = procRoot.listFiles()?.filter { it.name.toLongOrNull() != null }.orEmpty()
    if (entries.isEmpty()) return emptyList()
    val members = mutableListOf<Long>()
    for ((index, entry) in entries.withIndex()) {
      if (index >= MAX_SESSION_SCAN_PIDS) break
      val pid = entry.name.toLongOrNull() ?: continue
      if (pid == sessionLeaderPid) continue
      val stat = readStat(pid) ?: continue
      if (parseSessionField(stat) == sessionLeaderPid) {
        members += pid
        if (members.size >= MAX_DESCENDANTS) break
      }
    }
    return members.sorted()
  }

  private fun readStat(pid: Long): String? = runCatching {
    File(procRoot, "$pid/stat").readText().take(2048)
  }.getOrNull()

  /**
   * Parses the session id from a /proc stat line. Fields before the comm are
   * `pid (comm)` and comm may contain spaces or parentheses, so parsing starts
   * after the last ')': state ppid pgrp session …
   */
  private fun parseSessionField(stat: String): Long? {
    val close = stat.lastIndexOf(')')
    if (close < 0 || close + 1 >= stat.length) return null
    val fields = stat.substring(close + 1).trim().split(Regex("\\s+"))
    return fields.getOrNull(3)?.toLongOrNull()
  }

  private fun parseStateField(stat: String): Char? {
    val close = stat.lastIndexOf(')')
    if (close < 0 || close + 1 >= stat.length) return null
    return stat.substring(close + 1).trim().firstOrNull()
  }

  companion object {
    const val MAX_DESCENDANT_DEPTH = 8
    const val MAX_DESCENDANTS = 128
    const val MAX_SESSION_SCAN_PIDS = 4096
  }
}
