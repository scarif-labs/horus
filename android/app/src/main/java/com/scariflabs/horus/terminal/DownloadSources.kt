package com.scariflabs.horus.terminal

import java.io.File
import java.nio.file.Files
import org.json.JSONObject

/**
 * Where Horus downloads Alpine and npm packages from. Null means the default
 * server. A mirror cannot change what gets installed: the rootfs is checked
 * against its pinned SHA-256 and apk checks Alpine's package signatures.
 */
data class DownloadSources(
  /** Base of an Alpine mirror, e.g. https://mirrors.tuna.tsinghua.edu.cn/alpine */
  val alpineMirror: String? = null,
  /** An npm registry, e.g. https://registry.npmmirror.com */
  val npmRegistry: String? = null,
) {
  val alpineBase: String get() = alpineMirror ?: DEFAULT_ALPINE_MIRROR

  /** The pinned minirootfs on this mirror. */
  val rootfsUrl: String
    get() = "$alpineBase/${AlpineRootfsCatalog.ALPINE_BRANCH}/releases/${AlpineRootfsCatalog.EXPECTED_GUEST_ARCH}/" +
      "alpine-minirootfs-${AlpineRootfsCatalog.ALPINE_RELEASE}-${AlpineRootfsCatalog.EXPECTED_GUEST_ARCH}.tar.gz"

  /** Variables for guest processes: the provisioning script rewrites apk's repositories from the first. */
  fun guestEnvironment(): List<String> = buildList {
    add("HORUS_ALPINE_MIRROR=$alpineBase")
    npmRegistry?.let { add("npm_config_registry=$it") }
  }

  companion object {
    const val DEFAULT_ALPINE_MIRROR = "https://dl-cdn.alpinelinux.org/alpine"
    const val MAX_URL_LENGTH = 200

    // https only, no credentials, query, or fragment, and nothing a shell or
    // sed replacement would treat specially.
    private val URL_PATTERN = Regex("^https://[A-Za-z0-9.-]+(:[0-9]{1,5})?(/[A-Za-z0-9._~-]+)*/?$")

    /** The URL without a trailing slash, or null when it is not a usable mirror. */
    fun normalize(url: String?): String? {
      val trimmed = url?.trim() ?: return null
      if (trimmed.length > MAX_URL_LENGTH || !URL_PATTERN.matches(trimmed)) return null
      return trimmed.trimEnd('/')
    }
  }
}

/** Cross-process store for [DownloadSources]; reads are fresh, writes atomic. */
class DownloadSourceSettings(private val file: File) {

  @Synchronized
  fun read(): DownloadSources {
    if (Files.isSymbolicLink(file.toPath()) || !file.isFile || file.length() > MAX_FILE_BYTES) return DownloadSources()
    val json = runCatching { JSONObject(file.readText(Charsets.UTF_8)) }.getOrNull() ?: return DownloadSources()
    return DownloadSources(
      alpineMirror = DownloadSources.normalize(json.optString(KEY_ALPINE, "").ifEmpty { null }),
      npmRegistry = DownloadSources.normalize(json.optString(KEY_NPM, "").ifEmpty { null }),
    )
  }

  @Synchronized
  fun write(sources: DownloadSources): Boolean {
    val parent = file.parentFile ?: return false
    if (Files.isSymbolicLink(parent.toPath())) return false
    if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) return false
    if (Files.isSymbolicLink(file.toPath())) return false
    val temporary = File(parent, ".${file.name}.tmp")
    if (Files.isSymbolicLink(temporary.toPath())) return false
    val json = JSONObject().apply {
      sources.alpineMirror?.let { put(KEY_ALPINE, it) }
      sources.npmRegistry?.let { put(KEY_NPM, it) }
    }
    return runCatching {
      temporary.writeText(json.toString(), Charsets.UTF_8)
      if (!temporary.renameTo(file)) {
        temporary.delete()
        false
      } else {
        true
      }
    }.getOrElse {
      temporary.delete()
      false
    }
  }

  companion object {
    private const val KEY_ALPINE = "alpineMirror"
    private const val KEY_NPM = "npmRegistry"
    private const val MAX_FILE_BYTES = 2048L

    fun forStorageRoot(filesDir: File): DownloadSourceSettings = DownloadSourceSettings(
      File(filesDir, "${TerminalRuntimeContract.STORAGE_ROOT_DIR_NAME}/settings/download-sources.json"),
    )
  }
}
