package com.scariflabs.horus.terminal

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Streaming HTTPS archive download with an exact byte-size contract: at most
 * expectedBytes+1 bytes are accepted, and the caller verifies the digest
 * before anything is promoted. Plain HttpURLConnection keeps the terminal
 * namespace free of third-party dependencies.
 */
class HttpArchiveDownloader(
  // Some mirrors (Tsinghua, Aliyun) answer 403 to Android's default
  // "Dalvik/…" user agent, so name the client.
  private val userAgent: String = DEFAULT_USER_AGENT,
) : DistroStoreCore.ArchiveDownloader {

  @Throws(IOException::class)
  override fun download(url: String, expectedBytes: Long, destination: File, timeoutMs: Long): Long {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.connectTimeout = timeoutMs.toInt().coerceAtMost(30_000)
    connection.readTimeout = timeoutMs.toInt().coerceAtMost(60_000)
    connection.instanceFollowRedirects = true
    connection.setRequestProperty("User-Agent", userAgent)
    return try {
      val status = connection.responseCode
      if (status !in 200..299) throw IOException("HTTP $status for ${URL(url).host}")
      var total = 0L
      destination.parentFile?.mkdirs()
      FileOutputStream(destination, false).use { output ->
        connection.inputStream.use { input ->
          val buffer = ByteArray(1 shl 16)
          while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > expectedBytes) {
              throw IOException("download exceeds the expected $expectedBytes bytes")
            }
            output.write(buffer, 0, read)
          }
        }
      }
      total
    } catch (error: IOException) {
      destination.delete()
      throw error
    } finally {
      connection.disconnect()
    }
  }

  companion object {
    const val DEFAULT_USER_AGENT = "Horus (Android)"
  }
}
