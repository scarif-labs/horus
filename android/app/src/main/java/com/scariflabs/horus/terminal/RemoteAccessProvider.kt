package com.scariflabs.horus.terminal

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.net.Uri
import android.os.BatteryManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import org.json.JSONObject
import java.io.RandomAccessFile

/**
 * The `horus` CLI's door into the app, reached with `adb shell content call`.
 * Requests arrive as one base64 JSON `--arg` (older Androids support no
 * other extras) and answers go back as one JSON string under `json`.
 * Only the adb shell (or root) may call it: USB debugging must already trust
 * the computer. Pairing a key additionally needs the Horus password and shares
 * the login's attempt limit, so adb access alone never bypasses the lock.
 */
class RemoteAccessProvider : ContentProvider() {

  private val controller by lazy { RemoteAccessController(requireNotNull(context)) }

  override fun onCreate(): Boolean = true

  override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
    val callerUid = Binder.getCallingUid()
    if (callerUid != SHELL_UID && callerUid != ROOT_UID) throw SecurityException("remote access is limited to adb")
    val context = requireNotNull(context)
    // The binder identity is only needed for the check above; read our own
    // files and start our own service as the app.
    val token = Binder.clearCallingIdentity()
    val response = try {
      val request = parseRequest(arg)
      if (request == null) {
        result("invalid_request")
      } else {
        when (method) {
          METHOD_STATUS -> status(context, request)
          METHOD_PAIR -> pair(context, request)
          METHOD_UNPAIR -> unpair(request)
          METHOD_START -> start()
          METHOD_STOP -> result(RESULT_OK).also { controller.disable() }
          METHOD_LOG -> result(RESULT_OK).put("log", readLogTail())
          else -> result("unknown_method")
        }
      }
    } catch (_: Exception) {
      result("internal_error")
    } finally {
      Binder.restoreCallingIdentity(token)
    }
    return Bundle().apply { putString("json", response.toString()) }
  }

  private fun parseRequest(arg: String?): JSONObject? {
    if (arg.isNullOrEmpty()) return JSONObject()
    if (arg.length > MAX_REQUEST_CHARS) return null
    val bytes = Base64Codec.decode(arg) ?: return null
    return runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull()
  }

  private fun JSONObject.text(key: String): String? = if (has(key) && !isNull(key)) optString(key) else null

  private fun status(context: Context, request: JSONObject): JSONObject {
    val snapshot = controller.snapshot()
    val fingerprint = request.text(EXTRA_FINGERPRINT)
    return result(RESULT_OK).apply {
      put("protocol", PROTOCOL_VERSION)
      put("appVersion", appVersion(context))
      put("configured", PasswordGate.isConfigured(context))
      TerminalGuestIdentity.readStoredUsername(context)?.let { put("username", it) }
      put("enabled", snapshot.enabled)
      put("state", snapshot.state)
      put("detail", snapshot.detail)
      put("port", RemoteAccess.PORT)
      put("keys", snapshot.keys.size)
      if (fingerprint != null) put("authorized", snapshot.keys.any { it.fingerprint == fingerprint })
      put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
      put("sdk", Build.VERSION.SDK_INT)
      val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
      val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
      val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
      if (level >= 0 && scale > 0) put("battery", level * 100 / scale)
      val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
      put("charging", plugged != 0)
      val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
      put("batteryUnrestricted", power.isIgnoringBatteryOptimizations(context.packageName))
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put("thermal", power.currentThermalStatus)
    }
  }

  private fun pair(context: Context, request: JSONObject): JSONObject {
    if (!PasswordGate.isConfigured(context)) return result("not_configured")
    val key = when (
      val parsed = RemoteAccess.parsePublicKey(
        request.text(EXTRA_KEY_TYPE),
        request.text(EXTRA_KEY_DATA),
        request.text(EXTRA_LABEL),
      )
    ) {
      is RemoteAccess.KeyResult.Valid -> parsed.key
      is RemoteAccess.KeyResult.Invalid -> return result(parsed.reason)
    }
    when (val check = PasswordGate.verify(context, request.text(EXTRA_PASSWORD))) {
      PasswordGate.Check.Success -> Unit
      is PasswordGate.Check.Locked -> return result("locked").put("retryAfterMs", check.retryAfterMs)
      PasswordGate.Check.Incorrect -> return result("incorrect")
      PasswordGate.Check.Unavailable -> return result("not_configured")
    }
    controller.store.addKey(key)
    // Pairing with the password is consent to turn remote access on.
    val started = controller.enable()
    return result(RESULT_OK).put("fingerprint", key.fingerprint).put("started", started)
  }

  private fun unpair(request: JSONObject): JSONObject {
    val fingerprint = request.text(EXTRA_FINGERPRINT) ?: return result("invalid_request")
    val removed = controller.store.removeKey(fingerprint)
    return result(if (removed) RESULT_OK else "not_found")
  }

  /**
   * Restarts the server after a reboot or crash. Never turns the switch on:
   * that takes the password (pairing) or the app itself.
   */
  private fun start(): JSONObject {
    if (!controller.store.isEnabled()) return result("disabled")
    return result(if (controller.enable()) RESULT_OK else "start_refused")
  }

  private fun readLogTail(): String = runCatching {
    RandomAccessFile(controller.store.logFile, "r").use { file ->
      val size = file.length()
      val start = (size - MAX_LOG_TAIL_BYTES).coerceAtLeast(0L)
      file.seek(start)
      val bytes = ByteArray((size - start).toInt())
      file.readFully(bytes)
      String(bytes, Charsets.UTF_8)
    }
  }.getOrDefault("")

  private fun appVersion(context: Context): String = runCatching {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
  }.getOrDefault("")

  private fun result(status: String): JSONObject = JSONObject().put("status", status)

  override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
  override fun getType(uri: Uri): String? = null
  override fun insert(uri: Uri, values: ContentValues?): Uri? = null
  override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
  override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

  private companion object {
    // android.os.Process.SHELL_UID is not public before API 29.
    const val SHELL_UID = 2000
    const val ROOT_UID = 0
    const val PROTOCOL_VERSION = 1
    const val METHOD_STATUS = "status"
    const val METHOD_PAIR = "pair"
    const val METHOD_UNPAIR = "unpair"
    const val METHOD_START = "start"
    const val METHOD_STOP = "stop"
    const val METHOD_LOG = "log"
    const val EXTRA_PASSWORD = "password"
    const val EXTRA_KEY_TYPE = "keyType"
    const val EXTRA_KEY_DATA = "keyData"
    const val EXTRA_LABEL = "label"
    const val EXTRA_FINGERPRINT = "fingerprint"
    const val RESULT_OK = "ok"
    const val MAX_LOG_TAIL_BYTES = 8 * 1024L
    const val MAX_REQUEST_CHARS = 8 * 1024
  }
}
