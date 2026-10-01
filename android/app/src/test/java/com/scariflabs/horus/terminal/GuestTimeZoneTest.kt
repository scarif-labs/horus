package com.scariflabs.horus.terminal

import java.io.File
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GuestTimeZoneTest {
  @get:Rule
  val temp = TemporaryFolder()

  private val january = 1_767_225_600_000L // 2026-01-01T00:00:00Z
  private val july = 1_782_864_000_000L // 2026-07-01T00:00:00Z

  @Test
  fun `uses the IANA name when the guest has tzdata for it`() {
    val rootfs = temp.newFolder("rootfs")
    File(rootfs, "usr/share/zoneinfo/Asia").mkdirs()
    File(rootfs, "usr/share/zoneinfo/Asia/Kolkata").writeText("TZif")
    assertEquals("Asia/Kolkata", GuestTimeZone.forGuest(rootfs, TimeZone.getTimeZone("Asia/Kolkata"), january))
  }

  @Test
  fun `falls back to a POSIX offset without tzdata`() {
    val rootfs = temp.newFolder("rootfs")
    assertEquals("IST-5:30", GuestTimeZone.forGuest(rootfs, TimeZone.getTimeZone("Asia/Kolkata"), january))
    assertEquals("EST5", GuestTimeZone.forGuest(rootfs, TimeZone.getTimeZone("America/New_York"), january))
    assertEquals("EDT4", GuestTimeZone.forGuest(rootfs, TimeZone.getTimeZone("America/New_York"), july))
    assertEquals("<+0545>-5:45", GuestTimeZone.posixOffset(TimeZone.getTimeZone("GMT+05:45"), january))
  }

  @Test
  fun `never follows a zone id outside zoneinfo`() {
    val rootfs = temp.newFolder("rootfs")
    File(rootfs, "usr/share/zoneinfo").mkdirs()
    File(rootfs, "etc").mkdirs()
    File(rootfs, "etc/passwd").writeText("x")
    val sneaky = TimeZone.getTimeZone("UTC").apply { id = "../../../etc/passwd" }
    assertEquals("<+0000>0", GuestTimeZone.forGuest(rootfs, sneaky, january))
  }
}
