package com.scariflabs.horus.terminal

import android.content.ContentResolver
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Archive source for a rootfs the user downloaded in a browser and picked
 * with the system file picker. It honours the same byte-size contract as
 * [HttpArchiveDownloader]; the caller still verifies the size and digest
 * before anything is promoted, so a wrong file can never be installed.
 */
class ContentUriArchiveReader(
  private val resolver: ContentResolver,
  private val uri: Uri,
) : DistroStoreCore.ArchiveDownloader {

  @Throws(IOException::class)
  override fun download(url: String, expectedBytes: Long, destination: File, timeoutMs: Long): Long {
    val input = resolver.openInputStream(uri) ?: throw IOException("cannot open the picked file")
    var total = 0L
    return try {
      destination.parentFile?.mkdirs()
      FileOutputStream(destination, false).use { output ->
        input.use {
          val buffer = ByteArray(1 shl 16)
          while (true) {
            val read = it.read(buffer)
            if (read < 0) break
            total += read
            if (total > expectedBytes) {
              throw IOException("picked file exceeds the expected $expectedBytes bytes")
            }
            output.write(buffer, 0, read)
          }
        }
      }
      total
    } catch (error: IOException) {
      destination.delete()
      throw error
    } catch (error: SecurityException) {
      destination.delete()
      throw IOException("no permission to read the picked file", error)
    }
  }
}
