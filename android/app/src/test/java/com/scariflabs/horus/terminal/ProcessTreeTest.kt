package com.scariflabs.horus.terminal

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** /proc observation helpers against a synthetic tree. */
class ProcessTreeTest {

  @get:Rule
  val temp = TemporaryFolder()

  private fun writeStat(procRoot: File, pid: Long, session: Long, comm: String = "proc") {
    val dir = File(procRoot, pid.toString())
    dir.mkdirs()
    File(dir, "stat").writeText("$pid ($comm) R 1 $pid $session 0 0\n")
  }

  private fun writeStateStat(procRoot: File, pid: Long, session: Long, state: Char) {
    val dir = File(procRoot, pid.toString())
    dir.mkdirs()
    File(dir, "stat").writeText("$pid (proc) $state 1 $pid $session 0 0\n")
  }

  private fun writeChildren(procRoot: File, pid: Long, children: List<Long>) {
    val dir = File(procRoot, "$pid/task/$pid")
    dir.mkdirs()
    File(dir, "children").writeText(children.joinToString(" "))
  }

  @Test
  fun descendantsTraverseBreadthFirstThroughTheChildrenFiles() {
    val procRoot = temp.newFolder("proc")
    writeChildren(procRoot, 1, listOf(2, 3))
    writeChildren(procRoot, 2, listOf(4))
    writeChildren(procRoot, 4, emptyList())
    writeStat(procRoot, 1, 1)
    writeStat(procRoot, 2, 1)
    writeStat(procRoot, 3, 1)
    writeStat(procRoot, 4, 1)

    val tree = ProcessTree(procRoot)
    assertEquals(listOf(2L, 3L, 4L), tree.descendants(1))
    assertEquals(listOf(4L), tree.descendants(2))
    assertEquals(emptyList<Long>(), tree.descendants(3))
    assertEquals(emptyList<Long>(), tree.descendants(99))
  }

  @Test
  fun isAliveChecksDirectoryPresenceAndExcludesZombies() {
    val procRoot = temp.newFolder("proc")
    writeStat(procRoot, 42, 7)
    val tree = ProcessTree(procRoot)
    assertTrue(tree.isAlive(42))
    assertFalse(tree.isAlive(43))
    assertFalse(tree.isAlive(0))
    assertFalse(tree.isAlive(-1))
  }

  @Test
  fun zombiesAreNotReportedAsLiveProcesses() {
    val procRoot = temp.newFolder("proc-zombie")
    writeStateStat(procRoot, 42, 42, 'Z')
    writeStateStat(procRoot, 43, 43, 'R')
    val tree = ProcessTree(procRoot)
    assertFalse(tree.isAlive(42))
    assertTrue(tree.isAlive(43))
  }

  @Test
  fun sessionFieldParsingSurvivesCommsWithParentheses() {
    val procRoot = temp.newFolder("proc")
    File(procRoot, "10").mkdirs()
    File(procRoot, "10/stat").writeText("10 (weird) name) R 1 10 55 0 0\n")
    File(procRoot, "11").mkdirs()
    File(procRoot, "11/stat").writeText("11 (plain) S 1 11 56\n")
    val tree = ProcessTree(procRoot)
    assertEquals(55L, tree.sessionOfPid(10))
    assertEquals(56L, tree.sessionOfPid(11))
    assertEquals(null, tree.sessionOfPid(12))
  }

  @Test
  fun sessionMembersFindEveryPidInThatSessionExceptTheLeader() {
    val procRoot = temp.newFolder("proc")
    writeStat(procRoot, 50, 50, "leader")
    writeStat(procRoot, 100, 50, "same-session")
    writeStat(procRoot, 101, 51, "other-session")
    writeStat(procRoot, 102, 50, "straggler")
    val tree = ProcessTree(procRoot)
    assertEquals(listOf(100L, 102L), tree.sessionMembers(50))
    // Session 51 has one member (101); a session nobody joined has none.
    assertEquals(listOf(101L), tree.sessionMembers(51))
    assertEquals(emptyList<Long>(), tree.sessionMembers(52))
  }

  @Test
  fun unexpectedProcEntriesNeverThrow() {
    val procRoot = temp.newFolder("proc")
    val target = temp.newFolder("real-12")
    Files.createSymbolicLink(File(procRoot, "12").toPath(), target.toPath())
    File(procRoot, "13").mkdirs()
    File(procRoot, "13/stat").writeText("not a stat line at all")
    val tree = ProcessTree(procRoot)
    // A symlinked entry resolves like a directory (java.io.File semantics);
    // what matters is that observation never throws and reports no children.
    assertEquals(null, tree.sessionOfPid(13))
    assertEquals(emptyList<Long>(), tree.descendants(12))
    assertEquals(emptyList<Long>(), tree.descendants(13))
  }
}
