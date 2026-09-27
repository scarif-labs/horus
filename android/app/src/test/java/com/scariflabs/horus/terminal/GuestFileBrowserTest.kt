package com.scariflabs.horus.terminal

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GuestFileBrowserTest {
  @get:Rule
  val temp = TemporaryFolder()

  private lateinit var home: File
  private lateinit var workspace: File
  private lateinit var browser: GuestFileBrowser

  @Before
  fun setUp() {
    home = temp.newFolder("home")
    workspace = temp.newFolder("workspaces", "default")
    browser = GuestFileBrowser(homeRoot = home, workspaceRoot = workspace)
  }

  @Test
  fun listsKindsAndRegularFileSizesUnderEachRoot() {
    File(workspace, "notes.txt").writeText("hello")
    File(workspace, ".env").writeText("A=1\n")
    File(workspace, "src").mkdir()
    File(workspace, "src/main.ts").writeText("x")
    Files.createSymbolicLink(File(workspace, "link").toPath(), File(workspace, "notes.txt").toPath())
    Files.createSymbolicLink(File(workspace, "dir-link").toPath(), File(workspace, "src").toPath())
    File(home, "only-in-home").writeText("")

    val listing = browser.list("workspace", emptyList()) as GuestFileBrowser.ListOutcome.Success
    assertFalse(listing.truncated)
    assertEquals(0, listing.hiddenInvalidNameCount)
    assertEquals(
      listOf(
        entry(".env", GuestFileBrowser.EntryKind.FILE, 4),
        entry("dir-link", GuestFileBrowser.EntryKind.SYMLINK, 0),
        entry("link", GuestFileBrowser.EntryKind.SYMLINK, 0),
        entry("notes.txt", GuestFileBrowser.EntryKind.FILE, 5),
        entry("src", GuestFileBrowser.EntryKind.DIRECTORY, 0),
      ),
      listing.entries.sortedBy { it.name },
    )

    val nested = browser.list("workspace", listOf("src")) as GuestFileBrowser.ListOutcome.Success
    assertEquals(listOf(entry("main.ts", GuestFileBrowser.EntryKind.FILE, 1)), nested.entries)

    val homeListing = browser.list("home", emptyList()) as GuestFileBrowser.ListOutcome.Success
    assertEquals(listOf(entry("only-in-home", GuestFileBrowser.EntryKind.FILE, 0)), homeListing.entries)
  }

  @Test
  fun capsListingAtTheLimitAndReportsTruncation() {
    repeat(GuestFileBrowser.LIST_LIMIT) { index -> File(workspace, "entry-$index").writeText("") }
    val exact = browser.list("workspace", emptyList()) as GuestFileBrowser.ListOutcome.Success
    assertEquals(GuestFileBrowser.LIST_LIMIT, exact.entries.size)
    assertFalse(exact.truncated)

    File(workspace, "one-more").writeText("")
    val capped = browser.list("workspace", emptyList()) as GuestFileBrowser.ListOutcome.Success
    assertEquals(GuestFileBrowser.LIST_LIMIT, capped.entries.size)
    assertTrue(capped.truncated)
    assertEquals(capped.entries.size, capped.entries.map { it.name }.toSet().size)
  }

  @Test
  fun symlinkPathComponentsAreInvalidAndNeverFollowed() {
    val outside = temp.newFolder("outside")
    File(outside, "secret.txt").writeText("secret")
    Files.createSymbolicLink(File(workspace, "escape").toPath(), outside.toPath())
    Files.createSymbolicLink(File(workspace, "secret-link").toPath(), File(outside, "secret.txt").toPath())

    assertEquals(GuestFileBrowser.ListOutcome.Failure("invalid_path"), browser.list("workspace", listOf("escape")))
    assertEquals(
      GuestFileBrowser.ReadOutcome.Failure("invalid_path"),
      browser.read("workspace", listOf("escape", "secret.txt")),
    )
    assertEquals(GuestFileBrowser.ReadOutcome.Failure("invalid_path"), browser.read("workspace", listOf("secret-link")))
  }

  @Test
  fun symlinkedOrMissingRootIsNotFound() {
    val real = temp.newFolder("real-root")
    File(real, "a.txt").writeText("a")
    val linkedRoot = File(temp.root, "linked-root")
    Files.createSymbolicLink(linkedRoot.toPath(), real.toPath())
    val linked = GuestFileBrowser(homeRoot = linkedRoot, workspaceRoot = File(temp.root, "missing"))

    assertEquals(GuestFileBrowser.ListOutcome.Failure("not_found"), linked.list("home", emptyList()))
    assertEquals(GuestFileBrowser.ReadOutcome.Failure("not_found"), linked.read("home", listOf("a.txt")))
    assertEquals(GuestFileBrowser.ListOutcome.Failure("not_found"), linked.list("workspace", emptyList()))
    assertEquals(GuestFileBrowser.ReadOutcome.Failure("not_found"), linked.read("workspace", listOf("a.txt")))
  }

  @Test
  fun missingAndNonDirectoryComponentsAreNotFound() {
    File(workspace, "file.txt").writeText("x")
    File(workspace, "dir").mkdir()

    assertEquals(GuestFileBrowser.ListOutcome.Failure("not_found"), browser.list("workspace", listOf("nope")))
    assertEquals(GuestFileBrowser.ListOutcome.Failure("not_found"), browser.list("workspace", listOf("file.txt")))
    assertEquals(GuestFileBrowser.ReadOutcome.Failure("not_found"), browser.read("workspace", listOf("nope")))
    assertEquals(GuestFileBrowser.ReadOutcome.Failure("not_found"), browser.read("workspace", listOf("file.txt", "x")))
    assertEquals(GuestFileBrowser.ReadOutcome.Failure("not_found"), browser.read("workspace", listOf("dir")))
  }

  @Test
  fun rejectsInvalidRootsAndPathComponents() {
    val invalid = listOf(
      listOf(".."),
      listOf("."),
      listOf(""),
      listOf("a/b"),
      listOf("/"),
      listOf("nul\u0000byte"),
      listOf("bad\uD800surrogate"),
      listOf("x".repeat(256)),
      List(17) { "a" },
      List(16) { "y".repeat(200) },
    )
    invalid.forEach { path ->
      assertEquals(path.toString(), GuestFileBrowser.ListOutcome.Failure("invalid_path"), browser.list("workspace", path))
      assertEquals(path.toString(), GuestFileBrowser.ReadOutcome.Failure("invalid_path"), browser.read("workspace", path))
    }
    assertEquals(GuestFileBrowser.ReadOutcome.Failure("invalid_path"), browser.read("workspace", emptyList()))
    assertEquals(GuestFileBrowser.ListOutcome.Failure("invalid_path"), browser.list("rootfs", emptyList()))
    assertTrue(GuestFileBrowser.isValidPath(listOf("x".repeat(255), "λ", "it's")))
    assertTrue(GuestFileBrowser.isValidPath(List(16) { "🌙" }))
  }

  @Test
  fun readsRegularFilesUpToThePreviewLimit() {
    val text = "hello 🌙\n".toByteArray(Charsets.UTF_8)
    File(home, "notes.md").writeBytes(text)
    val read = browser.read("home", listOf("notes.md")) as GuestFileBrowser.ReadOutcome.Success
    assertArrayEquals(text, read.bytes)
    assertEquals(text.size.toLong(), read.sizeBytes)

    File(home, "empty").writeBytes(ByteArray(0))
    val empty = browser.read("home", listOf("empty")) as GuestFileBrowser.ReadOutcome.Success
    assertEquals(0, empty.bytes.size)
    assertEquals(0L, empty.sizeBytes)

    File(home, "exact").writeBytes(ByteArray(GuestFileBrowser.PREVIEW_LIMIT_BYTES) { 'a'.code.toByte() })
    val exact = browser.read("home", listOf("exact")) as GuestFileBrowser.ReadOutcome.Success
    assertEquals(GuestFileBrowser.PREVIEW_LIMIT_BYTES, exact.bytes.size)

    File(home, "big").writeBytes(ByteArray(GuestFileBrowser.PREVIEW_LIMIT_BYTES + 1))
    assertEquals(GuestFileBrowser.ReadOutcome.Failure("too_large"), browser.read("home", listOf("big")))
  }

  @Test
  fun returnsNonUtf8ContentAsRawBytesForTheJsClassifier() {
    val bytes = byteArrayOf(0x66, 0xff.toByte(), 0x00, 0x0a)
    File(workspace, "blob.bin").writeBytes(bytes)
    val read = browser.read("workspace", listOf("blob.bin")) as GuestFileBrowser.ReadOutcome.Success
    assertArrayEquals(bytes, read.bytes)
    assertEquals(bytes.size.toLong(), read.sizeBytes)
  }

  @Test
  fun unreadableFileIsCommandFailed() {
    val file = File(workspace, "locked.txt")
    file.writeText("x")
    assumeTrue(file.setReadable(false, false) && !file.canRead())
    try {
      assertEquals(GuestFileBrowser.ReadOutcome.Failure("command_failed"), browser.read("workspace", listOf("locked.txt")))
    } finally {
      file.setReadable(true, false)
    }
  }

  @Test
  fun hidesNonUtf8EntryNamesButCountsThemTowardTheCap() {
    // Some host filesystems (APFS) refuse non-UTF-8 names; skip there.
    val created = try {
      val process = ProcessBuilder("sh", "-c", "touch \"\$(printf 'bad\\377name')\"")
        .directory(workspace)
        .redirectErrorStream(true)
        .start()
      process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0
    } catch (_: Exception) {
      false
    }
    assumeTrue(created && workspace.list()?.size == 1)
    File(workspace, "good.txt").writeText("ok")

    val listing = browser.list("workspace", emptyList()) as GuestFileBrowser.ListOutcome.Success
    assertEquals(listOf(entry("good.txt", GuestFileBrowser.EntryKind.FILE, 2)), listing.entries)
    assertEquals(1, listing.hiddenInvalidNameCount)

    repeat(GuestFileBrowser.LIST_LIMIT) { index -> File(workspace, "more-$index").writeText("") }
    val capped = browser.list("workspace", emptyList()) as GuestFileBrowser.ListOutcome.Success
    assertTrue(capped.truncated)
    assertEquals(GuestFileBrowser.LIST_LIMIT, capped.entries.size + capped.hiddenInvalidNameCount)
  }

  private fun entry(name: String, kind: GuestFileBrowser.EntryKind, size: Long) =
    GuestFileBrowser.Entry(name, kind, size)
}
