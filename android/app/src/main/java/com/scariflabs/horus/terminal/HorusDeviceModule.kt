package com.scariflabs.horus.terminal

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import android.provider.Settings
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.module.annotations.ReactModule

/** Small, app-private bridge for the launcher skin and local Alpine login. */
@ReactModule(name = HorusDeviceModule.NAME)
class HorusDeviceModule(
  private val appContext: ReactApplicationContext,
) : ReactContextBaseJavaModule(appContext) {

  private val preferences by lazy {
    appContext.getSharedPreferences(PROFILE_PREFERENCES, Context.MODE_PRIVATE)
  }

  override fun getName(): String = NAME

  @ReactMethod
  fun getDeviceSnapshot(promise: Promise) {
    try {
      val stat = StatFs(appContext.filesDir.absolutePath)
      val memory = ActivityManager.MemoryInfo()
      val activityManager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
      activityManager.getMemoryInfo(memory)
      val battery = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
      val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
      val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
      val batteryPercent = if (level >= 0 && scale > 0) {
        (level.toDouble() / scale.toDouble() * 100.0).coerceIn(0.0, 100.0)
      } else {
        0.0
      }
      promise.resolve(
        Arguments.createMap().apply {
          putDouble("freeStorageBytes", stat.availableBytes.toDouble().coerceAtLeast(0.0))
          putDouble("totalStorageBytes", stat.totalBytes.toDouble().coerceAtLeast(0.0))
          putDouble("freeMemoryBytes", memory.availMem.toDouble().coerceAtLeast(0.0))
          putDouble("totalMemoryBytes", memory.totalMem.toDouble().coerceAtLeast(0.0))
          putBoolean("wifiConnected", isWifiConnected())
          putDouble("batteryPercent", batteryPercent)
          putDouble("capturedAtMs", System.currentTimeMillis().toDouble())
        },
      )
    } catch (_: Exception) {
      promise.reject("device_snapshot_failed", "device metrics unavailable")
    }
  }

  @ReactMethod
  fun getProfile(promise: Promise) {
    val configured = preferences.getString(KEY_PASSWORD_SALT, null) != null &&
      preferences.getString(KEY_PASSWORD_VERIFIER, null) != null
    if (configured) {
      preferences.edit()
        .remove(KEY_LEGACY_USERNAME)
        .remove(KEY_LEGACY_EMOJI)
        .apply()
    }
    promise.resolve(
      Arguments.createMap().apply {
        putBoolean("configured", configured)
        if (configured) {
          putBoolean("hasPassword", preferences.getBoolean(KEY_HAS_PASSWORD, false))
        }
      },
    )
  }

  @ReactMethod
  fun saveProfile(request: ReadableMap, promise: Promise) {
    val password = request.takeIf { it.hasKey("password") }?.getString("password")
    // The password is set once during onboarding. PasswordGate never
    // overwrites a configured one, or the lock could be reset without it.
    val created = password != null && PasswordGate.create(appContext, password)
    promise.resolve(Arguments.createMap().apply { putString("status", if (created) "success" else "error") })
  }

  @ReactMethod
  fun verifyPassword(request: ReadableMap, promise: Promise) {
    val password = request.takeIf { it.hasKey("password") }?.getString("password")
    val result = Arguments.createMap()
    when (val check = PasswordGate.verify(appContext, password)) {
      PasswordGate.Check.Success -> result.putString("status", "success")
      is PasswordGate.Check.Locked -> {
        result.putString("status", "locked")
        result.putDouble("retryAfterMs", check.retryAfterMs.toDouble())
      }
      PasswordGate.Check.Incorrect, PasswordGate.Check.Unavailable -> result.putString("status", "error")
    }
    promise.resolve(result)
  }

  /** Whether Android will let sessions keep running and tell the user about them. */
  @ReactMethod
  fun getBackgroundPermissions(promise: Promise) {
    val notifications = (appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
      .areNotificationsEnabled()
    val power = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
    val batteryUnrestricted = power.isIgnoringBatteryOptimizations(appContext.packageName)
    promise.resolve(
      Arguments.createMap().apply {
        putBoolean("notifications", notifications)
        putBoolean("batteryUnrestricted", batteryUnrestricted)
      },
    )
  }

  /** Shows Android's own "let Horus run in the background" dialog. */
  @ReactMethod
  fun requestBatteryUnrestricted(promise: Promise) {
    promise.resolve(
      openSystemScreen(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${appContext.packageName}")),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
      ),
    )
  }

  /** For when the notification prompt was refused for good. */
  @ReactMethod
  fun openNotificationSettings(promise: Promise) {
    val appNotifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, appContext.packageName)
    } else {
      Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${appContext.packageName}"))
    }
    promise.resolve(openSystemScreen(appNotifications, null))
  }

  private fun openSystemScreen(primary: Intent, fallback: Intent?): Boolean {
    val activity = appContext.currentActivity
    val launcher = activity ?: appContext
    for (intent in listOfNotNull(primary, fallback)) {
      if (activity == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      try {
        launcher.startActivity(intent)
        return true
      } catch (_: RuntimeException) {
        // Some vendors remove these screens; try the next one.
      }
    }
    return false
  }

  @ReactMethod
  fun getRemoteAccess(promise: Promise) {
    try {
      promise.resolve(remoteAccessMap(RemoteAccessController(appContext).snapshot()))
    } catch (_: Exception) {
      promise.resolve(Arguments.createMap().apply { putString("status", "error") })
    }
  }

  @ReactMethod
  fun setRemoteAccessEnabled(enabled: Boolean, promise: Promise) {
    try {
      val controller = RemoteAccessController(appContext)
      if (enabled) controller.enable() else controller.disable()
      promise.resolve(remoteAccessMap(controller.snapshot()))
    } catch (_: Exception) {
      promise.resolve(Arguments.createMap().apply { putString("status", "error") })
    }
  }

  @ReactMethod
  fun revokeRemoteComputer(fingerprint: String, promise: Promise) {
    try {
      val controller = RemoteAccessController(appContext)
      controller.store.removeKey(fingerprint)
      promise.resolve(remoteAccessMap(controller.snapshot()))
    } catch (_: Exception) {
      promise.resolve(Arguments.createMap().apply { putString("status", "error") })
    }
  }

  private fun remoteAccessMap(snapshot: RemoteAccessController.Snapshot) = Arguments.createMap().apply {
    putString("status", "success")
    putBoolean("enabled", snapshot.enabled)
    putString("state", snapshot.state)
    putString("detail", snapshot.detail)
    putInt("port", RemoteAccess.PORT)
    putArray(
      "computers",
      Arguments.createArray().apply {
        snapshot.keys.forEach { key ->
          pushMap(
            Arguments.createMap().apply {
              putString("fingerprint", key.fingerprint)
              putString("label", key.label)
              putBoolean("managed", key.managed)
            },
          )
        }
      },
    )
  }

  @ReactMethod
  fun getGithubAccount(promise: Promise) {
    val username = preferences.getString(KEY_GITHUB_USERNAME, null)
    val avatarUrl = preferences.getString(KEY_GITHUB_AVATAR_URL, null)
    promise.resolve(
      Arguments.createMap().apply {
        if (username != null && GITHUB_USERNAME_PATTERN.matches(username)) {
          putString("username", username)
          if (avatarUrl != null && GITHUB_AVATAR_PATH_PATTERN.matches(avatarUrl)) putString("avatarUrl", avatarUrl)
        }
      },
    )
  }

  @ReactMethod
  fun saveGithubAccount(request: ReadableMap, promise: Promise) {
    val username = request.takeIf { it.hasKey("username") }?.getString("username")
    val avatarUrl = request.takeIf { it.hasKey("avatarUrl") }?.getString("avatarUrl")
    if (username == null || !GITHUB_USERNAME_PATTERN.matches(username) ||
      (avatarUrl != null && !GITHUB_AVATAR_PATH_PATTERN.matches(avatarUrl))
    ) {
      promise.resolve(Arguments.createMap().apply { putString("status", "error") })
      return
    }
    preferences.edit().apply {
      putString(KEY_GITHUB_USERNAME, username)
      if (avatarUrl == null) remove(KEY_GITHUB_AVATAR_URL) else putString(KEY_GITHUB_AVATAR_URL, avatarUrl)
    }.apply()
    promise.resolve(Arguments.createMap().apply { putString("status", "success") })
  }

  @ReactMethod
  fun clearGithubAccount(promise: Promise) {
    preferences.edit()
      .remove(KEY_GITHUB_USERNAME)
      .remove(KEY_GITHUB_AVATAR_URL)
      .apply()
    promise.resolve(Arguments.createMap().apply { putString("status", "success") })
  }

  private fun isWifiConnected(): Boolean {
    val manager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    val network = manager.activeNetwork ?: return false
    val capabilities = manager.getNetworkCapabilities(network) ?: return false
    return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
  }

  companion object {
  const val NAME = "HorusDevice"
    private const val PROFILE_PREFERENCES = "horus_profile"
    private const val KEY_LEGACY_USERNAME = "username"
    private const val KEY_LEGACY_EMOJI = "emoji"
    private const val KEY_HAS_PASSWORD = "has_password"
    private const val KEY_PASSWORD_SALT = "password_salt"
    private const val KEY_PASSWORD_VERIFIER = "password_verifier"
    private const val KEY_GITHUB_USERNAME = "github_username"
    private const val KEY_GITHUB_AVATAR_URL = "github_avatar_url"
    private val GITHUB_USERNAME_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9-]{0,38}")
    private val GITHUB_AVATAR_PATH_PATTERN = Regex("https://avatars\\.githubusercontent\\.com/[A-Za-z0-9._~!$'()*+,;=:@%/-]{1,256}")

    fun readStoredUsername(context: Context): String? = TerminalGuestIdentity.readStoredUsername(context)
  }
}
