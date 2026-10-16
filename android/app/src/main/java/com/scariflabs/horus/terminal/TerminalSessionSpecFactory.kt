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
    extractor = SafeTarExtractor(),
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
    val profileUsername = activeProfileUsername()
      ?: ProotSessionLauncher.GUEST_ROOT_USER
    val harnessUser = TerminalHarnessUsers.forTarget(toolchainTarget, profileUsername)
    val guestUsername = harnessUser?.username ?: profileUsername
    val guestUid = harnessUser?.uid ?: ProotSessionLauncher.GUEST_UID
    val guestGid = harnessUser?.gid ?: ProotSessionLauncher.GUEST_GID
    val guestHomeDir = try {
      activeGuestHome(harnessUser)
    } catch (_: Exception) {
      return BuildOutcome.Failure("internal_error")
    }
    return try {
      val launcher = ProotSessionLauncher(
        runtime = located.runtime,
        scratchDir = paths.guestTmp,
        hardlinkDir = paths.hardlinks,
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
          rootHelper = harnessUser == null,
          // The user's own terminal runs the app's harnesses, like SSH does.
          harnessHomes = if (harnessUser == null) existingHarnessHomes(profileUsername) else emptyList(),
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
    val profileUsername = activeProfileUsername()
      ?: ProotSessionLauncher.GUEST_ROOT_USER
    val harnessUser = TerminalHarnessUsers.forTarget(target, profileUsername)
    val guestHomeDir = try {
      activeGuestHome(harnessUser)
    } catch (_: Exception) {
      return ProvisionBuildOutcome.Failure("internal_error")
    }
    return try {
      val launcher = ProotSessionLauncher(
        runtime = located.runtime,
        scratchDir = paths.guestTmp,
        hardlinkDir = paths.hardlinks,
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
    val profileUsername = activeProfileUsername()
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
        scratchDir = paths.guestTmp,
        hardlinkDir = paths.hardlinks,
        dnsServersProvider = ::activeDnsServers,
        downloadSources = downloadSourceSettings.read(),
      )
      val guestHomeDir = activeGuestHome(null)
      // The active user's home may postdate the pairing; give it the paired keys.
      val remoteStore = RemoteAccess.Store(paths.base)
      remoteStore.syncKeysToHomes()
      // One host key for the phone, whichever user is active.
      val hostKeyDir = remoteStore.prepareHostKey(guestHomeDir)
      // The app's harnesses, so SSH runs the same installs and logins.
      val harnessHomes = existingHarnessHomes(profileUsername)
      RemoteAccessBuildOutcome.Success(
        launcher.remoteAccessLaunchSpec(
          rootfsDir = rootfsDir,
          guestHomeDir = guestHomeDir,
          workspaceDir = paths.defaultWorkspace,
          guestUsername = profileUsername,
          harnessHomes = harnessHomes,
          hostKeyDir = hostKeyDir,
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

  /** The distribution sessions run in: the active rootfs's. */
  private fun activeDistro(): String = try {
    store.readActiveRecord()?.distro
  } catch (_: Exception) {
    null
  } ?: RootfsCatalog.DISTRO_ALPINE

  /** The Linux user of the active distribution. */
  fun activeProfileUsername(): String? = TerminalGuestIdentity.readStoredUsername(context, activeDistro())

  /** The active distribution's home for [harnessUser], or its user home; created on first use. */
  /** The harness homes the app has made in the active distribution so far. */
  private fun existingHarnessHomes(profileUsername: String): List<ProotSessionLauncher.HarnessHome> {
    val distro = activeDistro()
    return AGENT_TARGETS.mapNotNull { target ->
      val user = TerminalHarnessUsers.forTarget(target, profileUsername) ?: return@mapNotNull null
      val home = paths.harnessHomeFor(distro, user.homeKey)
      if (home.isDirectory) ProotSessionLauncher.HarnessHome(target, user.username, home) else null
    }
  }

  private fun activeGuestHome(harnessUser: TerminalHarnessUser?): File {
    val distro = activeDistro()
    val home = harnessUser?.let { paths.harnessHomeFor(distro, it.homeKey) } ?: paths.homeFor(distro)
    if (!home.isDirectory && !home.mkdirs()) throw IllegalStateException("guest home is unavailable")
    return home
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
    override fun probe(rootfsDir: File, guestHomeDir: File, timeoutMs: Long, entry: RootfsCatalogEntry): GuestProbeResult =
      throw IllegalStateException("session factory does not probe rootfs")
  }

  private companion object {
    const val LOG_TAG = "HorusTerminal"
    const val MAX_ERROR_LOG_CHARS = 160
    private val AGENT_TARGETS = listOf(
      TerminalRuntimeContract.TOOLCHAIN_TARGET_CLAUDE,
      TerminalRuntimeContract.TOOLCHAIN_TARGET_CODEX,
      TerminalRuntimeContract.TOOLCHAIN_TARGET_OPENCODE,
    )
  }
}
