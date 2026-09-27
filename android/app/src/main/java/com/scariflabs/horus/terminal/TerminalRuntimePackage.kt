package com.scariflabs.horus.terminal

import com.facebook.react.BaseReactPackage
import com.facebook.react.bridge.ModuleSpec
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.model.ReactModuleInfo
import com.facebook.react.module.model.ReactModuleInfoProvider

/** Registers the typed runtime module and the native terminal view. */
class TerminalRuntimePackage : BaseReactPackage() {
  override fun getModule(name: String, reactContext: ReactApplicationContext): NativeModule? =
    when (name) {
      TerminalRuntimeContract.MODULE_NAME -> TerminalRuntimeModule(reactContext)
      HorusDeviceModule.NAME -> HorusDeviceModule(reactContext)
      else -> null
    }

  override fun getReactModuleInfoProvider(): ReactModuleInfoProvider = ReactModuleInfoProvider {
    mapOf(
      TerminalRuntimeContract.MODULE_NAME to ReactModuleInfo(
        TerminalRuntimeContract.MODULE_NAME,
        TerminalRuntimeModule::class.java.name,
        false,
        false,
        false,
        true,
      ),
      HorusDeviceModule.NAME to ReactModuleInfo(
        HorusDeviceModule.NAME,
        HorusDeviceModule::class.java.name,
        false,
        false,
        false,
        false,
      ),
    )
  }

  override fun getViewManagers(reactContext: ReactApplicationContext): List<ModuleSpec> = listOf(
    ModuleSpec.viewManagerSpec { TerminalCanvasViewManager() },
    ModuleSpec.viewManagerSpec { TerminalInputViewManager() },
  )
}
