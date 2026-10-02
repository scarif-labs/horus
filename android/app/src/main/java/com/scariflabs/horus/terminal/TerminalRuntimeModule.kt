package com.scariflabs.horus.terminal

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.BaseActivityEventListener
import com.facebook.react.bridge.LifecycleEventListener
import com.facebook.react.common.LifecycleState
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.ReadableType
import com.facebook.react.bridge.WritableMap
import com.facebook.react.module.annotations.ReactModule
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.scariflabs.horus.specs.NativeTerminalRuntimeSpec
import java.io.File
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Converts a JS number to an Int only when it is exactly an integer in Int
 * range. NaN, infinities, fractions, and out-of-range values return null
 * rather than being truncated or saturated by Double.toInt().
 */
internal fun exactIntOrNull(value: Double): Int? =
  value.takeIf {
    it.isFinite() && it >= Int.MIN_VALUE.toDouble() && it <= Int.MAX_VALUE.toDouble() && it.toInt().toDouble() == it
  }?.toInt()

/**
 * Codegen-backed implementation of the Alpine terminal runtime module. The
 * generated superclass is produced from src/native/NativeTerminalRuntime.ts.
 *
 * Phase 1 surface: getRuntimeStatus (capability snapshot + install state),
 * installRootfs (download → verify → extract → probe → promote on a single
 * background worker), and resetRuntime (scoped rootfs, home, workspace, or
 * all-user-data deletion).
 *
 * Phase 2 surface: native PTY sessions. The foreground service owns the
 * launcher's fixed argv and the PRoot process tree in a private Android
 * process; this module maps the typed React Native surface onto that bounded
 * Messenger protocol. A bridge teardown detaches the UI only, so the service
 * can keep a session alive and a recreated bridge can reattach to it.
 */
@ReactModule(name = TerminalRuntimeContract.MODULE_NAME)
class TerminalRuntimeModule(
  private val appContext: ReactApplicationContext,
) : NativeTerminalRuntimeSpec(appContext) {

  private val installExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "alpine-distro-install").apply { isDaemon = true }
  }
  private val installInProgress = AtomicBoolean(false)
  private val invalidated = AtomicBoolean(false)
  // Packaged native libraries cannot change while this APK process is alive.
  private val packagedProotRuntime: ProotRuntimeLocator.Location by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    locateProotRuntimeUncached()
  }

  override fun getName(): String = NAME

  private val store: DistroStoreCore by lazy {
    DistroStoreCore(
      paths = DistroStorePaths(File(appContext.filesDir, TerminalRuntimeContract.STORAGE_ROOT_DIR_NAME)),
      downloader = HttpArchiveDownloader(),
      extractor = SafeTarGzExtractor(),
      prober = prootProber(),
    )
  }

  private val paths: DistroStorePaths
    get() = DistroStorePaths(File(appContext.filesDir, TerminalRuntimeContract.STORAGE_ROOT_DIR_NAME))

  private val downloadSourceSettings by lazy { DownloadSourceSettings.forStorageRoot(appContext.filesDir) }

  private val sessionSettings by lazy {
    TerminalSessionSettings(
      File(
        appContext.filesDir,
        "${TerminalRuntimeContract.STORAGE_ROOT_DIR_NAME}/settings/session-settings.json",
      ),
    )
  }

  private enum class SessionEventKind { OUTPUT, EXIT }

  private data class QueuedSessionEvent(
    val kind: SessionEventKind,
    val build: () -> WritableMap,
  )

  private val sessionEventQueue = ArrayBlockingQueue<QueuedSessionEvent>(SESSION_EVENT_QUEUE_CAPACITY)
  private val sessionEventExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "alpine-session-events").apply { isDaemon = true }
  }
  private val sessionServiceClient = TerminalSessionServiceClient(appContext, ::onSessionEvent)
  private val nativeInputCounter = AtomicLong(0)
  private val nativeAckCounter = AtomicLong(0)
  private val nativeReadySessions = HashSet<String>()
  private val nativeReadyTails = HashMap<String, String>()
  private val nativeMarkerSessions = HashSet<String>()
  private val nativeReadyLock = Any()
  private val nativeAckExecutor = ScheduledThreadPoolExecutor(1) { runnable ->
    Thread(runnable, "native-output-ack").apply { isDaemon = true }
  }.apply { removeOnCancelPolicy = true }
  private val nativeAckBatcher = NativeOutputAckBatcher(
    scheduler = { delayMs, task ->
      val future = nativeAckExecutor.schedule(task, delayMs, TimeUnit.MILLISECONDS)
      NativeOutputAckBatcher.Cancellable { future.cancel(false) }
    },
    sendAck = ::acknowledgeNativeOutput,
  )

  private val redrawNudgeCounter = AtomicLong(0)
  private val lastRedrawNudgeAt = ConcurrentHashMap<String, Long>()
  // Insertion-ordered so the oldest screen goes first.
  private val exitScreens = LinkedHashMap<String, String>()

  private val visibilityListener = object : LifecycleEventListener {
    override fun onHostResume() = sessionServiceClient.reportUiVisibility(true)
    override fun onHostPause() = sessionServiceClient.reportUiVisibility(false)
    override fun onHostDestroy() = sessionServiceClient.reportUiVisibility(false)
  }

  init {
    TerminalDebugLog.record(appContext, "module_created")
    appContext.addLifecycleEventListener(visibilityListener)
    if (appContext.lifecycleState == LifecycleState.RESUMED) sessionServiceClient.reportUiVisibility(true)
    NativeTerminalEngineRegistry.configureClipboardWriter(::writeTerminalClipboard)
    NativeTerminalEngineRegistry.configureOutputGapListener(::requestRedrawAfterOutputGap)
    NativeTerminalEngineRegistry.configureInputWriter { sessionId, bytes ->
      val counter = nativeInputCounter.updateAndGet { current ->
        if (current >= Long.MAX_VALUE) 1L else current + 1L
      }
      sessionServiceClient.writeSession(
        "native-input-${counter.toString(36)}",
        sessionId,
        bytes,
      ) { response ->
        if (response.getString(TerminalSessionServiceProtocol.KEY_STATUS) != TerminalSessionServiceProtocol.STATUS_SUCCESS) {
          TerminalDebugLog.record(appContext, "native_input_failed session=$sessionId")
        }
      }
    }
    // Keep a single ordered emitter and a bounded queue. Producers (the PTY
    // reader/reaper threads) wait for queue space, which gives output a real
    // native backpressure boundary instead of allowing an unbounded executor
    // queue to grow while the bridge is stalled.
    sessionEventExecutor.execute {
      try {
        while (!Thread.currentThread().isInterrupted) {
          val event = sessionEventQueue.take()
          if (invalidated.get()) continue
          try {
            appContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
              .emit(SESSION_EVENT_NAME, event.build())
          } catch (_: Exception) {
            // The bridge may already be gone during teardown; drop the event.
          }
        }
      } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
      }
    }
  }

  /**
   * Events are emitted from one dedicated thread and never from the reader or
   * reaper threads directly: a single emitter preserves cross-session event
   * order and keeps bridge calls off the pty drain path.
   */
  private fun emitSessionEvent(kind: SessionEventKind, build: () -> WritableMap) {
    if (invalidated.get()) return
    val event = QueuedSessionEvent(kind, build)
    if (sessionEventQueue.offer(event)) return
    // The bridge handler runs on Android's main looper. Never wait there for
    // the JS emitter: output can be dropped/coalesced and replayed from the
    // native history ring after the client observes a sequence gap. Exit
    // events take priority over queued output so teardown remains visible.
    val iterator = sessionEventQueue.iterator()
    while (iterator.hasNext()) {
      if (iterator.next().kind == SessionEventKind.OUTPUT) {
        iterator.remove()
        break
      }
    }
    sessionEventQueue.offer(event)
  }

  private fun onSessionEvent(event: Bundle) {
    if (invalidated.get()) return
    val eventType = event.getString(TerminalSessionServiceProtocol.KEY_EVENT_TYPE)
    val sessionId = event.getString(TerminalSessionServiceProtocol.KEY_SESSION_ID) ?: return
    if (!TerminalSessionContract.isValidSessionId(sessionId)) return
    when (eventType) {
      TerminalSessionServiceProtocol.EVENT_OUTPUT -> {
        val seq = event.getLong(TerminalSessionServiceProtocol.KEY_SEQ, -1L)
        val bytes = event.getByteArray(TerminalSessionServiceProtocol.KEY_BYTES)
        if (seq < 1L || bytes == null || bytes.isEmpty() || bytes.size > TerminalSessionSupervisor.READ_CHUNK_BYTES) return
        val target = event.getString(TerminalSessionServiceProtocol.KEY_TARGET)
        val harness = isNativeHarnessTarget(target)
        // Shell output always feeds the native parser, but only an interactive
        // shell binds a native canvas. One-off shell commands never bind one,
        // so their output keeps flowing to the JS completion-marker scan.
        val nativeOutput = harness || target == TerminalRuntimeContract.TOOLCHAIN_TARGET_SHELL
        val nativeReadyBefore = nativeOutput && isNativeSessionReady(sessionId)
        if (nativeOutput) {
          val rows = event.getInt(TerminalSessionServiceProtocol.KEY_ROWS, DEFAULT_SESSION_ROWS)
          val columns = event.getInt(TerminalSessionServiceProtocol.KEY_COLUMNS, DEFAULT_SESSION_COLUMNS)
          if (!NativeTerminalEngineRegistry.enqueueOutput(sessionId, seq, rows, columns, bytes)) {
            TerminalDebugLog.record(appContext, "native_engine_queue_full session=$sessionId seq=$seq")
          }
          if (!nativeReadyBefore && nativeReadyMarkerSeen(sessionId, bytes) &&
            (harness || NativeTerminalEngineRegistry.isRendered(sessionId))
          ) {
            markNativeSessionReady(sessionId)
          }
          if (nativeReadyBefore) nativeAckBatcher.record(sessionId, seq)
        }
        // Keep the startup prefix on the existing JS path so readiness and
        // install-failure handling remain unchanged. Once the marker has
        // arrived (and, for a shell, a native canvas is drawing it), native
        // parsing, rendering, and acknowledgement stay off JS.
        if (nativeOutput && nativeReadyBefore) return
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        emitSessionEvent(SessionEventKind.OUTPUT) {
          Arguments.createMap().apply {
            putString("type", "output")
            putString("sessionId", sessionId)
            putDouble("seq", seq.toDouble())
            putString("base64", encoded)
          }
        }
      }
      TerminalSessionServiceProtocol.EVENT_EXIT -> {
        val reason = event.getString(TerminalSessionServiceProtocol.KEY_EXIT_REASON) ?: return
        if (!TerminalSessionContract.isValidStopReason(reason)) return
        rememberExitScreen(sessionId)
        NativeTerminalEngineRegistry.close(sessionId)
        nativeAckBatcher.clear(sessionId)
        lastRedrawNudgeAt.remove(sessionId)
        synchronized(nativeReadyLock) {
          nativeReadySessions.remove(sessionId)
          nativeReadyTails.remove(sessionId)
          nativeMarkerSessions.remove(sessionId)
        }
        emitSessionEvent(SessionEventKind.EXIT) {
          Arguments.createMap().apply {
            putString("type", "exit")
            putString("sessionId", sessionId)
            putString("reason", reason)
            if (event.containsKey(TerminalSessionServiceProtocol.KEY_EXIT_CODE)) {
              putInt("exitCode", event.getInt(TerminalSessionServiceProtocol.KEY_EXIT_CODE))
            }
            if (event.containsKey(TerminalSessionServiceProtocol.KEY_EXIT_SIGNAL)) {
              putString("signal", event.getString(TerminalSessionServiceProtocol.KEY_EXIT_SIGNAL))
            }
          }
        }
      }
    }
  }

  private fun isNativeSessionReady(sessionId: String): Boolean = synchronized(nativeReadyLock) {
    nativeReadySessions.contains(sessionId)
  }

  private fun markNativeSessionReady(sessionId: String) {
    synchronized(nativeReadyLock) {
      nativeReadySessions.add(sessionId)
    }
  }

  /** Sticky per session: a shell may print the marker before its canvas binds. */
  private fun nativeReadyMarkerSeen(sessionId: String, bytes: ByteArray): Boolean {
    synchronized(nativeReadyLock) {
      if (nativeMarkerSessions.contains(sessionId)) return true
      val combined = nativeReadyTails[sessionId].orEmpty() + String(bytes, StandardCharsets.UTF_8)
      nativeReadyTails[sessionId] = combined.takeLast(NATIVE_READY_SCAN_CHARS)
      if (!NATIVE_READY_MARKER_PATTERN.containsMatchIn(combined)) return false
      nativeMarkerSessions.add(sessionId)
      nativeReadyTails.remove(sessionId)
      return true
    }
  }

  /** OSC 52 from a native session: decode and place plain text on the clipboard. */
  private fun writeTerminalClipboard(payload: String) {
    if (invalidated.get() || payload.length > MAX_CLIPBOARD_BASE64_CHARS) return
    val bytes = runCatching { Base64.decode(payload, Base64.DEFAULT) }.getOrNull() ?: return
    val text = String(bytes, StandardCharsets.UTF_8)
    if (text.isEmpty()) return
    android.os.Handler(android.os.Looper.getMainLooper()).post {
      runCatching {
        val clipboard = appContext.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Terminal", text))
      }
    }
  }

  private fun acknowledgeNativeOutput(sessionId: String, seq: Long) {
    val counter = nativeAckCounter.updateAndGet { current ->
      if (current >= Long.MAX_VALUE) 1L else current + 1L
    }
    sessionServiceClient.acknowledgeOutput(
      "native-ack-${counter.toString(36)}",
      sessionId,
      seq,
    ) { response ->
      if (response.getString(TerminalSessionServiceProtocol.KEY_STATUS) != TerminalSessionServiceProtocol.STATUS_SUCCESS) {
        TerminalDebugLog.record(appContext, "native_ack_failed session=$sessionId seq=$seq")
      }
    }
  }

  private fun prootProber(): GuestProber {
    val located = locateProotRuntime()
    return if (located is ProotRuntimeLocator.Location.Available) {
      ProotGuestProber(located.runtime, File(paths.sessions, ".proot-scratch"))
    } else {
      UnavailableGuestProber((located as ProotRuntimeLocator.Location.Unavailable).reasonCode)
    }
  }

  private fun locateProotRuntime(): ProotRuntimeLocator.Location = packagedProotRuntime

  private fun locateProotRuntimeUncached(): ProotRuntimeLocator.Location = try {
    val manifest = appContext.assets.open("alpine-runtime/manifest.json").bufferedReader().readText()
    val nativeLibraryDir = File(appContext.applicationInfo.nativeLibraryDir ?: "")
    ProotRuntimeLocator(manifest).locate(nativeLibraryDir)
  } catch (error: Exception) {
    ProotRuntimeLocator.Location.Unavailable("proot_locate_failed", error.message ?: "cannot locate the packaged runtime")
  }

  override fun getRuntimeStatus(promise: Promise) {
    if (isInvalidated()) {
      promise.resolve(errorStatus("internal_error"))
      return
    }
    val startedAt = SystemClock.elapsedRealtime()
    val snapshot = TerminalRuntimeContract.buildStatusSnapshot(
      supportedAbis = Build.SUPPORTED_ABIS,
      apiLevel = Build.VERSION.SDK_INT,
      appVersion = appVersion(),
      filesDirPath = appContext.filesDir?.path,
    )
    val active = store.readActiveRecord()
    val located = locateProotRuntime()
    val status = Arguments.createMap().apply {
      putString("status", "success")
      putInt("schemaVersion", snapshot.schemaVersion)
      putString("runtimeState", if (active != null) TerminalRuntimeContract.RUNTIME_STATE_READY else TerminalRuntimeContract.RUNTIME_STATE_NOT_INSTALLED)
      putString("runtimeVersion", snapshot.runtimeVersion)
      putString("abi", snapshot.abi)
      putInt("apiLevel", snapshot.apiLevel)
      putString("appVersion", snapshot.appVersion)
      putString("storageRoot", snapshot.storageRoot)
      when (located) {
        is ProotRuntimeLocator.Location.Available -> {
          putBoolean("prootAvailable", true)
          putString("prootVersion", located.runtime.prootVersion)
        }
        is ProotRuntimeLocator.Location.Unavailable -> {
          putBoolean("prootAvailable", false)
          putString("prootVersion", located.reasonCode)
        }
      }
      if (active != null) {
        putString("activeRootfsId", active.rootfsId)
        putString("activeAlpineRelease", active.alpineRelease)
        putString("activeRootfsSha256", active.rootfsSha256)
        putString("activeInstalledAt", active.installedAtIso)
      }
      putArray(
        "installedVersionIds",
        Arguments.fromList(store.installedVersionIds()),
      )
    }
    val durationMs = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
    promise.resolve(status)
    android.util.Log.i(LOG_TAG, "runtime_status_complete duration_ms=$durationMs")
  }

  override fun getSessionSettings(promise: Promise) {
    if (isInvalidated()) {
      promise.resolve(sessionSettingsError("internal_error"))
      return
    }
    promise.resolve(sessionSettingsSuccess(sessionSettings.readLimit()))
  }

  override fun getDownloadSources(promise: Promise) {
    if (isInvalidated()) {
      promise.resolve(sessionSettingsError("internal_error"))
      return
    }
    promise.resolve(downloadSourcesSuccess(downloadSourceSettings.read()))
  }

  /** Empty or missing fields mean the default server. */
  override fun setDownloadSources(request: ReadableMap, promise: Promise) {
    if (isInvalidated()) {
      promise.resolve(sessionSettingsError("internal_error"))
      return
    }
    var invalid = false
    fun source(key: String): String? {
      if (!request.hasKey(key) || request.getType(key) == ReadableType.Null) return null
      if (request.getType(key) != ReadableType.String) {
        invalid = true
        return null
      }
      val raw = request.getString(key)?.trim().orEmpty()
      if (raw.isEmpty()) return null
      return DownloadSources.normalize(raw) ?: null.also { invalid = true }
    }
    val sources = DownloadSources(alpineMirror = source("alpineMirror"), npmRegistry = source("npmRegistry"))
    if (invalid) {
      promise.resolve(sessionSettingsError("invalid_request"))
      return
    }
    if (!downloadSourceSettings.write(sources)) {
      promise.resolve(sessionSettingsError("internal_error"))
      return
    }
    promise.resolve(downloadSourcesSuccess(sources))
  }

  override fun setSessionLimit(request: ReadableMap, promise: Promise) {
    if (isInvalidated()) {
      promise.resolve(sessionSettingsError("internal_error"))
      return
    }
    val limit = intField(request, "maxConcurrentSessions")
    if (limit == null || !TerminalSessionContract.isValidActiveSessionLimit(limit)) {
      promise.resolve(sessionSettingsError("invalid_request"))
      return
    }
    if (!sessionSettings.writeLimit(limit)) {
      promise.resolve(sessionSettingsError("internal_error"))
      return
    }
    promise.resolve(sessionSettingsSuccess(limit))
  }

  override fun getDebugLog(promise: Promise) {
    if (isInvalidated()) {
      promise.resolve(errorStatus("internal_error"))
      return
    }
    TerminalDebugLog.record(appContext, "module_debug_log_dump")
    val snapshot = TerminalDebugLog.snapshot(appContext)
    if (snapshot == null) {
      promise.resolve(errorStatus("internal_error"))
      return
    }
    promise.resolve(
      Arguments.createMap().apply {
        putString("status", "success")
        putString("path", snapshot.path)
        snapshot.externalPath?.let { putString("externalPath", it) }
        putDouble("bytes", snapshot.bytes.toDouble())
        putString("tail", snapshot.tail)
      },
    )
  }

  override fun installRootfs(request: ReadableMap, promise: Promise) {
    withRequestId(request, promise, ::installError) { requestId ->
      val requestedId = if (request.hasKey("rootfsId") && request.getType("rootfsId") == ReadableType.String) {
        request.getString("rootfsId")
      } else {
        AlpineRootfsCatalog.ROOTFS_ID
      }
      if (requestedId != AlpineRootfsCatalog.ROOTFS_ID) {
        promise.resolve(installError(requestId, "invalid_request"))
        return
      }
      if (!installInProgress.compareAndSet(false, true)) {
        promise.resolve(installError(requestId, "install_in_progress"))
        return
      }
      TerminalDebugLog.record(appContext, "module_rootfs_install_start request=$requestId")
      runRootfsInstall(requestId, promise, imported = null)
    }
  }

  /**
   * Manual fallback for networks that cannot reach the Alpine CDN: the user
   * downloads the pinned archive in a browser and picks it here. The picked
   * file goes through the same size, SHA-256, extraction, and probe checks
   * as a download.
   */
  override fun importRootfs(request: ReadableMap, promise: Promise) {
    withRequestId(request, promise, ::installError) { requestId ->
      val activity = appContext.currentActivity
      if (activity == null) {
        promise.resolve(installError(requestId, "runtime_unavailable"))
        return
      }
      if (!installInProgress.compareAndSet(false, true)) {
        promise.resolve(installError(requestId, "install_in_progress"))
        return
      }
      val listener = object : BaseActivityEventListener() {
        override fun onActivityResult(activity: Activity, requestCode: Int, resultCode: Int, data: Intent?) {
          if (requestCode != IMPORT_ROOTFS_REQUEST_CODE) return
          appContext.removeActivityEventListener(this)
          val uri = data?.data
          if (resultCode != Activity.RESULT_OK || uri == null) {
            installInProgress.set(false)
            deliverInstallResponse(promise, installError(requestId, "import_cancelled"))
            return
          }
          TerminalDebugLog.record(appContext, "module_rootfs_import_start request=$requestId")
          runRootfsInstall(requestId, promise, imported = uri)
        }
      }
      appContext.addActivityEventListener(listener)
      val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE)
        .setType("*/*")
      try {
        activity.startActivityForResult(intent, IMPORT_ROOTFS_REQUEST_CODE)
      } catch (_: ActivityNotFoundException) {
        appContext.removeActivityEventListener(listener)
        installInProgress.set(false)
        promise.resolve(installError(requestId, "runtime_unavailable"))
      }
    }
  }

  /** Runs one install on the install worker; the caller already holds installInProgress. */
  private fun runRootfsInstall(responseRequestId: String, promise: Promise, imported: Uri?) {
    installExecutor.execute {
      val response = try {
        val outcome = if (imported == null) {
          store.install(url = downloadSourceSettings.read().rootfsUrl)
        } else {
          store.install(source = ContentUriArchiveReader(appContext.contentResolver, imported))
        }
        when (outcome) {
          is DistroStoreCore.InstallOutcome.Success -> {
            TerminalDebugLog.record(appContext, "module_rootfs_install_success request=$responseRequestId")
            Arguments.createMap().apply {
              putString("requestId", responseRequestId)
              putString("status", "success")
              putString("rootfsId", outcome.rootfsId)
              putString("archiveSha256", outcome.archiveSha256)
              putDouble("archiveBytes", outcome.archiveBytes.toDouble())
              putInt("extractionFiles", outcome.extraction.fileCount)
              putInt("probeExitCode", outcome.probe.exitCode)
              putArray("probeMarkers", Arguments.fromList(outcome.probe.markerLines()))
              putBoolean("reusedCache", outcome.reusedCachedArchive)
              putInt("durationMs", outcome.durationMs.toInt().coerceAtLeast(0))
            }
          }
          is DistroStoreCore.InstallOutcome.Failure -> {
            TerminalDebugLog.record(
              appContext,
              "module_rootfs_install_failure request=$responseRequestId stage=${outcome.stage}",
            )
            val code = mapFailureCode(outcome)
            installError(responseRequestId, if (imported != null && code == "download_failed") "import_failed" else code)
          }
        }
      } catch (error: Exception) {
        TerminalDebugLog.record(appContext, "module_rootfs_install_exception request=$responseRequestId type=${error::class.java.simpleName}")
        installError(responseRequestId, "internal_error")
      } finally {
        installInProgress.set(false)
      }
      // The when/try above must resolve exactly once; keep the promise
      // resolution outside the catch so a late failure cannot double-resolve.
      deliverInstallResponse(promise, response)
    }
  }

  private fun deliverInstallResponse(promise: Promise, response: WritableMap) {
    try {
      promise.resolve(response)
    } catch (_: Exception) {
      // The bridge may already be gone during teardown; nothing to do.
    }
  }

  private fun mapFailureCode(failure: DistroStoreCore.InstallOutcome.Failure): String = when (failure.stage) {
    "download" -> if (failure.reasonCode == "digest_mismatch" || failure.reasonCode == "size_mismatch") "digest_mismatch" else "download_failed"
    "extract" -> "extraction_failed"
    "probe" -> "probe_failed"
    "promote" -> "promote_failed"
    else -> "internal_error"
  }

  /**
   * Installs only the requested Alpine shell base or launcher harness before
   * its session. The foreground service owns the bounded root PRoot command;
   * the UI bridge only carries the typed result back to JavaScript.
   */
  override fun provisionToolchain(request: ReadableMap, promise: Promise) {
    withRequestId(request, promise, ::provisionError) { requestId ->
      val target = stringField(request, "target")?.takeIf(TerminalRuntimeContract::isValidToolchainTarget)
      if (target == null) {
        promise.resolve(provisionError(requestId, "invalid_request"))
        return
      }
      sessionServiceClient.provisionToolchain(requestId, target) { response ->
        deliver(promise, provisionResponse(response, requestId))
      }
    }
  }

  override fun resetRuntime(request: ReadableMap, promise: Promise) {
    withRequestId(request, promise, ::resetError) { requestId ->
      val scope = stringField(request, "scope")
      if (!TerminalRuntimeContract.isValidResetScope(scope)) {
        promise.resolve(resetError(requestId, "invalid_request"))
        return
      }
      val result = try {
        store.reset(
          deleteHome = scope == TerminalRuntimeContract.RESET_SCOPE_HOME ||
            scope == TerminalRuntimeContract.RESET_SCOPE_ALL_USER_DATA,
          deleteWorkspaces = scope == TerminalRuntimeContract.RESET_SCOPE_WORKSPACE ||
            scope == TerminalRuntimeContract.RESET_SCOPE_ALL_USER_DATA,
        )
      } catch (_: Exception) {
        promise.resolve(resetError(requestId, "internal_error"))
        return
      }
      promise.resolve(
        Arguments.createMap().apply {
          putString("requestId", requestId)
          putString("status", "success")
          putArray("removedVersionIds", Arguments.fromList(result.removedVersionIds))
          putBoolean("homeRemoved", result.homeRemoved)
          putBoolean("workspacesRemoved", result.workspacesRemoved)
        },
      )
    }
  }

  override fun startSession(request: ReadableMap, promise: Promise) {
    withRequestId(request, promise, ::sessionError) { requestId ->
      // A present but non-integral size is rejected below, not replaced by the default.
      val rows = if (request.hasKey("rows")) intField(request, "rows") else DEFAULT_SESSION_ROWS
      val columns = if (request.hasKey("columns")) intField(request, "columns") else DEFAULT_SESSION_COLUMNS
      val sessionCommand = if (!request.hasKey("command")) {
        null
      } else {
        stringField(request, "command")?.takeIf { it.isNotEmpty() && it.length <= TerminalSessionContract.MAX_COMMAND_LENGTH }
      }
      val toolchainTarget = if (!request.hasKey("toolchain")) {
        null
      } else {
        stringField(request, "toolchain")
      }
      val countsAgainstSessionLimit = if (!request.hasKey(TerminalSessionServiceProtocol.KEY_COUNTS_AGAINST_SESSION_LIMIT)) {
        true
      } else if (request.getType(TerminalSessionServiceProtocol.KEY_COUNTS_AGAINST_SESSION_LIMIT) == ReadableType.Boolean) {
        request.getBoolean(TerminalSessionServiceProtocol.KEY_COUNTS_AGAINST_SESSION_LIMIT)
      } else {
        promise.resolve(sessionError(requestId, "invalid_request"))
        return
      }
      if (request.hasKey("command") && sessionCommand == null) {
        promise.resolve(sessionError(requestId, "invalid_request"))
        return
      }
      if (request.hasKey("toolchain") && !TerminalRuntimeContract.isValidToolchainTarget(toolchainTarget)) {
        promise.resolve(sessionError(requestId, "invalid_request"))
        return
      }
      if (rows == null || columns == null ||
        !TerminalSessionContract.isValidRows(rows) ||
        !TerminalSessionContract.isValidColumns(columns)
      ) {
        promise.resolve(sessionError(requestId, "invalid_request"))
        return
      }
      android.util.Log.i(
        LOG_TAG,
        "module_start_session request=$requestId target=${toolchainTarget ?: "none"}",
      )
      TerminalDebugLog.record(
        appContext,
        "module_start_session request=$requestId target=${toolchainTarget ?: "none"}",
      )
      try {
        sessionServiceClient.startSession(requestId, rows, columns, sessionCommand, toolchainTarget, countsAgainstSessionLimit) { response ->
          deliver(promise, sessionStartResponse(response, requestId, rows, columns))
        }
      } catch (error: Exception) {
        android.util.Log.e(
          LOG_TAG,
          "module_start_session_failed type=${error::class.java.simpleName} message=${error.message?.take(160)}",
        )
        TerminalDebugLog.record(appContext, "module_start_session_failed request=$requestId")
        promise.resolve(sessionError(requestId, "internal_error"))
      }
    }
  }

  override fun listTerminalSessions(request: ReadableMap, promise: Promise) {
    withRequestId(request, promise, ::sessionError) { requestId ->
      sessionServiceClient.listSessions(requestId) { response ->
        deliver(promise, sessionListResponse(response, requestId))
      }
    }
  }

  override fun writeSessionInput(request: ReadableMap, promise: Promise) {
    withRequestId(request, promise, ::sessionError) { requestId ->
      val sessionId = sessionIdField(request)
      val base64 = stringField(request, "base64")
      if (sessionId == null ||
        base64 == null || base64.isEmpty() ||
        base64.length > TerminalSessionContract.MAX_INPUT_BASE64_CHARS
      ) {
        promise.resolve(sessionError(requestId, "invalid_request"))
        return
      }
      val bytes = try {
        Base64.decode(base64, Base64.NO_WRAP)
      } catch (_: IllegalArgumentException) {
        null
      }
      if (bytes == null || bytes.isEmpty() || bytes.size > TerminalSessionContract.MAX_INPUT_BYTES) {
        promise.resolve(sessionError(requestId, "invalid_request"))
        return
      }
      sessionServiceClient.writeSession(requestId, sessionId, bytes) { response ->
        deliver(promise, sessionWriteResponse(response, requestId))
      }
    }
  }

  override fun resizeSession(request: ReadableMap, promise: Promise) {
    withRequestId(request, promise, ::sessionError) { requestId ->
      val sessionId = sessionIdField(request)
      val rows = intField(request, "rows")
      val columns = intField(request, "columns")
      if (sessionId == null ||
        rows == null || columns == null ||
        !TerminalSessionContract.isValidRows(rows) ||
        !TerminalSessionContract.isValidColumns(columns)
      ) {
        promise.resolve(sessionError(requestId, "invalid_request"))
        return
      }
      sessionServiceClient.resizeSession(requestId, sessionId, rows, columns) { response ->
        if (isSessionSuccess(response)) NativeTerminalEngineRegistry.get(sessionId)?.resize(rows, columns)
        deliver(promise, sessionResizeResponse(response, requestId, rows, columns))
      }
    }
  }

  override fun signalSession(request: ReadableMap, promise: Promise) {
    withRequestId(request, promise, ::sessionError) { requestId ->
      val sessionId = sessionIdField(request)
      val signalName = stringField(request, "signal")?.takeIf { TerminalSessionContract.signalNumber(it) != null }
      if (sessionId == null || signalName == null) {
        promise.resolve(sessionError(requestId, "invalid_request"))
        return
      }
      sessionServiceClient.signalSession(requestId, sessionId, signalName) { response ->
        deliver(promise, sessionSignalResponse(response, requestId, signalName))
      }
    }
  }

  override fun stopSession(request: ReadableMap, promise: Promise) {
    withRequestId(request, promise, ::sessionError) { requestId ->
      val sessionId = sessionIdField(request)
      val reason = stringField(request, "reason")?.takeIf(TerminalSessionContract::isValidStopReason)
      if (sessionId == null || reason == null) {
        promise.resolve(sessionError(requestId, "invalid_request"))
        return
      }
      sessionServiceClient.stopSession(requestId, sessionId, reason) { response ->
        if (isSessionSuccess(response)) {
          NativeTerminalEngineRegistry.close(sessionId)
          nativeAckBatcher.clear(sessionId)
        }
        deliver(promise, sessionStopResponse(response, requestId, sessionId))
      }
    }
  }

  override fun stopAllTerminalSessions(request: ReadableMap, promise: Promise) {
    withRequestId(request, promise, ::sessionError) { requestId ->
      val reason = stringField(request, "reason")?.takeIf(TerminalSessionContract::isValidStopReason)
      if (reason == null) {
        promise.resolve(sessionError(requestId, "invalid_request"))
        return
      }
      sessionServiceClient.stopAll(requestId, reason) { response ->
        deliver(promise, sessionResponseBase(response, requestId))
      }
    }
  }

  override fun subscribeSessionEvents(request: ReadableMap, promise: Promise) {
    withRequestId(request, promise, ::sessionError) { requestId ->
      val sessionId = sessionIdField(request)
      val afterSeq = if (request.hasKey("afterSeq")) longField(request, "afterSeq") else 0L
      if (sessionId == null || afterSeq == null) {
        promise.resolve(sessionError(requestId, "invalid_request"))
        return
      }
      sessionServiceClient.subscribeSession(requestId, sessionId, afterSeq) { response ->
        deliver(promise, sessionSubscribeResponse(response, requestId, sessionId))
      }
    }
  }

  override fun detachTerminalSession(request: ReadableMap, promise: Promise) {
    withRequestId(request, promise, ::sessionError) { requestId ->
      val sessionId = sessionIdField(request)
      if (sessionId == null) {
        promise.resolve(sessionError(requestId, "invalid_request"))
        return
      }
      sessionServiceClient.detachSession(requestId, sessionId) { response ->
        deliver(promise, sessionResponseBase(response, requestId))
      }
    }
  }

  override fun consumeLaunchSessionId(promise: Promise) {
    deliver(promise, LaunchSessionIntent.consume())
  }

  override fun updateUnlockGrant(request: ReadableMap, promise: Promise) {
    withRequestId(request, promise, ::sessionError) { requestId ->
      val op = stringField(request, "op")?.takeIf(SessionUnlockGrant::isValidOp)
      if (op == null) {
        promise.resolve(sessionError(requestId, "invalid_request"))
        return
      }
      sessionServiceClient.updateUnlockGrant(requestId, op) { response ->
        deliver(
          promise,
          sessionResponseBase(response, requestId).apply {
            putBoolean("unlocked", response.getBoolean(TerminalSessionServiceProtocol.KEY_UNLOCKED, false))
          },
        )
      }
    }
  }

  override fun acknowledgeSessionOutput(request: ReadableMap, promise: Promise) {
    withRequestId(request, promise, ::sessionError) { requestId ->
      val sessionId = sessionIdField(request)
      val seq = longField(request, "seq")
      if (sessionId == null || seq == null || seq < 1L) {
        promise.resolve(sessionError(requestId, "invalid_request"))
        return
      }
      sessionServiceClient.acknowledgeOutput(requestId, sessionId, seq) { response ->
        deliver(promise, sessionAcknowledgeResponse(response, requestId, sessionId, seq))
      }
    }
  }

  /** Required by NativeEventEmitter; session events are emitted natively. */
  override fun addListener(eventName: String) {
    // Deliberate no-op: events flow on SESSION_EVENT_NAME only.
  }

  /** Required by NativeEventEmitter; session events are emitted natively. */
  override fun removeListeners(count: Double) {
    // Deliberate no-op, mirroring addListener.
  }

  /**
   * A native engine dropped output (see NativeTerminalEngine.recoverFromOutputGap).
   * Its screen no longer matches what the app believes it drew, so ask the
   * service to make the app repaint at the PTY's real size.
   */
  private fun requestRedrawAfterOutputGap(sessionId: String) {
    if (invalidated.get()) return
    val now = SystemClock.elapsedRealtime()
    val previous = lastRedrawNudgeAt[sessionId]
    if (previous != null && now - previous < REDRAW_NUDGE_MIN_INTERVAL_MS) return
    lastRedrawNudgeAt[sessionId] = now
    TerminalDebugLog.record(appContext, "native_output_gap_redraw session=$sessionId")
    sessionServiceClient.redrawSession(nextRedrawRequestId(), sessionId) { response ->
      if (!isSessionSuccess(response)) {
        TerminalDebugLog.record(appContext, "native_output_gap_redraw_failed session=$sessionId")
      }
    }
  }

  private fun nextRedrawRequestId(): String = "native-redraw-${redrawNudgeCounter.incrementAndGet().toString(36)}"

  private fun deliver(promise: Promise, response: Any?) {
    try {
      promise.resolve(response)
    } catch (_: Exception) {
      // The bridge may already be gone during teardown; nothing to do.
    }
  }

  /**
   * Shared preamble for requestId-carrying bridge methods: fail closed after
   * invalidate(), then require a well-formed requestId before running [block]
   * with it. [error] is the method's own error builder, so each method keeps
   * its response shape. The invalidated path reports INVALID_REQUEST_ID
   * unless [echoRequestIdWhenInvalidated] is set, in which case it echoes the
   * caller's raw requestId when one was sent.
   */
  private inline fun withRequestId(
    request: ReadableMap,
    promise: Promise,
    error: (requestId: String, errorCode: String) -> WritableMap,
    echoRequestIdWhenInvalidated: Boolean = false,
    block: (requestId: String) -> Unit,
  ) {
    if (isInvalidated()) {
      val reported = if (echoRequestIdWhenInvalidated) stringField(request, "requestId") else null
      promise.resolve(error(reported ?: INVALID_REQUEST_ID, "internal_error"))
      return
    }
    val requestId = stringField(request, "requestId")
    if (requestId == null || !TerminalRuntimeContract.isValidRequestId(requestId)) {
      promise.resolve(error(requestId ?: INVALID_REQUEST_ID, "invalid_request"))
      return
    }
    block(requestId)
  }

  private fun stringField(request: ReadableMap, key: String): String? =
    request.takeIf { it.hasKey(key) && it.getType(key) == ReadableType.String }?.getString(key)

  private fun sessionIdField(request: ReadableMap): String? =
    stringField(request, "sessionId")?.takeIf(TerminalSessionContract::isValidSessionId)

  /** Rejects NaN, infinities, fractions, and out-of-range numbers instead of truncating them. */
  private fun intField(request: ReadableMap, key: String): Int? =
    request.takeIf { it.hasKey(key) && it.getType(key) == ReadableType.Number }
      ?.getDouble(key)
      ?.let(::exactIntOrNull)

  private fun longField(request: ReadableMap, key: String): Long? =
    request.takeIf { it.hasKey(key) && it.getType(key) == ReadableType.Number }
      ?.getDouble(key)
      ?.takeIf { it.isFinite() && it >= 0.0 && it <= Long.MAX_VALUE.toDouble() && it.toLong().toDouble() == it }
      ?.toLong()

  private fun sessionResponseBase(response: Bundle, fallbackRequestId: String): WritableMap =
    Arguments.createMap().apply {
      putString(
        "requestId",
        response.getString(TerminalSessionServiceProtocol.KEY_REQUEST_ID) ?: fallbackRequestId,
      )
      putString(
        "status",
        response.getString(TerminalSessionServiceProtocol.KEY_STATUS) ?: "error",
      )
      response.getString(TerminalSessionServiceProtocol.KEY_ERROR_CODE)?.let { putString("errorCode", it) }
    }

  private fun provisionResponse(response: Bundle, requestId: String): WritableMap =
    sessionResponseBase(response, requestId)

  private fun isSessionSuccess(response: Bundle): Boolean =
    response.getString(TerminalSessionServiceProtocol.KEY_STATUS) == TerminalSessionServiceProtocol.STATUS_SUCCESS

  private fun sessionStartResponse(
    response: Bundle,
    requestId: String,
    rows: Int,
    columns: Int,
  ): WritableMap = sessionResponseBase(response, requestId).apply {
    if (isSessionSuccess(response)) {
      response.getString(TerminalSessionServiceProtocol.KEY_SESSION_ID)?.let { putString("sessionId", it) }
      if (response.containsKey(TerminalSessionServiceProtocol.KEY_PID)) {
        putInt("pid", response.getInt(TerminalSessionServiceProtocol.KEY_PID))
      }
      putInt("rows", response.getInt(TerminalSessionServiceProtocol.KEY_ROWS, rows))
      putInt("columns", response.getInt(TerminalSessionServiceProtocol.KEY_COLUMNS, columns))
    }
  }

  private fun sessionListResponse(response: Bundle, requestId: String): WritableMap =
    sessionResponseBase(response, requestId).apply {
      if (isSessionSuccess(response)) {
        val sessions = Arguments.createArray()
        response.getParcelableArrayList<Bundle>(TerminalSessionServiceProtocol.KEY_SESSIONS)
          .orEmpty()
          .take(TerminalSessionContract.MAX_ACTIVE_SESSIONS)
          .forEach { item ->
            val sessionId = item.getString(TerminalSessionServiceProtocol.KEY_SESSION_ID)
            val target = item.getString(TerminalSessionServiceProtocol.KEY_TARGET)
            val startedAtMs = item.getLong(TerminalSessionServiceProtocol.KEY_STARTED_AT_MS, -1L)
            if (TerminalSessionContract.isValidSessionId(sessionId) &&
              TerminalRuntimeContract.isValidToolchainTarget(target) &&
              startedAtMs >= 0L
            ) {
              sessions.pushMap(
                Arguments.createMap().apply {
                  putString("sessionId", sessionId)
                  putString("toolchain", target)
                  putDouble("startedAtMs", startedAtMs.toDouble())
                },
              )
            }
          }
        putArray("sessions", sessions)
      }
    }

  private fun sessionWriteResponse(response: Bundle, requestId: String): WritableMap =
    sessionResponseBase(response, requestId).apply {
      if (isSessionSuccess(response) && response.containsKey(TerminalSessionServiceProtocol.KEY_BYTES_WRITTEN)) {
        putInt("bytesWritten", response.getInt(TerminalSessionServiceProtocol.KEY_BYTES_WRITTEN))
      }
    }

  private fun sessionResizeResponse(
    response: Bundle,
    requestId: String,
    rows: Int,
    columns: Int,
  ): WritableMap = sessionResponseBase(response, requestId).apply {
    if (isSessionSuccess(response)) {
      putInt("rows", response.getInt(TerminalSessionServiceProtocol.KEY_ROWS, rows))
      putInt("columns", response.getInt(TerminalSessionServiceProtocol.KEY_COLUMNS, columns))
    }
  }

  private fun sessionSignalResponse(
    response: Bundle,
    requestId: String,
    signal: String,
  ): WritableMap = sessionResponseBase(response, requestId).apply {
    if (isSessionSuccess(response)) {
      putString("signal", response.getString(TerminalSessionServiceProtocol.KEY_SIGNAL) ?: signal)
    }
  }

  private fun sessionStopResponse(
    response: Bundle,
    requestId: String,
    sessionId: String,
  ): WritableMap = sessionResponseBase(response, requestId).apply {
    if (isSessionSuccess(response)) {
      putString("sessionId", response.getString(TerminalSessionServiceProtocol.KEY_SESSION_ID) ?: sessionId)
      if (response.containsKey(TerminalSessionServiceProtocol.KEY_EXIT_CODE)) {
        putInt("exitCode", response.getInt(TerminalSessionServiceProtocol.KEY_EXIT_CODE))
      }
      response.getString(TerminalSessionServiceProtocol.KEY_EXIT_SIGNAL)?.let { putString("signal", it) }
      response.getString(TerminalSessionServiceProtocol.KEY_EXIT_REASON)?.let { putString("exitReason", it) }
      putInt("remainingProcessCount", response.getInt(TerminalSessionServiceProtocol.KEY_REMAINING_PROCESS_COUNT, 0))
      putBoolean(
        "stoppedWithinDeadline",
        response.getBoolean(TerminalSessionServiceProtocol.KEY_STOPPED_WITHIN_DEADLINE, false),
      )
    }
  }

  private fun sessionSubscribeResponse(
    response: Bundle,
    requestId: String,
    sessionId: String,
  ): WritableMap = sessionResponseBase(response, requestId).apply {
    if (isSessionSuccess(response)) {
      putString("sessionId", response.getString(TerminalSessionServiceProtocol.KEY_SESSION_ID) ?: sessionId)
      putString("eventName", SESSION_EVENT_NAME)
      putString("sessionState", response.getString(TerminalSessionServiceProtocol.KEY_SESSION_STATE) ?: "running")
      putDouble("firstAvailableSeq", response.getLong(TerminalSessionServiceProtocol.KEY_FIRST_AVAILABLE_SEQ, 1L).toDouble())
      putDouble("lastEmittedSeq", response.getLong(TerminalSessionServiceProtocol.KEY_LAST_EMITTED_SEQ, 0L).toDouble())
      putBoolean("replayAvailable", response.getBoolean(TerminalSessionServiceProtocol.KEY_REPLAY_AVAILABLE, false))
      if (response.containsKey(TerminalSessionServiceProtocol.KEY_EXIT_CODE)) {
        putInt("exitCode", response.getInt(TerminalSessionServiceProtocol.KEY_EXIT_CODE))
      }
      response.getString(TerminalSessionServiceProtocol.KEY_EXIT_SIGNAL)?.let { putString("signal", it) }
      response.getString(TerminalSessionServiceProtocol.KEY_EXIT_REASON)?.let { putString("exitReason", it) }
    }
  }

  private fun sessionAcknowledgeResponse(
    response: Bundle,
    requestId: String,
    sessionId: String,
    seq: Long,
  ): WritableMap = sessionResponseBase(response, requestId).apply {
    if (isSessionSuccess(response)) {
      putString("sessionId", response.getString(TerminalSessionServiceProtocol.KEY_SESSION_ID) ?: sessionId)
      putDouble("acknowledgedSeq", response.getLong(TerminalSessionServiceProtocol.KEY_ACKNOWLEDGED_SEQ, seq).toDouble())
      putInt("outstandingChunks", response.getInt(TerminalSessionServiceProtocol.KEY_OUTSTANDING_CHUNKS, 0))
    }
  }

  /** A prober that fails closed when the packaged runtime is unusable. */
  private class UnavailableGuestProber(private val reasonCode: String) : GuestProber {
    override fun probe(rootfsDir: File, guestHomeDir: File, timeoutMs: Long): GuestProbeResult =
      throw IllegalStateException("proot runtime unavailable: $reasonCode")
  }

  private fun appVersion(): String = try {
    appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
      ?: TerminalRuntimeContract.UNKNOWN
  } catch (_: RuntimeException) {
    TerminalRuntimeContract.UNKNOWN
  }

  private fun errorStatus(errorCode: String): WritableMap = Arguments.createMap().apply {
    putString("status", "error")
    putString("errorCode", errorCode)
  }

  private fun downloadSourcesSuccess(sources: DownloadSources): WritableMap = Arguments.createMap().apply {
    putString("status", "success")
    sources.alpineMirror?.let { putString("alpineMirror", it) }
    sources.npmRegistry?.let { putString("npmRegistry", it) }
  }

  private fun sessionSettingsSuccess(limit: Int): WritableMap = Arguments.createMap().apply {
    putString("status", "success")
    putInt("maxConcurrentSessions", limit)
    putInt("minConcurrentSessions", TerminalSessionContract.MIN_ACTIVE_SESSIONS)
    putInt("maxSupportedConcurrentSessions", TerminalSessionContract.MAX_ACTIVE_SESSIONS)
  }

  private fun sessionSettingsError(errorCode: String): WritableMap = Arguments.createMap().apply {
    putString("status", "error")
    putString("errorCode", errorCode)
  }

  private fun installError(requestId: String, errorCode: String): WritableMap = Arguments.createMap().apply {
    putString("requestId", requestId)
    putString("status", "error")
    putString("errorCode", errorCode)
  }

  private fun resetError(requestId: String, errorCode: String): WritableMap = Arguments.createMap().apply {
    putString("requestId", requestId)
    putString("status", "error")
    putString("errorCode", errorCode)
  }

  private fun sessionError(requestId: String, errorCode: String): WritableMap = Arguments.createMap().apply {
    putString("requestId", requestId)
    putString("status", "error")
    putString("errorCode", errorCode)
  }

  private fun provisionSuccess(requestId: String): WritableMap = Arguments.createMap().apply {
    putString("requestId", requestId)
    putString("status", "success")
  }

  private fun provisionError(requestId: String, errorCode: String): WritableMap = Arguments.createMap().apply {
    putString("requestId", requestId)
    putString("status", "error")
    putString("errorCode", errorCode)
  }

  private fun isInvalidated(): Boolean = invalidated.get()

  private fun isNativeHarnessTarget(target: String?): Boolean = target == TerminalRuntimeContract.TOOLCHAIN_TARGET_CLAUDE ||
    target == TerminalRuntimeContract.TOOLCHAIN_TARGET_CODEX ||
    target == TerminalRuntimeContract.TOOLCHAIN_TARGET_OPENCODE

  override fun invalidate() {
    invalidated.set(true)
    appContext.removeLifecycleEventListener(visibilityListener)
    sessionServiceClient.reportUiVisibility(false)
    // The service owns the PTY process tree. Bridge teardown only detaches
    // this UI adapter; the service journal and foreground state make a later
    // React Native instance able to reattach or restart the session.
    nativeAckBatcher.close()
    nativeAckExecutor.shutdownNow()
    sessionServiceClient.close()
    NativeTerminalEngineRegistry.closeAll()
    NativeTerminalEngineRegistry.configureInputWriter(null)
    NativeTerminalEngineRegistry.configureClipboardWriter(null)
    NativeTerminalEngineRegistry.configureOutputGapListener(null)
    lastRedrawNudgeAt.clear()
    synchronized(nativeReadyLock) {
      nativeReadySessions.clear()
      nativeReadyTails.clear()
      nativeMarkerSessions.clear()
    }
    sessionEventExecutor.shutdownNow()
    sessionEventQueue.clear()
    runCatching {
      sessionEventExecutor.awaitTermination(2, TimeUnit.SECONDS)
    }
    installExecutor.shutdownNow()
    super.invalidate()
  }

  /**
   * Resolves the trusted URL drawn at ([row], [column]) of the native
   * session's current frame, or null. Called only when the user taps; the
   * row's link ranges were already cached when that row was snapshotted.
   */
  override fun terminalLinkAt(sessionId: String, row: Double, column: Double, promise: Promise) {
    if (isInvalidated() || !TerminalSessionContract.isValidSessionId(sessionId) ||
      !row.isFinite() || !column.isFinite() || row < 0.0 || column < 0.0
    ) {
      promise.resolve(null)
      return
    }
    val frame = NativeTerminalEngineRegistry.get(sessionId)?.currentFrame()
    val rowIndex = row.toInt()
    promise.resolve(frame?.let { NativeTerminalLinks.urlAt(it.lines, rowIndex, column.toInt()) })
  }

  /**
   * Keeps the text of a native session's last screen once it exits, so the
   * app can show why it quit. Held in memory only, for a few sessions, until
   * the screen takes it.
   */
  private fun rememberExitScreen(sessionId: String) {
    val lines = NativeTerminalEngineRegistry.get(sessionId)?.currentFrame()?.lines ?: return
    val text = lines.map { row -> row.text.joinToString("").trimEnd() }
      .dropLastWhile(String::isEmpty)
      .takeLast(MAX_EXIT_SCREEN_LINES)
      .joinToString("\n")
      .trim('\n')
    if (text.isEmpty()) return
    synchronized(exitScreens) {
      exitScreens[sessionId] = text
      while (exitScreens.size > MAX_EXIT_SCREENS) exitScreens.remove(exitScreens.keys.first())
    }
  }

  /** The last screen of a native session that exited, once; or null. */
  override fun takeExitScreen(sessionId: String, promise: Promise) {
    promise.resolve(synchronized(exitScreens) { exitScreens.remove(sessionId) })
  }

  /** The clipboard's text for the PASTE key, or null when it holds none. */
  override fun readClipboardText(promise: Promise) {
    if (isInvalidated()) {
      promise.resolve(null)
      return
    }
    // Android only lets the focused app read the clipboard; ask from the
    // main thread, where that focus is tracked.
    android.os.Handler(android.os.Looper.getMainLooper()).post {
      val text = runCatching {
        val clipboard = appContext.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(appContext)?.toString()
      }.getOrNull()
      promise.resolve(text?.takeIf { it.isNotEmpty() && it.length <= MAX_PASTE_CHARS })
    }
  }

  /** Whether the app in a native session asked for bracketed paste. */
  override fun isBracketedPaste(sessionId: String, promise: Promise) {
    promise.resolve(
      !isInvalidated() && TerminalSessionContract.isValidSessionId(sessionId) &&
        NativeTerminalEngineRegistry.isBracketedPaste(sessionId),
    )
  }

  // File-explorer reads run on one short-lived worker: the thread exits when
  // idle, and a small queue bounds work a burst of taps can enqueue.
  private val guestFileExecutor: ThreadPoolExecutor by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    ThreadPoolExecutor(
      1,
      1,
      GUEST_FILE_IDLE_SECONDS,
      TimeUnit.SECONDS,
      ArrayBlockingQueue<Runnable>(GUEST_FILE_QUEUE_CAPACITY),
    ) { runnable ->
      Thread(runnable, "guest-file-browser").apply { isDaemon = true }
    }.apply { allowCoreThreadTimeOut(true) }
  }

  private fun guestFileBrowser(): GuestFileBrowser {
    val storePaths = paths
    return GuestFileBrowser(homeRoot = storePaths.home, workspaceRoot = storePaths.defaultWorkspace)
  }

  /** Parses the shared guest file request; null means invalid_request. */
  private fun guestFileRequest(request: ReadableMap): Pair<String, List<String>>? {
    val root = stringField(request, "root")?.takeIf { it == "home" || it == "workspace" } ?: return null
    if (!request.hasKey("path") || request.getType("path") != ReadableType.Array) return null
    val array = request.getArray("path") ?: return null
    if (array.size() > GUEST_FILE_MAX_PATH_COMPONENTS) return null
    val path = ArrayList<String>(array.size())
    for (index in 0 until array.size()) {
      if (array.getType(index) != ReadableType.String) return null
      path += array.getString(index) ?: return null
    }
    return root to path
  }

  private fun guestFileError(requestId: String, errorCode: String): WritableMap = Arguments.createMap().apply {
    putString("requestId", requestId)
    putString("status", "error")
    putString("errorCode", errorCode)
  }

  private fun runGuestFileQuery(requestId: String, promise: Promise, query: () -> WritableMap) {
    try {
      guestFileExecutor.execute {
        val response = try {
          if (isInvalidated()) guestFileError(requestId, "internal_error") else query()
        } catch (_: Exception) {
          guestFileError(requestId, "internal_error")
        }
        deliver(promise, response)
      }
    } catch (_: RejectedExecutionException) {
      deliver(promise, guestFileError(requestId, "internal_error"))
    }
  }

  /**
   * Lists one directory of the guest home or workspace straight from
   * app-private storage. GuestFileBrowser re-validates the path natively.
   */
  override fun listGuestDirectory(request: ReadableMap, promise: Promise) {
    withRequestId(request, promise, ::guestFileError, echoRequestIdWhenInvalidated = true) { requestId ->
      val parsed = guestFileRequest(request)
      if (parsed == null) {
        promise.resolve(guestFileError(requestId, "invalid_request"))
        return
      }
      val (root, path) = parsed
      runGuestFileQuery(requestId, promise) {
        when (val outcome = guestFileBrowser().list(root, path)) {
          is GuestFileBrowser.ListOutcome.Failure -> guestFileError(requestId, outcome.errorCode)
          is GuestFileBrowser.ListOutcome.Success -> Arguments.createMap().apply {
            putString("requestId", requestId)
            putString("status", "success")
            putArray(
              "entries",
              Arguments.createArray().apply {
                outcome.entries.forEach { entry ->
                  pushMap(
                    Arguments.createMap().apply {
                      putString("name", entry.name)
                      putString("kind", entry.kind.wireName)
                      putDouble("sizeBytes", entry.sizeBytes.toDouble())
                    },
                  )
                }
              },
            )
            putBoolean("truncated", outcome.truncated)
            putInt("hiddenInvalidNameCount", outcome.hiddenInvalidNameCount)
          }
        }
      }
    }
  }

  /**
   * Reads at most a 64 KiB preview of a regular guest file as base64. UTF-8
   * and binary checks stay in JS so they match the PTY query exactly.
   */
  override fun readGuestFile(request: ReadableMap, promise: Promise) {
    withRequestId(request, promise, ::guestFileError, echoRequestIdWhenInvalidated = true) { requestId ->
      val parsed = guestFileRequest(request)
      if (parsed == null) {
        promise.resolve(guestFileError(requestId, "invalid_request"))
        return
      }
      val (root, path) = parsed
      runGuestFileQuery(requestId, promise) {
        when (val outcome = guestFileBrowser().read(root, path)) {
          is GuestFileBrowser.ReadOutcome.Failure -> guestFileError(requestId, outcome.errorCode)
          is GuestFileBrowser.ReadOutcome.Success -> Arguments.createMap().apply {
            putString("requestId", requestId)
            putString("status", "success")
            putString("base64", Base64.encodeToString(outcome.bytes, Base64.NO_WRAP))
            putDouble("sizeBytes", outcome.sizeBytes.toDouble())
          }
        }
      }
    }
  }

  // Exports can take minutes, so they get their own worker and never block
  // folder browsing. Only one export runs at a time.
  private val guestExportExecutor: ExecutorService by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    Executors.newSingleThreadExecutor { runnable ->
      Thread(runnable, "guest-file-export").apply { isDaemon = true }
    }
  }
  private val guestExportInProgress = AtomicBoolean(false)

  /**
   * Copies every regular file below a guest home/workspace directory into
   * the shared Download/Horus folder so it can be opened by other apps.
   */
  override fun exportGuestDirectory(request: ReadableMap, promise: Promise) {
    withRequestId(request, promise, ::guestFileError, echoRequestIdWhenInvalidated = true) { requestId ->
      val parsed = guestFileRequest(request)
      if (parsed == null) {
        promise.resolve(guestFileError(requestId, "invalid_request"))
        return
      }
      val (root, path) = parsed
      val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
      val sink = DownloadsExportSink(appContext, GuestFileBrowser.exportFolderName(root, path, timestamp))
      if (!sink.hasWriteAccess()) {
        promise.resolve(guestFileError(requestId, "permission_denied"))
        return
      }
      if (!guestExportInProgress.compareAndSet(false, true)) {
        promise.resolve(guestFileError(requestId, "busy"))
        return
      }
      try {
        guestExportExecutor.execute {
          val response = try {
            if (isInvalidated()) {
              guestFileError(requestId, "internal_error")
            } else {
              when (val outcome = guestFileBrowser().export(root, path, sink)) {
                is GuestFileBrowser.ExportOutcome.Failure -> guestFileError(requestId, outcome.errorCode)
                is GuestFileBrowser.ExportOutcome.Success -> {
                  sink.finish()
                  Arguments.createMap().apply {
                    putString("requestId", requestId)
                    putString("status", "success")
                    putString("destination", sink.displayPath)
                    putInt("fileCount", outcome.fileCount)
                    putDouble("byteCount", outcome.byteCount.toDouble())
                    putInt("skippedCount", outcome.skippedCount)
                  }
                }
              }
            }
          } catch (_: Exception) {
            guestFileError(requestId, "internal_error")
          } finally {
            guestExportInProgress.set(false)
          }
          deliver(promise, response)
        }
      } catch (_: RejectedExecutionException) {
        guestExportInProgress.set(false)
        deliver(promise, guestFileError(requestId, "internal_error"))
      }
    }
  }

  private companion object {
    const val NAME = TerminalRuntimeContract.MODULE_NAME
    const val INVALID_REQUEST_ID = "invalid-request"
    const val IMPORT_ROOTFS_REQUEST_CODE = 0x4852
    const val SESSION_EVENT_NAME = "terminalSessionEvents"
    const val DEFAULT_SESSION_ROWS = 24
    const val REDRAW_NUDGE_MIN_INTERVAL_MS = 1_000L
    const val DEFAULT_SESSION_COLUMNS = 80
    const val SESSION_EVENT_QUEUE_CAPACITY = 256
    const val NATIVE_READY_SCAN_CHARS = 64
    const val MAX_CLIPBOARD_BASE64_CHARS = 65_536
    const val MAX_PASTE_CHARS = 262_144
    const val MAX_EXIT_SCREEN_LINES = 40
    const val MAX_EXIT_SCREENS = 4
    const val LOG_TAG = "HorusTerminal"
    const val GUEST_FILE_IDLE_SECONDS = 30L
    const val GUEST_FILE_QUEUE_CAPACITY = 8
    const val GUEST_FILE_MAX_PATH_COMPONENTS = 16
    val NATIVE_READY_MARKER_PATTERN = Regex("(?:^|[\\r\\n])HORUS_TOOLCHAIN_READY(?:[\\r\\n]|$)")
  }
}
