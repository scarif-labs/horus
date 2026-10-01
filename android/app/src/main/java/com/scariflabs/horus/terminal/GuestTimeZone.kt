package com.scariflabs.horus.terminal

import java.io.File
import java.util.Locale
import java.util.TimeZone

/**
 * TZ value for the guest, so `ls -l`, `date`, and `git log` show the phone's
 * local time instead of UTC. The IANA name is used when the guest has tzdata
 * for it; otherwise a fixed POSIX offset for the current moment is used,
 * which musl understands without any zoneinfo files. Each session launch
 * recomputes it, so a DST change applies from the next session on.
 */
object GuestTimeZone {
  private val ianaId = Regex("^[A-Za-z0-9_+-]+(/[A-Za-z0-9_+-]+)*$")
  private val abbreviation = Regex("^[A-Za-z]{3,6}$")

  fun forGuest(rootfsDir: File, zone: TimeZone = TimeZone.getDefault(), nowMs: Long = System.currentTimeMillis()): String {
    val id = zone.id
    if (ianaId.matches(id) && !id.contains("..") && File(rootfsDir, "usr/share/zoneinfo/$id").isFile) return id
    return posixOffset(zone, nowMs)
  }

  internal fun posixOffset(zone: TimeZone, nowMs: Long): String {
    val offsetMinutes = zone.getOffset(nowMs) / 60_000
    val sign = if (offsetMinutes < 0) "-" else "+"
    val hours = Math.abs(offsetMinutes) / 60
    val minutes = Math.abs(offsetMinutes) % 60
    val inDaylight = zone.inDaylightTime(java.util.Date(nowMs))
    val shortName = zone.getDisplayName(inDaylight, TimeZone.SHORT, Locale.US)
    val name = if (abbreviation.matches(shortName)) shortName else String.format(Locale.US, "<%s%02d%02d>", sign, hours, minutes)
    // POSIX offsets are west-positive, the opposite of the usual notation.
    val posixSign = if (offsetMinutes <= 0) "" else "-"
    return if (minutes == 0) "$name$posixSign$hours" else String.format(Locale.US, "%s%s%d:%02d", name, posixSign, hours, minutes)
  }
}
