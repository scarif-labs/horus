package com.scariflabs.horus.terminal

import android.content.Context
import android.net.ConnectivityManager
import java.io.File

/**
 * Builds the fixed PRoot argv/environment for an interactive session. This
 * lives outside the React Native module so the foreground-service process can
 * recreate a session after the UI process has been reclaimed.
 */
class TerminalSessionSpecFactory(
  private val context: Context,
) {

  sealed interface BuildOutcome {
    data class Success(val spec: TerminalSessionSupervisor.StartSpec) : BuildOutcome
    data class Failure(val errorCode: String) : BuildOutcome
  }

  sealed interface ProvisionBuildOutcome {
    data class Ready(val target: String) : ProvisionBuildOutcome
    data class Work(
      val target: String,
      val rootfsDir: File,
      val guestHomeDir: File,
      val launcher: ProotSessionLauncher,
      val launch: ProotSessionLauncher.LaunchSpec,
    ) : ProvisionBuildOutcome
    data class Failure(val errorCode: String) : ProvisionBuildOutcome
  }

  private val paths = DistroStorePaths(
    File(context.filesDir, TerminalRuntimeContract.STORAGE_ROOT_DIR_NAME),
  )
  // The APK-managed native library directory is immutable for this service process.
  private val packagedProotRuntime: ProotRuntimeLocator.Location by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    locateProotRuntimeUncached()
  }
  private val downloadSourceSettings = DownloadSourceSettings.forStorageRoot(context.filesDir)
  private val store = DistroStoreCore(
    paths = paths,
    downloader = HttpArchiveDownloader(),
    extractor = SafeTarGzExtractor(),
    // Session/provisioning startup only reads the verified active record.
    // Rootfs installation remains owned by TerminalRuntimeModule in the UI
    // process.
    prober = UnusedGuestProber,
  )

  fun build(
    rows: Int,
    columns: Int,
    sessionCommand: String?,
    toolchainTarget: String? = null,
    countsAgainstSessionLimit: Boolean? = null,
  ): BuildOutcome {
    if (!TerminalSessionContract.isValidRows(rows) || !TerminalSessionContract.isValidColumns(columns)) {
      return BuildOutcome.Failure("invalid_request")
    }
    if (toolchainTarget != null && !TerminalRuntimeContract.isValidToolchainTarget(toolchainTarget)) {
      return BuildOutcome.Failure("invalid_request")
    }
    if (sessionCommand != null &&
      (sessionCommand.isEmpty() || sessionCommand.length > TerminalSessionContract.MAX_COMMAND_LENGTH)
    ) {
      return BuildOutcome.Failure("invalid_request")
    }
    val rootfsDir = try {
      store.ensureLayout()
      store.activeRootfsDir()
    } catch (_: Exception) {
      null
    }
    if (rootfsDir == null || !rootfsDir.isDirectory) {
      return BuildOutcome.Failure("runtime_unavailable")
    }
    val located = locateProotRuntime()
    if (located !is ProotRuntimeLocator.Location.Available) {
      return BuildOutcome.Failure("runtime_unavailable")
    }
    val profileUsername = TerminalGuestIdentity.readStoredUsername(context)
      ?: ProotSessionLauncher.GUEST_ROOT_USER
    val harnessUser = TerminalHarnessUsers.forTarget(toolchainTarget, profileUsername)
    val guestUsername = harnessUser?.username ?: profileUsername
    val guestUid = harnessUser?.uid ?: ProotSessionLauncher.GUEST_UID
    val guestGid = harnessUser?.gid ?: ProotSessionLauncher.GUEST_GID
    val guestHomeDir = harnessUser?.let { paths.harnessHome(it.homeKey) } ?: paths.home
    return try {
      val launcher = ProotSessionLauncher(
        runtime = located.runtime,
        scratchDir = File(paths.sessions, ".proot-scratch"),
        dnsServersProvider = ::activeDnsServers,
        downloadSources = downloadSourceSettings.read(),
      )
      val launch = if (toolchainTarget == null) {
        launcher.interactiveShellLaunchSpec(
          rootfsDir = rootfsDir,
          guestHomeDir = guestHomeDir,
          workspaceDir = paths.defaultWorkspace,
          guestUsername = guestUsername,
          guestUid = guestUid,
          guestGid = guestGid,
          sessionCommand = sessionCommand,
        )
      } else {
        val toolchainReady = launcher.hasProvisionedToolchain(rootfsDir, guestHomeDir, toolchainTarget)
        launcher.toolchainSessionLaunchSpec(
          rootfsDir = rootfsDir,
          guestHomeDir = guestHomeDir,
          workspaceDir = paths.defaultWorkspace,
          guestUsername = guestUsername,
          guestUid = guestUid,
          guestGid = guestGid,
          sessionCommand = sessionCommand,
          target = toolchainTarget,
          provision = !toolchainReady,
        )
      }
      BuildOutcome.Success(
        TerminalSessionSupervisor.StartSpec(
          argv = launch.argv,
          environment = launch.environment,
          workingDirectory = launch.workingDirectory,
          rows = rows,
          columns = columns,
          countsAgainstSessionLimit = countsAgainstSessionLimit
            ?: (toolchainTarget != TerminalRuntimeContract.TOOLCHAIN_TARGET_GITHUB),
        ),
      )
    } catch (error: Exception) {
      android.util.Log.e(
        LOG_TAG,
        "session_launch_spec_failed target=${toolchainTarget ?: "none"} type=${error::class.java.simpleName} message=${error.message?.take(MAX_ERROR_LOG_CHARS)}",
      )
      TerminalDebugLog.record(context, "session_launch_spec_failed target=${toolchainTarget ?: "none"}")
      BuildOutcome.Failure("internal_error")
    }
  }

  /** Builds the bounded root provisioning command in the service process. */
  fun buildProvision(target: String): ProvisionBuildOutcome {
    if (!TerminalRuntimeContract.isValidToolchainTarget(target)) {
      return ProvisionBuildOutcome.Failure("invalid_request")
    }
    val rootfsDir = try {
      store.ensureLayout()
      store.activeRootfsDir()
    } catch (_: Exception) {
      null
    }
    if (rootfsDir == null || !rootfsDir.isDirectory) {
      return ProvisionBuildOutcome.Failure("runtime_unavailable")
    }
    val located = locateProotRuntime()
    if (located !is ProotRuntimeLocator.Location.Available) {
      return ProvisionBuildOutcome.Failure("runtime_unavailable")
    }
    val profileUsername = TerminalGuestIdentity.readStoredUsername(context)
      ?: ProotSessionLauncher.GUEST_ROOT_USER
    val harnessUser = TerminalHarnessUsers.forTarget(target, profileUsername)
    val guestHomeDir = harnessUser?.let { paths.harnessHome(it.homeKey) } ?: paths.home
    return try {
      val launcher = ProotSessionLauncher(
        runtime = located.runtime,
        scratchDir = File(paths.sessions, ".proot-scratch"),
        dnsServersProvider = ::activeDnsServers,
        downloadSources = downloadSourceSettings.read(),
      )
      if (launcher.hasProvisionedToolchain(rootfsDir, guestHomeDir, target)) {
        ProvisionBuildOutcome.Ready(target)
      } else {
        ProvisionBuildOutcome.Work(
          target = target,
          rootfsDir = rootfsDir,
          guestHomeDir = guestHomeDir,
          launcher = launcher,
          launch = launcher.toolchainProvisionLaunchSpec(rootfsDir, guestHomeDir, target),
        )
      }
    } catch (_: Exception) {
      ProvisionBuildOutcome.Failure("internal_error")
    }
  }

  sealed interface RemoteAccessBuildOutcome {
    data class Success(val launch: ProotSessionLauncher.LaunchSpec) : RemoteAccessBuildOutcome
    data class Failure(val errorCode: String) : RemoteAccessBuildOutcome
  }

  /** Builds the loopback-only SSH server launch for the profile user. */
  fun buildRemoteAccess(): RemoteAccessBuildOutcome {
    val profileUsername = TerminalGuestIdentity.readStoredUsername(context)
      ?: return RemoteAccessBuildOutcome.Failure("profile_missing")
    val rootfsDir = try {
      store.ensureLayout()
      store.activeRootfsDir()
    } catch (_: Exception) {
      null
    }
    if (rootfsDir == null || !rootfsDir.isDirectory) {
      return RemoteAccessBuildOutcome.Failure("runtime_unavailable")
    }
    val located = locateProotRuntime()
    if (located !is ProotRuntimeLocator.Location.Available) {
      return RemoteAccessBuildOutcome.Failure("runtime_unavailable")
    }
    return try {
      val launcher = ProotSessionLauncher(
        runtime = located.runtime,
        scratchDir = File(paths.sessions, ".proot-scratch"),
        dnsServersProvider = ::activeDnsServers,
        downloadSources = downloadSourceSettings.read(),
      )
      RemoteAccessBuildOutcome.Success(
        launcher.remoteAccessLaunchSpec(
          rootfsDir = rootfsDir,
          guestHomeDir = paths.home,
          workspaceDir = paths.defaultWorkspace,
          guestUsername = profileUsername,
        ),
      )
    } catch (error: Exception) {
      android.util.Log.e(
        LOG_TAG,
        "remote_access_spec_failed type=${error::class.java.simpleName} message=${error.message?.take(MAX_ERROR_LOG_CHARS)}",
      )
      RemoteAccessBuildOutcome.Failure("internal_error")
    }
  }

  private fun locateProotRuntime(): ProotRuntimeLocator.Location = packagedProotRuntime

  private fun locateProotRuntimeUncached(): ProotRuntimeLocator.Location = try {
    val manifest = context.assets.open("alpine-runtime/manifest.json")
      .bufferedReader()
      .use { it.readText() }
    val nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir ?: "")
    ProotRuntimeLocator(manifest).locate(nativeLibraryDir)
  } catch (_: Exception) {
    ProotRuntimeLocator.Location.Unavailable("proot_locate_failed", "cannot locate the packaged runtime")
  }

  private fun activeDnsServers(): List<String> = try {
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    val network = connectivity.activeNetwork ?: return emptyList()
    connectivity.getLinkProperties(network)?.dnsServers
      ?.mapNotNull { it.hostAddress?.trim()?.takeIf(String::isNotEmpty) }
      .orEmpty()
  } catch (_: RuntimeException) {
    emptyList()
  }

  private object UnusedGuestProber : GuestProber {
    override fun probe(rootfsDir: File, guestHomeDir: File, timeoutMs: Long): GuestProbeResult =
      throw IllegalStateException("session factory does not probe rootfs")
  }

  private companion object {
    const val LOG_TAG = "HorusTerminal"
    const val MAX_ERROR_LOG_CHARS = 160
  }
}
