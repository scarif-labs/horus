package com.scariflabs.horus.terminal

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import java.io.File

/** UI-process view of remote access: the switch, its keys, and server state. */
class RemoteAccessController(context: Context) {
  private val appContext = context.applicationContext
  val store = RemoteAccess.Store(File(appContext.filesDir, TerminalRuntimeContract.STORAGE_ROOT_DIR_NAME))

  data class Snapshot(
    val enabled: Boolean,
    val state: String,
    val detail: String,
    val keys: List<RemoteAccess.AuthorizedKey>,
  )

  fun snapshot(): Snapshot {
    val enabled = store.isEnabled()
    val status = store.readStatus()
    // The status file outlives a killed service; trust it only while the
    // process that wrote it is still one of ours.
    val live = status.pid > 0 && isOwnProcess(status.pid)
    val state = when {
      live -> status.state
      status.state == RemoteAccess.STATE_FAILED -> status.state
      else -> RemoteAccess.STATE_STOPPED
    }
    val keys = runCatching { store.keys() }.getOrDefault(emptyList())
    return Snapshot(enabled, state, if (live || state == RemoteAccess.STATE_FAILED) status.detail else "", keys)
  }

  /**
   * Turns the switch on and asks the terminal service to start the server.
   * Returns false if Android refused to start the service from the
   * background; the switch stays on and the server starts with Horus.
   */
  fun enable(): Boolean {
    store.setEnabled(true)
    return try {
      val intent = Intent(appContext, TerminalSessionService::class.java).setAction(RemoteAccess.ACTION_START)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        appContext.startForegroundService(intent)
      } else {
        appContext.startService(intent)
      }
      true
    } catch (_: RuntimeException) {
      false
    }
  }

  fun disable() {
    store.setEnabled(false)
    // A stopped service has nothing to stop; Android may also refuse a
    // background start, and the cleared switch keeps it off either way.
    runCatching {
      appContext.startService(Intent(appContext, TerminalSessionService::class.java).setAction(RemoteAccess.ACTION_STOP))
    }
  }

  private fun isOwnProcess(pid: Int): Boolean {
    val manager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
    return manager.runningAppProcesses.orEmpty().any { it.pid == pid }
  }
}
