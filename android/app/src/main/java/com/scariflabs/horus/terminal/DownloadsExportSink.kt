package com.scariflabs.horus.terminal

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Writes exported guest files under the shared Download/Horus/<folder>
 * directory. Android 10+ inserts through MediaStore and needs no permission;
 * Android 7-9 writes the public directory directly and needs
 * WRITE_EXTERNAL_STORAGE, which the JS caller requests first.
 *
 * Shared storage cannot hold empty directories through MediaStore, so only
 * files are written; their folders are created implicitly.
 */
class DownloadsExportSink(
  private val context: Context,
  folderName: String,
) : GuestFileBrowser.ExportSink {
  /** Shown to the user, e.g. "Download/Horus/project-20260928-143205". */
  val displayPath: String = "${Environment.DIRECTORY_DOWNLOADS}/$HORUS_DIRECTORY/$folderName"

  private val legacyScanPaths = ArrayList<String>()

  override fun writeFile(relativePath: List<String>, source: InputStream) {
    val safe = relativePath.map(GuestFileBrowser::exportSafeName)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) writeMediaStore(safe, source) else writeLegacy(safe, source)
  }

  /** Lets the legacy media scanner index the new files so they show up over MTP. */
  fun finish() {
    if (legacyScanPaths.isEmpty()) return
    MediaScannerConnection.scanFile(context, legacyScanPaths.toTypedArray(), null, null)
  }

  private fun writeMediaStore(relativePath: List<String>, source: InputStream) {
    val resolver = context.contentResolver
    val name = relativePath.last()
    val directory = (listOf(displayPath) + relativePath.dropLast(1)).joinToString("/", postfix = "/")
    val values = ContentValues().apply {
      put(MediaStore.MediaColumns.DISPLAY_NAME, name)
      put(MediaStore.MediaColumns.MIME_TYPE, mimeTypeFor(name))
      put(MediaStore.MediaColumns.RELATIVE_PATH, directory)
      put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
      ?: throw IOException("MediaStore refused $name")
    try {
      val output = resolver.openOutputStream(uri, "w") ?: throw IOException("no output stream")
      output.use { source.copyTo(it) }
      resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    } catch (error: Exception) {
      try {
        resolver.delete(uri, null, null)
      } catch (_: Exception) {
        // Best effort; a pending row is hidden and expires on its own.
      }
      throw error as? IOException ?: IOException(error)
    }
  }

  private fun writeLegacy(relativePath: List<String>, source: InputStream) {
    val permission = context.checkPermission(
      Manifest.permission.WRITE_EXTERNAL_STORAGE,
      Process.myPid(),
      Process.myUid(),
    )
    if (permission != PackageManager.PERMISSION_GRANTED) throw SecurityException("storage permission denied")
    @Suppress("DEPRECATION")
    val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    val target = relativePath.fold(File(downloads, displayPath.substringAfter('/'))) { parent, part -> File(parent, part) }
    val parent = target.parentFile ?: throw IOException("no parent")
    if (!parent.isDirectory && !parent.mkdirs()) throw IOException("cannot create $parent")
    try {
      target.outputStream().use { source.copyTo(it) }
    } catch (error: IOException) {
      target.delete()
      throw error
    }
    legacyScanPaths += target.absolutePath
  }

  /** Also true on Android 10+, where no permission is needed. */
  fun hasWriteAccess(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
    context.checkPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE, Process.myPid(), Process.myUid()) ==
    PackageManager.PERMISSION_GRANTED

  private companion object {
    const val HORUS_DIRECTORY = "Horus"

    /**
     * MediaStore appends an extension that matches the MIME type, so an
     * extensionless file (Makefile, .env) must stay application/octet-stream.
     */
    fun mimeTypeFor(name: String): String {
      val extension = name.substringAfterLast('.', "").lowercase()
      if (extension.isEmpty() || name.startsWith('.') && name.indexOf('.', 1) < 0) return OCTET_STREAM
      return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: OCTET_STREAM
    }

    const val OCTET_STREAM = "application/octet-stream"
  }
}
