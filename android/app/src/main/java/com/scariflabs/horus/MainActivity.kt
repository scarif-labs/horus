package com.scariflabs.horus

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.facebook.react.ReactActivity
import com.facebook.react.ReactActivityDelegate
import com.facebook.react.defaults.DefaultNewArchitectureEntryPoint.fabricEnabled
import com.facebook.react.defaults.DefaultReactActivityDelegate
import com.scariflabs.horus.terminal.LaunchSessionIntent
import com.scariflabs.horus.terminal.RemoteAccess
import com.scariflabs.horus.terminal.RemoteAccessController

class MainActivity : ReactActivity() {

  /**
   * Returns the name of the main component registered from JavaScript. This is used to schedule
   * rendering of the component.
   */
  override fun getMainComponentName(): String = "Horus"

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    LaunchSessionIntent.record(intent)
    keepContentClearOfNavigationBar(findViewById(android.R.id.content))
  }

  // The app draws edge to edge, so the navigation bar would cover the bottom
  // of every screen. JS pads the top for the status bar; pad the rest here.
  // The keyboard is left to KeyboardAvoidingView, which measures against
  // this padded frame.
  private fun keepContentClearOfNavigationBar(content: View) {
    ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
      val bars = insets.getInsets(WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.displayCutout())
      view.setPadding(bars.left, 0, bars.right, bars.bottom)
      insets
    }
  }

  // A session notification tapped while Horus is already running.
  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    LaunchSessionIntent.record(intent)
  }

  // Android may refuse to start the remote access server from the background
  // (after a reboot, or when the CLI asks while Horus is closed). Opening
  // Horus brings it back if the user left the switch on.
  override fun onResume() {
    super.onResume()
    runCatching {
      val controller = RemoteAccessController(this)
      val snapshot = controller.snapshot()
      if (snapshot.enabled && snapshot.state == RemoteAccess.STATE_STOPPED) controller.enable()
    }
  }

  /**
   * Returns the instance of the [ReactActivityDelegate]. We use [DefaultReactActivityDelegate]
   * which allows you to enable New Architecture with a single boolean flags [fabricEnabled]
   */
  override fun createReactActivityDelegate(): ReactActivityDelegate =
      DefaultReactActivityDelegate(this, mainComponentName, fabricEnabled)
}
