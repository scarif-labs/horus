package com.scariflabs.horus.terminal

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Path-safety and format coverage for the rootfs extractor. Every attack
 * entry is built as real tar bytes by [TarFixture]; nothing here trusts the
 * extractor to be its own fixture.
 */
class SafeTarGzExtractorTest {
  @get:Rule
  val temp = TemporaryFolder()

  private var fixtureNumber = 0
  private lateinit var extractionRoot: File

  private fun extractFrom(entries: List<TarFixture.Entry>): SafeTarGzExtractor.Summary {
    val suffix = fixtureNumber++
    val archive = temp.newFile("fixture-$suffix.tar.gz")
    archive.writeBytes(TarFixture.buildGzip(entries))
    extractionRoot = temp.newFolder("root-$suffix")
    return SafeTarGzExtractor().extract(archive, extractionRoot)
  }

  @Test
  fun `extracts directories files symlinks and hardlinks with modes`() {
    val summary = extractFrom(
      listOf(
        TarFixture.Entry.Dir("etc"),
        TarFixture.Entry.File("etc/alpine-release", "3.24.0\n".toByteArray()),
        TarFixture.Entry.File("bin/busybox", byteArrayOf(1, 2, 3), mode = 0b111_101_101),
        TarFixture.Entry.Dir("usr"),
        TarFixture.Entry.Dir("usr/bin"),
        TarFixture.Entry.File("usr/bin/tool", byteArrayOf(9, 8, 7), mode = 0b111_101_101),
        TarFixture.Entry.Hardlink("usr/bin/tool-link", "usr/bin/tool"),
        TarFixture.Entry.Symlink("bin/sh", "/usr/bin/busybox"),
      ),
    )
    assertEquals(8, summary.entryCount)
    assertEquals(3, summary.fileCount)
    assertEquals(3, summary.directoryCount)
    assertEquals(1, summary.symlinkCount)
    assertEquals(1, summary.hardlinkCount)
    val root = extractionRoot
    assertEquals("3.24.0\n", root.resolve("etc/alpine-release").readText())
    assertTrue(root.resolve("bin/busybox").canExecute())
    assertFalse(root.resolve("etc/alpine-release").canExecute())
    assertTrue(Files.isSymbolicLink(root.resolve("bin/sh").toPath()))
    assertEquals(3, root.resolve("usr/bin/tool-link").length())
  }

  @Test
  fun `rejects absolute entry names`() {
    val error = assertThrows(SafeTarGzExtractor.UnsafeArchiveException::class.java) {
      extractFrom(listOf(TarFixture.Entry.File("/etc/evil", byteArrayOf(1))))
    }
    assertTrue(error.message!!.contains("unsafe entry name"))
  }

  @Test
  fun `rejects traversal entry names`() {
    val error = assertThrows(SafeTarGzExtractor.UnsafeArchiveException::class.java) {
      extractFrom(listOf(TarFixture.Entry.File("../evil", byteArrayOf(1))))
    }
    assertTrue(error.message!!.contains("unsafe entry name"))
    val dotted = assertThrows(SafeTarGzExtractor.UnsafeArchiveException::class.java) {
      extractFrom(listOf(TarFixture.Entry.File("safe/../../evil", byteArrayOf(1))))
    }
    assertTrue(dotted.message!!.contains("unsafe entry name"))
  }

  @Test
  fun `rejects a symlink redirect outside the root`() {
    // First entry plants a symlink pointing outside; the second tries to
    // write through it. The canonical parent check must reject the write.
    val error = assertThrows(SafeTarGzExtractor.UnsafeArchiveException::class.java) {
      extractFrom(
        listOf(
          TarFixture.Entry.Dir("etc"),
          TarFixture.Entry.Symlink("etc/pwned", "../../outside"),
          TarFixture.Entry.File("etc/pwned/secret", byteArrayOf(1)),
        ),
      )
    }
    assertTrue(error.message!!.contains("escapes the extraction root"))
    assertFalse(File(temp.root, "outside").exists())
  }

  @Test
  fun `rejects a hardlink target outside the root`() {
    val error = assertThrows(SafeTarGzExtractor.UnsafeArchiveException::class.java) {
      extractFrom(
        listOf(
          TarFixture.Entry.File("keep", byteArrayOf(1)),
          TarFixture.Entry.Hardlink("alias", "../keep"),
        ),
      )
    }
    assertTrue(error.message!!.contains("unsafe hardlink target"))
  }

  @Test
  fun `supports ustar prefix long names pax path overrides and gnu longname`() {
    val longName = "usr/lib/very-long-directory-name/".repeat(8).trimEnd('/') + "/payload"
    val summary = extractFrom(
      listOf(
        TarFixture.Entry.File("file", ByteArray(7) { 7 }, prefix = "prefixed"),
        TarFixture.Entry.PaxPath("pax/renamed-entry", TarFixture.Entry.File("ignored-name", ByteArray(8) { 8 })),
        TarFixture.Entry.GnuLongName(longName, TarFixture.Entry.File("ignored", ByteArray(9) { 9 })),
      ),
    )
    assertEquals(3, summary.fileCount)
    val root = extractionRoot
    assertEquals(7, root.resolve("prefixed/file").length())
    assertEquals(8, root.resolve("pax/renamed-entry").length())
    assertEquals(9, root.resolve(longName).length())
  }

  @Test
  fun `handles utf8 pax byte lengths and clears one-entry path state`() {
    val summary = extractFrom(
      listOf(
        TarFixture.Entry.PaxPath(
          "pax/renamed-é.txt",
          TarFixture.Entry.File("ignored-name", ByteArray(8) { 8 }),
        ),
        TarFixture.Entry.File("ordinary.txt", ByteArray(513) { 4 }),
      ),
    )
    assertEquals(2, summary.fileCount)
    val root = extractionRoot
    assertEquals(8, root.resolve("pax/renamed-é.txt").length())
    assertEquals(513, root.resolve("ordinary.txt").length())
    assertFalse(root.resolve("pax/ordinary.txt").exists())
  }

  @Test
  fun `skips device nodes and records them`() {
    val summary = extractFrom(
      listOf(
        TarFixture.Entry.Dir("dev"),
        TarFixture.Entry.Raw(TarFixture.header("dev/null", 0b110_100_100, 0, '3')),
      ),
    )
    assertEquals(1, summary.skippedEntries.size)
    assertTrue(summary.skippedEntries.single().startsWith("dev/null"))
  }

  @Test
  fun `rejects unknown entry types and non-gzip input`() {
    assertThrows(SafeTarGzExtractor.UnsafeArchiveException::class.java) {
      extractFrom(listOf(TarFixture.Entry.Raw(TarFixture.header("weird", typeFlag = 'Z'))))
    }
    val archive = temp.newFile("not-gzip.tar.gz")
    archive.writeBytes("definitely not gzip".toByteArray())
    assertThrows(Exception::class.java) {
      SafeTarGzExtractor().extract(archive, temp.newFolder("root2"))
    }
  }

  @Test
  fun `sanitize rules are total`() {
    val extractor = SafeTarGzExtractor()
    assertEquals("safe/path", extractor.sanitizeRelativeName("safe/path"))
    assertEquals("safe/path", extractor.sanitizeRelativeName("safe/path/"))
    assertNullish(extractor.sanitizeRelativeName(""))
    assertNullish(extractor.sanitizeRelativeName("/leading"))
    assertNullish(extractor.sanitizeRelativeName("double//slash"))
    assertNullish(extractor.sanitizeRelativeName("dot/./slash"))
    assertNullish(extractor.sanitizeRelativeName("up/../down"))
    assertNullish(extractor.sanitizeRelativeName("back\\slash"))
  }

  private fun assertNullish(value: String?) {
    assertEquals(null, value)
  }
}
