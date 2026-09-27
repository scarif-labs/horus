package com.scariflabs.horus.terminal

import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.uimanager.SimpleViewManager
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.annotations.ReactProp
import com.facebook.react.common.MapBuilder

class TerminalCanvasViewManager : SimpleViewManager<TerminalCanvasView>() {
  override fun getName(): String = REACT_CLASS

  override fun createViewInstance(reactContext: ThemedReactContext): TerminalCanvasView = TerminalCanvasView(reactContext)

  @ReactProp(name = "frame")
  fun setFrame(view: TerminalCanvasView, frame: ReadableMap?) {
    view.setFrame(frame)
  }

  @ReactProp(name = "sessionId")
  fun setSessionId(view: TerminalCanvasView, value: String?) {
    view.setNativeSessionId(value)
  }

  @ReactProp(name = "nativeRows", defaultInt = 24)
  fun setNativeRows(view: TerminalCanvasView, value: Int) {
    view.setNativeRows(value)
  }

  @ReactProp(name = "nativeColumns", defaultInt = 80)
  fun setNativeColumns(view: TerminalCanvasView, value: Int) {
    view.setNativeColumns(value)
  }

  @ReactProp(name = "loadingText")
  fun setLoadingText(view: TerminalCanvasView, value: String?) {
    view.setNativeLoadingText(value)
  }

  @ReactProp(name = "links")
  fun setLinks(view: TerminalCanvasView, links: ReadableArray?) {
    view.setLinks(links)
  }

  @ReactProp(name = "cellWidth", defaultFloat = 8f)
  fun setCellWidth(view: TerminalCanvasView, value: Float) {
    view.setCellWidth(value)
  }

  @ReactProp(name = "cellHeight", defaultFloat = 19f)
  fun setCellHeight(view: TerminalCanvasView, value: Float) {
    view.setCellHeight(value)
  }

  @ReactProp(name = "fontSize", defaultFloat = 13f)
  fun setFontSize(view: TerminalCanvasView, value: Float) {
    view.setFontSize(value)
  }

  @ReactProp(name = "running", defaultBoolean = false)
  fun setRunning(view: TerminalCanvasView, value: Boolean) {
    view.setRunning(value)
  }

  override fun getExportedCustomDirectEventTypeConstants(): MutableMap<String, Any> =
    MapBuilder.builder<String, Any>()
      .put("topNativeFrameMeta", MapBuilder.of("registrationName", "onNativeFrameMeta"))
      .build()
      .toMutableMap()

  companion object {
    const val REACT_CLASS = "HorusTerminalCanvas"
  }
}
