package com.scariflabs.horus.terminal

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import java.util.ArrayDeque

/**
 * Bounded main-process client for [TerminalSessionService]. The React Native
 * module remains a thin response mapper; this class owns service binding,
 * request deadlines, and the reconnect boundary after a UI-process restart.
 */
class TerminalSessionServiceClient(
  private val context: Context,
  private val onEvent: (Bundle) -> Unit,
) {

  private data class Pending(
    val requestId: String,
    val callback: (Bundle) -> Unit,
    val timeout: Runnable,
  )

  private val lock = Any()
  private val queued = ArrayDeque<Message>()
  private val pending = HashMap<String, Pending>()
  private var service: Messenger? = null
  private var bound = false
  private var bindingRequested = false
  private var closed = false

  // PTY output and request replies arrive here. Keep them off the main looper
  // so a busy UI frame or React commit cannot delay terminal output delivery.
  private val handlerThread = HandlerThread("terminal-session-client").apply { start() }
  private val handler = object : Handler(handlerThread.looper) {
    override fun handleMessage(message: Message) {
      when (message.what) {
        TerminalSessionServiceProtocol.MSG_SESSION_RESPONSE -> handleResponse(Bundle(message.data))
        TerminalSessionServiceProtocol.MSG_SESSION_EVENT -> runCatching { onEvent(Bundle(message.data)) }
      }
    }
  }
  private val replyMessenger = Messenger(handler)

  private val connection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
      if (binder == null) {
        android.util.Log.e(LOG_TAG, "client_service_connected_without_binder")
        TerminalDebugLog.record(context, "client_service_connected_without_binder")
        onServiceDisconnected(name)
        return
      }
      val connected = Messenger(binder)
      synchronized(lock) {
        if (closed) return
        service = connected
        bound = true
        bindingRequested = false
      }
      android.util.Log.i(LOG_TAG, "client_service_connected queued=${synchronized(lock) { queued.size }}")
      TerminalDebugLog.record(context, "client_service_connected queued=${synchronized(lock) { queued.size }}")
      val register = Message.obtain(null, TerminalSessionServiceProtocol.MSG_REGISTER_CLIENT).apply {
        replyTo = replyMessenger
      }
      if (!sendRaw(connected, register)) {
        android.util.Log.e(LOG_TAG, "client_register_send_failed")
        return
      }
      // A new or restarted service starts out assuming the UI is hidden.
      uiVisible?.let { visible -> sendRaw(connected, uiVisibilityMessage(visible)) }
      val messages = synchronized(lock) {
        val copy = queued.toList()
        queued.clear()
        copy
      }
      android.util.Log.i(LOG_TAG, "client_flush_queued count=${messages.size}")
      messages.forEach { message ->
        if (!sendRaw(connected, message)) return@forEach
      }
    }

    override fun onServiceDisconnected(name: ComponentName?) {
      android.util.Log.e(LOG_TAG, "client_service_disconnected")
      TerminalDebugLog.record(context, "client_service_disconnected")
      synchronized(lock) {
        service = null
        bound = false
        bindingRequested = false
      }
      handler.post { ensureService() }
    }

    override fun onBindingDied(name: ComponentName?) {
      onServiceDisconnected(name)
    }

    override fun onNullBinding(name: ComponentName?) {
      onServiceDisconnected(name)
    }
  }

  fun startSession(
    requestId: String,
    rows: Int,
    columns: Int,
    command: String?,
    toolchain: String?,
    countsAgainstSessionLimit: Boolean,
    callback: (Bundle) -> Unit,
  ) {
    send(
      TerminalSessionServiceProtocol.MSG_START_SESSION,
      requestId,
      Bundle().apply {
        putInt(TerminalSessionServiceProtocol.KEY_ROWS, rows)
        putInt(TerminalSessionServiceProtocol.KEY_COLUMNS, columns)
        command?.let { putString(TerminalSessionServiceProtocol.KEY_COMMAND, it) }
        toolchain?.let { putString(TerminalSessionServiceProtocol.KEY_TARGET, it) }
        putBoolean(TerminalSessionServiceProtocol.KEY_COUNTS_AGAINST_SESSION_LIMIT, countsAgainstSessionLimit)
      },
      callback,
    )
  }

  fun provisionToolchain(
    requestId: String,
    target: String,
    callback: (Bundle) -> Unit,
  ) {
    send(
      TerminalSessionServiceProtocol.MSG_PROVISION_TOOLCHAIN,
      requestId,
      Bundle().apply { putString(TerminalSessionServiceProtocol.KEY_TARGET, target) },
      callback,
      PROVISION_REQUEST_TIMEOUT_MS,
    )
  }

  fun writeSession(
    requestId: String,
    sessionId: String,
    bytes: ByteArray,
    callback: (Bundle) -> Unit,
  ) {
    send(
      TerminalSessionServiceProtocol.MSG_WRITE_SESSION,
      requestId,
      Bundle().apply {
        putString(TerminalSessionServiceProtocol.KEY_SESSION_ID, sessionId)
        putByteArray(TerminalSessionServiceProtocol.KEY_BYTES, bytes.copyOf())
      },
      callback,
    )
  }

  fun resizeSession(
    requestId: String,
    sessionId: String,
    rows: Int,
    columns: Int,
    callback: (Bundle) -> Unit,
  ) {
    send(
      TerminalSessionServiceProtocol.MSG_RESIZE_SESSION,
      requestId,
      Bundle().apply {
        putString(TerminalSessionServiceProtocol.KEY_SESSION_ID, sessionId)
        putInt(TerminalSessionServiceProtocol.KEY_ROWS, rows)
        putInt(TerminalSessionServiceProtocol.KEY_COLUMNS, columns)
      },
      callback,
    )
  }

  fun signalSession(
    requestId: String,
    sessionId: String,
    signal: String,
    callback: (Bundle) -> Unit,
  ) {
    send(
      TerminalSessionServiceProtocol.MSG_SIGNAL_SESSION,
      requestId,
      Bundle().apply {
        putString(TerminalSessionServiceProtocol.KEY_SESSION_ID, sessionId)
        putString(TerminalSessionServiceProtocol.KEY_SIGNAL, signal)
      },
      callback,
    )
  }

  fun stopSession(
    requestId: String,
    sessionId: String,
    reason: String,
    callback: (Bundle) -> Unit,
  ) {
    send(
      TerminalSessionServiceProtocol.MSG_STOP_SESSION,
      requestId,
      Bundle().apply {
        putString(TerminalSessionServiceProtocol.KEY_SESSION_ID, sessionId)
        putString(TerminalSessionServiceProtocol.KEY_REASON, reason)
      },
      callback,
    )
  }

  fun listSessions(requestId: String, callback: (Bundle) -> Unit) {
    send(TerminalSessionServiceProtocol.MSG_LIST_SESSIONS, requestId, Bundle(), callback)
  }

  fun redrawSession(
    requestId: String,
    sessionId: String,
    callback: (Bundle) -> Unit,
  ) {
    send(
      TerminalSessionServiceProtocol.MSG_REDRAW_SESSION,
      requestId,
      Bundle().apply { putString(TerminalSessionServiceProtocol.KEY_SESSION_ID, sessionId) },
      callback,
    )
  }

  fun updateUnlockGrant(
    requestId: String,
    op: String,
    callback: (Bundle) -> Unit,
  ) {
    send(
      TerminalSessionServiceProtocol.MSG_UNLOCK_GRANT,
      requestId,
      Bundle().apply { putString(TerminalSessionServiceProtocol.KEY_UNLOCK_OP, op) },
      callback,
    )
  }

  fun detachSession(
    requestId: String,
    sessionId: String,
    callback: (Bundle) -> Unit,
  ) {
    send(
      TerminalSessionServiceProtocol.MSG_DETACH_SESSION,
      requestId,
      Bundle().apply { putString(TerminalSessionServiceProtocol.KEY_SESSION_ID, sessionId) },
      callback,
    )
  }

  fun subscribeSession(
    requestId: String,
    sessionId: String,
    afterSeq: Long,
    callback: (Bundle) -> Unit,
  ) {
    send(
      TerminalSessionServiceProtocol.MSG_SUBSCRIBE_SESSION,
      requestId,
      Bundle().apply {
        putString(TerminalSessionServiceProtocol.KEY_SESSION_ID, sessionId)
        putLong(TerminalSessionServiceProtocol.KEY_AFTER_SEQ, afterSeq)
      },
      callback,
    )
  }

  fun acknowledgeOutput(
    requestId: String,
    sessionId: String,
    seq: Long,
    callback: (Bundle) -> Unit,
  ) {
    send(
      TerminalSessionServiceProtocol.MSG_ACKNOWLEDGE_OUTPUT,
      requestId,
      Bundle().apply {
        putString(TerminalSessionServiceProtocol.KEY_SESSION_ID, sessionId)
        putLong(TerminalSessionServiceProtocol.KEY_SEQ, seq)
      },
      callback,
    )
  }

  fun stopAll(requestId: String, reason: String, callback: (Bundle) -> Unit) {
    send(
      TerminalSessionServiceProtocol.MSG_STOP_ALL,
      requestId,
      Bundle().apply { putString(TerminalSessionServiceProtocol.KEY_REASON, reason) },
      callback,
      STOP_ALL_REQUEST_TIMEOUT_MS,
    )
  }

  @Volatile private var uiVisible: Boolean? = null

  /**
   * Tells the service whether the Horus activity is on screen, so it only
   * sends turn reminders while the user is elsewhere. Never starts the
   * service by itself; the latest value is re-sent whenever it connects.
   */
  fun reportUiVisibility(visible: Boolean) {
    uiVisible = visible
    val connected = synchronized(lock) { if (closed) return else service } ?: return
    sendRaw(connected, uiVisibilityMessage(visible))
  }

  private fun uiVisibilityMessage(visible: Boolean): Message =
    Message.obtain(null, TerminalSessionServiceProtocol.MSG_UI_VISIBILITY).apply {
      data = Bundle().apply { putBoolean(TerminalSessionServiceProtocol.KEY_VISIBLE, visible) }
      replyTo = replyMessenger
    }

  /** Detaches the UI bridge without stopping the service-owned PTYs. */
  fun close() {
    val callbacks = synchronized(lock) {
      if (closed) return
      closed = true
      val values = pending.values.map { it.callback }
      pending.values.forEach { item -> handler.removeCallbacks(item.timeout) }
      pending.clear()
      queued.clear()
      values
    }
    val error = errorResponse(INVALID_REQUEST_ID, "internal_error")
    callbacks.forEach { callback -> runCatching { callback(Bundle(error)) } }
    handler.post {
      closeBinding()
      handlerThread.quitSafely()
    }
  }

  private fun send(
    what: Int,
    requestId: String,
    fields: Bundle,
    callback: (Bundle) -> Unit,
    timeoutMs: Long = REQUEST_TIMEOUT_MS,
  ) {
    val message = Message.obtain(null, what).apply {
      data = Bundle(fields).also { it.putString(TerminalSessionServiceProtocol.KEY_REQUEST_ID, requestId) }
      replyTo = replyMessenger
    }
    var sendTo: Messenger? = null
    var immediate: Bundle? = null
    synchronized(lock) {
      if (closed) {
        immediate = errorResponse(requestId, "internal_error")
      } else if (pending.containsKey(requestId)) {
        immediate = errorResponse(requestId, "invalid_request")
      } else {
        val timeout = Runnable { timeout(requestId) }
        pending[requestId] = Pending(requestId, callback, timeout)
        handler.postDelayed(timeout, timeoutMs)
        val connected = service
        if (connected != null) {
          sendTo = connected
        } else if (queued.size < MAX_QUEUED_REQUESTS) {
          queued.addLast(message)
        } else {
          pending.remove(requestId)
          handler.removeCallbacks(timeout)
          immediate = errorResponse(requestId, "internal_error")
        }
      }
    }
    if (!isHotPath(what)) {
      android.util.Log.i(
        LOG_TAG,
        "client_send what=$what request=$requestId immediate=${immediate != null} queued=${synchronized(lock) { queued.size }}",
      )
      TerminalDebugLog.record(
        context,
        "client_send what=$what request=$requestId immediate=${immediate != null} queued=${synchronized(lock) { queued.size }}",
      )
    }
    immediate?.let { response -> runCatching { callback(response) } }
    ensureService()
    sendTo?.let { connected -> sendRaw(connected, message) }
  }

  private fun ensureService() {
    synchronized(lock) {
      if (closed) return
      if (service != null || bindingRequested) return
      bindingRequested = true
    }
    try {
      val intent = Intent(context, TerminalSessionService::class.java).apply {
        action = TerminalSessionServiceProtocol.ACTION
      }
      // Bind without a started lifetime. The service starts/promotes itself
      // only after a request or restored journal establishes durable work.
      val boundResult = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
      if (!boundResult) throw IllegalStateException("terminal service bind failed")
      android.util.Log.i(LOG_TAG, "client_bind_requested bound_only=true")
      TerminalDebugLog.record(context, "client_bind_requested bound_only=true")
    } catch (_: Exception) {
      android.util.Log.e(LOG_TAG, "client_bind_failed")
      TerminalDebugLog.record(context, "client_bind_failed")
      synchronized(lock) {
        bindingRequested = false
      }
    }
  }

  private fun sendRaw(target: Messenger, message: Message): Boolean = try {
    target.send(message)
    if (!isHotPath(message.what)) {
      android.util.Log.i(LOG_TAG, "client_message_sent what=${message.what}")
      TerminalDebugLog.record(context, "client_message_sent what=${message.what}")
    }
    true
  } catch (_: RemoteException) {
    android.util.Log.e(LOG_TAG, "client_message_send_failed what=${message.what}")
    TerminalDebugLog.record(context, "client_message_send_failed what=${message.what}")
    synchronized(lock) {
      if (service?.binder == target.binder) {
        service = null
        bound = false
        bindingRequested = false
      }
    }
    handler.post { ensureService() }
    false
  }

  private fun handleResponse(data: Bundle) {
    val requestId = data.getString(TerminalSessionServiceProtocol.KEY_REQUEST_ID) ?: return
    val status = data.getString(TerminalSessionServiceProtocol.KEY_STATUS)
    if (status != TerminalSessionServiceProtocol.STATUS_SUCCESS || !isHotPathRequest(requestId)) logResponse(requestId, data)
    val item = synchronized(lock) { pending.remove(requestId) } ?: return
    handler.removeCallbacks(item.timeout)
    runCatching { item.callback(data) }
  }

  private fun logResponse(requestId: String, data: Bundle) {
    android.util.Log.i(
      LOG_TAG,
      "client_response request=$requestId status=${data.getString(TerminalSessionServiceProtocol.KEY_STATUS)} error=${data.getString(TerminalSessionServiceProtocol.KEY_ERROR_CODE)}",
    )
    TerminalDebugLog.record(
      context,
      "client_response request=$requestId status=${data.getString(TerminalSessionServiceProtocol.KEY_STATUS)} error=${data.getString(TerminalSessionServiceProtocol.KEY_ERROR_CODE) ?: "none"}",
    )
  }

  // Keystrokes and output acknowledgements run once per key or PTY chunk.
  // Logging them (with an fsync per line) added I/O to every echo round trip.
  private fun isHotPath(what: Int): Boolean =
    what == TerminalSessionServiceProtocol.MSG_WRITE_SESSION || what == TerminalSessionServiceProtocol.MSG_ACKNOWLEDGE_OUTPUT

  private fun isHotPathRequest(requestId: String): Boolean =
    requestId.startsWith("native-input-") || requestId.startsWith("native-ack-")

  private fun timeout(requestId: String) {
    val item = synchronized(lock) {
      pending.remove(requestId)?.also {
        val iterator = queued.iterator()
        while (iterator.hasNext()) {
          if (iterator.next().data.getString(TerminalSessionServiceProtocol.KEY_REQUEST_ID) == requestId) {
            iterator.remove()
            break
          }
        }
      }
    } ?: return
    android.util.Log.e(LOG_TAG, "client_request_timeout request=$requestId")
    TerminalDebugLog.record(context, "client_request_timeout request=$requestId")
    runCatching { item.callback(errorResponse(requestId, "internal_error")) }
  }

  private fun closeBinding() {
    val (connected, needsUnbind) = synchronized(lock) {
      val current = service
      val unbindBeforeClose = bound || bindingRequested
      service = null
      bound = false
      bindingRequested = false
      current to unbindBeforeClose
    }
    connected?.let { target ->
      val unregister = Message.obtain(null, TerminalSessionServiceProtocol.MSG_UNREGISTER_CLIENT).apply {
        replyTo = replyMessenger
      }
      sendRaw(target, unregister)
    }
    runCatching {
      if (needsUnbind) context.unbindService(connection)
    }
  }

  private fun errorResponse(requestId: String, errorCode: String): Bundle = Bundle().apply {
    putString(TerminalSessionServiceProtocol.KEY_REQUEST_ID, requestId)
    putString(TerminalSessionServiceProtocol.KEY_STATUS, TerminalSessionServiceProtocol.STATUS_ERROR)
    putString(TerminalSessionServiceProtocol.KEY_ERROR_CODE, errorCode)
  }

  private companion object {
    const val REQUEST_TIMEOUT_MS = 10_000L
    const val STOP_ALL_REQUEST_TIMEOUT_MS = 20_000L
    const val PROVISION_REQUEST_TIMEOUT_MS = 310_000L
    const val MAX_QUEUED_REQUESTS = 64
    const val INVALID_REQUEST_ID = "invalid-request"
    const val LOG_TAG = "HorusTerminal"
  }
}
