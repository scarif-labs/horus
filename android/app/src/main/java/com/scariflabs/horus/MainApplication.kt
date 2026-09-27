package com.scariflabs.horus

import android.app.ActivityManager
import android.app.Application
import android.os.Build
import android.os.Process
import com.facebook.react.PackageList
import com.facebook.react.ReactApplication
import com.facebook.react.ReactHost
import com.facebook.react.ReactNativeApplicationEntryPoint.loadReactNative
import com.facebook.react.defaults.DefaultReactHost.getDefaultReactHost
import com.scariflabs.horus.terminal.TerminalDebugLog

class MainApplication : Application(), ReactApplication {

  override val reactHost: ReactHost by lazy {
    getDefaultReactHost(
      context = applicationContext,
      packageList =
        PackageList(this).packages.apply {
          add(com.scariflabs.horus.terminal.TerminalRuntimePackage())
        },
    )
  }

  override fun onCreate() {
    super.onCreate()
    TerminalDebugLog.record(this, "application_created process=${currentProcessName() ?: "unknown"}")
    // The terminal foreground service runs in :terminal. Keeping React
    // Native/Hermes out of that process is important: the service process is
    // the memory-survival boundary for PRoot sessions.
    if (!isTerminalServiceProcess()) loadReactNative(this)
  }

  private fun isTerminalServiceProcess(): Boolean = currentProcessName() == "$packageName:terminal"

  private fun currentProcessName(): String? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return Application.getProcessName()
    val manager = getSystemService(ActivityManager::class.java) ?: return null
    return manager.runningAppProcesses
      ?.firstOrNull { it.pid == Process.myPid() }
      ?.processName
  }
}
