package com.scariflabs.horus.terminal

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Opt-in SSH access from a computer over USB. The guest runs dropbear bound
 * to the phone's loopback only; a computer reaches it through `adb forward`,
 * so nothing listens on Wi-Fi. Only public keys paired with the Horus password
 * may log in, and only as the profile user.
 */
object RemoteAccess {
  const val PORT = 8022
  const val ACTION_START = "com.scariflabs.horus.action.REMOTE_ACCESS_START"
  const val ACTION_STOP = "com.scariflabs.horus.action.REMOTE_ACCESS_STOP"
  /** The active user changed; the server must run as the new one. */
  const val ACTION_RESTART = "com.scariflabs.horus.action.REMOTE_ACCESS_RESTART"
  const val DIR_NAME = "remote-access"
  const val STATE_STOPPED = "stopped"
  const val STATE_STARTING = "starting"
  const val STATE_INSTALLING = "installing"
  const val STATE_RUNNING = "running"
  const val STATE_FAILED = "failed"
  const val KEY_COMMENT_PREFIX = "horus:"
  private const val KEY_OPTIONS = "no-agent-forwarding,no-X11-forwarding"
  private const val MAX_KEY_BLOB_BYTES = 1_100
  private const val MAX_AUTHORIZED_KEYS_BYTES = 64 * 1024L
  private const val MAX_KEYS = 32
  private const val MAX_HOST_KEY_BYTES = 4096L
  const val HOST_KEY_NAME = "ssh_host_ed25519_key"
  /** Where the SSH server's PRoot sees the host key directory; no other session binds it. */
  const val GUEST_HOST_KEY_DIR = "/run/horus-hostkey"
  private val LABEL_PATTERN = Regex("[A-Za-z0-9._@-]{1,64}")
  private val ALLOWED_KEY_TYPES = setOf(
    "ssh-ed25519",
    "ecdsa-sha2-nistp256",
    "ecdsa-sha2-nistp384",
    "ecdsa-sha2-nistp521",
    "ssh-rsa",
  )
  private val lock = Any()

  data class PublicKey(val type: String, val data: String, val label: String) {
    val fingerprint: String get() = fingerprintOf(Base64Codec.decode(data)!!)
    fun authorizedLine(): String = "$KEY_OPTIONS $type $data $KEY_COMMENT_PREFIX$label"
  }

  data class AuthorizedKey(val fingerprint: String, val type: String, val label: String, val managed: Boolean)

  sealed interface KeyResult {
    data class Valid(val key: PublicKey) : KeyResult
    data class Invalid(val reason: String) : KeyResult
  }

  /** Accepts one bare public key: a supported type and its matching blob. */
  fun parsePublicKey(type: String?, data: String?, label: String?): KeyResult {
    if (type == null || type !in ALLOWED_KEY_TYPES) return KeyResult.Invalid("unsupported_key_type")
    if (label == null || !LABEL_PATTERN.matches(label)) return KeyResult.Invalid("invalid_label")
    if (data == null || data.length > MAX_KEY_BLOB_BYTES * 2) return KeyResult.Invalid("invalid_key")
    val blob = Base64Codec.decode(data) ?: return KeyResult.Invalid("invalid_key")
    if (blob.size < 20 || blob.size > MAX_KEY_BLOB_BYTES) return KeyResult.Invalid("invalid_key")
    // The blob starts with its own length-prefixed type name; it must agree
    // with the declared type or sshd would never match the key anyway.
    val nameLength = ((blob[0].toInt() and 0xff) shl 24) or ((blob[1].toInt() and 0xff) shl 16) or
      ((blob[2].toInt() and 0xff) shl 8) or (blob[3].toInt() and 0xff)
    if (nameLength != type.length || 4 + nameLength > blob.size) return KeyResult.Invalid("invalid_key")
    if (String(blob, 4, nameLength, Charsets.US_ASCII) != type) return KeyResult.Invalid("invalid_key")
    return KeyResult.Valid(PublicKey(type, Base64Codec.encode(blob, padding = true), label))
  }

  /** OpenSSH-style `SHA256:<base64 without padding>`. */
  fun fingerprintOf(blob: ByteArray): String =
    "SHA256:" + Base64Codec.encode(MessageDigest.getInstance("SHA-256").digest(blob), padding = false)

  /** Parses every key line, including keys the user added by hand. */
  fun parseAuthorizedKeys(content: String): List<AuthorizedKey> = content.lineSequence()
    .map(String::trim)
    .filter { it.isNotEmpty() && !it.startsWith("#") }
    .mapNotNull { line ->
      val fields = line.split(Regex("\\s+"))
      val typeIndex = fields.indexOfFirst { it in ALLOWED_KEY_TYPES }
      if (typeIndex < 0 || typeIndex + 1 >= fields.size) return@mapNotNull null
      val blob = Base64Codec.decode(fields[typeIndex + 1]) ?: return@mapNotNull null
      val comment = fields.drop(typeIndex + 2).joinToString(" ")
      val managed = comment.startsWith(KEY_COMMENT_PREFIX)
      AuthorizedKey(
        fingerprint = fingerprintOf(blob),
        type = fields[typeIndex],
        label = if (managed) comment.removePrefix(KEY_COMMENT_PREFIX) else comment,
        managed = managed,
      )
    }
    .toList()

  /** Adds [key], replacing an earlier line for the same key. */
  fun withKey(content: String, key: PublicKey): String {
    val fingerprint = key.fingerprint
    val kept = content.lineSequence().filter { line ->
      line.isNotBlank() && lineFingerprint(line) != fingerprint
    }.toList()
    return (kept + key.authorizedLine()).joinToString("\n", postfix = "\n")
  }

  fun withoutKey(content: String, fingerprint: String): String {
    val kept = content.lineSequence().filter { line ->
      line.isNotBlank() && lineFingerprint(line) != fingerprint
    }.toList()
    return if (kept.isEmpty()) "" else kept.joinToString("\n", postfix = "\n")
  }

  private fun lineFingerprint(line: String): String? {
    val fields = line.trim().split(Regex("\\s+"))
    val typeIndex = fields.indexOfFirst { it in ALLOWED_KEY_TYPES }
    if (typeIndex < 0 || typeIndex + 1 >= fields.size) return null
    return Base64Codec.decode(fields[typeIndex + 1])?.let(::fingerprintOf)
  }

  /** Paths for the profile user's keys and the service's state files. */
  class Store(storageRoot: File) {
    private val paths = DistroStorePaths(storageRoot)
    val dir = File(storageRoot, DIR_NAME)
    // Alpine's home holds the canonical list. Every distribution's user home
    // gets a copy, so a paired computer reaches whichever user is active.
    private val sshDir = File(paths.home, ".ssh")
    private val authorizedKeys = File(sshDir, "authorized_keys")
    private val mirroredSshDirs = paths.userHomes().filter { it != paths.home }.map { File(it, ".ssh") }
    private val enabledFile = File(dir, "enabled")
    private val statusFile = File(dir, "status")
    val logFile = File(dir, "server.log")
    private val hostKeyDir = File(dir, "hostkey")
    private val hostKey = File(hostKeyDir, HOST_KEY_NAME)
    /** Written by dropbear itself, in the home of the user it runs as; survives the service being killed. */
    val serverPidFiles = paths.userHomes().map { File(it, ".cache/horus/dropbear.pid") }

    fun readServerPid(): Long = serverPidFiles.firstNotNullOfOrNull { file ->
      runCatching {
        if (!file.isFile || file.length() > 32) null else file.readText(Charsets.US_ASCII).trim().toLong()
      }.getOrNull()
    } ?: 0L

    fun deleteServerPidFiles() {
      serverPidFiles.forEach(File::delete)
    }

    fun keys(): List<AuthorizedKey> = synchronized(lock) { parseAuthorizedKeys(readAuthorizedKeys()) }

    fun isAuthorized(fingerprint: String): Boolean = keys().any { it.fingerprint == fingerprint }

    fun addKey(key: PublicKey) = synchronized(lock) {
      val existing = readAuthorizedKeys()
      if (parseAuthorizedKeys(existing).size >= MAX_KEYS && !parseAuthorizedKeys(existing).any { it.fingerprint == key.fingerprint }) {
        throw IllegalStateException("too many keys")
      }
      writeAuthorizedKeys(withKey(existing, key))
    }

    fun removeKey(fingerprint: String): Boolean = synchronized(lock) {
      val existing = readAuthorizedKeys()
      val updated = withoutKey(existing, fingerprint)
      if (updated == existing) return false
      writeAuthorizedKeys(updated)
      true
    }

    fun isEnabled(): Boolean = enabledFile.isFile

    fun setEnabled(enabled: Boolean) {
      if (enabled) atomicWrite(enabledFile, "1\n") else enabledFile.delete()
    }

    fun readStatus(): Status {
      val values = runCatching {
        if (statusFile.length() > 4096) emptyMap() else statusFile.readLines(Charsets.UTF_8)
          .mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }
          .toMap()
      }.getOrDefault(emptyMap())
      return Status(
        state = values["state"] ?: STATE_STOPPED,
        detail = values["detail"].orEmpty(),
        pid = values["pid"]?.toIntOrNull() ?: 0,
      )
    }

    fun writeStatus(status: Status) {
      atomicWrite(statusFile, "state=${status.state}\ndetail=${status.detail}\npid=${status.pid}\n")
    }

    private fun readAuthorizedKeys(): String {
      rejectSymlinks()
      if (!authorizedKeys.exists()) return ""
      if (!authorizedKeys.isFile || authorizedKeys.length() > MAX_AUTHORIZED_KEYS_BYTES) {
        throw IllegalStateException("authorized_keys is unreadable")
      }
      return authorizedKeys.readText(Charsets.UTF_8)
    }

    private fun writeAuthorizedKeys(content: String) {
      rejectSymlinks()
      if (!sshDir.isDirectory && !sshDir.mkdirs()) throw IllegalStateException("cannot create .ssh")
      sshDir.setReadable(false, false); sshDir.setReadable(true, true)
      sshDir.setWritable(false, false); sshDir.setWritable(true, true)
      sshDir.setExecutable(false, false); sshDir.setExecutable(true, true)
      atomicWrite(authorizedKeys, content)
      for (mirror in mirroredSshDirs) {
        // Only homes that exist: a distribution without a user has nothing to log in to.
        if (!mirror.parentFile!!.isDirectory) continue
        if (!mirror.isDirectory && !mirror.mkdirs()) throw IllegalStateException("cannot create .ssh")
        mirror.setReadable(false, false); mirror.setReadable(true, true)
        mirror.setWritable(false, false); mirror.setWritable(true, true)
        mirror.setExecutable(false, false); mirror.setExecutable(true, true)
        atomicWrite(File(mirror, "authorized_keys"), content)
      }
    }

    /**
     * The phone's one SSH host key, kept outside every guest home so the
     * computer sees the same identity whichever user is active, and only the
     * SSH server's PRoot can read it. Returns the directory to bind.
     *
     * Horus 1.0.4 kept a key per user home. The first start adopts the
     * active user's, so computers paired with that user see no change, then
     * removes every per-home copy.
     */
    fun prepareHostKey(activeHome: File): File = synchronized(lock) {
      if (Files.isSymbolicLink(hostKeyDir.toPath())) throw IllegalStateException("host key directory is a symbolic link")
      if (!hostKeyDir.isDirectory && !hostKeyDir.mkdirs()) throw IllegalStateException("cannot create host key directory")
      // Keep the server's key a plain file; adopt 1.0.4's when there is none.
      val key = readKey(hostKey) ?: legacyKeyDir(activeHome)?.let { readKey(File(it, HOST_KEY_NAME)) }
      if (key != null && (Files.isSymbolicLink(hostKey.toPath()) || !hostKey.isFile)) {
        val temp = File(hostKeyDir, ".${hostKey.name}.tmp")
        Files.deleteIfExists(temp.toPath())
        temp.writeBytes(key)
        temp.setReadable(false, false); temp.setReadable(true, true)
        temp.setWritable(false, false); temp.setWritable(true, true)
        Files.move(temp.toPath(), hostKey.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
      }
      // A key the server will not use should not sit where agents read it.
      (paths.userHomes() + activeHome).distinct().mapNotNull(::legacyKeyDir).forEach(::deleteKeyFiles)
      hostKeyDir
    }

    /** A home reset gives the phone a new identity, as it did when the key lived there. */
    fun deleteHostKey() = synchronized(lock) {
      if (!Files.isSymbolicLink(hostKeyDir.toPath())) deleteKeyFiles(hostKeyDir)
    }

    /** 1.0.4's key directory in [home], when nothing on the way is a link. */
    private fun legacyKeyDir(home: File): File? {
      val config = File(home, ".config")
      val dir = File(config, "horus")
      return dir.takeIf { listOf(home, config, dir).none { Files.isSymbolicLink(it.toPath()) } && it.isDirectory }
    }

    /**
     * dropbearkey publishes its key with link(2), which PRoot emulates as a
     * symlink chain into its backing store (beside the key, or the shared
     * store). Follow a link only when it ends in one of those places, so a
     * link the guest plants cannot pull another app file into the key.
     */
    private fun readKey(file: File): ByteArray? {
      if (!Files.exists(file.toPath())) return null
      val real = runCatching { file.toPath().toRealPath() }.getOrNull() ?: return null
      val allowed = listOfNotNull(file.parentFile, paths.hardlinks).map { it.canonicalFile.toPath() }
      if (allowed.none { real.parent == it }) return null
      val size = Files.size(real)
      if (size !in 1..MAX_HOST_KEY_BYTES) return null
      return Files.readAllBytes(real)
    }

    /** Unlinks the key, its .pub, and PRoot's link entries beside them; never follows links. */
    private fun deleteKeyFiles(dir: File) {
      dir.listFiles()?.filter {
        it.name == HOST_KEY_NAME || it.name == "$HOST_KEY_NAME.pub" || it.name.startsWith(".l2s.$HOST_KEY_NAME")
      }?.forEach { Files.deleteIfExists(it.toPath()) }
    }

    /** Copies the paired keys into a user home created after they were added. */
    fun syncKeysToHomes() = synchronized(lock) {
      writeAuthorizedKeys(readAuthorizedKeys())
    }

    /**
     * The guest can edit its own home. Never follow a link it planted, or a
     * key write could land on an app-private file outside the guest.
     */
    private fun rejectSymlinks() {
      (listOf(paths.home, sshDir, authorizedKeys) + paths.userHomes() + mirroredSshDirs + mirroredSshDirs.map { File(it, "authorized_keys") }).forEach { file ->
        if (Files.isSymbolicLink(file.toPath())) throw IllegalStateException("${file.name} is a symbolic link")
      }
    }

    private fun atomicWrite(target: File, content: String) {
      val parent = target.parentFile ?: throw IllegalStateException("no parent directory")
      if (!parent.isDirectory && !parent.mkdirs()) throw IllegalStateException("cannot create ${parent.name}")
      val temp = File(parent, ".${target.name}.tmp")
      temp.delete()
      temp.writeText(content, Charsets.UTF_8)
      temp.setReadable(false, false); temp.setReadable(true, true)
      temp.setWritable(false, false); temp.setWritable(true, true)
      Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
  }

  data class Status(val state: String, val detail: String = "", val pid: Int = 0)
}

/** Base64 without android.util, so key parsing runs in JVM unit tests on API 24. */
internal object Base64Codec {
  private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
  private val INDEX = IntArray(128) { -1 }.also { table -> ALPHABET.forEachIndexed { i, c -> table[c.code] = i } }

  fun encode(bytes: ByteArray, padding: Boolean): String {
    val out = StringBuilder((bytes.size + 2) / 3 * 4)
    var i = 0
    while (i < bytes.size) {
      val b0 = bytes[i].toInt() and 0xff
      val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xff else 0
      val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xff else 0
      out.append(ALPHABET[b0 shr 2])
      out.append(ALPHABET[((b0 and 3) shl 4) or (b1 shr 4)])
      if (i + 1 < bytes.size) out.append(ALPHABET[((b1 and 15) shl 2) or (b2 shr 6)]) else if (padding) out.append('=')
      if (i + 2 < bytes.size) out.append(ALPHABET[b2 and 63]) else if (padding) out.append('=')
      i += 3
    }
    return out.toString()
  }

  /** Strict standard base64 with optional padding; null on anything else. */
  fun decode(text: String): ByteArray? {
    val body = text.trimEnd('=')
    if (text.length - body.length > 2 || body.length % 4 == 1) return null
    if (text.length != body.length && text.length % 4 != 0) return null
    val out = java.io.ByteArrayOutputStream(body.length * 3 / 4)
    var buffer = 0
    var bits = 0
    for (c in body) {
      val value = if (c.code < 128) INDEX[c.code] else -1
      if (value < 0) return null
      buffer = (buffer shl 6) or value
      bits += 6
      if (bits >= 8) {
        bits -= 8
        out.write((buffer shr bits) and 0xff)
      }
    }
    return out.toByteArray()
  }
}
