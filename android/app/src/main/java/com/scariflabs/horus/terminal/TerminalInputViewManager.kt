package com.scariflabs.horus.terminal

import com.facebook.react.common.MapBuilder
import com.facebook.react.uimanager.SimpleViewManager
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.annotations.ReactProp

class TerminalInputViewManager : SimpleViewManager<TerminalInputView>() {
  override fun getName(): String = REACT_CLASS

  override fun createViewInstance(reactContext: ThemedReactContext): TerminalInputView = TerminalInputView(reactContext)

  @ReactProp(name = "sessionId")
  fun setSessionId(view: TerminalInputView, value: String?) {
    view.setTerminalSessionId(value)
  }

  @ReactProp(name = "terminalEnabled", defaultBoolean = false)
  fun setTerminalEnabled(view: TerminalInputView, value: Boolean) {
    view.setTerminalEnabled(value)
  }

  @ReactProp(name = "terminalAutoFocus", defaultBoolean = false)
  fun setTerminalAutoFocus(view: TerminalInputView, value: Boolean) {
    view.setTerminalAutoFocus(value)
  }

  @ReactProp(name = "ctrlActive", defaultBoolean = false)
  fun setCtrlActive(view: TerminalInputView, value: Boolean) {
    view.setCtrlActive(value)
  }

  @ReactProp(name = "altActive", defaultBoolean = false)
  fun setAltActive(view: TerminalInputView, value: Boolean) {
    view.setAltActive(value)
  }

  @ReactProp(name = "keyboardShowRequest", defaultInt = 0)
  fun setKeyboardShowRequest(view: TerminalInputView, value: Int) {
    view.setKeyboardShowRequest(value)
  }

  @ReactProp(name = "keyboardHideRequest", defaultInt = 0)
  fun setKeyboardHideRequest(view: TerminalInputView, value: Int) {
    view.setKeyboardHideRequest(value)
  }

  @ReactProp(name = "lineResetRequest", defaultInt = 0)
  fun setLineResetRequest(view: TerminalInputView, value: Int) {
    view.setLineResetRequest(value)
  }

  override fun getExportedCustomDirectEventTypeConstants(): MutableMap<String, Any> =
    MapBuilder.builder<String, Any>()
      .put("topModifiersConsumed", MapBuilder.of("registrationName", "onModifiersConsumed"))
      .build()
      .toMutableMap()

  companion object {
    const val REACT_CLASS = "HorusTerminalInput"
  }
}
