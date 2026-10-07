package com.scariflabs.horus.terminal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.PowerManager
import android.os.RemoteException
import android.os.SystemClock
import android.text.format.DateUtils
import com.scariflabs.horus.R
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * User-visible owner of interactive Alpine PTYs and bounded toolchain
 * provisioning. The service is started and bound by the React Native bridge,
 * but the supervisor and all PRoot children live here so a cached/recreated UI
 * process does not tear them down.
 */
class TerminalSessionService : Service() {

  private val handler = object : Handler(Looper.getMainLooper()) {
    override fun handleMessage(message: Message) {
      this@TerminalSessionService.handleMessage(message)
    }
  }
  private val messenger = Messenger(handler)
  private val worker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "horus-terminal-service").apply { isDaemon = true }
  }
  private val provisionExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "horus-toolchain-provision").apply { isDaemon = true }
  }
  private val provisionInProgress = AtomicBoolean(false)
  private val provisionProcess = AtomicReference<Process?>(null)

  private val journal by lazy {
    TerminalSessionJournal(
      File(
        filesDir,
        "${TerminalRuntimeContract.STORAGE_ROOT_DIR_NAME}/sessions/active-sessions.json",
      ),
    )
  }
  private val specFactory by lazy { TerminalSessionSpecFactory(applicationContext) }
  private val sessionRecords = ConcurrentHashMap<String, TerminalSessionJournal.Record>()
  /** Active utility sessions are intentionally invisible to the user quota and Recents. */
  private val nonCountingSessionIds = ConcurrentHashMap.newKeySet<String>()
  private val histories = ConcurrentHashMap<String, BoundedSessionOutputHistory>()
  private val unlockGrant = SessionUnlockGrant(clock = SystemClock::elapsedRealtime)
  private val turnLock = Any()
  private val turnTracker = HarnessTurnTracker(clock = SystemClock::elapsedRealtime)
  private val startupRedraw = HarnessStartupRedraw(clock = SystemClock::elapsedRealtime)
  private val turnTickScheduled = AtomicBoolean(false)
  // Whether the Horus activity is on screen. Unknown until the UI reports;
  // a missing client (process killed or unreachable) always means hidden.
  @Volatile private var uiVisible = false
  private val turnTick = object : Runnable {
    override fun run() {
      turnTickScheduled.set(false)
      val (finished, redraws, stillActive) = synchronized(turnLock) {
        Triple(
          turnTracker.pollFinished(),
          startupRedraw.pollDue(),
          turnTracker.hasActiveTurns() || startupRedraw.hasPending(),
        )
      }
      if (redraws.isNotEmpty()) enqueueWorker { redraws.forEach { nudgeRedraw(it, "startup") } }
      if (finished.isNotEmpty()) {
        enqueueWorker {
          refreshSessionNotifications()
          if (!uiVisible || !hasClient()) finished.forEach(::notifyTurnFinished)
        }
      }
      if (stillActive) scheduleTurnTick()
    }
  }
  private val sessionSettings by lazy {
    TerminalSessionSettings(
      File(
        filesDir,
        "${TerminalRuntimeContract.STORAGE_ROOT_DIR_NAME}/settings/session-settings.json",
      ),
    )
  }
  private val sessionStartElapsedMs = ConcurrentHashMap<String, Long>()
  private val firstOutputLogged = ConcurrentHashMap.newKeySet<String>()
  private val readyMarkerLogged = ConcurrentHashMap.newKeySet<String>()
  private val readyMarkerTails = ConcurrentHashMap<String, String>()
  /** Last elapsed time at which the UI acknowledged output for each session. */
  private val attachedSessions = ConcurrentHashMap<String, Long>()
  private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }
  private val clientLock = Any()
  private val clientSendLock = Any()
  private var client: Messenger? = null
  private var clientBinder: IBinder? = null
  private var clientDeathRecipient: IBinder.DeathRecipient? = null
  @Volatile private var stopping = false
  private val startedForWork = AtomicBoolean(false)
  /** Latest onStartCommand id; 0 until the service is first started. */
  @Volatile private var lastStartId = 0
  private val recoveryQueued = AtomicBoolean(false)

  private val sessionEventListener = object : TerminalSessionSupervisor.EventListener {
    override fun onSessionOutput(sessionId: String, seq: Long, chunk: ByteArray) {
      histories.computeIfAbsent(sessionId) { BoundedSessionOutputHistory() }.add(seq, chunk)
      if (isHarnessSession(sessionId)) {
        val startupPending = synchronized(turnLock) {
          turnTracker.onOutput(sessionId)
          startupRedraw.onOutput(sessionId, chunk)
          startupRedraw.hasPending()
        }
        if (startupPending) scheduleTurnTick()
      }
      recordSessionOutputTimings(sessionId, chunk)
      sendEvent(
        Bundle().apply {
          putString(TerminalSessionServiceProtocol.KEY_EVENT_TYPE, TerminalSessionServiceProtocol.EVENT_OUTPUT)
          putString(TerminalSessionServiceProtocol.KEY_SESSION_ID, sessionId)
          putLong(TerminalSessionServiceProtocol.KEY_SEQ, seq)
          sessionRecords[sessionId]?.let { record ->
            putString(
              TerminalSessionServiceProtocol.KEY_TARGET,
              record.toolchainTarget ?: TerminalRuntimeContract.TOOLCHAIN_TARGET_SHELL,
            )
            putInt(TerminalSessionServiceProtocol.KEY_ROWS, record.rows)
            putInt(TerminalSessionServiceProtocol.KEY_COLUMNS, record.columns)
          }
          // The supervisor hands each listener a fresh chunk that is never
          // modified, and Messenger.send parcels it synchronously.
          putByteArray(TerminalSessionServiceProtocol.KEY_BYTES, chunk)
        },
      )
      // A background PTY still needs to drain output. When no terminal screen
      // is attached, keep the replay ring bounded and release the reader's
      // output credit here instead of letting the hidden UI window stall it.
      if (!shouldBlockOutput(sessionId)) releaseOutputCredit(sessionId, seq)
    }

    override fun onSessionExit(info: TerminalSessionSupervisor.SessionExitInfo) {
      attachedSessions.remove(info.sessionId)
      nonCountingSessionIds.remove(info.sessionId)
      synchronized(turnLock) {
        turnTracker.remove(info.sessionId)
        startupRedraw.remove(info.sessionId)
      }
      notificationManager.cancel(turnNotificationId(info.sessionId))
      TerminalDebugLog.record(
        this@TerminalSessionService,
        "session_exit session=${info.sessionId} reason=${info.reason} code=${info.exitCode ?: "none"} signal=${info.signal ?: "none"}",
      )
      sendEvent(
        Bundle().apply {
          putString(TerminalSessionServiceProtocol.KEY_EVENT_TYPE, TerminalSessionServiceProtocol.EVENT_EXIT)
          putString(TerminalSessionServiceProtocol.KEY_SESSION_ID, info.sessionId)
          putString(TerminalSessionServiceProtocol.KEY_EXIT_REASON, info.reason)
          info.exitCode?.let { putInt(TerminalSessionServiceProtocol.KEY_EXIT_CODE, it) }
          info.signal?.let { putString(TerminalSessionServiceProtocol.KEY_EXIT_SIGNAL, it) }
        },
      )
      // Journal I/O must not block the supervisor's reaper thread.
      enqueueWorker {
        sessionRecords.remove(info.sessionId)
        runCatching { journal.remove(info.sessionId) }
        histories.remove(info.sessionId)
        clearSessionTimings(info.sessionId)
        refreshSessionNotifications()
        maybeStopIfIdle()
      }
    }
  }

  private val supervisorDelegate = lazy {
    TerminalSessionSupervisor(
      backend = JniPtyBackend(),
      listener = sessionEventListener,
      processTree = ProcessTree(),
      sendSignal = { pid, signal -> android.system.Os.kill(pid, signal) },
      maxActiveSessions = sessionSettings::readLimit,
      shouldBlockOutput = ::shouldBlockOutput,
    )
  }
  private val supervisor: TerminalSessionSupervisor get() = supervisorDelegate.value

  private val remoteStore by lazy {
    RemoteAccess.Store(File(filesDir, TerminalRuntimeContract.STORAGE_ROOT_DIR_NAME))
  }
  private val remoteServerDelegate = lazy {
    RemoteAccessServer(
      store = remoteStore,
      buildLaunch = { specFactory.buildRemoteAccess() },
      onStateChanged = { enqueueWorker { refreshSessionNotifications() } },
    )
  }
  private val remoteServer: RemoteAccessServer get() = remoteServerDelegate.value
  private fun isRemoteActive(): Boolean = remoteServerDelegate.isInitialized() && remoteServer.isActive

  // Keeps the CPU running while a PTY session or provisioning is active, so
  // agents keep working with the screen off. The screen itself may sleep.
  private val wakeLock by lazy {
    (getSystemService(Context.POWER_SERVICE) as PowerManager)
      .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Horus:terminal-sessions")
      .apply { setReferenceCounted(false) }
  }

  private fun syncWakeLock(held: Boolean) = synchronized(wakeLock) {
    if (held && !wakeLock.isHeld) {
      wakeLock.acquire()
      TerminalDebugLog.record(this, "wake_lock_acquired")
    } else if (!held && wakeLock.isHeld) {
      wakeLock.release()
      TerminalDebugLog.record(this, "wake_lock_released")
    }
  }

  override fun onCreate() {
    super.onCreate()
    android.util.Log.i(LOG_TAG, "service_created")
    TerminalDebugLog.record(this, "service_created")
    createNotificationChannel()
    journal.read().forEach { record -> sessionRecords[record.sessionId] = record }
    // Creation alone (including a bound-only client or an idle sticky restart)
    // does not justify foreground work. Promotion follows an explicit start
    // or a request that actually owns work.
  }

  private fun lifecycleWork() = TerminalServiceLifecycle.Work(
    sessions = sessionRecords.isNotEmpty() ||
      (supervisorDelegate.isInitialized() && supervisor.activeSessionIds().isNotEmpty()),
    provisioning = provisionInProgress.get(),
    remoteAccess = remoteStore.isEnabled() || isRemoteActive(),
  )

  private fun queueRecovery() {
    if (!recoveryQueued.compareAndSet(false, true)) return
    enqueueWorker {
      if (lifecycleWork().durable) retainForWork()
      restorePersistedSessions()
      if (remoteStore.isEnabled()) remoteServer.start()
      refreshSessionNotifications()
    }
  }

  /**
   * Give bound-only work a started lifetime before the UI can disappear.
   * Returns false when Android refuses promotion (a request that arrives
   * after the app went to the background); the caller fails that request
   * instead of letting the exception kill the process and every session.
   */
  private fun retainForWork(): Boolean {
    if (!lifecycleWork().durable) return true
    try {
      startForeground(NOTIFICATION_ID, buildNotification())
      if (startedForWork.compareAndSet(false, true)) {
        startService(Intent(this, TerminalSessionService::class.java).setAction(TerminalSessionServiceProtocol.ACTION))
      }
    } catch (error: IllegalStateException) {
      // ForegroundServiceStartNotAllowedException and background
      // startService refusals are both IllegalStateExceptions.
      android.util.Log.w(LOG_TAG, "service_retain_refused type=${error::class.java.simpleName}")
      TerminalDebugLog.record(this, "service_retain_refused type=${error::class.java.simpleName}")
      return false
    }
    return true
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    lastStartId = startId
    if (intent?.action == ACTION_STOP) {
      enqueueWorker { stopServicePermanently() }
      return START_NOT_STICKY
    }
    if (intent?.action == RemoteAccess.ACTION_STOP) {
      remoteStore.setEnabled(false)
      enqueueWorker {
        if (remoteServerDelegate.isInitialized()) remoteServer.stop()
        refreshSessionNotifications()
        maybeStopIfIdle()
      }
    }
    val work = lifecycleWork()
    android.util.Log.i(LOG_TAG, "service_start null_intent=${intent == null} durable=${work.durable}")
    if (!work.durable) {
      // In particular, a null-intent restart with an empty journal must never
      // attempt startForeground from the background.
      startedForWork.set(false)
      stopSelf(startId)
      return START_NOT_STICKY
    }
    startedForWork.set(true)
    startForeground(NOTIFICATION_ID, buildNotification())
    queueRecovery()
    if (intent?.action == RemoteAccess.ACTION_START) enqueueWorker {
      if (remoteStore.isEnabled()) remoteServer.start()
      refreshSessionNotifications()
    }
    return START_STICKY
  }

  override fun onBind(intent: Intent?): IBinder {
    android.util.Log.i(LOG_TAG, "service_bound")
    TerminalDebugLog.record(this, "service_bound")
    queueRecovery()
    return messenger.binder
  }

  override fun onDestroy() {
    stopping = true
    TerminalDebugLog.record(this, "service_destroyed")
    handler.removeCallbacks(turnTick)
    stopProvisionProcess()
    if (remoteServerDelegate.isInitialized()) runCatching { remoteServer.close() }
    provisionExecutor.shutdownNow()
    worker.shutdownNow()
    if (supervisorDelegate.isInitialized()) {
      runCatching { supervisor.shutdownAll("service_destroyed") }
    }
    clearClient(null)
    cancelSessionNotifications()
    syncWakeLock(false)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
      stopForeground(STOP_FOREGROUND_REMOVE)
    } else {
      @Suppress("DEPRECATION")
      stopForeground(true)
    }
    super.onDestroy()
  }

  private fun handleMessage(message: Message) {
    if (!isHotPath(message.what)) {
      android.util.Log.i(LOG_TAG, "service_message what=${message.what} reply=${message.replyTo != null}")
      TerminalDebugLog.record(this, "service_message what=${message.what} reply=${message.replyTo != null}")
    }
    when (message.what) {
      TerminalSessionServiceProtocol.MSG_REGISTER_CLIENT -> registerClient(message.replyTo)
      TerminalSessionServiceProtocol.MSG_UNREGISTER_CLIENT -> clearClient(message.replyTo)
      TerminalSessionServiceProtocol.MSG_UI_VISIBILITY -> {
        val visible = message.data?.getBoolean(TerminalSessionServiceProtocol.KEY_VISIBLE, false) == true
        if (uiVisible != visible) {
          uiVisible = visible
          enqueueWorker { refreshSessionNotifications() }
        }
      }
      TerminalSessionServiceProtocol.MSG_START_SESSION,
      TerminalSessionServiceProtocol.MSG_WRITE_SESSION,
      TerminalSessionServiceProtocol.MSG_RESIZE_SESSION,
      TerminalSessionServiceProtocol.MSG_SIGNAL_SESSION,
      TerminalSessionServiceProtocol.MSG_STOP_SESSION,
      TerminalSessionServiceProtocol.MSG_SUBSCRIBE_SESSION,
      TerminalSessionServiceProtocol.MSG_ACKNOWLEDGE_OUTPUT,
      TerminalSessionServiceProtocol.MSG_STOP_ALL,
      TerminalSessionServiceProtocol.MSG_PROVISION_TOOLCHAIN,
      TerminalSessionServiceProtocol.MSG_LIST_SESSIONS,
      TerminalSessionServiceProtocol.MSG_DETACH_SESSION,
      TerminalSessionServiceProtocol.MSG_UNLOCK_GRANT,
      TerminalSessionServiceProtocol.MSG_REDRAW_SESSION -> {
        val what = message.what
        val data = Bundle(message.data)
        val reply = message.replyTo
        restoreClientIfCleared(reply)
        enqueueWorker { handleRequest(what, data, reply) }
      }
      else -> Unit
    }
  }

  private fun registerClient(incoming: Messenger?) {
    if (incoming == null) return
    val binder = incoming.binder
    val recipient = IBinder.DeathRecipient { clearClient(incoming) }
    synchronized(clientLock) {
      clientDeathRecipient?.let { previous ->
        runCatching { clientBinder?.unlinkToDeath(previous, 0) }
      }
      client = incoming
      clientBinder = binder
      clientDeathRecipient = recipient
      try {
        binder.linkToDeath(recipient, 0)
      } catch (_: RemoteException) {
        client = null
        clientBinder = null
        clientDeathRecipient = null
      }
    }
    android.util.Log.i(LOG_TAG, "service_client_registered")
    TerminalDebugLog.record(this, "service_client_registered")
    enqueueWorker { refreshSessionNotifications() }
  }

  /**
   * A failed event send (for example, Android froze the backgrounded UI
   * process and its binder buffer filled) clears the client, but the UI stays
   * bound and never registers again. Every request carries the UI's reply
   * messenger, so adopt it again; otherwise live output would stop reaching
   * the screen until the app process restarted.
   */
  private fun restoreClientIfCleared(reply: Messenger?) {
    if (reply == null) return
    val cleared = synchronized(clientLock) { client == null }
    if (!cleared || !reply.binder.isBinderAlive) return
    TerminalDebugLog.record(this, "service_client_restored")
    registerClient(reply)
  }

  private fun clearClient(expected: Messenger?) {
    var cleared = false
    synchronized(clientLock) {
      if (expected != null && client?.binder !== expected.binder) return
      clientDeathRecipient?.let { recipient ->
        runCatching { clientBinder?.unlinkToDeath(recipient, 0) }
      }
      client = null
      clientBinder = null
      clientDeathRecipient = null
      cleared = true
    }
    if (cleared) {
      TerminalDebugLog.record(this, "service_client_cleared")
      uiVisible = false
      val detached = attachedSessions.toList()
      attachedSessions.clear()
      enqueueWorker {
        detached.forEach { (sessionId, _) -> releaseCurrentOutputCredit(sessionId) }
        // Tell the user their session outlived the UI.
        refreshSessionNotifications()
        maybeStopIfIdle()
      }
    }
  }

  private fun handleRequest(what: Int, data: Bundle, reply: Messenger?) {
    if (reply == null) return
    if (!isHotPath(what)) {
      android.util.Log.i(LOG_TAG, "service_request what=$what request=${requestId(data)}")
      TerminalDebugLog.record(this, "service_request what=$what request=${requestId(data)}")
    }
    try {
      when (what) {
        TerminalSessionServiceProtocol.MSG_START_SESSION -> handleStart(data, reply)
        TerminalSessionServiceProtocol.MSG_WRITE_SESSION -> handleWrite(data, reply)
        TerminalSessionServiceProtocol.MSG_RESIZE_SESSION -> handleResize(data, reply)
        TerminalSessionServiceProtocol.MSG_SIGNAL_SESSION -> handleSignal(data, reply)
        TerminalSessionServiceProtocol.MSG_STOP_SESSION -> handleStop(data, reply)
        TerminalSessionServiceProtocol.MSG_SUBSCRIBE_SESSION -> handleSubscribe(data, reply)
        TerminalSessionServiceProtocol.MSG_ACKNOWLEDGE_OUTPUT -> handleAcknowledge(data, reply)
        TerminalSessionServiceProtocol.MSG_STOP_ALL -> handleStopAll(data, reply)
        TerminalSessionServiceProtocol.MSG_PROVISION_TOOLCHAIN -> handleProvision(data, reply)
        TerminalSessionServiceProtocol.MSG_LIST_SESSIONS -> handleListSessions(data, reply)
        TerminalSessionServiceProtocol.MSG_DETACH_SESSION -> handleDetachSession(data, reply)
        TerminalSessionServiceProtocol.MSG_UNLOCK_GRANT -> handleUnlockGrant(data, reply)
        TerminalSessionServiceProtocol.MSG_REDRAW_SESSION -> handleRedraw(data, reply)
      }
    } catch (error: Exception) {
      android.util.Log.e(
        LOG_TAG,
        "terminal_request_failed what=$what type=${error::class.java.simpleName} message=${error.message?.take(MAX_ERROR_LOG_CHARS)}",
      )
      TerminalDebugLog.record(this, "terminal_request_failed what=$what type=${error::class.java.simpleName}")
      sendResponse(
        reply,
        errorResponse(requestId(data), "internal_error"),
      )
    } finally {
      maybeStopIfIdle()
    }
  }

  private fun handleStart(data: Bundle, reply: Messenger) {
    val requestId = requestId(data)
    val rows = data.getInt(TerminalSessionServiceProtocol.KEY_ROWS, DEFAULT_ROWS)
    val columns = data.getInt(TerminalSessionServiceProtocol.KEY_COLUMNS, DEFAULT_COLUMNS)
    val command = if (data.containsKey(TerminalSessionServiceProtocol.KEY_COMMAND)) {
      data.getString(TerminalSessionServiceProtocol.KEY_COMMAND)
    } else {
      null
    }
    val toolchainTarget = if (data.containsKey(TerminalSessionServiceProtocol.KEY_TARGET)) {
      data.getString(TerminalSessionServiceProtocol.KEY_TARGET)
    } else {
      null
    }
    val countsAgainstSessionLimit = if (!data.containsKey(TerminalSessionServiceProtocol.KEY_COUNTS_AGAINST_SESSION_LIMIT)) {
      true
    } else {
      val raw = data.get(TerminalSessionServiceProtocol.KEY_COUNTS_AGAINST_SESSION_LIMIT)
      if (raw !is Boolean) {
        sendResponse(reply, errorResponse(requestId, "invalid_request"))
        return
      }
      raw
    }
    if (!TerminalRuntimeContract.isValidRequestId(requestId) ||
      !TerminalSessionContract.isValidRows(rows) ||
      !TerminalSessionContract.isValidColumns(columns) ||
      (command != null && (command.isEmpty() || command.length > TerminalSessionContract.MAX_COMMAND_LENGTH)) ||
      (toolchainTarget != null && !TerminalRuntimeContract.isValidToolchainTarget(toolchainTarget))
    ) {
      sendResponse(reply, errorResponse(requestId, "invalid_request"))
      return
    }
    val startRequestElapsedMs = SystemClock.elapsedRealtime()
    TerminalDebugLog.record(
      this,
      "service_start_target request=$requestId target=${toolchainTarget ?: "none"}",
    )

    val recoverable = sessionRecords.values.firstOrNull {
      it.sessionId !in nonCountingSessionIds &&
        it.command == command && it.toolchainTarget == toolchainTarget
    }
    if (recoverable != null) {
      val current = supervisor.snapshot(recoverable.sessionId)
      when {
        current?.state == TerminalSessionSupervisor.SessionState.RUNNING -> {
          supervisor.resize(recoverable.sessionId, rows, columns)
          sendResponse(reply, startSuccess(requestId, recoverable.sessionId, current.pid, rows, columns))
          return
        }
        current?.state == TerminalSessionSupervisor.SessionState.EXITED -> {
          sessionRecords.remove(recoverable.sessionId)
          runCatching { journal.remove(recoverable.sessionId) }
          histories.remove(recoverable.sessionId)
          clearSessionTimings(recoverable.sessionId)
        }
      }
    }

    val spec = when (val built = specFactory.build(rows, columns, command, toolchainTarget, countsAgainstSessionLimit)) {
      is TerminalSessionSpecFactory.BuildOutcome.Success -> built.spec
      is TerminalSessionSpecFactory.BuildOutcome.Failure -> {
        TerminalDebugLog.record(
          this,
          "service_start_spec_failed request=$requestId target=${toolchainTarget ?: "none"} error=${built.errorCode}",
        )
        sendResponse(reply, errorResponse(requestId, built.errorCode))
        return
      }
    }
    val reuseId = recoverable?.takeIf { supervisor.snapshot(it.sessionId) == null }?.sessionId
    val sessionId = reuseId ?: supervisor.nextSessionId()
    val record = TerminalSessionJournal.Record(
      sessionId = sessionId,
      rows = rows,
      columns = columns,
      command = command,
      startedAtMs = recoverable?.startedAtMs ?: System.currentTimeMillis(),
      toolchainTarget = toolchainTarget,
    )
    val wasPendingRecord = recoverable != null && reuseId != null
    val persistsAcrossServiceRestart = spec.countsAgainstSessionLimit
    if (persistsAcrossServiceRestart) {
      val upsert = try {
        journal.upsert(record)
      } catch (error: Exception) {
        android.util.Log.e(
          LOG_TAG,
          "session_journal_upsert_failed type=${error::class.java.simpleName} message=${error.message?.take(MAX_ERROR_LOG_CHARS)}",
        )
        sendResponse(reply, errorResponse(requestId, "internal_error"))
        return
      }
      when (upsert) {
        TerminalSessionJournal.UpsertOutcome.Stored -> Unit
        is TerminalSessionJournal.UpsertOutcome.CapacityReached -> {
          TerminalDebugLog.record(
            this,
            "service_session_capacity_reached request=$requestId active=${upsert.activeCount} limit=${upsert.limit}",
          )
          sendResponse(reply, errorResponse(requestId, "session_limit_reached"))
          return
        }
      }
    }
    sessionRecords[sessionId] = record
    if (!spec.countsAgainstSessionLimit) nonCountingSessionIds.add(sessionId)
    sessionStartElapsedMs[sessionId] = startRequestElapsedMs
    fun discardRecord() {
      sessionStartElapsedMs.remove(sessionId)
      if (!wasPendingRecord) {
        sessionRecords.remove(sessionId)
        if (persistsAcrossServiceRestart) runCatching { journal.remove(sessionId) }
      }
      nonCountingSessionIds.remove(sessionId)
    }
    if (!retainForWork()) {
      discardRecord()
      sendResponse(reply, errorResponse(requestId, "internal_error"))
      return
    }
    when (val outcome = supervisor.start(sessionId, spec)) {
      is TerminalSessionSupervisor.StartOutcome.Success -> {
        TerminalDebugLog.record(
          this,
          "service_session_started request=$requestId session=$sessionId target=${toolchainTarget ?: "none"} rows=$rows columns=$columns duration_ms=${SystemClock.elapsedRealtime() - startRequestElapsedMs}",
        )
        refreshSessionNotifications()
        sendResponse(reply, startSuccess(requestId, sessionId, outcome.handle.pid, rows, columns))
      }
      is TerminalSessionSupervisor.StartOutcome.Failure -> {
        TerminalDebugLog.record(
          this,
          "service_session_start_failed request=$requestId target=${toolchainTarget ?: "none"} reason=${outcome.reasonCode}",
        )
        discardRecord()
        // retainForWork() posted the generic foreground notification over the
        // first running session's; put the session notifications back.
        refreshSessionNotifications()
        sendResponse(reply, errorResponse(requestId, mapSessionFailureCode(outcome.reasonCode)))
      }
    }
  }

  /** Runs toolchain preparation in the service process, outside the RN heap. */
  private fun handleProvision(data: Bundle, reply: Messenger) {
    val requestId = requestId(data)
    val target = data.getString(TerminalSessionServiceProtocol.KEY_TARGET)
    if (!TerminalRuntimeContract.isValidRequestId(requestId) ||
      !TerminalRuntimeContract.isValidToolchainTarget(target)
    ) {
      sendResponse(reply, errorResponse(requestId, "invalid_request"))
      return
    }
    when (val built = specFactory.buildProvision(target!!)) {
      is TerminalSessionSpecFactory.ProvisionBuildOutcome.Ready -> {
        sendResponse(reply, successResponse(requestId))
        return
      }
      is TerminalSessionSpecFactory.ProvisionBuildOutcome.Failure -> {
        sendResponse(reply, errorResponse(requestId, built.errorCode))
        return
      }
      is TerminalSessionSpecFactory.ProvisionBuildOutcome.Work -> {
        if (!provisionInProgress.compareAndSet(false, true)) {
          sendResponse(reply, errorResponse(requestId, "install_in_progress"))
          return
        }
        if (!retainForWork()) {
          provisionInProgress.set(false)
          sendResponse(reply, errorResponse(requestId, "internal_error"))
          return
        }
        syncWakeLock(true)
        try {
          provisionExecutor.execute {
            val response = try {
              if (stopping) {
                errorResponse(requestId, "internal_error")
              } else {
                File(built.guestHomeDir, ".cache/horus/provision-stage").delete()
                val completed = runBoundedProvision(built.launch, TOOLCHAIN_PROVISION_TIMEOUT_MS)
                if (completed && built.launcher.hasProvisionedToolchain(
                    built.rootfsDir,
                    built.guestHomeDir,
                    built.target,
                  )
                ) {
                  successResponse(requestId)
                } else {
                  val failureCode = classifyProvisionFailure(built.guestHomeDir, built.target)
                  errorResponse(
                    requestId,
                    if (failureCode == "toolchain_incomplete" && !completed) {
                      "toolchain_install_failed"
                    } else {
                      failureCode
                    },
                  )
                }
              }
            } catch (_: Exception) {
              errorResponse(requestId, "internal_error")
            } finally {
              provisionInProgress.set(false)
              enqueueWorker { refreshSessionNotifications() }
            }
            sendResponse(reply, response)
            if (!hasClient()) enqueueWorker { maybeStopIfIdle() }
          }
        } catch (_: RuntimeException) {
          provisionInProgress.set(false)
          enqueueWorker { refreshSessionNotifications() }
          sendResponse(reply, errorResponse(requestId, "internal_error"))
        }
      }
    }
  }

  private fun runBoundedProvision(
    launch: ProotSessionLauncher.LaunchSpec,
    timeoutMs: Long,
  ): Boolean {
    val process = ProcessBuilder(launch.argv).apply {
      launch.workingDirectory?.let(::directory)
      environment().clear()
      launch.environment.forEach { entry ->
        val separator = entry.indexOf('=')
        if (separator > 0) environment()[entry.substring(0, separator)] = entry.substring(separator + 1)
      }
      redirectErrorStream(true)
    }.start()
    provisionProcess.set(process)
    val drain = Thread {
      try {
        process.inputStream.use { input ->
          val buffer = ByteArray(4096)
          while (input.read(buffer) >= 0) {
            // Provisioning output is intentionally drained but never bridged
            // into the visible terminal or Android logs.
          }
        }
      } catch (_: Exception) {
        // Process teardown closes the stream.
      }
    }.apply {
      name = "horus-toolchain-output"
      isDaemon = true
    }
    drain.start()
    return try {
      if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
        stopProvisionProcess(process)
        false
      } else {
        process.exitValue() == 0
      }
    } catch (_: InterruptedException) {
      stopProvisionProcess(process)
      Thread.currentThread().interrupt()
      false
    } finally {
      provisionProcess.compareAndSet(process, null)
      runCatching { process.inputStream.close() }
      if (drain.isAlive) {
        drain.interrupt()
        runCatching { drain.join(PROVISION_DRAIN_WAIT_MS) }
      }
    }
  }

  private fun stopProvisionProcess() {
    provisionProcess.getAndSet(null)?.let(::stopProvisionProcess)
  }

  private fun stopProvisionProcess(process: Process) {
    runCatching { process.destroy() }
    if (runCatching { process.waitFor(PROVISION_STOP_WAIT_MS, TimeUnit.MILLISECONDS) }.getOrDefault(false)) return
    runCatching { process.destroyForcibly() }
    runCatching { process.waitFor(PROVISION_STOP_WAIT_MS, TimeUnit.MILLISECONDS) }
  }

  private fun handleWrite(data: Bundle, reply: Messenger) {
    val requestId = requestId(data)
    val sessionId = data.getString(TerminalSessionServiceProtocol.KEY_SESSION_ID)
    val bytes = data.getByteArray(TerminalSessionServiceProtocol.KEY_BYTES)
    if (!TerminalRuntimeContract.isValidRequestId(requestId) ||
      !TerminalSessionContract.isValidSessionId(sessionId) ||
      bytes == null || bytes.isEmpty() || bytes.size > TerminalSessionContract.MAX_INPUT_BYTES
    ) {
      sendResponse(reply, errorResponse(requestId, "invalid_request"))
      return
    }
    when (val outcome = supervisor.write(sessionId!!, bytes)) {
      is TerminalSessionSupervisor.WriteOutcome.Success -> {
        if (isHarnessSession(sessionId)) {
          val started = synchronized(turnLock) { turnTracker.onInput(sessionId, bytes) }
          if (started) {
            notificationManager.cancel(turnNotificationId(sessionId))
            refreshSessionNotifications()
            scheduleTurnTick()
          }
        }
        sendResponse(
        reply,
        successResponse(requestId).apply {
          putInt(TerminalSessionServiceProtocol.KEY_BYTES_WRITTEN, outcome.bytesWritten)
        },
        )
      }
      is TerminalSessionSupervisor.WriteOutcome.Failure -> sendResponse(
        reply,
        errorResponse(requestId, mapSessionFailureCode(outcome.reasonCode)),
      )
    }
  }

  private fun handleResize(data: Bundle, reply: Messenger) {
    val requestId = requestId(data)
    val sessionId = data.getString(TerminalSessionServiceProtocol.KEY_SESSION_ID)
    val rows = data.getInt(TerminalSessionServiceProtocol.KEY_ROWS, -1)
    val columns = data.getInt(TerminalSessionServiceProtocol.KEY_COLUMNS, -1)
    if (!TerminalRuntimeContract.isValidRequestId(requestId) ||
      !TerminalSessionContract.isValidSessionId(sessionId) ||
      !TerminalSessionContract.isValidRows(rows) ||
      !TerminalSessionContract.isValidColumns(columns)
    ) {
      sendResponse(reply, errorResponse(requestId, "invalid_request"))
      return
    }
    TerminalDebugLog.record(this, "service_resize session=$sessionId rows=$rows columns=$columns")
    when (supervisor.resize(sessionId!!, rows, columns)) {
      TerminalSessionSupervisor.SessionOpOutcome.APPLIED -> sendResponse(
        reply,
        successResponse(requestId).apply {
          putInt(TerminalSessionServiceProtocol.KEY_ROWS, rows)
          putInt(TerminalSessionServiceProtocol.KEY_COLUMNS, columns)
        },
      )
      TerminalSessionSupervisor.SessionOpOutcome.SESSION_NOT_FOUND -> sendResponse(reply, errorResponse(requestId, "session_not_found"))
      TerminalSessionSupervisor.SessionOpOutcome.SESSION_EXITED -> sendResponse(reply, errorResponse(requestId, "session_exited"))
      TerminalSessionSupervisor.SessionOpOutcome.FAILED -> sendResponse(reply, errorResponse(requestId, "internal_error"))
    }
  }

  private fun handleSignal(data: Bundle, reply: Messenger) {
    val requestId = requestId(data)
    val sessionId = data.getString(TerminalSessionServiceProtocol.KEY_SESSION_ID)
    val signal = data.getString(TerminalSessionServiceProtocol.KEY_SIGNAL)
    if (!TerminalRuntimeContract.isValidRequestId(requestId) ||
      !TerminalSessionContract.isValidSessionId(sessionId) ||
      TerminalSessionContract.signalNumber(signal) == null
    ) {
      sendResponse(reply, errorResponse(requestId, "invalid_request"))
      return
    }
    when (supervisor.signal(sessionId!!, signal!!)) {
      TerminalSessionSupervisor.SessionOpOutcome.APPLIED -> sendResponse(
        reply,
        successResponse(requestId).apply { putString(TerminalSessionServiceProtocol.KEY_SIGNAL, signal) },
      )
      TerminalSessionSupervisor.SessionOpOutcome.SESSION_NOT_FOUND -> sendResponse(reply, errorResponse(requestId, "session_not_found"))
      TerminalSessionSupervisor.SessionOpOutcome.SESSION_EXITED -> sendResponse(reply, errorResponse(requestId, "session_exited"))
      TerminalSessionSupervisor.SessionOpOutcome.FAILED -> sendResponse(reply, errorResponse(requestId, "internal_error"))
    }
  }

  private fun handleStop(data: Bundle, reply: Messenger) {
    val requestId = requestId(data)
    val sessionId = data.getString(TerminalSessionServiceProtocol.KEY_SESSION_ID)
    val reason = data.getString(TerminalSessionServiceProtocol.KEY_REASON)
    if (!TerminalRuntimeContract.isValidRequestId(requestId) ||
      !TerminalSessionContract.isValidSessionId(sessionId) ||
      !TerminalSessionContract.isValidStopReason(reason)
    ) {
      sendResponse(reply, errorResponse(requestId, "invalid_request"))
      return
    }
    when (val outcome = supervisor.stop(sessionId!!, reason!!)) {
      is TerminalSessionSupervisor.StopOutcome.Stopped -> {
        sessionRecords.remove(sessionId)
        nonCountingSessionIds.remove(sessionId)
        attachedSessions.remove(sessionId)
        runCatching { journal.remove(sessionId) }
        histories.remove(sessionId)
        refreshSessionNotifications()
        sendResponse(
          reply,
          successResponse(requestId).apply {
            putString(TerminalSessionServiceProtocol.KEY_SESSION_ID, sessionId)
            outcome.observation.exit?.let { exit ->
              exit.exitCode?.let { putInt(TerminalSessionServiceProtocol.KEY_EXIT_CODE, it) }
              exit.signal?.let { putString(TerminalSessionServiceProtocol.KEY_EXIT_SIGNAL, it) }
              putString(TerminalSessionServiceProtocol.KEY_EXIT_REASON, exit.reason)
            }
            putInt(TerminalSessionServiceProtocol.KEY_REMAINING_PROCESS_COUNT, outcome.observation.remainingProcessCount)
            putBoolean(TerminalSessionServiceProtocol.KEY_STOPPED_WITHIN_DEADLINE, outcome.observation.stoppedWithinDeadline)
          },
        )
        maybeStopIfIdle()
      }
      is TerminalSessionSupervisor.StopOutcome.Failure -> sendResponse(
        reply,
        errorResponse(requestId, mapSessionFailureCode(outcome.reasonCode)),
      )
    }
  }

  private fun handleSubscribe(data: Bundle, reply: Messenger) {
    val requestId = requestId(data)
    val sessionId = data.getString(TerminalSessionServiceProtocol.KEY_SESSION_ID)
    val afterSeq = data.getLong(TerminalSessionServiceProtocol.KEY_AFTER_SEQ, 0L)
    if (!TerminalRuntimeContract.isValidRequestId(requestId) ||
      !TerminalSessionContract.isValidSessionId(sessionId) ||
      afterSeq < 0L
    ) {
      sendResponse(reply, errorResponse(requestId, "invalid_request"))
      return
    }
    val snapshot = supervisor.snapshot(sessionId!!)
    if (snapshot == null) {
      sendResponse(reply, errorResponse(requestId, "session_not_found"))
      return
    }
    if (snapshot.state == TerminalSessionSupervisor.SessionState.RUNNING) {
      attachedSessions[sessionId] = SystemClock.elapsedRealtime()
    }
    notificationManager.cancel(turnNotificationId(sessionId))
    val replayAfterSeq = minOf(afterSeq, snapshot.lastEmittedSeq)
    val history = histories[sessionId]?.snapshot().orEmpty().filter { it.seq > replayAfterSeq }
    val firstAvailable = history.firstOrNull()?.seq
      ?: snapshot.lastEmittedSeq + 1L
    val response = successResponse(requestId).apply {
      putString(TerminalSessionServiceProtocol.KEY_SESSION_ID, sessionId)
      putString(
        TerminalSessionServiceProtocol.KEY_SESSION_STATE,
        if (snapshot.state == TerminalSessionSupervisor.SessionState.EXITED) "exited" else "running",
      )
      putLong(TerminalSessionServiceProtocol.KEY_FIRST_AVAILABLE_SEQ, firstAvailable)
      putLong(TerminalSessionServiceProtocol.KEY_LAST_EMITTED_SEQ, snapshot.lastEmittedSeq)
      putBoolean(TerminalSessionServiceProtocol.KEY_REPLAY_AVAILABLE, firstAvailable == replayAfterSeq + 1L)
      snapshot.exit?.let { exit ->
        exit.exitCode?.let { putInt(TerminalSessionServiceProtocol.KEY_EXIT_CODE, it) }
        exit.signal?.let { putString(TerminalSessionServiceProtocol.KEY_EXIT_SIGNAL, it) }
        putString(TerminalSessionServiceProtocol.KEY_EXIT_REASON, exit.reason)
      }
    }
    // Replay must reach the client's event queue before the response resolves
    // attachAndSubscribe(). The JS listener buffers output until that response;
    // sending the response first can let a live frame advance its cursor before
    // these older replay frames are delivered.
    sendResponseAndReplay(reply, response, sessionId, history)
  }

  private fun handleListSessions(data: Bundle, reply: Messenger) {
    val requestId = requestId(data)
    if (!TerminalRuntimeContract.isValidRequestId(requestId)) {
      sendResponse(reply, errorResponse(requestId, "invalid_request"))
      return
    }
    val activeIds = supervisor.activeSessionIds().toSet()
    val sessions = ArrayList<Bundle>(minOf(activeIds.size, TerminalSessionContract.MAX_ACTIVE_SESSIONS))
    sessionRecords.values
      .asSequence()
      .filter { it.sessionId in activeIds }
      .filter { it.toolchainTarget != TerminalRuntimeContract.TOOLCHAIN_TARGET_GITHUB }
      .sortedByDescending { it.startedAtMs }
      .take(TerminalSessionContract.MAX_ACTIVE_SESSIONS)
      .forEach { record ->
        sessions += Bundle().apply {
          putString(TerminalSessionServiceProtocol.KEY_SESSION_ID, record.sessionId)
          putString(TerminalSessionServiceProtocol.KEY_TARGET, record.toolchainTarget ?: TerminalRuntimeContract.TOOLCHAIN_TARGET_SHELL)
          putLong(TerminalSessionServiceProtocol.KEY_STARTED_AT_MS, record.startedAtMs)
        }
      }
    sendResponse(
      reply,
      successResponse(requestId).apply {
        putParcelableArrayList(TerminalSessionServiceProtocol.KEY_SESSIONS, sessions)
      },
    )
  }

  /**
   * Makes the session's app repaint by shrinking the PTY one row and then
   * restoring its recorded size. The UI asks for this after its native
   * parser lost output and can no longer trust its screen.
   */
  private fun handleRedraw(data: Bundle, reply: Messenger) {
    val requestId = requestId(data)
    val sessionId = data.getString(TerminalSessionServiceProtocol.KEY_SESSION_ID)
    if (!TerminalRuntimeContract.isValidRequestId(requestId) || !TerminalSessionContract.isValidSessionId(sessionId)) {
      sendResponse(reply, errorResponse(requestId, "invalid_request"))
      return
    }
    if (!nudgeRedraw(sessionId!!, "output_gap")) {
      sendResponse(reply, errorResponse(requestId, "session_not_found"))
      return
    }
    sendResponse(reply, successResponse(requestId))
  }

  /** Shrinks the PTY one row, then restores its recorded size, so the app repaints. */
  private fun nudgeRedraw(sessionId: String, reason: String): Boolean {
    val nudge = supervisor.startRedrawNudge(sessionId) ?: return false
    TerminalDebugLog.record(this, "service_redraw_nudge session=$sessionId reason=$reason rows=${nudge.rows} columns=${nudge.columns}")
    handler.postDelayed({
      enqueueWorker {
        if (!supervisor.finishRedrawNudge(nudge)) {
          TerminalDebugLog.record(this, "service_redraw_restore_skipped session=$sessionId")
        }
      }
    }, REDRAW_RESTORE_DELAY_MS)
    return true
  }

  private fun handleUnlockGrant(data: Bundle, reply: Messenger) {
    val requestId = requestId(data)
    val op = data.getString(TerminalSessionServiceProtocol.KEY_UNLOCK_OP)
    if (!TerminalRuntimeContract.isValidRequestId(requestId) || !SessionUnlockGrant.isValidOp(op)) {
      sendResponse(reply, errorResponse(requestId, "invalid_request"))
      return
    }
    val unlocked = unlockGrant.apply(op!!)
    TerminalDebugLog.record(this, "service_unlock_grant op=$op unlocked=$unlocked")
    sendResponse(
      reply,
      successResponse(requestId).apply { putBoolean(TerminalSessionServiceProtocol.KEY_UNLOCKED, unlocked) },
    )
  }

  private fun handleDetachSession(data: Bundle, reply: Messenger) {
    val requestId = requestId(data)
    val sessionId = data.getString(TerminalSessionServiceProtocol.KEY_SESSION_ID)
    if (!TerminalRuntimeContract.isValidRequestId(requestId) ||
      !TerminalSessionContract.isValidSessionId(sessionId)
    ) {
      sendResponse(reply, errorResponse(requestId, "invalid_request"))
      return
    }
    attachedSessions.remove(sessionId!!)
    releaseCurrentOutputCredit(sessionId)
    sendResponse(reply, successResponse(requestId))
  }

  private fun handleAcknowledge(data: Bundle, reply: Messenger) {
    val requestId = requestId(data)
    val sessionId = data.getString(TerminalSessionServiceProtocol.KEY_SESSION_ID)
    val seq = data.getLong(TerminalSessionServiceProtocol.KEY_SEQ, -1L)
    if (!TerminalRuntimeContract.isValidRequestId(requestId) ||
      !TerminalSessionContract.isValidSessionId(sessionId) ||
      seq < 1L
    ) {
      sendResponse(reply, errorResponse(requestId, "invalid_request"))
      return
    }
    when (val outcome = supervisor.acknowledgeOutput(sessionId!!, seq)) {
      is TerminalSessionSupervisor.AcknowledgeOutcome.Acknowledged -> {
        attachedSessions[sessionId] = SystemClock.elapsedRealtime()
        sendResponse(
          reply,
          successResponse(requestId).apply {
            putString(TerminalSessionServiceProtocol.KEY_SESSION_ID, sessionId)
            putLong(TerminalSessionServiceProtocol.KEY_ACKNOWLEDGED_SEQ, outcome.value.acknowledgedSeq)
            putInt(TerminalSessionServiceProtocol.KEY_OUTSTANDING_CHUNKS, outcome.value.outstandingChunks)
          },
        )
      }
      is TerminalSessionSupervisor.AcknowledgeOutcome.Failure -> sendResponse(
        reply,
        errorResponse(requestId, mapSessionFailureCode(outcome.reasonCode)),
      )
    }
  }

  private fun handleStopAll(data: Bundle, reply: Messenger) {
    val requestId = requestId(data)
    val reason = data.getString(TerminalSessionServiceProtocol.KEY_REASON)
    if (!TerminalRuntimeContract.isValidRequestId(requestId) ||
      !TerminalSessionContract.isValidStopReason(reason)
    ) {
      sendResponse(reply, errorResponse(requestId, "invalid_request"))
      return
    }
    if (supervisorDelegate.isInitialized()) supervisor.shutdownAll(reason!!)
    attachedSessions.clear()
    sessionRecords.clear()
    nonCountingSessionIds.clear()
    histories.clear()
    cancelSessionNotifications()
    clearAllSessionTimings()
    runCatching { journal.clear() }
    sendResponse(reply, successResponse(requestId))
    maybeStopIfIdle()
  }

  private fun releaseOutputCredit(sessionId: String, seq: Long) {
    runCatching { supervisor.acknowledgeOutput(sessionId, seq) }
  }

  private fun releaseCurrentOutputCredit(sessionId: String) {
    val lastEmittedSeq = runCatching { supervisor.snapshot(sessionId)?.lastEmittedSeq }.getOrNull() ?: return
    if (lastEmittedSeq > 0L) releaseOutputCredit(sessionId, lastEmittedSeq)
  }

  private fun restorePersistedSessions() {
    if (stopping) return
    sessionRecords.values.toList().forEach { record ->
      if (stopping || supervisor.snapshot(record.sessionId) != null) return@forEach
      when (val built = specFactory.build(record.rows, record.columns, record.command, record.toolchainTarget)) {
        is TerminalSessionSpecFactory.BuildOutcome.Success -> {
          sessionStartElapsedMs[record.sessionId] = SystemClock.elapsedRealtime()
          when (supervisor.start(record.sessionId, built.spec)) {
            is TerminalSessionSupervisor.StartOutcome.Success -> Unit
            is TerminalSessionSupervisor.StartOutcome.Failure -> sessionStartElapsedMs.remove(record.sessionId)
          }
        }
        is TerminalSessionSpecFactory.BuildOutcome.Failure -> Unit
      }
    }
    refreshSessionNotifications()
  }

  private fun stopServicePermanently() {
    stopping = true
    runCatching { remoteStore.setEnabled(false) }
    if (remoteServerDelegate.isInitialized()) runCatching { remoteServer.close() }
    sessionRecords.clear()
    nonCountingSessionIds.clear()
    histories.clear()
    clearAllSessionTimings()
    runCatching { journal.clear() }
    if (supervisorDelegate.isInitialized()) runCatching { supervisor.shutdownAll("service_stopped") }
    cancelSessionNotifications()
    stopForegroundAndSelf()
  }

  private fun maybeStopIfIdle() {
    if (stopping || lifecycleWork().durable) return
    // stopSelf clears the started lifetime even while RN remains bound. The
    // binding keeps this instance available for the next request; do not set
    // stopping or shut down its executor until Android calls onDestroy.
    if (startedForWork.getAndSet(false)) {
      TerminalDebugLog.record(this, "service_stop_idle")
      android.util.Log.i(LOG_TAG, "service_stop_idle")
    }
    cancelSessionNotifications()
    syncWakeLock(false)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
      stopForeground(STOP_FOREGROUND_REMOVE)
    } else {
      @Suppress("DEPRECATION")
      stopForeground(true)
    }
    // This runs on the worker while starts arrive on the main thread. Stopping
    // by id leaves a start that raced in (remote access enabled a moment ago)
    // alive; a bare stopSelf() would cancel it.
    stopSelfResult(lastStartId)
  }

  private fun stopForegroundAndSelf() {
    stopping = true
    cancelSessionNotifications()
    syncWakeLock(false)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
      stopForeground(STOP_FOREGROUND_REMOVE)
    } else {
      @Suppress("DEPRECATION")
      stopForeground(true)
    }
    stopSelf()
  }

  private fun enqueueWorker(task: () -> Unit) {
    if (stopping) return
    runCatching { worker.execute(task) }
  }

  private fun clearSessionTimings(sessionId: String) {
    sessionStartElapsedMs.remove(sessionId)
    firstOutputLogged.remove(sessionId)
    readyMarkerLogged.remove(sessionId)
    readyMarkerTails.remove(sessionId)
  }

  private fun clearAllSessionTimings() {
    sessionStartElapsedMs.clear()
    firstOutputLogged.clear()
    readyMarkerLogged.clear()
    readyMarkerTails.clear()
  }

  /**
   * Treat a subscriber as live only while it has acknowledged recent output.
   * A bridge can remain registered after Android backgrounds or throttles the
   * React Native process; this bounded lease prevents that stale attachment
   * from applying PTY backpressure forever.
   */
  private fun shouldBlockOutput(sessionId: String): Boolean {
    val lastAcknowledgedAt = attachedSessions[sessionId] ?: return false
    if (!hasClient()) return false
    val idleMs = (SystemClock.elapsedRealtime() - lastAcknowledgedAt).coerceAtLeast(0L)
    return idleMs <= OUTPUT_SUBSCRIBER_IDLE_TIMEOUT_MS
  }

  /** Refreshes one ongoing notification per currently running PTY session. */
  private fun refreshSessionNotifications() {
    if (stopping) return
    val running = if (!supervisorDelegate.isInitialized()) {
      emptyList()
    } else {
      supervisor.activeSessionIds()
        .mapNotNull { sessionId ->
          sessionRecords[sessionId]?.takeIf {
            sessionId !in nonCountingSessionIds &&
            supervisor.snapshot(sessionId)?.state == TerminalSessionSupervisor.SessionState.RUNNING
          }
        }
        .sortedBy { it.startedAtMs }
        .take(TerminalSessionContract.MAX_ACTIVE_SESSIONS)
    }
    val slots = notificationSlotIds()
    val remoteActive = remoteStore.isEnabled() || isRemoteActive()
    val work = lifecycleWork()
    syncWakeLock(work.durable)
    val remoteInstalling = remoteActive && remoteServer.state == RemoteAccess.STATE_INSTALLING
    if (running.isEmpty()) {
      cancelSessionNotifications()
      notificationManager.cancel(REMOTE_NOTIFICATION_ID)
      if (provisionInProgress.get() || work.sessions) {
        startForeground(NOTIFICATION_ID, buildNotification())
      } else if (remoteActive) {
        startForeground(NOTIFICATION_ID, buildRemoteNotification(remoteInstalling))
      } else {
        maybeStopIfIdle()
      }
      return
    }
    val assigned = running.mapIndexed { index, record -> slots[index] to record }
    startForeground(NOTIFICATION_ID, buildSessionNotification(assigned.first().second, NOTIFICATION_ID))
    assigned.forEach { (notificationId, record) ->
      notificationManager.notify(notificationId, buildSessionNotification(record, notificationId))
    }
    val used = assigned.mapTo(HashSet()) { it.first }
    slots.filterNot(used::contains).forEach(notificationManager::cancel)
    if (remoteActive) {
      notificationManager.notify(REMOTE_NOTIFICATION_ID, buildRemoteNotification(remoteInstalling))
    } else {
      notificationManager.cancel(REMOTE_NOTIFICATION_ID)
    }
  }

  private fun cancelSessionNotifications() {
    notificationSlotIds().forEach(notificationManager::cancel)
    notificationManager.cancel(REMOTE_NOTIFICATION_ID)
  }

  private fun notificationSlotIds(): List<Int> = buildList {
    add(NOTIFICATION_ID)
    repeat(TerminalSessionContract.MAX_ACTIVE_SESSIONS - 1) { index ->
      add(SESSION_NOTIFICATION_BASE_ID + index)
    }
  }

  private fun notificationLabel(record: TerminalSessionJournal.Record): String = when (record.toolchainTarget) {
    TerminalRuntimeContract.TOOLCHAIN_TARGET_CLAUDE -> "Claude Code"
    TerminalRuntimeContract.TOOLCHAIN_TARGET_CODEX -> "Codex"
    TerminalRuntimeContract.TOOLCHAIN_TARGET_OPENCODE -> "OpenCode"
    TerminalRuntimeContract.TOOLCHAIN_TARGET_GITHUB -> "GitHub CLI"
    else -> "Bare terminal"
  }

  /** Logs only timing metadata; PTY bytes stay in the bounded output path. */
  private fun recordSessionOutputTimings(sessionId: String, chunk: ByteArray) {
    val startedAt = sessionStartElapsedMs[sessionId] ?: return
    if (firstOutputLogged.add(sessionId)) {
      val durationMs = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
      enqueueWorker {
        TerminalDebugLog.record(this, "session_first_output session=$sessionId duration_ms=$durationMs")
      }
    }
    if (sessionRecords[sessionId]?.toolchainTarget == null || readyMarkerLogged.contains(sessionId)) return
    val text = (readyMarkerTails[sessionId] ?: "") + String(chunk, Charsets.US_ASCII)
    readyMarkerTails[sessionId] = text.takeLast(READY_MARKER_SCAN_CHARS)
    if (!READY_MARKER_PATTERN.containsMatchIn(text) || !readyMarkerLogged.add(sessionId)) return
    val durationMs = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
    readyMarkerTails.remove(sessionId)
    enqueueWorker {
      TerminalDebugLog.record(this, "toolchain_ready_marker session=$sessionId duration_ms=$durationMs")
    }
  }

  private fun hasClient(): Boolean = synchronized(clientLock) { client != null }

  private fun sendResponse(target: Messenger, data: Bundle) {
    val requestId = requestId(data)
    val status = data.getString(TerminalSessionServiceProtocol.KEY_STATUS)
    if (status != TerminalSessionServiceProtocol.STATUS_SUCCESS ||
      !(requestId.startsWith("native-input-") || requestId.startsWith("native-ack-"))
    ) {
      TerminalDebugLog.record(
        this,
        "service_response request=$requestId status=${status ?: "unknown"} error=${data.getString(TerminalSessionServiceProtocol.KEY_ERROR_CODE) ?: "none"}",
      )
    }
    val message = Message.obtain(null, TerminalSessionServiceProtocol.MSG_SESSION_RESPONSE).apply {
      this.data = data
    }
    sendMessage(target, message)
  }

  private fun sendResponseAndReplay(
    target: Messenger,
    response: Bundle,
    sessionId: String,
    history: List<BoundedSessionOutputHistory.Event>,
  ) {
    try {
      val record = sessionRecords[sessionId]
      synchronized(clientSendLock) {
        history.forEach { event ->
          target.send(Message.obtain(null, TerminalSessionServiceProtocol.MSG_SESSION_EVENT).apply {
            data = Bundle().apply {
              putString(TerminalSessionServiceProtocol.KEY_EVENT_TYPE, TerminalSessionServiceProtocol.EVENT_OUTPUT)
              putString(TerminalSessionServiceProtocol.KEY_SESSION_ID, sessionId)
              putLong(TerminalSessionServiceProtocol.KEY_SEQ, event.seq)
              record?.let {
                putString(
                  TerminalSessionServiceProtocol.KEY_TARGET,
                  it.toolchainTarget ?: TerminalRuntimeContract.TOOLCHAIN_TARGET_SHELL,
                )
                putInt(TerminalSessionServiceProtocol.KEY_ROWS, it.rows)
                putInt(TerminalSessionServiceProtocol.KEY_COLUMNS, it.columns)
              }
              putByteArray(TerminalSessionServiceProtocol.KEY_BYTES, event.bytes.copyOf())
            }
          })
        }
        target.send(Message.obtain(null, TerminalSessionServiceProtocol.MSG_SESSION_RESPONSE).apply {
          data = response
        })
      }
    } catch (_: RemoteException) {
      clearClient(target)
    }
  }

  private fun sendEvent(data: Bundle, target: Messenger? = synchronized(clientLock) { client }) {
    if (target == null) return
    val message = Message.obtain(null, TerminalSessionServiceProtocol.MSG_SESSION_EVENT).apply {
      this.data = data
    }
    sendMessage(target, message)
  }

  private fun sendMessage(target: Messenger, message: Message) {
    try {
      synchronized(clientSendLock) { target.send(message) }
    } catch (_: RemoteException) {
      clearClient(target)
    }
  }

  // Keystrokes and output acknowledgements arrive once per key or PTY chunk;
  // logging each one put an fsync on every echo round trip.
  private fun isHotPath(what: Int): Boolean =
    what == TerminalSessionServiceProtocol.MSG_WRITE_SESSION || what == TerminalSessionServiceProtocol.MSG_ACKNOWLEDGE_OUTPUT

  private fun requestId(data: Bundle): String =
    data.getString(TerminalSessionServiceProtocol.KEY_REQUEST_ID) ?: INVALID_REQUEST_ID

  private fun successResponse(requestId: String): Bundle = Bundle().apply {
    putString(TerminalSessionServiceProtocol.KEY_REQUEST_ID, requestId)
    putString(TerminalSessionServiceProtocol.KEY_STATUS, TerminalSessionServiceProtocol.STATUS_SUCCESS)
  }

  private fun errorResponse(requestId: String, errorCode: String): Bundle = Bundle().apply {
    putString(TerminalSessionServiceProtocol.KEY_REQUEST_ID, requestId)
    putString(TerminalSessionServiceProtocol.KEY_STATUS, TerminalSessionServiceProtocol.STATUS_ERROR)
    putString(TerminalSessionServiceProtocol.KEY_ERROR_CODE, errorCode)
  }

  private fun startSuccess(requestId: String, sessionId: String, pid: Int, rows: Int, columns: Int): Bundle =
    successResponse(requestId).apply {
      putString(TerminalSessionServiceProtocol.KEY_SESSION_ID, sessionId)
      putInt(TerminalSessionServiceProtocol.KEY_PID, pid)
      putInt(TerminalSessionServiceProtocol.KEY_ROWS, rows)
      putInt(TerminalSessionServiceProtocol.KEY_COLUMNS, columns)
    }

  private fun mapSessionFailureCode(reasonCode: String): String = when (reasonCode) {
    "session_limit_reached" -> "session_limit_reached"
    "session_not_found" -> "session_not_found"
    "session_exited" -> "session_exited"
    "spawn_failed" -> "session_spawn_failed"
    "write_failed", "write_timeout" -> "session_write_failed"
    "invalid_request" -> "invalid_request"
    else -> "internal_error"
  }

  /** Classifies only stable failure categories; install logs stay app-private. */
  private fun classifyProvisionFailure(home: File, target: String): String {
    val logDirectory = File(home, ".cache/horus")
    val logName = when (target) {
      TerminalRuntimeContract.TOOLCHAIN_TARGET_GITHUB -> "github-install.log"
      TerminalRuntimeContract.TOOLCHAIN_TARGET_CLAUDE -> "claude-install.log"
      TerminalRuntimeContract.TOOLCHAIN_TARGET_CODEX -> "codex-install.log"
      TerminalRuntimeContract.TOOLCHAIN_TARGET_OPENCODE -> "opencode-install.log"
      else -> "alpine-bootstrap.log"
    }
    val targetLog = File(logDirectory, logName).let { file ->
      readPrivateTail(file)
    }
    when {
      targetLog.contains("EBADENGINE", ignoreCase = true) -> return "toolchain_node_version"
      targetLog.contains("ENOSPC", ignoreCase = true) -> return "toolchain_install_failed"
      targetLog.contains("EACCES", ignoreCase = true) || targetLog.contains("permission denied", ignoreCase = true) -> return "toolchain_permissions"
      targetLog.contains("ENETUNREACH", ignoreCase = true) || targetLog.contains("EAI_AGAIN", ignoreCase = true) || targetLog.contains("fetch failed", ignoreCase = true) -> return "toolchain_network"
      targetLog.contains("EBADPLATFORM", ignoreCase = true) || targetLog.contains("Unsupported platform", ignoreCase = true) -> return "toolchain_install_failed"
    }
    val stage = File(logDirectory, "provision-stage").let { file ->
      readPrivateTail(file).trim()
    }
    return when (stage) {
      "github_failed" -> "toolchain_github_install_failed"
      "claude_failed" -> "toolchain_install_failed"
      "codex_failed" -> "toolchain_codex_install_failed"
      "opencode_failed" -> "toolchain_opencode_install_failed"
      "normalize_failed" -> "toolchain_normalization_failed"
      "verify_failed" -> "toolchain_incomplete"
      "apk_failed" -> "toolchain_install_failed"
      else -> "toolchain_incomplete"
    }
  }

  private fun readPrivateTail(file: File): String = runCatching {
    if (!file.isFile) return@runCatching ""
    val length = file.length()
    val start = (length - MAX_CLASSIFICATION_BYTES).coerceAtLeast(0L)
    RandomAccessFile(file, "r").use { input ->
      input.seek(start)
      val size = (length - start).toInt()
      val bytes = ByteArray(size)
      var offset = 0
      while (offset < size) {
        val read = input.read(bytes, offset, size - offset)
        if (read < 0) break
        offset += read
      }
      String(bytes, 0, offset, Charsets.UTF_8)
    }
  }.getOrDefault("")

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val channel = NotificationChannel(
      NOTIFICATION_CHANNEL_ID,
      getString(R.string.terminal_service_channel_name),
      NotificationManager.IMPORTANCE_LOW,
    ).apply {
      description = getString(R.string.terminal_service_channel_description)
      setShowBadge(false)
    }
    val turnChannel = NotificationChannel(
      TURN_CHANNEL_ID,
      getString(R.string.terminal_turn_channel_name),
      NotificationManager.IMPORTANCE_HIGH,
    ).apply {
      description = getString(R.string.terminal_turn_channel_description)
    }
    getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(channel, turnChannel))
  }

  private fun buildNotification(): Notification {
    return notificationBuilder()
      .setContentTitle(getString(R.string.terminal_service_title))
      .setContentText(getString(R.string.terminal_service_text))
      .setContentIntent(buildPendingIntent(NOTIFICATION_REQUEST_CODE))
      .setOngoing(true)
      .setAutoCancel(false)
      .setShowWhen(false)
      .setCategory(Notification.CATEGORY_SERVICE)
      .build()
  }

  private fun buildRemoteNotification(installing: Boolean): Notification {
    val stopIntent = PendingIntent.getService(
      this,
      REMOTE_STOP_REQUEST_CODE,
      Intent(this, TerminalSessionService::class.java).setAction(RemoteAccess.ACTION_STOP),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val text = getString(if (installing) R.string.remote_access_text_installing else R.string.remote_access_text)
    return notificationBuilder()
      .setContentTitle(getString(R.string.remote_access_title))
      .setContentText(text)
      .setStyle(Notification.BigTextStyle().bigText(text))
      .setContentIntent(buildPendingIntent(REMOTE_NOTIFICATION_ID))
      .addAction(Notification.Action.Builder(null, getString(R.string.remote_access_stop), stopIntent).build())
      .setOngoing(true)
      .setAutoCancel(false)
      .setShowWhen(false)
      .setCategory(Notification.CATEGORY_SERVICE)
      .build()
  }

  private fun buildSessionNotification(
    record: TerminalSessionJournal.Record,
    notificationId: Int,
  ): Notification {
    val label = notificationLabel(record)
    val harness = isHarnessTarget(record.toolchainTarget)
    val working = harness && synchronized(turnLock) {
      turnTracker.state(record.sessionId) == HarnessTurnTracker.State.WORKING
    }
    // Without a client the UI process was reclaimed (or frozen and
    // unreachable). Say plainly that the work is safe.
    val uiGone = !hasClient()
    val title = when {
      working && uiGone -> getString(R.string.terminal_session_title_working_detached, label)
      working -> getString(R.string.terminal_session_title_working, label)
      uiGone -> getString(R.string.terminal_session_title_open_detached, label)
      else -> getString(R.string.terminal_session_title_open, label)
    }
    val text = when {
      uiGone -> getString(R.string.terminal_session_text_detached)
      working -> getString(R.string.terminal_session_text_working)
      else -> getString(R.string.terminal_session_text_open)
    }
    return notificationBuilder()
      .setContentTitle(title)
      .setContentText(text)
      .setStyle(Notification.BigTextStyle().bigText(text))
      .setSubText(getString(R.string.terminal_session_subtext, sessionStartedAt(record)))
      .setWhen(record.startedAtMs)
      .setShowWhen(true)
      .setUsesChronometer(true)
      .setContentIntent(buildPendingIntent(notificationId, record.sessionId))
      .setOngoing(true)
      .setAutoCancel(false)
      .setCategory(Notification.CATEGORY_SERVICE)
      .build()
  }

  /** One alerting reminder per finished turn, only while Horus is off screen. */
  private fun notifyTurnFinished(sessionId: String) {
    val record = sessionRecords[sessionId] ?: return
    if (supervisor.snapshot(sessionId)?.state != TerminalSessionSupervisor.SessionState.RUNNING) return
    val label = notificationLabel(record)
    val text = getString(R.string.terminal_turn_text)
    val notificationId = turnNotificationId(sessionId)
    val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      Notification.Builder(this, TURN_CHANNEL_ID)
    } else {
      @Suppress("DEPRECATION")
      Notification.Builder(this).setPriority(Notification.PRIORITY_HIGH).setDefaults(Notification.DEFAULT_ALL)
    }
    val notification = builder
      .setSmallIcon(R.mipmap.ic_launcher)
      .setContentTitle(getString(R.string.terminal_turn_title, label))
      .setContentText(text)
      .setStyle(Notification.BigTextStyle().bigText(text))
      .setSubText(getString(R.string.terminal_session_subtext, sessionStartedAt(record)))
      .setContentIntent(buildPendingIntent(notificationId, sessionId))
      .setAutoCancel(true)
      .setShowWhen(true)
      .setCategory(Notification.CATEGORY_REMINDER)
      .build()
    TerminalDebugLog.record(this, "turn_finished_notification session=$sessionId")
    notificationManager.notify(notificationId, notification)
  }

  private fun turnNotificationId(sessionId: String): Int =
    TURN_NOTIFICATION_BASE_ID + Math.floorMod(sessionId.hashCode(), TURN_NOTIFICATION_ID_RANGE)

  private fun scheduleTurnTick() {
    if (!turnTickScheduled.compareAndSet(false, true)) return
    handler.postDelayed(turnTick, TURN_TICK_MS)
  }

  private fun isHarnessSession(sessionId: String): Boolean = isHarnessTarget(sessionRecords[sessionId]?.toolchainTarget)

  private fun isHarnessTarget(target: String?): Boolean =
    target == TerminalRuntimeContract.TOOLCHAIN_TARGET_CLAUDE ||
      target == TerminalRuntimeContract.TOOLCHAIN_TARGET_CODEX ||
      target == TerminalRuntimeContract.TOOLCHAIN_TARGET_OPENCODE

  private fun sessionStartedAt(record: TerminalSessionJournal.Record): String = DateUtils.formatDateTime(
    this,
    record.startedAtMs,
    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_ALL,
  )

  private fun buildPendingIntent(requestCode: Int, sessionId: String? = null): PendingIntent {
    // Resolve the launcher entry point without loading the React activity in
    // this private service process. Keeping RN/Hermes out of :terminal is part
    // of the memory-survival boundary.
    val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
      flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
      sessionId?.let { putExtra(LaunchSessionIntent.EXTRA_SESSION_ID, it) }
    } ?: Intent().setPackage(packageName)
    return PendingIntent.getActivity(
      this,
      requestCode,
      launchIntent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
  }

  private fun notificationBuilder(): Notification.Builder {
    val builder: Notification.Builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
    } else {
      @Suppress("DEPRECATION")
      Notification.Builder(this)
    }
    return builder
      .setSmallIcon(R.mipmap.ic_launcher)
      .setOnlyAlertOnce(true)
  }

  private companion object {
    const val ACTION_STOP = "com.scariflabs.horus.action.STOP_TERMINAL_SESSION_SERVICE"
    const val REMOTE_NOTIFICATION_ID = 1378
    const val REMOTE_STOP_REQUEST_CODE = 1377
    const val NOTIFICATION_CHANNEL_ID = "terminal_sessions"
    const val NOTIFICATION_ID = 1379
    const val TURN_CHANNEL_ID = "harness_turns"
    const val TURN_NOTIFICATION_BASE_ID = 2000
    const val TURN_NOTIFICATION_ID_RANGE = 1000
    const val TURN_TICK_MS = 1_000L
    // Long enough for the app to handle SIGWINCH at the shrunken size before
    // the restore arrives; a coalesced pair would look like no change.
    const val REDRAW_RESTORE_DELAY_MS = 250L
    const val SESSION_NOTIFICATION_BASE_ID = 1380
    const val NOTIFICATION_REQUEST_CODE = 1379
    const val DEFAULT_ROWS = 24
    const val DEFAULT_COLUMNS = 80
    const val INVALID_REQUEST_ID = "invalid-request"
    const val TOOLCHAIN_PROVISION_TIMEOUT_MS = 300_000L
    const val PROVISION_STOP_WAIT_MS = 2_000L
    const val PROVISION_DRAIN_WAIT_MS = 500L
    const val MAX_CLASSIFICATION_BYTES = 16 * 1024L
    const val LOG_TAG = "HorusTerminal"
    const val MAX_ERROR_LOG_CHARS = 160
    const val READY_MARKER_SCAN_CHARS = 64
    const val OUTPUT_SUBSCRIBER_IDLE_TIMEOUT_MS = 750L
    private val READY_MARKER_PATTERN = Regex("(?:^|[\\r\\n])HORUS_TOOLCHAIN_READY(?:[\\r\\n]|$)")
  }
}

/** Bounded in-memory replay sized to restore the terminal's visible scrollback. */
class BoundedSessionOutputHistory(
  private val maxChunks: Int = MAX_CHUNKS,
  private val maxBytes: Int = MAX_BYTES,
) {
  data class Event(val seq: Long, val bytes: ByteArray)

  private val lock = Any()
  private val events = ArrayDeque<Event>()
  private var bytes = 0

  fun add(seq: Long, value: ByteArray) {
    if (seq < 1L || value.isEmpty() || value.size > maxBytes) return
    synchronized(lock) {
      val copy = value.copyOf()
      events.addLast(Event(seq, copy))
      bytes += copy.size
      while (events.size > maxChunks || bytes > maxBytes) {
        val removed = events.removeFirst()
        bytes -= removed.bytes.size
      }
    }
  }

  fun snapshot(): List<Event> = synchronized(lock) {
    events.map { event -> Event(event.seq, event.bytes.copyOf()) }
  }

  private companion object {
    const val MAX_CHUNKS = 4_096
    const val MAX_BYTES = 1024 * 1024
  }
}
