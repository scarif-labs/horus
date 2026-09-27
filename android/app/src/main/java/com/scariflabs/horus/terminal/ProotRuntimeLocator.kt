package com.scariflabs.horus.terminal

import java.io.File
import java.security.MessageDigest

/**
 * Locates and verifies the PRoot runtime inside the APK's native library
 * directory (the only app-visible location Android keeps executable). The
 * digests come from the packaged alpine-runtime manifest asset, so a tampered
 * or half-packaged build fails closed before any process is started.
 */
class ProotRuntimeLocator(private val manifestJson: String) {

  data class LocatedRuntime(
    val prootBin: File,
    val loaderBin: File,
    val libraryDir: File,
    val prootVersion: String,
  )

  sealed interface Location {
    data class Available(val runtime: LocatedRuntime) : Location
    data class Unavailable(val reasonCode: String, val detail: String) : Location
  }

  fun locate(nativeLibraryDir: File): Location {
    val manifest = try {
      org.json.JSONObject(manifestJson)
    } catch (_: Exception) {
      return Location.Unavailable("proot_manifest_invalid", "packaged alpine-runtime manifest is not JSON")
    }
    if (manifest.optString("status") != "available") {
      return Location.Unavailable("proot_manifest_unavailable", "packaged manifest status is ${manifest.optString("status")}")
    }
    val artifacts = manifest.optJSONObject("target")?.optJSONArray("artifacts")
      ?: return Location.Unavailable("proot_manifest_missing_artifacts", "manifest has no artifact list")
    val expected = mutableMapOf<String, String>()
    for (i in 0 until artifacts.length()) {
      val artifact = artifacts.optJSONObject(i) ?: continue
      val jniName = artifact.optString("jniName")
      val sha256 = artifact.optString("sha256")
      if (jniName.isNotEmpty() && sha256.length == 64) expected[jniName] = sha256
    }
    if (!expected.containsKey(PROOT_JNI_NAME) || !expected.containsKey(LOADER_JNI_NAME)) {
      return Location.Unavailable("proot_manifest_missing_entries", "proot or loader entry missing")
    }
    for (jniName in expected.keys) {
      val file = File(nativeLibraryDir, jniName)
      if (!file.isFile || !file.canRead()) {
        return Location.Unavailable("proot_binary_missing", "$jniName is not present in the native library directory")
      }
      if (!file.canExecute() && jniName == PROOT_JNI_NAME) {
        return Location.Unavailable("proot_binary_not_executable", "$jniName lost its execute permission")
      }
      if (sha256OfFile(file) != expected[jniName]) {
        return Location.Unavailable("proot_digest_mismatch", "$jniName does not match the packaged digest")
      }
    }
    return Location.Available(
      LocatedRuntime(
        prootBin = File(nativeLibraryDir, PROOT_JNI_NAME),
        loaderBin = File(nativeLibraryDir, LOADER_JNI_NAME),
        libraryDir = nativeLibraryDir,
        prootVersion = manifest.optJSONObject("source")?.optJSONArray("packages")?.optJSONObject(0)?.optString("version") ?: "unknown",
      ),
    )
  }

  companion object {
    const val PROOT_JNI_NAME = "libproot.so"
    const val LOADER_JNI_NAME = "libproot_loader.so"

    private fun sha256OfFile(file: File): String {
      val digest = MessageDigest.getInstance("SHA-256")
      file.inputStream().use { input ->
        val buffer = ByteArray(1 shl 16)
        while (true) {
          val read = input.read(buffer)
          if (read < 0) break
          digest.update(buffer, 0, read)
        }
      }
      return digest.digest().joinToString("") { "%02x".format(it) }
    }
  }
}
