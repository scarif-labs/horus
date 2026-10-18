package com.scariflabs.horus.terminal

import android.system.Os
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime

/**
 * Launch policy for interactive guest sessions. Builds the
 * same fixed-argument-vector PRoot invocation the Phase 1 prober proved on
 * the device, extended with the interactive shell: no shell-string
 * concatenation anywhere, and the child environment is assembled
 * from scratch so no Android or host-shell variable leaks into the guest.
 *
 * The launcher binds only the guest rootfs, persistent home, explicit
 * app-private workspace, and the host /proc view required by process-aware
 * interactive tools such as `top`. If Android denies a global proc file that
 * BusyBox top requires, only that file gets a synthetic compatibility bind;
 * the live per-process directories remain from the host /proc view. The small
 * set of standard character devices required by Alpine tools is bound
 * explicitly; /sys and the rest of /dev remain unbound.
 */
class ProotSessionLauncher(
  private val runtime: ProotRuntimeLocator.LocatedRuntime,
  private val scratchDir: File,
  private val hostProcDir: File = File(HOST_PROC),
  private val hostDevDir: File = File(HOST_DEV),
  private val dnsServersProvider: () -> List<String> = { emptyList() },
  /** Boot time in Unix seconds, for the `btime` line of the stand-in /proc/stat. */
  private val bootTimeSeconds: () -> Long = { DEVICE_BOOT_TIME_SECONDS },
  /**
   * Internal diagnostic switch. When false (the default) PRoot runs with
   * PROOT_NO_SECCOMP=1; when true the variable is omitted so PRoot may use
   * its seccomp-filter acceleration. Only the seccomp device probe sets it.
   */
  private val seccompAcceleration: Boolean = false,
  /** Alpine mirror and npm registry for package installs inside the guest. */
  private val downloadSources: DownloadSources = DownloadSources(),
  private val hardlinkDir: File = File(scratchDir.absoluteFile.parentFile, "l"),
) {

  /** A harness's home, as the app's own sessions of that harness see it. */
  data class HarnessHome(val target: String, val username: String, val hostDir: File)

  data class LaunchSpec(
    val argv: List<String>,
    val environment: List<String>,
    val workingDirectory: File?,
  )

  /**
   * Launches `/bin/sh -l` (login shell) inside the guest rootfs with the
   * deliberate guest environment: HOME, PWD, XDG paths, TERM, PATH, and LANG
   * are set here and nowhere else.
   */
  fun interactiveShellLaunchSpec(
    rootfsDir: File,
    guestHomeDir: File,
    workspaceDir: File? = null,
    guestUsername: String = GUEST_ROOT_USER,
    guestUid: Int = GUEST_UID,
    guestGid: Int = GUEST_GID,
    term: String = DEFAULT_TERM,
    sessionCommand: String? = null,
  ): LaunchSpec = buildLaunchSpec(
    rootfsDir = rootfsDir,
    guestHomeDir = guestHomeDir,
    workspaceDir = workspaceDir,
    guestUsername = guestUsername,
    guestUid = guestUid,
    guestGid = guestGid,
    term = term,
    // Keep the bootstrap shell non-interactive so it cannot print its own
    // prompt before zsh is ready. The selected launcher is an argv argument,
    // not a line typed into the PTY, so it can never echo into the terminal.
    command = if (sessionCommand == null) {
      listOf(GUEST_SHELL, "-c", "exec zsh -l")
    } else {
      require(sessionCommand.isNotEmpty() && sessionCommand.length <= MAX_SESSION_COMMAND_LENGTH) {
        "session command is invalid"
      }
      listOf(
        GUEST_SHELL,
        "-c",
        "stty echo 2>/dev/null || true; unset HORUS_BOOTSTRAP; exec ${GUEST_SHELL} -c \"${'$'}1\"",
        "horus-session",
        sessionCommand,
      )
    },
  )

  /** Runs the requested shell or harness bootstrap as root before its session. */
  fun toolchainProvisionLaunchSpec(
    rootfsDir: File,
    guestHomeDir: File,
    target: String,
    term: String = DEFAULT_TERM,
  ): LaunchSpec = buildLaunchSpec(
    rootfsDir = rootfsDir,
    guestHomeDir = guestHomeDir,
    workspaceDir = null,
    guestUsername = GUEST_ROOT_USER,
    term = term,
    command = listOf(GUEST_SHELL, "-c", TOOLCHAIN_PROVISION_SCRIPT),
    toolchainTarget = target,
  )

  /**
   * Runs the idempotent target installer in the visible PTY, then hands the
   * same terminal to the configured Alpine user. The persistent home is
   * mounted at both /root and the user's home while the installer is root so
   * the resulting CLI payload is immediately available to the user session.
   */
  fun toolchainSessionLaunchSpec(
    rootfsDir: File,
    guestHomeDir: File,
    workspaceDir: File? = null,
    guestUsername: String = GUEST_ROOT_USER,
    guestUid: Int = GUEST_UID,
    guestGid: Int = GUEST_GID,
    target: String,
    term: String = DEFAULT_TERM,
    sessionCommand: String? = null,
    provision: Boolean = true,
    /**
     * Starts the root helper behind `sudo` before the session drops to its
     * user. Only the profile user's own sessions get it, never a harness's.
     */
    rootHelper: Boolean = false,
    /** The app's harness homes, mounted for the profile user's own sessions. */
    harnessHomes: List<HarnessHome> = emptyList(),
  ): LaunchSpec {
    require(TerminalRuntimeContract.isValidToolchainTarget(target)) { "toolchain target is invalid" }
    val command = sessionCommand ?: "exec zsh -l"
    require(command.isNotEmpty() && command.length <= MAX_SESSION_COMMAND_LENGTH) {
      "session command is invalid"
    }
    val guestHomePath = if (guestUsername == GUEST_ROOT_USER) {
      GUEST_HOME
    } else {
      "/home/$guestUsername"
    }
    val sessionWorkingDirectory = if (workspaceDir == null) guestHomePath else "/workspace"
    if (rootHelper) installRootHelper(rootfsDir)
    requireHarnessHomes(harnessHomes, guestUsername)
    if (harnessHomes.isNotEmpty()) installAgentHomes(rootfsDir, harnessHomes)
    val launch = buildLaunchSpec(
      rootfsDir = rootfsDir,
      guestHomeDir = guestHomeDir,
      workspaceDir = workspaceDir,
      guestUsername = guestUsername,
      term = term,
      command = listOf(
        GUEST_SHELL,
        "-c",
        TOOLCHAIN_SESSION_SCRIPT,
        "horus-toolchain-session",
        guestUsername,
        guestUid.toString(),
        guestGid.toString(),
        command,
        sessionWorkingDirectory,
      ),
      toolchainTarget = target,
      guestUid = guestUid,
      guestGid = guestGid,
      forceRootIdentity = true,
      // sudo runs commands on their own pseudo-terminal, as SSH logins do.
      extraDeviceBinds = if (rootHelper) ptyDeviceBinds() else emptyList(),
      extraBinds = harnessHomes.map { it.hostDir to "/home/${it.username}" },
    )
    val environment = buildList {
      addAll(launch.environment)
      if (rootHelper) add("HORUS_ROOT_HELPER=1")
      if (!provision) add("HORUS_SKIP_TOOLCHAIN_PROVISION=1")
    }
    return launch.copy(environment = environment)
  }

  /**
   * Runs the opt-in USB SSH server. Root installs dropbear and prepares the
   * host key, then the server drops to the profile user, so that is the only
   * account a paired computer can log in as. The server binds the phone's
   * loopback only; computers reach it through `adb forward`.
   */
  fun remoteAccessLaunchSpec(
    rootfsDir: File,
    guestHomeDir: File,
    workspaceDir: File?,
    guestUsername: String,
    guestUid: Int = GUEST_UID,
    guestGid: Int = GUEST_GID,
    port: Int = RemoteAccess.PORT,
    harnessHomes: List<HarnessHome> = emptyList(),
    /** App-private directory holding the phone's one host key; null keeps it in the user's home. */
    hostKeyDir: File? = null,
  ): LaunchSpec {
    require(guestUsername != GUEST_ROOT_USER) { "remote access needs a non-root profile user" }
    require(hostKeyDir == null || (hostKeyDir.isDirectory && !Files.isSymbolicLink(hostKeyDir.toPath()))) {
      "remote access host key directory is unavailable"
    }
    require(port in 1025..65535) { "remote access port is invalid" }
    requireHarnessHomes(harnessHomes, guestUsername)
    installRootHelper(rootfsDir)
    installAgentHomes(rootfsDir, harnessHomes)
    return buildLaunchSpec(
      rootfsDir = rootfsDir,
      guestHomeDir = guestHomeDir,
      workspaceDir = workspaceDir,
      guestUsername = guestUsername,
      guestUid = guestUid,
      guestGid = guestGid,
      term = DEFAULT_TERM,
      command = listOf(
        GUEST_SHELL,
        "-c",
        REMOTE_ACCESS_SCRIPT,
        "horus-remote-access",
        guestUsername,
        guestUid.toString(),
        guestGid.toString(),
        port.toString(),
        if (hostKeyDir == null) "" else RemoteAccess.GUEST_HOST_KEY_DIR,
      ),
      forceRootIdentity = true,
      // Root's own /root stays the rootfs directory, so logins see their
      // home only as /home/<user>.
      bindHomeAtRoot = false,
      // SSH logins allocate their own pseudo-terminals inside the guest.
      extraDeviceBinds = ptyDeviceBinds(),
      // The app's harness homes, where its sessions see them, so `codex`
      // and friends over SSH run the app's installs with its logins.
      extraBinds = harnessHomes.map { it.hostDir to "/home/${it.username}" } +
        listOfNotNull(hostKeyDir?.let { it to RemoteAccess.GUEST_HOST_KEY_DIR }),
    )
  }

  private fun buildLaunchSpec(
    rootfsDir: File,
    guestHomeDir: File,
    workspaceDir: File?,
    guestUsername: String,
    guestUid: Int = GUEST_UID,
    guestGid: Int = GUEST_GID,
    term: String,
    command: List<String>,
    toolchainTarget: String? = null,
    forceRootIdentity: Boolean = false,
    extraDeviceBinds: List<Pair<File, String>> = emptyList(),
    extraBinds: List<Pair<File, String>> = emptyList(),
    bindHomeAtRoot: Boolean = true,
  ): LaunchSpec {
    require(rootfsDir.isDirectory) { "rootfs directory is missing" }
    require(guestHomeDir.isDirectory) { "guest home directory is missing" }
    if (workspaceDir != null) {
      require(workspaceDir.isDirectory) { "workspace directory is missing" }
    }
    require(guestUsername == GUEST_ROOT_USER || USERNAME_PATTERN.matches(guestUsername)) {
      "guest username is invalid"
    }
    require(guestUid in 1..MAX_GUEST_ID && guestGid in 1..MAX_GUEST_ID) {
      "guest uid or gid is invalid"
    }
    if (toolchainTarget != null) {
      require(TerminalRuntimeContract.isValidToolchainTarget(toolchainTarget)) { "toolchain target is invalid" }
    }
    val requestedRoot = guestUsername == GUEST_ROOT_USER
    val launchAsRoot = forceRootIdentity || requestedRoot
    val guestHomePath = if (requestedRoot) GUEST_HOME else "/home/$guestUsername"
    val launchHomePath = if (launchAsRoot) GUEST_HOME else guestHomePath
    if (!requestedRoot) prepareGuestAccount(rootfsDir, guestUsername, guestUid, guestGid)
    if (!scratchDir.exists() && !scratchDir.mkdirs() && !scratchDir.isDirectory) {
      throw IllegalStateException("cannot create proot scratch directory")
    }
    require(!Files.isSymbolicLink(scratchDir.toPath())) { "proot scratch directory is a symbolic link" }
    // This is the guest /tmp for every rootfs and user, so give it the
    // rootfs tarball's 1777. The app-private parent still keeps it private.
    runCatching { Os.chmod(scratchDir.absolutePath, "1777".toInt(8)) }
    DistroStoreCore.pruneProotTemps(scratchDir, hostProcDir)
    require(!Files.isSymbolicLink(hardlinkDir.toPath())) { "proot hardlink directory is a symbolic link" }
    if (!hardlinkDir.isDirectory && !hardlinkDir.mkdirs() && !hardlinkDir.isDirectory) {
      throw IllegalStateException("cannot create proot hardlink directory")
    }
    prepareResolverFile(rootfsDir)
    prepareCodexProfile(guestHomeDir)
    when (toolchainTarget) {
      TerminalRuntimeContract.TOOLCHAIN_TARGET_CODEX -> prepareGlobalAgentInstructions(
        guestHomeDir,
        listOf(CODEX_CONFIG_DIRECTORY_NAME),
        CODEX_AGENT_INSTRUCTIONS_NAME,
        CODEX_AGENT_INSTRUCTIONS_BEGIN,
        CODEX_AGENT_INSTRUCTIONS_END,
        CODEX_AGENT_INSTRUCTIONS_BLOCK,
        "Codex",
      )
      TerminalRuntimeContract.TOOLCHAIN_TARGET_CLAUDE -> prepareGlobalAgentInstructions(
        guestHomeDir,
        listOf(CLAUDE_CONFIG_DIRECTORY_NAME),
        CLAUDE_AGENT_INSTRUCTIONS_NAME,
        CLAUDE_AGENT_INSTRUCTIONS_BEGIN,
        CLAUDE_AGENT_INSTRUCTIONS_END,
        CLAUDE_AGENT_INSTRUCTIONS_BLOCK,
        "Claude Code",
      )
      TerminalRuntimeContract.TOOLCHAIN_TARGET_OPENCODE -> prepareGlobalAgentInstructions(
        guestHomeDir,
        OPENCODE_CONFIG_DIRECTORY_PARTS,
        OPENCODE_AGENT_INSTRUCTIONS_NAME,
        OPENCODE_AGENT_INSTRUCTIONS_BEGIN,
        OPENCODE_AGENT_INSTRUCTIONS_END,
        OPENCODE_AGENT_INSTRUCTIONS_BLOCK,
        "OpenCode",
      )
    }
    prepareZshProfile(guestHomeDir)
    val rootfsPath = rootfsDir.canonicalFile.absolutePath
    val homePath = guestHomeDir.canonicalFile.absolutePath
    val workspacePath = workspaceDir?.canonicalFile?.absolutePath
    val deviceBinds = prepareDeviceBinds()
    val procCompatibilityBinds = prepareProcCompatibilityBinds()
    val homeGuestPaths = if (forceRootIdentity && !requestedRoot && !bindHomeAtRoot) {
      listOf(guestHomePath)
    } else if (forceRootIdentity && !requestedRoot) {
      listOf(GUEST_HOME, guestHomePath)
    } else {
      listOf(launchHomePath)
    }
    val guestPath = if (launchAsRoot) GUEST_PATH else "$guestHomePath/.local/bin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
    val argv = buildList {
      add(runtime.prootBin.absolutePath)
      if (launchAsRoot) add("-0")
      add("-r")
      add(rootfsPath)
      // Android's SELinux policy denies link(2) on app data, so emulate guest
      // hard links (apk installs e.g. zsh as one) with PRoot's symlink scheme.
      add("--link2symlink")
      if (!launchAsRoot) {
        add("-i")
        add("$guestUid:$guestGid")
      }
      homeGuestPaths.forEach { path ->
        add("-b")
        add("$homePath:$path")
      }
      if (workspacePath != null) {
        add("-b")
        add("$workspacePath:/workspace")
      }
      extraBinds.forEach { (source, guestPath) ->
        add("-b")
        add("${source.canonicalFile.absolutePath}:$guestPath")
      }
      add("-b")
      add("${scratchDir.canonicalPath}:/tmp")
      // Emulated links contain absolute host backing paths. Map only this
      // backing directory at the same path so apk/Node can stat those links.
      add("-b")
      add("${hardlinkDir.canonicalPath}:${hardlinkDir.canonicalPath}")
      deviceBinds.forEach { bind ->
        add("-b")
        add("${bind.source.canonicalFile.absolutePath}:${bind.guestPath}")
      }
      extraDeviceBinds.forEach { (source, guestPath) ->
        require(source.exists()) { "required host device is missing: ${source.absolutePath}" }
        add("-b")
        add("${source.absolutePath}:$guestPath")
      }
      // BusyBox top needs a live procfs for its per-process scan. PRoot
      // translates the host view through this bind; only denied global files
      // are overlaid below, so process directories remain live.
      add("-b")
      add("${hostProcDir.canonicalFile.absolutePath}:$GUEST_PROC")
      procCompatibilityBinds.forEach { bind ->
        add("-b")
        add("${bind.source.canonicalFile.absolutePath}:${bind.guestPath}")
      }
      add("-w")
      add(if (workspacePath == null) launchHomePath else "/workspace")
      addAll(command)
    }
    val environment = buildList {
      add("PROOT_LOADER=${runtime.loaderBin.absolutePath}")
      add("PROOT_LOADER_32=${runtime.loaderBin.absolutePath}")
      add("PROOT_TMP_DIR=${scratchDir.canonicalFile.absolutePath}")
      // Backing files beside the source keep a package directory nonempty
      // while another alias still exists. Keep them outside replaceable trees.
      add("PROOT_L2S_DIR=${hardlinkDir.canonicalPath}")
      // Keep the stable ptrace path on the supported Android API. PRoot's
      // seccomp acceleration crashes this device before the guest shell opens.
      if (!seccompAcceleration) add("PROOT_NO_SECCOMP=1")
      // Android PRoot exposes a live /proc, but cannot complete apk's
      // O_TMPFILE linkat commit through /proc/self/fd. Report that one probe
      // as unavailable so apk uses its named-temp/rename fallback instead.
      add("PROOT_NO_O_TMPFILE=1")
      add("LD_LIBRARY_PATH=${runtime.libraryDir.absolutePath}")
      add("HOME=$launchHomePath")
      add("PWD=${if (workspacePath == null) launchHomePath else "/workspace"}")
      add("XDG_CONFIG_HOME=$launchHomePath/.config")
      add("XDG_DATA_HOME=$launchHomePath/.local/share")
      add("XDG_CACHE_HOME=$launchHomePath/.cache")
      add("TERM=$term")
      if (toolchainTarget == TerminalRuntimeContract.TOOLCHAIN_TARGET_CLAUDE) {
        // Claude's fullscreen alternate buffer owns its own sparse viewport
        // and bypasses terminal scrollback. Keep conversation output in the
        // normal buffer so Horus can render and scroll the complete history.
        add("CLAUDE_CODE_DISABLE_ALTERNATE_SCREEN=1")
      }
      toolchainTarget?.let { add("HORUS_TOOLCHAIN_TARGET=$it") }
      // Do not shadow Alpine's real BusyBox applets: both `top` and
      // `/bin/busybox top` must reach the guest binary with their argv intact.
      add("PATH=$guestPath")
      add("LANG=$GUEST_LANG")
      // Bound CoreCLR's GC reservations on Android ARM64's limited VA space.
      add("DOTNET_gcServer=0")
      add("DOTNET_GCHeapHardLimit=0x40000000")
      add("DOTNET_CLI_DO_NOT_USE_MSBUILD_SERVER=1")
      // SDK server opt-out and Roslyn shared compilation are separate knobs.
      // A detached VBCSCompiler otherwise keeps PRoot alive after shell exit.
      add("DOTNET_CLI_USE_MSBUILD_SERVER=0")
      add("UseSharedCompilation=false")
      add("TZ=${GuestTimeZone.forGuest(rootfsDir)}")
      addAll(downloadSources.guestEnvironment())
    }
    return LaunchSpec(
      argv = argv,
      environment = environment,
      // The host working directory is inherited deliberately, exactly like
      // the Phase 1 prober; the guest sees the selected bind through PRoot's -w.
      workingDirectory = null,
    )
  }

  private fun ptyDeviceBinds(): List<Pair<File, String>> =
    listOf(File(hostDevDir, "ptmx") to "$GUEST_DEV/ptmx", File(hostDevDir, "pts") to "$GUEST_DEV/pts")

  /** Writes `sudo` and its root helper into the rootfs. */
  private fun installRootHelper(rootfsDir: File) {
    ROOT_HELPER_FILES.forEach { (resource, guestPath) ->
      val content = requireNotNull(ProotSessionLauncher::class.java.getResourceAsStream("/com/scariflabs/horus/terminal/guest/$resource")) {
        "missing guest resource $resource"
      }.use { it.readBytes() }
      writeGuestFile(rootfsDir, guestPath, content, executable = true)
    }
  }

  /**
   * Lists where the app's harness homes are mounted, for the `codex`,
   * `claude`, and `opencode` commands outside the app's own harness sessions.
   */
  private fun installAgentHomes(rootfsDir: File, harnessHomes: List<HarnessHome>) {
    val content = harnessHomes.joinToString("") { "${it.target}=/home/${it.username}\n" }
    writeGuestFile(rootfsDir, GUEST_AGENT_HOMES_PATH, content.toByteArray(), executable = false)
  }

  private fun requireHarnessHomes(harnessHomes: List<HarnessHome>, guestUsername: String) {
    harnessHomes.forEach { home ->
      require(home.target in AGENT_STUB_TARGETS && USERNAME_PATTERN.matches(home.username) && home.username != guestUsername) {
        "harness home is invalid"
      }
    }
  }

  /**
   * Writes one file into the rootfs. The guest owns the rootfs, so a
   * directory on the way may be a symlink planted there; never write through
   * one, or the app could overwrite its own files.
   */
  private fun writeGuestFile(rootfsDir: File, guestPath: String, content: ByteArray, executable: Boolean) {
    var directory = rootfsDir
    guestPath.trim('/').split('/').dropLast(1).forEach { part ->
      directory = File(directory, part)
      if (Files.isSymbolicLink(directory.toPath())) {
        throw IllegalStateException("guest path $guestPath goes through a symlink")
      }
      if (!directory.isDirectory && !directory.mkdir() && !directory.isDirectory) {
        throw IllegalStateException("cannot create guest directory for $guestPath")
      }
    }
    val target = File(rootfsDir, guestPath.trim('/'))
    if (!Files.isSymbolicLink(target.toPath()) && target.isFile && (!executable || target.canExecute()) &&
      target.readBytes().contentEquals(content)
    ) {
      return
    }
    var temporary: File? = null
    try {
      temporary = File.createTempFile("${target.name}-", ".tmp", directory)
      temporary.writeBytes(content)
      temporary.setReadable(true, false)
      if (executable) temporary.setExecutable(true, false)
      Files.move(
        temporary.toPath(),
        target.toPath(),
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING,
      )
    } finally {
      temporary?.takeIf { it.exists() }?.delete()
    }
  }

  private fun prepareDeviceBinds(): List<DeviceBind> {
    require(hostDevDir.isDirectory) { "host dev directory is missing" }
    return DEVICE_FILES.map { name ->
      val source = File(hostDevDir, name)
      require(source.exists()) { "required host device is missing: ${source.absolutePath}" }
      DeviceBind(source, "$GUEST_DEV/$name")
    }
  }

  private fun prepareProcCompatibilityBinds(): List<ProcCompatibilityBind> {
    require(hostProcDir.isDirectory) { "host proc directory is missing" }
    var compatibilityDir: File? = null
    return procGlobalFiles(bootTimeSeconds()).mapNotNull { (name, content) ->
      val hostFile = File(hostProcDir, name)
      if (isReadable(hostFile)) return@mapNotNull null

      val directory = compatibilityDir ?: File(scratchDir, PROC_COMPATIBILITY_DIR).also {
        if (!it.exists() && !it.mkdirs() && !it.isDirectory) {
          throw IllegalStateException("cannot create proc compatibility directory")
        }
        compatibilityDir = it
      }
      val fallback = File(directory, name)
      if (!fallback.exists() || fallback.readText() != content) {
        var temporary: File? = null
        try {
          temporary = File.createTempFile("$name-", ".tmp", directory)
          temporary.writeText(content)
          Files.move(
            temporary.toPath(),
            fallback.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
          )
        } finally {
          temporary?.takeIf { it.exists() }?.delete()
        }
      }
      ProcCompatibilityBind(fallback, "$GUEST_PROC/$name")
    }
  }

  /**
   * Alpine minirootfs does not ship a resolver file. Android's resolver is
   * exposed through ConnectivityManager rather than a host `/etc/resolv.conf`,
   * so materialize only the current DNS addresses into the app-owned guest
   * rootfs. An empty provider deliberately leaves the guest file untouched
   * for JVM/unit-test launchers that do not model Android networking.
   */
  private fun prepareResolverFile(rootfsDir: File) {
    val addresses = dnsServersProvider()
      .asSequence()
      .map(String::trim)
      .filter { it.isNotEmpty() && !it.any { character -> character.isWhitespace() } }
      .distinct()
      .take(MAX_DNS_SERVERS)
      .toList()
    if (addresses.isEmpty()) return
    val directory = File(rootfsDir, RESOLV_CONF_DIRECTORY)
    if (!directory.isDirectory) {
      throw IllegalStateException("guest resolver directory is missing")
    }
    val resolver = File(directory, RESOLV_CONF_NAME)
    if (Files.isSymbolicLink(resolver.toPath())) {
      throw IllegalStateException("guest resolver path is a symlink")
    }
    val content = addresses.joinToString(separator = "", postfix = "") { "nameserver $it\n" }
    if (!resolver.exists() || resolver.readText() != content) {
      var temporary: File? = null
      try {
        temporary = File.createTempFile("$RESOLV_CONF_NAME-", ".tmp", directory)
        temporary.writeText(content)
        Files.move(
          temporary.toPath(),
          resolver.toPath(),
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING,
        )
      } finally {
        temporary?.takeIf { it.exists() }?.delete()
      }
    }
  }

  /**
   * Codex's Linux sandbox is unavailable under Android PRoot. Keep Codex
   * usable by making the selected mobile policy explicit at the interactive
   * shell boundary: `codex` requests full local access from Codex while its
   * normal approval policy remains unchanged. The `command` builtin skips
   * this function when resolving the real executable, so npm-installed Codex
   * binaries continue to be found through the bounded guest PATH.
   */
  private fun prepareCodexProfile(guestHomeDir: File) {
    val profile = File(guestHomeDir, PROFILE_NAME)
    if (Files.isSymbolicLink(profile.toPath())) {
      throw IllegalStateException("guest profile is a symbolic link")
    }
    if (profile.exists() && !profile.isFile) {
      throw IllegalStateException("guest profile is not a regular file")
    }
    if (profile.exists() && profile.length() > MAX_PROFILE_BYTES) {
      throw IllegalStateException("guest profile is too large")
    }
    val existing = if (profile.exists()) profile.readText(Charsets.UTF_8) else ""
    val start = existing.indexOf(CODEX_PROFILE_BEGIN)
    val end = existing.indexOf(CODEX_PROFILE_END)
    if ((start < 0) != (end < 0) || (start >= 0 && end < start)) {
      throw IllegalStateException("guest profile has an incomplete Codex policy block")
    }
    if (start >= 0 && existing.indexOf(CODEX_PROFILE_BEGIN, start + CODEX_PROFILE_BEGIN.length) >= 0) {
      throw IllegalStateException("guest profile has duplicate Codex policy blocks")
    }
    val blockEnd = if (end >= 0) end + CODEX_PROFILE_END.length else -1
    val updated = if (start >= 0) {
      existing.substring(0, start) + CODEX_PROFILE_BLOCK + existing.substring(blockEnd)
    } else {
      val prefix = existing.trimEnd()
      if (prefix.isEmpty()) CODEX_PROFILE_BLOCK + "\n" else "$prefix\n\n$CODEX_PROFILE_BLOCK\n"
    }
    if (updated == existing) return

    var temporary: File? = null
    try {
      temporary = File.createTempFile(".horus-profile-", ".tmp", guestHomeDir)
      temporary.writeText(updated, Charsets.UTF_8)
      Files.move(
        temporary.toPath(),
        profile.toPath(),
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING,
      )
    } finally {
      temporary?.takeIf { it.exists() }?.delete()
    }
  }

  /** Seeds a tool's global instructions before its first lazy install/launch. */
  private fun prepareGlobalAgentInstructions(
    guestHomeDir: File,
    configDirectoryParts: List<String>,
    instructionsFileName: String,
    beginMarker: String,
    endMarker: String,
    managedBlock: String,
    toolName: String,
  ) {
    var configDir = guestHomeDir
    configDirectoryParts.forEach { part ->
      require(part.isNotBlank() && part != "." && part != ".." && '/' !in part) {
        "agent config directory part is invalid"
      }
      configDir = File(configDir, part)
      if (Files.isSymbolicLink(configDir.toPath()) || (configDir.exists() && !configDir.isDirectory)) {
        throw IllegalStateException("$toolName config directory is unavailable")
      }
      if (!configDir.exists() && !configDir.mkdirs() && !configDir.isDirectory) {
        throw IllegalStateException("cannot create $toolName config directory")
      }
    }

    val instructionsFile = File(configDir, instructionsFileName)
    if (Files.isSymbolicLink(instructionsFile.toPath()) ||
      (instructionsFile.exists() && !instructionsFile.isFile)
    ) {
      throw IllegalStateException("$toolName agent instructions are unavailable")
    }
    if (instructionsFile.exists() && instructionsFile.length() > MAX_AGENT_INSTRUCTIONS_BYTES) {
      throw IllegalStateException("$toolName agent instructions are too large")
    }
    val existing = if (instructionsFile.exists()) instructionsFile.readText(Charsets.UTF_8) else ""
    val updated = repairManagedBlock(existing, beginMarker, endMarker, managedBlock)
    if (updated.toByteArray(Charsets.UTF_8).size > MAX_AGENT_INSTRUCTIONS_BYTES) {
      throw IllegalStateException("$toolName agent instructions are too large")
    }
    if (updated != existing) atomicWrite(instructionsFile, updated)
  }

  /** Adds one bounded non-root identity to the active Alpine rootfs. */
  private fun prepareGuestAccount(rootfsDir: File, username: String, uid: Int, gid: Int) {
    synchronized(GUEST_ACCOUNT_MUTATION_LOCK) {
      prepareGuestAccountLocked(rootfsDir, username, uid, gid)
    }
  }

  private fun prepareGuestAccountLocked(rootfsDir: File, username: String, uid: Int, gid: Int) {
    val etc = File(rootfsDir, "etc")
    if (!etc.isDirectory || Files.isSymbolicLink(etc.toPath())) {
      throw IllegalStateException("guest etc directory is missing")
    }
    val homeRoot = File(rootfsDir, "home")
    val home = File(homeRoot, username)
    // Renaming back to a former name reuses its alias; drop the link first.
    if (isHomeAlias(home)) Files.delete(home.toPath())
    if (Files.isSymbolicLink(home.toPath()) || (home.exists() && !home.isDirectory)) {
      throw IllegalStateException("guest home directory is unavailable")
    }
    val passwd = File(etc, "passwd")
    val group = File(etc, "group")
    val shadow = File(etc, "shadow")
    listOf(passwd, group, shadow).forEach { file ->
      if (Files.isSymbolicLink(file.toPath()) || (file.exists() && !file.isFile)) {
        throw IllegalStateException("guest account file is not regular: ${file.name}")
      }
    }
    val originalPasswdLines = if (passwd.exists()) passwd.readLines(Charsets.UTF_8) else emptyList()
    val originalGroupLines = if (group.exists()) group.readLines(Charsets.UTF_8) else emptyList()
    val originalShadowLines = if (shadow.exists()) shadow.readLines(Charsets.UTF_8) else emptyList()
    var passwdLines = originalPasswdLines
    var groupLines = originalGroupLines
    var shadowLines = originalShadowLines
    var migratedLegacyProfile = false
    var formerUsername: String? = null
    val existingUser = passwdLines.firstOrNull { it.substringBefore(':') == username }
    if (existingUser != null) {
      val fields = existingUser.split(':')
      if (
        passwdLines.count { it.substringBefore(':') == username } != 1 ||
        fields.size < 7 ||
        fields[2] != uid.toString() ||
        fields[3] != gid.toString()
      ) {
        throw IllegalStateException("guest username already has a different identity")
      }
      if (isFixedProfileIdentity(username, uid, gid) &&
        passwdLines.count { it.split(':').getOrNull(2) == uid.toString() } != 1
      ) {
        throw IllegalStateException("fixed profile uid is ambiguous")
      }
    } else {
      val uidRows = passwdLines.filter { it.split(':').getOrNull(2) == uid.toString() }
      if (uidRows.isNotEmpty()) {
        if (!isFixedProfileIdentity(username, uid, gid)) {
          throw IllegalStateException("guest uid is already occupied")
        }
        formerUsername = uidRows.singleOrNull()?.substringBefore(':')
        val migrated = migrateLegacyProfileAccount(passwdLines, groupLines, shadowLines, username)
        passwdLines = migrated.passwd
        groupLines = migrated.group
        shadowLines = migrated.shadow
        migratedLegacyProfile = true
      } else {
        passwdLines = passwdLines + "$username:x:$uid:$gid:$username:${guestHomePathFor(username)}:/bin/sh"
      }
    }
    if (groupLines.none { it.split(':').getOrNull(2) == gid.toString() }) {
      val groupName = username.takeIf { name -> groupLines.none { it.substringBefore(':') == name } }
        ?: "horus_shared"
      if (groupLines.any { it.substringBefore(':') == groupName }) {
        throw IllegalStateException("guest group name is already occupied")
      }
      groupLines = groupLines + "$groupName:x:$gid:"
    }
    // The Android login gate verifies the salted password verifier. Keep the
    // guest shadow entry locked so a second, unaudited auth path cannot bypass
    // that gate, even if a prior root session edited the entry.
    val lockedShadowEntry = "$username:!:19000:0:99999:7:::"
    val shadowEntryIndex = shadowLines.indexOfFirst { it.substringBefore(':') == username }
    if (shadowEntryIndex < 0) {
      shadowLines = shadowLines + lockedShadowEntry
    } else if (shadowLines[shadowEntryIndex].split(':').getOrNull(1) != "!") {
      shadowLines = shadowLines.mapIndexed { index, line -> if (index == shadowEntryIndex) lockedShadowEntry else line }
    }

    // The migration is retryable if Android stops the service between file
    // replacements: write group and shadow first, then passwd as the commit
    // point so the old profile remains resolvable until its replacement is
    // ready. Every transform above is validated before the first write.
    if (migratedLegacyProfile) {
      if (groupLines != originalGroupLines) {
        atomicWrite(group, groupLines.joinToString("\n", postfix = "\n"))
      }
      if (shadowLines != originalShadowLines) {
        atomicWrite(shadow, shadowLines.joinToString("\n", postfix = "\n"))
      }
      if (passwdLines != originalPasswdLines) {
        atomicWrite(passwd, passwdLines.joinToString("\n", postfix = "\n"))
      }
    } else {
      if (passwdLines != originalPasswdLines) {
        atomicWrite(passwd, passwdLines.joinToString("\n", postfix = "\n"))
      }
      if (groupLines != originalGroupLines) {
        atomicWrite(group, groupLines.joinToString("\n", postfix = "\n"))
      }
      if (shadowLines != originalShadowLines) {
        atomicWrite(shadow, shadowLines.joinToString("\n", postfix = "\n"))
      }
    }
    if (!home.exists() && !home.mkdirs() || !home.isDirectory) {
      throw IllegalStateException("guest home directory is unavailable")
    }
    linkFormerHomes(homeRoot, formerUsername, username)
  }

  /**
   * After a rename, files that saved the old absolute path (virtualenvs,
   * rc files, tool configs) keep working: /home/<former> becomes a relative
   * link to the current name, and older aliases follow each later rename.
   * Only an empty mount-point directory is ever replaced; anything with
   * content is left alone. Running sessions keep their own bind instead.
   */
  private fun linkFormerHomes(homeRoot: File, former: String?, current: String) {
    if (former != null && former != current && USERNAME_PATTERN.matches(former)) {
      val old = File(homeRoot, former)
      val path = old.toPath()
      val replaceable = when {
        Files.isSymbolicLink(path) -> isHomeAlias(old)
        old.isDirectory -> old.list()?.isEmpty() == true
        else -> !old.exists()
      }
      if (replaceable) {
        Files.deleteIfExists(path)
        Files.createSymbolicLink(path, java.nio.file.Paths.get(current))
      }
    }
    homeRoot.listFiles()?.filter { isHomeAlias(it) && Files.readSymbolicLink(it.toPath()).toString() != current }?.forEach {
      Files.delete(it.toPath())
      Files.createSymbolicLink(it.toPath(), java.nio.file.Paths.get(current))
    }
  }

  /** A link Horus made for a former name: a symlink to a bare sibling username. */
  private fun isHomeAlias(file: File): Boolean =
    Files.isSymbolicLink(file.toPath()) &&
      runCatching { Files.readSymbolicLink(file.toPath()).toString() }.getOrNull()?.let { it != "." && it != ".." && USERNAME_PATTERN.matches(it) } == true

  private data class GuestAccountLines(
    val passwd: List<String>,
    val group: List<String>,
    val shadow: List<String>,
  )

  /** The profile user's uid and gid, under whatever name the user chose. */
  private fun isFixedProfileIdentity(username: String, uid: Int, gid: Int): Boolean =
    username != GUEST_ROOT_USER && uid == GUEST_UID && gid == GUEST_GID

  /**
   * Renames the profile uid's account to [profileUsername]. Accepts only the
   * exact row format Horus writes, so it covers the 1.0.x custom-name accounts
   * and a rootfs whose account predates the configured name.
   */
  private fun migrateLegacyProfileAccount(
    passwdLines: List<String>,
    groupLines: List<String>,
    shadowLines: List<String>,
    profileUsername: String,
  ): GuestAccountLines {
    val uidRows = passwdLines.filter { it.split(':').getOrNull(2) == GUEST_UID.toString() }
    if (uidRows.size != 1) throw IllegalStateException("legacy profile uid is ambiguous")
    val fields = uidRows.single().split(':')
    val legacyUsername = fields[0]
    if (
      fields.size != 7 ||
      fields[1] != "x" ||
      fields[3] != GUEST_GID.toString() ||
      !USERNAME_PATTERN.matches(legacyUsername) ||
      legacyUsername == GUEST_ROOT_USER ||
      fields[4] != legacyUsername ||
      fields[5] != "/home/$legacyUsername" ||
      // Remote access gives the profile user its login shell.
      fields[6] !in setOf("/bin/sh", "/usr/local/bin/horus-ssh-shell") ||
      passwdLines.count { it.substringBefore(':') == legacyUsername } != 1
    ) {
      throw IllegalStateException("legacy profile account does not match the prior format")
    }
    if (passwdLines.any { it.substringBefore(':') == profileUsername && it != uidRows.single() }) {
      throw IllegalStateException("fixed profile username is already occupied")
    }
    if (groupLines.any { line ->
        val fields = line.split(':')
        fields.size == 4 && fields[0] != legacyUsername && fields[3].split(',').contains(legacyUsername)
      }
    ) {
      throw IllegalStateException("legacy profile has unexpected supplemental group memberships")
    }

    val legacyGroupIndexes = groupLines.indices.filter { groupLines[it].substringBefore(':') == legacyUsername }
    val profileGroupIndexes = groupLines.indices.filter { groupLines[it].substringBefore(':') == profileUsername }
    val gidRows = groupLines.filter { it.split(':').getOrNull(2) == GUEST_GID.toString() }
    if (legacyGroupIndexes.size > 1) throw IllegalStateException("legacy profile group is ambiguous")
    var migratedGroups = groupLines
    if (legacyGroupIndexes.isNotEmpty()) {
      val index = legacyGroupIndexes.single()
      val groupFields = groupLines[index].split(':')
      if (
        groupFields.size != 4 ||
        groupFields[1] != "x" ||
        groupFields[2] != GUEST_GID.toString() ||
        gidRows.size != 1 ||
        profileGroupIndexes.isNotEmpty() ||
        groupFields[3].isNotEmpty()
      ) {
        throw IllegalStateException("legacy profile group does not match the prior format")
      }
      migratedGroups = groupLines.mapIndexed { row, line ->
        if (row == index) listOf(profileUsername, groupFields[1], groupFields[2], "").joinToString(":") else line
      }
    } else if (profileGroupIndexes.isNotEmpty()) {
      // A prior interrupted migration may already have renamed the group but
      // not committed the passwd row. Accept only the exact renamed form.
      val profileGroup = profileGroupIndexes.singleOrNull()?.let(groupLines::get)
      if (
        profileGroupIndexes.size != 1 ||
        gidRows.size != 1 ||
        profileGroup != "$profileUsername:x:$GUEST_GID:"
      ) {
        throw IllegalStateException("fixed profile group conflicts with migration")
      }
      migratedGroups = groupLines
    } else {
      if (gidRows.size > 1) throw IllegalStateException("legacy profile group id is ambiguous")
    }

    val legacyShadowIndexes = shadowLines.indices.filter { shadowLines[it].substringBefore(':') == legacyUsername }
    val profileShadowIndexes = shadowLines.indices.filter { shadowLines[it].substringBefore(':') == profileUsername }
    if (legacyShadowIndexes.size > 1 || profileShadowIndexes.size > 1 ||
      (legacyShadowIndexes.isNotEmpty() && profileShadowIndexes.isNotEmpty())
    ) {
      throw IllegalStateException("legacy profile shadow entry is ambiguous")
    }
    val lockedShadowEntry = "$profileUsername:!:19000:0:99999:7:::"
    val migratedShadow = when {
      profileShadowIndexes.isNotEmpty() -> {
        if (profileShadowIndexes.size != 1 || shadowLines[profileShadowIndexes.single()] != lockedShadowEntry) {
          throw IllegalStateException("fixed profile shadow entry conflicts with migration")
        }
        if (legacyShadowIndexes.isNotEmpty()) {
          throw IllegalStateException("legacy profile shadow entry conflicts with migration")
        }
        shadowLines
      }
      legacyShadowIndexes.isNotEmpty() -> {
        val legacyLockedShadowEntry = "$legacyUsername:!:19000:0:99999:7:::"
        if (shadowLines[legacyShadowIndexes.single()] != legacyLockedShadowEntry) {
          throw IllegalStateException("legacy profile shadow entry does not match the prior format")
        }
        shadowLines.mapIndexed { index, line ->
          if (index == legacyShadowIndexes.single()) lockedShadowEntry else line
        }
      }
      else -> shadowLines + lockedShadowEntry
    }

    val migratedPasswd = passwdLines.map { line ->
      if (line == uidRows.single()) {
        listOf(profileUsername, fields[1], fields[2], fields[3], profileUsername, "/home/$profileUsername", fields[6]).joinToString(":")
      } else {
        line
      }
    }
    return GuestAccountLines(migratedPasswd, migratedGroups, migratedShadow)
  }

  private fun guestHomePathFor(username: String): String = "/home/$username"

  /**
   * Give every interactive guest the same small zsh theme. Neither pinned
   * rootfs includes zsh, so the profile is ready for the first install and
   * automatically hands future login shells to zsh once it exists. User content outside these bounded blocks is preserved.
   */
  private fun prepareZshProfile(guestHomeDir: File) {
    val profile = File(guestHomeDir, PROFILE_NAME)
    val existingProfile = if (profile.exists()) profile.readText(Charsets.UTF_8) else ""
    val updatedProfile = repairManagedBlock(
      existingProfile,
      ZSH_PROFILE_BEGIN,
      ZSH_PROFILE_END,
      ZSH_PROFILE_BLOCK,
    )
    if (updatedProfile != existingProfile) atomicWrite(profile, updatedProfile)

    val zshrc = File(guestHomeDir, ZSHRC_NAME)
    if (Files.isSymbolicLink(zshrc.toPath())) {
      throw IllegalStateException("guest zshrc is a symbolic link")
    }
    if (zshrc.exists() && !zshrc.isFile) {
      throw IllegalStateException("guest zshrc is not a regular file")
    }
    if (zshrc.exists() && zshrc.length() > MAX_ZSHRC_BYTES) {
      throw IllegalStateException("guest zshrc is too large")
    }
    val existingZshrc = if (zshrc.exists()) zshrc.readText(Charsets.UTF_8) else ""
    val updatedZshrc = repairManagedBlock(
      existingZshrc,
      ZSHRC_BEGIN,
      ZSHRC_END,
      ZSHRC_BLOCK,
    )
    if (updatedZshrc != existingZshrc) atomicWrite(zshrc, updatedZshrc)
  }

  private fun repairManagedBlock(existing: String, begin: String, end: String, block: String): String {
    val start = existing.indexOf(begin)
    val finish = existing.indexOf(end)
    if ((start < 0) != (finish < 0) || (start >= 0 && finish < start)) {
      throw IllegalStateException("guest profile has an incomplete managed block")
    }
    if (start >= 0 && existing.indexOf(begin, start + begin.length) >= 0) {
      throw IllegalStateException("guest profile has duplicate managed blocks")
    }
    if (start >= 0) {
      return existing.substring(0, start) + block + existing.substring(finish + end.length)
    }
    val prefix = existing.trimEnd()
    return if (prefix.isEmpty()) "$block\n" else "$prefix\n\n$block\n"
  }

  private fun atomicWrite(target: File, content: String) {
    var temporary: File? = null
    try {
      temporary = File.createTempFile(".horus-profile-", ".tmp", target.parentFile)
      temporary.writeText(content, Charsets.UTF_8)
      Files.move(
        temporary.toPath(),
        target.toPath(),
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING,
      )
    } finally {
      temporary?.takeIf { it.exists() }?.delete()
    }
  }

  private fun isReadable(file: File): Boolean = try {
    file.inputStream().use {
      it.read()
      true
    }
  } catch (_: java.io.IOException) {
    false
  } catch (_: SecurityException) {
    false
  }

  private fun hasCodexPayload(guestHomeDir: File): Boolean {
    val entry = File(guestHomeDir, ".local/lib/node_modules/@openai/codex/bin/codex.js")
    if (!entry.isFile || !entry.canExecute()) return false
    return runCatching {
      entry.bufferedReader(Charsets.UTF_8).use { it.readLine() == "#!/usr/bin/env node" }
    }.getOrDefault(false)
  }

  internal fun codexPayloadStamp(guestHomeDir: File): String? {
    if (!hasCodexPayload(guestHomeDir)) return null
    val modules = File(guestHomeDir, ".local/lib/node_modules")
    val packageRoot = File(modules, "@openai/codex")
    val platformRoot = listOf(
      File(packageRoot, "node_modules/@openai/codex-linux-arm64"),
      File(modules, "@openai/codex-linux-arm64"),
    ).firstOrNull { File(it, "package.json").isFile } ?: packageRoot
    val compatible = runCatching {
      val wrapper = org.json.JSONObject(File(packageRoot, "package.json").readText())
      val spec = wrapper.optJSONObject("optionalDependencies")?.optString("@openai/codex-linux-arm64").orEmpty()
      val version = wrapper.getString("version")
      if (platformRoot == packageRoot) spec.isEmpty() else {
        val nativeVersion = org.json.JSONObject(File(platformRoot, "package.json").readText()).getString("version")
        spec == "npm:@openai/codex@$nativeVersion" && nativeVersion == "$version-linux-arm64"
      }
    }.getOrDefault(false)
    if (!compatible) return null
    val vendor = File(platformRoot, "vendor/aarch64-unknown-linux-musl")
    val native = listOf(File(vendor, "bin/codex"), File(vendor, "codex/codex"))
      .firstOrNull { it.isFile && it.canExecute() } ?: return null
    // A script/empty file is not a Linux ARM64 native payload.
    val valid = runCatching {
      native.inputStream().use {
        val header = ByteArray(20)
        it.read(header) == 20 && header[0] == 0x7f.toByte() &&
          header[1] == 0x45.toByte() && header[2] == 0x4c.toByte() && header[3] == 0x46.toByte() &&
          header[4] == 2.toByte() && header[5] == 1.toByte() &&
          header[18] == 0xb7.toByte() && header[19] == 0.toByte()
      }
    }.getOrDefault(false)
    if (!valid) return null
    val identities = listOf(
      File(packageRoot, "bin/codex.js"), File(packageRoot, "package.json"),
      File(platformRoot, "package.json"), native,
    ).map { filePayloadStamp(it) ?: return null }
    val metadata = listOf(File(packageRoot, "package.json"), File(platformRoot, "package.json")).map { file ->
      runCatching {
        java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
      }.getOrNull() ?: return null
    }
    return (identities + metadata).joinToString(",", postfix = ",")
  }

  private fun hasOpenCodePayload(guestHomeDir: File): Boolean {
    val entry = listOf(
      File(guestHomeDir, ".local/lib/node_modules/opencode-ai/bin/opencode"),
      File(guestHomeDir, ".local/lib/node_modules/opencode-ai/bin/opencode.exe"),
    ).firstOrNull { it.isFile && it.canExecute() } ?: return false
    return runCatching {
      entry.inputStream().use {
        it.read() == 0x7f && it.read() == 0x45 && it.read() == 0x4c && it.read() == 0x46
      }
    }.getOrDefault(false)
  }

  internal fun openCodePayloadStamp(guestHomeDir: File): String? {
    val entry = listOf(
      File(guestHomeDir, ".local/lib/node_modules/opencode-ai/bin/opencode"),
      File(guestHomeDir, ".local/lib/node_modules/opencode-ai/bin/opencode.exe"),
    ).firstOrNull { it.isFile && it.canExecute() } ?: return null
    return filePayloadStamp(entry)
  }

  private fun filePayloadStamp(entry: File): String? {
    return runCatching {
      val stat = Os.stat(entry.absolutePath)
      "${stat.st_size}:${stat.st_mtime}:${stat.st_ino}:${stat.st_dev}"
    }.getOrElse {
      // Host-side JVM tests do not provide android.system.Os; the NIO fallback
      // keeps their readiness check equivalent while Android API 24+ uses Os.
      runCatching {
        val attributes = Files.readAttributes(entry.toPath(), "unix:size,lastModifiedTime,ino,dev")
        val size = attributes["size"]
        val modified = attributes["lastModifiedTime"]
        val inode = attributes["ino"]
        val device = attributes["dev"]
        if (size is Long && modified is FileTime && inode is Long && device is Long) {
          "$size:${modified.toMillis() / 1000}:$inode:$device"
        } else {
          null
        }
      }.getOrNull()
    }
  }

  private fun hasClaudeLauncher(guestHomeDir: File): Boolean {
    val launcher = File(guestHomeDir, ".local/bin/claude")
    return launcher.isFile && launcher.canExecute() && !Files.isSymbolicLink(launcher.toPath())
  }

  private fun hasPortableShellLauncher(
    guestHomeDir: File,
    launcherName: String,
    relativePayload: String,
  ): Boolean {
    val launcher = File(guestHomeDir, ".local/bin/$launcherName")
    if (!launcher.isFile || !launcher.canExecute() || Files.isSymbolicLink(launcher.toPath())) return false
    return runCatching {
      val content = launcher.readText(Charsets.UTF_8)
      content.lineSequence().firstOrNull() == "#!/bin/sh" &&
        content.contains("\$HOME/$relativePayload") &&
        !content.contains("/root/")
    }.getOrDefault(false)
  }

  fun hasProvisionedToolchain(rootfsDir: File, guestHomeDir: File, target: String): Boolean {
    require(TerminalRuntimeContract.isValidToolchainTarget(target)) { "toolchain target is invalid" }
    val rootfsZsh = listOf(File(rootfsDir, "bin/zsh"), File(rootfsDir, "usr/bin/zsh"))
      .any { it.isFile && it.canExecute() }
    val userBin = File(guestHomeDir, ".local/bin")
    if (!rootfsZsh) return false
    if (target == TerminalRuntimeContract.TOOLCHAIN_TARGET_SHELL) return hasBaseUtilities(rootfsDir)
    val readyMarker = File(guestHomeDir, ".cache/horus/$target.ready")
    if (target == TerminalRuntimeContract.TOOLCHAIN_TARGET_GITHUB) {
      val rootfsGh = listOf(File(rootfsDir, "bin/gh"), File(rootfsDir, "usr/bin/gh"))
        .any { it.isFile && it.canExecute() }
      return rootfsGh && readyMarker.isFile
    }
    if (target == TerminalRuntimeContract.TOOLCHAIN_TARGET_CLAUDE) {
      // The native installer creates an absolute /root symlink. Provisioning
      // rewrites it to a HOME-relative wrapper because interactive sessions
      // run as the configured Alpine user, not root.
      return readyMarker.isFile && hasClaudeLauncher(guestHomeDir)
    }
    val rootfsNode = File(rootfsDir, "usr/bin/node").let { it.isFile && it.canExecute() }
    // apk installs npm in /usr/bin; on Debian it is npm's own release in /usr/local/bin.
    val rootfsNpm = listOf(File(rootfsDir, "usr/bin/npm"), File(rootfsDir, "usr/local/bin/npm"))
      .any { it.isFile && it.canExecute() }
    val launcher = when (target) {
      TerminalRuntimeContract.TOOLCHAIN_TARGET_CLAUDE -> "claude"
      TerminalRuntimeContract.TOOLCHAIN_TARGET_CODEX -> "codex"
      TerminalRuntimeContract.TOOLCHAIN_TARGET_OPENCODE -> "opencode"
      else -> return false
    }
    val markerReady = if (target == TerminalRuntimeContract.TOOLCHAIN_TARGET_OPENCODE ||
      target == TerminalRuntimeContract.TOOLCHAIN_TARGET_CODEX) {
      val stamp = if (target == TerminalRuntimeContract.TOOLCHAIN_TARGET_CODEX) codexPayloadStamp(guestHomeDir)
        else openCodePayloadStamp(guestHomeDir)
      stamp != null && readyMarker.isFile && readyMarker.length() in 1..512 &&
        runCatching { readyMarker.readText(Charsets.US_ASCII).trim() == "ready:$stamp" }.getOrDefault(false)
    } else {
      readyMarker.isFile
    }
    return rootfsNode && rootfsNpm && markerReady && when (target) {
      TerminalRuntimeContract.TOOLCHAIN_TARGET_CODEX ->
        hasPortableShellLauncher(guestHomeDir, launcher, ".local/lib/node_modules/@openai/codex/bin/codex.js") &&
          hasCodexPayload(guestHomeDir) &&
          hasProcpsPs(rootfsDir)
      TerminalRuntimeContract.TOOLCHAIN_TARGET_OPENCODE ->
        (hasPortableShellLauncher(guestHomeDir, launcher, ".local/lib/node_modules/opencode-ai/bin/opencode") ||
          hasPortableShellLauncher(guestHomeDir, launcher, ".local/lib/node_modules/opencode-ai/bin/opencode.exe")) &&
          hasOpenCodePayload(guestHomeDir)
      else -> false
    }
  }

  /**
   * Codex reads process start times with `ps -p PID -o lstart=`. BusyBox's
   * /bin/ps is a symlink and lacks both options; procps-ng replaces it with
   * a real binary.
   */
  private fun hasProcpsPs(rootfsDir: File): Boolean {
    val ps = File(rootfsDir, "bin/ps").toPath()
    return Files.isRegularFile(ps, java.nio.file.LinkOption.NOFOLLOW_LINKS)
  }

  private fun hasBaseUtilities(rootfsDir: File): Boolean =
    listOf("rg", "curl", "jq", "python3").all { name ->
      File(rootfsDir, "usr/bin/$name").let { it.isFile && it.canExecute() }
    } && File(rootfsDir, "lib/ld-linux-aarch64.so.1").isFile

  private data class ProcCompatibilityBind(
    val source: File,
    val guestPath: String,
  )

  private data class DeviceBind(
    val source: File,
    val guestPath: String,
  )

  companion object {
    const val GUEST_SHELL = "/bin/sh"
    const val GUEST_HOME = "/root"
    const val GUEST_ROOT_USER = "root"
    const val GUEST_UID = 1000
    const val GUEST_GID = 1000
    private val GUEST_ACCOUNT_MUTATION_LOCK = Any()
    const val GUEST_PATH = "/root/.local/bin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
    const val GUEST_LANG = "C.UTF-8"
    const val GUEST_SUDO_PATH = "/usr/local/bin/sudo"
    const val GUEST_ROOT_HELPER_PATH = "/usr/local/lib/horus/sudo-helper"
    const val GUEST_AGENT_HOMES_PATH = "/usr/local/lib/horus/agent-homes"
    private val AGENT_STUB_TARGETS = setOf(
      TerminalRuntimeContract.TOOLCHAIN_TARGET_CLAUDE,
      TerminalRuntimeContract.TOOLCHAIN_TARGET_CODEX,
      TerminalRuntimeContract.TOOLCHAIN_TARGET_OPENCODE,
    )
    private val ROOT_HELPER_FILES = listOf("sudo" to GUEST_SUDO_PATH, "sudo-helper" to GUEST_ROOT_HELPER_PATH)
    const val HOST_PROC = "/proc"
    const val HOST_DEV = "/dev"
    const val GUEST_PROC = "/proc"
    const val GUEST_DEV = "/dev"
    private const val PROC_COMPATIBILITY_DIR = "proc-compat"
    private const val PROFILE_NAME = ".profile"
    private const val ZSHRC_NAME = ".zshrc"
    private const val MAX_PROFILE_BYTES = 256 * 1024L
    private const val MAX_ZSHRC_BYTES = 256 * 1024L
    private const val MAX_AGENT_INSTRUCTIONS_BYTES = 256 * 1024L
    private const val MAX_SESSION_COMMAND_LENGTH = 4096
    private const val MAX_GUEST_ID = 65_533
    const val CODEX_PROFILE_BEGIN = "# BEGIN HORUS_CODEX_UNSANDBOXED_V1"
    const val CODEX_PROFILE_END = "# END HORUS_CODEX_UNSANDBOXED_V1"
    const val ZSH_PROFILE_BEGIN = "# BEGIN HORUS_ZSH_V1"
    const val ZSH_PROFILE_END = "# END HORUS_ZSH_V1"
    const val ZSHRC_BEGIN = "# BEGIN HORUS_ZSHRC_V1"
    const val ZSHRC_END = "# END HORUS_ZSHRC_V1"
    const val CODEX_AGENT_INSTRUCTIONS_BEGIN = "<!-- BEGIN HORUS_CODEX_MOBILE_ENV_V1 -->"
    const val CODEX_AGENT_INSTRUCTIONS_END = "<!-- END HORUS_CODEX_MOBILE_ENV_V1 -->"
    const val CLAUDE_AGENT_INSTRUCTIONS_BEGIN = "<!-- BEGIN HORUS_CLAUDE_CODE_MOBILE_ENV_V1 -->"
    const val CLAUDE_AGENT_INSTRUCTIONS_END = "<!-- END HORUS_CLAUDE_CODE_MOBILE_ENV_V1 -->"
    const val OPENCODE_AGENT_INSTRUCTIONS_BEGIN = "<!-- BEGIN HORUS_OPENCODE_MOBILE_ENV_V1 -->"
    const val OPENCODE_AGENT_INSTRUCTIONS_END = "<!-- END HORUS_OPENCODE_MOBILE_ENV_V1 -->"
    private const val CODEX_CONFIG_DIRECTORY_NAME = ".codex"
    private const val CODEX_AGENT_INSTRUCTIONS_NAME = "AGENTS.md"
    private const val CLAUDE_CONFIG_DIRECTORY_NAME = ".claude"
    private const val CLAUDE_AGENT_INSTRUCTIONS_NAME = "CLAUDE.md"
    private val OPENCODE_CONFIG_DIRECTORY_PARTS = listOf(".config", "opencode")
    private const val OPENCODE_AGENT_INSTRUCTIONS_NAME = "AGENTS.md"
    private val CODEX_PROFILE_BLOCK = """
      $CODEX_PROFILE_BEGIN
      # Codex Linux sandboxing is unavailable under Android PRoot, and so is
      # its background app-server: PRoot maps its control socket to a host
      # path longer than a Unix socket allows, so the server never listens.
      codex() {
        command codex --sandbox danger-full-access --no-daemon "${'$'}@"
      }
      export HORUS_CODEX_MODE=unsandboxed
      $CODEX_PROFILE_END
    """.trimIndent()
    private val ZSH_PROFILE_BLOCK = """
      $ZSH_PROFILE_BEGIN
      export HORUS_ZSH_THEME=horus
      umask 0002
      if [ "${'$'}{HORUS_BOOTSTRAP:-}" = 1 ]; then
        stty -echo 2>/dev/null || true
      fi
      $ZSH_PROFILE_END
    """.trimIndent()
    internal val ZSH_EXIT_SCRIPT = """
      # A bare exit, logout or Ctrl-D must not inherit the last command's
      # status (a failed build, or 130 after Ctrl-C). An explicit `exit N`
      # keeps N, and a killed shell still reports its signal. The status is
      # set before exiting because a login shell with a zlogout file (Debian
      # ships /etc/zsh/zlogout) ignores an exit inside zshexit; zshexit only
      # covers EOF without the line editor. Never apply this to command shells.
      if [[ -o interactive && -z "${'$'}{ZSH_EXECUTION_STRING-}" ]]; then
        exit() { if (( ${'$'}# )); then horus_exit_status=1; else set -- 0; fi; builtin exit "${'$'}@"; }
        logout() { if (( ${'$'}# )); then horus_exit_status=1; else set -- 0; fi; builtin logout "${'$'}@"; }
        zshexit() { [[ -n ${'$'}{horus_exit_status-} ]] || exit 0; }
        setopt ignore_eof
        horus-eof() { [[ -z ${'$'}BUFFER ]] && exit; zle delete-char-or-list; }
        zle -N horus-eof
        bindkey '^D' horus-eof
      fi
    """.trimIndent()
    internal val DOTNET_ENV_SCRIPT = """
      export DOTNET_gcServer="${'$'}{DOTNET_gcServer:-0}"
      export DOTNET_GCHeapHardLimit="${'$'}{DOTNET_GCHeapHardLimit:-0x40000000}"
      export DOTNET_CLI_DO_NOT_USE_MSBUILD_SERVER="${'$'}{DOTNET_CLI_DO_NOT_USE_MSBUILD_SERVER:-1}"
      export DOTNET_CLI_USE_MSBUILD_SERVER="${'$'}{DOTNET_CLI_USE_MSBUILD_SERVER:-0}"
      export UseSharedCompilation="${'$'}{UseSharedCompilation:-false}"
    """.trimIndent()
    private val ZSHRC_BLOCK = """
      $ZSHRC_BEGIN
${ZSH_EXIT_SCRIPT.prependIndent("      ")}
      setopt prompt_subst
      setopt transient_rprompt
      umask 0002
      # A stray ^S (XOFF) froze all output until ^Q, which a phone user has no
      # reason to know. zsh re-enables flow control itself, so turn it off here.
      unsetopt flow_control
      stty -ixon 2>/dev/null || true
      export PATH="${'$'}HOME/.local/bin:/usr/local/bin:${'$'}{PATH:-/usr/sbin:/usr/bin:/sbin:/bin}"
      # Codex's Linux sandboxing and background app-server are unavailable
      # under Android PRoot (see the profile block).
      codex() {
        command codex --sandbox danger-full-access --no-daemon "${'$'}@"
      }
      export HORUS_CODEX_MODE=unsandboxed
      if [[ "${'$'}{HORUS_BOOTSTRAP:-}" == 1 ]]; then
        # Keep the first launcher command invisible while the React Native
        # side waits for this prompt. buildZshCommand restores echo before
        # handing control to zsh or the selected harness.
        stty -echo 2>/dev/null || true
        unset HORUS_BOOTSTRAP
      fi
      # duellj theme, kept inline so it works without Oh My Zsh on Alpine:
      # two-line user/host/path/history prompt without a per-line clock.
      PROMPT=$'%{\e[0;34m%}%B┌─[%b%{\e[0m%}%{\e[1;32m%}%n%{\e[1;34m%}@%{\e[0m%}%{\e[0;36m%}%m%{\e[0;34m%}%B]%b%{\e[0m%} - %b%{\e[0;34m%}%B[%b%{\e[1;37m%}%~%{\e[0;34m%}%B]%b%{\e[0m%} - %{\e[0;34m%}%B[%b%{\e[0;33m%}%!%{\e[0;34m%}%B]%b%{\e[0m%}\n%{\e[0;34m%}%B└─[%{\e[1;35m%}${'$'}%{\e[0;34m%}%B]%{\e[0m%}%b '
      PS2='%F{blue}>%f '
      $ZSHRC_END
    """.trimIndent()
    private val CODEX_AGENT_INSTRUCTIONS_BLOCK = """
      $CODEX_AGENT_INSTRUCTIONS_BEGIN
      ## Runtime environment
      - Codex runs in Horus on Android inside a Linux ARM64 guest through PRoot. The distribution is Alpine or Debian; read `/etc/os-release` before assuming either.
      - Alpine uses `apk`, musl, and BusyBox applets; Debian uses `apt` and glibc. Do not assume `systemd` or a running init, and check available commands and options before relying on them.
      - Codex runs as the dedicated non-root `harness_codex` user with its own persistent home; projects are available under `/workspace`.
      - Horus provisions system dependencies as root before launching Codex. If a system package is missing, report it instead of assuming `apk add` or `apt install` will work from the session.
      - Codex's Linux sandbox is unavailable under Android PRoot. Horus launches the CLI with `--sandbox danger-full-access`; do not assume Codex's own sandbox isolates commands from files accessible to this harness user.
      $CODEX_AGENT_INSTRUCTIONS_END
    """.trimIndent()
    private val CLAUDE_AGENT_INSTRUCTIONS_BLOCK = """
      $CLAUDE_AGENT_INSTRUCTIONS_BEGIN
      ## Runtime environment
      - Claude Code runs in Horus on Android inside a Linux ARM64 guest through PRoot. The distribution is Alpine or Debian; read `/etc/os-release` before assuming either.
      - Alpine uses `apk`, musl, and BusyBox applets; Debian uses `apt` and glibc. Do not assume `systemd` or a running init, and check available commands and options before relying on them.
      - Claude Code runs as the dedicated non-root `harness_claude` user with its own persistent home; projects are available under `/workspace`.
      - Horus provisions system dependencies as root before launching Claude Code. If a system package is missing, report it instead of assuming `apk add` or `apt install` will work from the session.
      - Commands run through Android PRoot. Do not assume Linux namespaces or kernel sandboxing isolate them from files accessible to the Horus app; PRoot's emulated user IDs are not a hard security boundary.
      $CLAUDE_AGENT_INSTRUCTIONS_END
    """.trimIndent()
    private val OPENCODE_AGENT_INSTRUCTIONS_BLOCK = """
      $OPENCODE_AGENT_INSTRUCTIONS_BEGIN
      ## Runtime environment
      - OpenCode runs in Horus on Android inside a Linux ARM64 guest through PRoot. The distribution is Alpine or Debian; read `/etc/os-release` before assuming either.
      - Alpine uses `apk`, musl, and BusyBox applets; Debian uses `apt` and glibc. Do not assume `systemd` or a running init, and check available commands and options before relying on them.
      - OpenCode runs as the dedicated non-root `harness_opencode` user with its own persistent home; projects are available under `/workspace`.
      - Horus provisions system dependencies as root before launching OpenCode. If a system package is missing, report it instead of assuming `apk add` or `apt install` will work from the session.
      - Commands run through Android PRoot. Do not assume Linux namespaces or kernel sandboxing isolate them from files accessible to the Horus app; PRoot's emulated user IDs are not a hard security boundary.
      $OPENCODE_AGENT_INSTRUCTIONS_END
    """.trimIndent()
    // Points apk at the chosen mirror. Each repository line keeps its branch
    // and name (v3.24/main); only the server in front of it changes. The
    // mirror URL is validated by DownloadSources and cannot contain '@'.
    internal val APK_MIRROR_SCRIPT = """
      if [ -n "${'$'}{HORUS_ALPINE_MIRROR:-}" ] && [ -w /etc/apk/repositories ]; then
        sed -i -E "s@^[^#]*/(v[0-9]+\.[0-9]+|edge)/([a-z]+)/?\${'$'}@${'$'}HORUS_ALPINE_MIRROR/\1/\2@" /etc/apk/repositories || true
      fi
    """.trimIndent()
    /**
     * The guest's package manager and the packages each target needs from
     * it: apk on Alpine, apt on Debian. Detected inside the guest, so the
     * same script serves either rootfs. apt runs as root under PRoot, where
     * it cannot drop to its _apt sandbox user, and refreshes its lists at
     * most once a day.
     */
    /** npm release installed on Debian, and its registry `dist.integrity`. */
    internal const val UPSTREAM_NPM_VERSION = "11.12.1"
    internal const val UPSTREAM_NPM_INTEGRITY =
      "sha512-zcoUuF1kezGSAo0CqtvoLXX3mkRqzuqYdL6Y5tdo8g69NVV3CkjQ6ZBhBgB4d7vGkPcV6TcvLi3GRKPDFX+xTA=="

    internal val PACKAGE_MANAGER_SCRIPT = """
      if [ -x /usr/bin/apt-get ]; then
        horus_pkg=apt
        # Debian splits venv (and its ensurepip) out of python3.
        pkgs_shell="ca-certificates curl git jq less openssh-client python3 python3-venv ripgrep tzdata zsh"
        has_python_venv() {
          for venv_dir in /usr/lib/python3.*/ensurepip; do [ -d "${'$'}venv_dir" ] && return 0; done
          return 1
        }
        pkgs_github="ca-certificates git openssh-client gh zsh"
        pkgs_claude="bash curl ca-certificates git openssh-client zsh"
        # npm itself comes from npm's own release; see install_upstream_npm.
        pkgs_node="bash ca-certificates git openssh-client nodejs zsh"
        pkgs_procps="procps"
        # Rewritten every time so an older Horus's settings are replaced.
        mkdir -p /etc/apt/apt.conf.d /etc/dpkg/dpkg.cfg.d
        printf '%s\n' 'APT::Sandbox::User "root";' 'Acquire::Retries "3";' 'Acquire::Languages "none";' 'Dpkg::Use-Pty "0";' 'APT::Install-Recommends "0";' 'APT::Install-Suggests "0";' > /etc/apt/apt.conf.d/99horus
        # Every file dpkg writes is a trapped syscall under PRoot. Skip its
        # per-file fsync (a broken install is simply provisioned again) and
        # the docs, manuals, and translations nothing here reads.
        printf '%s\n' force-unsafe-io \
          'path-exclude=/usr/share/doc/*' 'path-include=/usr/share/doc/*/copyright' \
          'path-exclude=/usr/share/man/*' 'path-exclude=/usr/share/info/*' \
          'path-exclude=/usr/share/locale/*' 'path-include=/usr/share/locale/locale.alias' \
          'path-exclude=/usr/share/lintian/*' > /etc/dpkg/dpkg.cfg.d/99horus
        export DEBIAN_FRONTEND=noninteractive
      else
        horus_pkg=apk
        pkgs_shell="ca-certificates curl gcompat git jq less openssh-client-default python3 ripgrep tzdata zsh"
        pkgs_github="ca-certificates git openssh-client-default github-cli zsh"
        pkgs_claude="bash curl ca-certificates git openssh-client-default zsh libgcc libstdc++"
        pkgs_node="bash ca-certificates git openssh-client-default nodejs npm zsh libgcc libstdc++"
        pkgs_procps="procps-ng"
        has_python_venv() { true; }
      fi
      # The latest progress line, for the app to show while tools install.
      pkg_progress=/root/.cache/horus/provision-progress
      pkg_status=/root/.cache/horus/pkg-status
      # Turns apt's Status-Fd lines, or apk's "(12/47) Installing ..." log
      # lines, into "Downloading packages… 37%" and "Installing packages…
      # 64%", printed and kept in pkg_progress.
      watch_packages() {
        watch_last=
        while sleep 1; do
          if [ "${'$'}horus_pkg" = apt ]; then
            watch_line=${'$'}(tail -n 1 "${'$'}pkg_status" 2>/dev/null) || continue
          else
            watch_line=${'$'}(grep -o '^([0-9]*/[0-9]*)' "${'$'}pkg_log" 2>/dev/null | tail -n 1 | tr -d '()') || continue
          fi
          watch_text=${'$'}(printf '%s\n' "${'$'}watch_line" | awk -F: '
            /^dlstatus:/ { printf "Downloading packages… %d%%\n", ${'$'}3; exit }
            /^pmstatus:/ { for (i = 3; i <= NF; i++) if (${'$'}i ~ /^[0-9]+(\.[0-9]+)?${'$'}/) { printf "Installing packages… %d%%\n", ${'$'}i; exit } }
            /^[0-9]+\/[0-9]+${'$'}/ { split(${'$'}0, part, "/"); if (part[2] > 0) printf "Installing packages… %d%%\n", part[1] * 100 / part[2]; exit }
          ')
          if [ -n "${'$'}watch_text" ] && [ "${'$'}watch_text" != "${'$'}watch_last" ]; then
            printf '%s\n' "${'$'}watch_text"
            printf '%s\n' "${'$'}watch_text" > "${'$'}pkg_progress"
            watch_last=${'$'}watch_text
          fi
        done
      }
      apt_with_status() { apt-get -o APT::Status-Fd=3 "${'$'}@" 3>>"${'$'}pkg_status"; }
      # pkg_install LOG PACKAGE... installs with the guest's package manager.
      # Callers pass a package list unquoted, so each package is one argument.
      pkg_install() {
        pkg_log=${'$'}1
        shift
        : > "${'$'}pkg_status"
        : > "${'$'}pkg_progress"
        watch_packages &
        pkg_watch_pid=${'$'}!
        if [ "${'$'}horus_pkg" = apt ]; then
          pkg_result=0
          if [ -z "${'$'}(find /var/lib/apt/lists -maxdepth 1 -name '*_Packages*' -mmin -1440 2>/dev/null | head -n 1)" ]; then
            run_logged "${'$'}pkg_log" apt-get update || pkg_result=${'$'}?
          fi
          if [ "${'$'}pkg_result" -eq 0 ]; then
            run_logged "${'$'}pkg_log" apt_with_status install -y --no-install-recommends "${'$'}@" || pkg_result=${'$'}?
          fi
        else
          if run_logged "${'$'}pkg_log" apk add --no-cache --no-progress "${'$'}@"; then pkg_result=0; else pkg_result=${'$'}?; fi
        fi
        kill "${'$'}pkg_watch_pid" 2>/dev/null || true
        wait "${'$'}pkg_watch_pid" 2>/dev/null || true
        : > "${'$'}pkg_progress"
        return "${'$'}pkg_result"
      }
      # Debian's npm (9.2) needs ~400 node-* packages, and its cache moves
      # files with link, then unlink and chmod in parallel, which races
      # under PRoot's link2symlink (npm ERR! ENOENT ... rename). Install
      # npm's own release instead: one pinned tarball, checked against its
      # registry checksum, with its dependencies bundled.
      upstream_npm_dir=/usr/local/lib/node_modules/npm
      has_upstream_npm() {
        [ -x /usr/local/bin/npm ] && grep -q '"version": "$UPSTREAM_NPM_VERSION"' "${'$'}upstream_npm_dir/package.json" 2>/dev/null
      }
      install_upstream_npm() {
        npm_registry=${'$'}{npm_config_registry:-https://registry.npmjs.org}
        npm_registry=${'$'}{npm_registry%/}
        npm_work=${'$'}(mktemp -d /root/.cache/horus/npm.XXXXXX) || return 1
        if node -e '
          const [url, integrity, out] = process.argv.slice(1);
          (async () => {
            const response = await fetch(url, {signal: AbortSignal.timeout(300000)});
            if (!response.ok) throw new Error("npm download failed: HTTP " + response.status);
            const body = Buffer.from(await response.arrayBuffer());
            const digest = "sha512-" + require("crypto").createHash("sha512").update(body).digest("base64");
            if (digest !== integrity) throw new Error("npm download failed its checksum");
            require("fs").writeFileSync(out, body);
          })().catch(error => { console.error(error.message); process.exit(1); });
        ' "${'$'}npm_registry/npm/-/npm-$UPSTREAM_NPM_VERSION.tgz" "$UPSTREAM_NPM_INTEGRITY" "${'$'}npm_work/npm.tgz" &&
          mkdir -p "${'$'}npm_work/npm" /usr/local/lib/node_modules /usr/local/bin &&
          tar -xzf "${'$'}npm_work/npm.tgz" -C "${'$'}npm_work/npm" --strip-components=1 &&
          rm -rf "${'$'}upstream_npm_dir" &&
          mv "${'$'}npm_work/npm" "${'$'}upstream_npm_dir" &&
          printf '#!/bin/sh\nexec node %s/bin/npm-cli.js "$@"\n' "${'$'}upstream_npm_dir" > /usr/local/bin/npm &&
          printf '#!/bin/sh\nexec node %s/bin/npx-cli.js "$@"\n' "${'$'}upstream_npm_dir" > /usr/local/bin/npx &&
          chmod 755 /usr/local/bin/npm /usr/local/bin/npx; then
          npm_result=0
        else
          npm_result=1
        fi
        rm -rf "${'$'}npm_work"
        return "${'$'}npm_result"
      }
    """.trimIndent()
    // npm may successfully install the JS wrapper while skipping its optional
    // native package. Install the exact declared alias as a required package
    // on recovery; both Linux layouts contain Alpine-compatible musl binaries.
    internal val CODEX_INSTALL_SCRIPT = """
      codex_entry="${'$'}HOME/.local/lib/node_modules/@openai/codex/bin/codex.js"
      codex_native_path() {
        codex_root=${'$'}{codex_entry%/bin/codex.js}
        codex_platform=${'$'}codex_root
        for candidate in "${'$'}codex_root/node_modules/@openai/codex-linux-arm64" "${'$'}{codex_root%/codex}/codex-linux-arm64"; do
          if [ -f "${'$'}candidate/package.json" ]; then codex_platform=${'$'}candidate; break; fi
        done
        for candidate in "${'$'}codex_platform/vendor/aarch64-unknown-linux-musl/bin/codex" "${'$'}codex_platform/vendor/aarch64-unknown-linux-musl/codex/codex"; do
          if [ -x "${'$'}candidate" ]; then codex_native=${'$'}candidate; return 0; fi
        done
        return 1
      }
      codex_stamp() {
        # File identities cover the wrapper, declared version, selected alias
        # and native payload. Routine SSH checks start neither Node nor Codex.
        codex_native_path || return 1
        codex_stats=${'$'}(stat -L -c '%s:%Y:%i:%d' "${'$'}codex_entry" "${'$'}codex_root/package.json" "${'$'}codex_platform/package.json" "${'$'}codex_native") || return 1
        codex_metadata=${'$'}(sha256sum "${'$'}codex_root/package.json" "${'$'}codex_platform/package.json") || return 1
        printf '%s\n' "${'$'}codex_stats" | tr '\n' ','
        printf '%s\n' "${'$'}codex_metadata" | cut -d ' ' -f 1 | tr '\n' ','
      }
      codex_metadata_valid() {
        codex_native_path || return 1
        /usr/bin/node --input-type=commonjs -e '
          const fs = require("fs");
          const [root, platform, binary] = process.argv.slice(1);
          const wrapper = JSON.parse(fs.readFileSync(root + "/package.json"));
          const native = JSON.parse(fs.readFileSync(platform + "/package.json"));
          const spec = wrapper.optionalDependencies?.["@openai/codex-linux-arm64"];
          if (platform !== root) {
            if (!spec?.startsWith("npm:@openai/codex@") || native.version !== spec.slice("npm:@openai/codex@".length) || native.version !== wrapper.version + "-linux-arm64") process.exit(1);
          } else if (spec || !wrapper.version) process.exit(1);
          const fd = fs.openSync(binary, "r"), h = Buffer.alloc(20);
          const size = fs.readSync(fd, h, 0, 20, 0); fs.closeSync(fd);
          if (size !== 20 || h.readUInt32BE(0) !== 0x7f454c46 || h[4] !== 2 || h[5] !== 1 || h.readUInt16LE(18) !== 183) process.exit(1);
        ' "${'$'}codex_root" "${'$'}codex_platform" "${'$'}codex_native"
      }
      codex_works() {
        timeout -s KILL 20 /usr/bin/node "${'$'}codex_entry" --version
      }
      codex_stamped() {
        codex_identity=${'$'}(codex_stamp) && [ -n "${'$'}codex_identity" ] &&
          [ "${'$'}(cat "${'$'}HOME/.cache/horus/codex.ready" 2>/dev/null)" = "ready:${'$'}codex_identity" ]
      }
      mark_codex_ready() {
        codex_identity=${'$'}(codex_stamp) && [ -n "${'$'}codex_identity" ] || {
          rm -f "${'$'}HOME/.cache/horus/codex.ready"
          return 1
        }
        printf 'ready:%s\n' "${'$'}codex_identity" > "${'$'}HOME/.cache/horus/codex.ready.tmp" &&
          mv -f "${'$'}HOME/.cache/horus/codex.ready.tmp" "${'$'}HOME/.cache/horus/codex.ready"
      }
      restore_codex_package() {
        codex_package="${'$'}HOME/.local/lib/node_modules/@openai/codex"
        codex_previous="${'$'}HOME/.local/lib/node_modules/@openai/.codex.previous"
        if [ ! -e "${'$'}codex_package" ] && [ -d "${'$'}codex_previous" ]; then
          mv "${'$'}codex_previous" "${'$'}codex_package" || return 1
        fi
      }
      stage_codex_package() (
        codex_stage="${'$'}HOME/.local/.codex-stage"
        rm -rf "${'$'}codex_stage" || exit 1
        mkdir -p "${'$'}codex_stage" || exit 1
        trap 'rm -rf "${'$'}codex_stage"' EXIT
        npm install --global --force --prefix "${'$'}codex_stage" --include=optional --no-package-lock --no-audit --no-fund --no-progress --loglevel=error @openai/codex || exit 1
        codex_entry="${'$'}codex_stage/lib/node_modules/@openai/codex/bin/codex.js"
        # Required recovery installs cannot reuse a top-level alias from an
        # earlier wrapper. Move the exact alias inside the staged wrapper.
        if ! codex_metadata_valid; then
          codex_spec=${'$'}(/usr/bin/node --input-type=commonjs -e '
            const p = require(process.argv[1]);
            const spec = p.optionalDependencies && p.optionalDependencies["@openai/codex-linux-arm64"];
            if (!spec || spec !== "npm:@openai/codex@" + p.version + "-linux-arm64") process.exit(1);
            console.log("@openai/codex-linux-arm64@" + spec);
          ' "${'$'}{codex_entry%/bin/codex.js}/package.json") || exit 1
          rm -rf "${'$'}{codex_entry%/bin/codex.js}/node_modules/@openai/codex-linux-arm64" "${'$'}codex_stage/lib/node_modules/@openai/codex-linux-arm64" || exit 1
          npm install --global --force --prefix "${'$'}codex_stage" --include=optional --no-package-lock --no-audit --no-fund --no-progress --loglevel=error "${'$'}codex_spec" || exit 1
        fi
        codex_native_path >/dev/null || exit 1
        if [ "${'$'}codex_platform" = "${'$'}codex_stage/lib/node_modules/@openai/codex-linux-arm64" ]; then
          mkdir -p "${'$'}{codex_entry%/bin/codex.js}/node_modules/@openai" &&
            mv "${'$'}codex_platform" "${'$'}{codex_entry%/bin/codex.js}/node_modules/@openai/codex-linux-arm64" || exit 1
        fi
        chmod u+rx "${'$'}codex_entry" || exit 1
        codex_metadata_valid && codex_works && codex_stamp >/dev/null || exit 1
        codex_package="${'$'}HOME/.local/lib/node_modules/@openai/codex"
        codex_previous="${'$'}HOME/.local/lib/node_modules/@openai/.codex.previous"
        mkdir -p "${'$'}{codex_package%/codex}" || exit 1
        rm -rf "${'$'}codex_previous" || exit 1
        # The durable backup also covers SIGKILL/power loss between renames.
        # Keep it after success; running sessions may still use its payload.
        if [ -e "${'$'}codex_package" ]; then mv "${'$'}codex_package" "${'$'}codex_previous" || exit 1; fi
        trap 'restore_codex_package; rm -rf "${'$'}codex_stage"' EXIT
        trap 'exit 1' HUP INT TERM
        mv "${'$'}{codex_entry%/bin/codex.js}" "${'$'}codex_package" || exit 1
        codex_entry="${'$'}codex_package/bin/codex.js"
        if ! mark_codex_ready; then
          rm -rf "${'$'}codex_package"
          restore_codex_package
          exit 1
        fi
      )
      # The backup outlives its install so sessions started from it keep
      # running. Once a later check finds the new package stamped, drop it.
      prune_codex_backup() (
        exec 8>"${'$'}HOME/.cache/horus/codex-install.lock"
        flock -n 8 || exit 0
        if codex_stamped; then rm -rf "${'$'}HOME/.local/lib/node_modules/@openai/.codex.previous"; fi
      )
      install_codex_package() (
        mkdir -p "${'$'}HOME/.cache/horus" || exit 1
        # App provisioning and SSH share this lock, including crash recovery.
        exec 8>"${'$'}HOME/.cache/horus/codex-install.lock"
        flock 8 || exit 1
        restore_codex_package || exit 1
        if codex_stamped; then exit 0; fi
        if codex_metadata_valid; then
          codex_status=0
          codex_works || codex_status=${'$'}?
          if [ "${'$'}codex_status" = 0 ]; then mark_codex_ready; exit ${'$'}?; fi
          case "${'$'}codex_status" in
            124|137)
              printf '%s\n' 'Codex readiness check timed out; the installed package was preserved. Retry when the device is less busy.' >&2
              exit "${'$'}codex_status" ;;
          esac
        fi
        if ! stage_codex_package; then
          printf '%s\n' 'Codex recovery failed; the previous package was preserved. Check connectivity/free storage and retry.' >&2
          exit 1
        fi
      )
    """.trimIndent()
    // npm can skip the optional package that carries OpenCode's native binary,
    // and the wrapper's postinstall fetches that binary separately, so an
    // install can succeed while `bin/opencode.exe` is missing or is not a
    // runnable Linux binary. Install the exact platform package the wrapper
    // declares and place its binary where the launcher and the Kotlin
    // readiness check expect it. The package is staged and only promoted once
    // its payload runs, so a failed attempt never removes a working install.
    internal val OPENCODE_INSTALL_SCRIPT = """
      opencode_package="${'$'}HOME/.local/lib/node_modules/opencode-ai"
      opencode_entry="${'$'}opencode_package/bin/opencode"
      opencode_previous="${'$'}HOME/.local/lib/node_modules/.opencode.previous"
      opencode_ready="${'$'}HOME/.cache/horus/opencode.ready"

      opencode_entry_path() {
        if [ -f "${'$'}opencode_entry" ]; then printf '%s\n' "${'$'}opencode_entry"
        elif [ -f "${'$'}opencode_entry.exe" ]; then printf '%s\n' "${'$'}opencode_entry.exe"
        fi
      }
      opencode_is_elf() {
        [ -f "${'$'}1" ] &&
          [ "${'$'}(head -c 4 "${'$'}1" 2>/dev/null | od -An -tx1 | tr -d ' \n')" = "7f454c46" ]
      }
      # Validate a package root without assuming it already lives in ${'$'}HOME.
      opencode_works_at() {
        opencode_probe="${'$'}1/bin/opencode"
        [ -f "${'$'}opencode_probe" ] || opencode_probe="${'$'}1/bin/opencode.exe"
        [ -f "${'$'}opencode_probe" ] || return 1
        opencode_is_elf "${'$'}opencode_probe" || return 1
        timeout -s KILL 20 "${'$'}opencode_probe" --version >/dev/null 2>&1
      }
      opencode_stamp() {
        opencode_stamped_entry=${'$'}(opencode_entry_path) || return 1
        opencode_is_elf "${'$'}opencode_stamped_entry" || return 1
        stat -L -c '%s:%Y:%i:%d' "${'$'}opencode_stamped_entry" || return 1
      }
      opencode_stamped() {
        opencode_identity=${'$'}(opencode_stamp) && [ -n "${'$'}opencode_identity" ] &&
          [ "${'$'}(cat "${'$'}opencode_ready" 2>/dev/null)" = "ready:${'$'}opencode_identity" ]
      }
      mark_opencode_ready() {
        opencode_identity=${'$'}(opencode_stamp) && [ -n "${'$'}opencode_identity" ] || {
          rm -f "${'$'}opencode_ready"
          return 1
        }
        printf 'ready:%s\n' "${'$'}opencode_identity" > "${'$'}opencode_ready.tmp" &&
          mv -f "${'$'}opencode_ready.tmp" "${'$'}opencode_ready"
      }
      restore_opencode_package() {
        if [ ! -e "${'$'}opencode_package" ] && [ -d "${'$'}opencode_previous" ]; then
          mv "${'$'}opencode_previous" "${'$'}opencode_package" || return 1
        fi
      }
      opencode_platform_name() {
        opencode_arch=${'$'}(uname -m)
        case "${'$'}opencode_arch" in
          aarch64|arm64) opencode_arch=arm64 ;;
          x86_64|amd64) opencode_arch=x64 ;;
        esac
        opencode_libc=
        if [ -e /lib/ld-musl-aarch64.so.1 ] || [ -e /lib/ld-musl-x86_64.so.1 ] ||
          [ -f /etc/alpine-release ]; then
          opencode_libc=-musl
        fi
        printf 'opencode-linux-%s%s\n' "${'$'}opencode_arch" "${'$'}opencode_libc"
      }
      opencode_platform_spec() {
        node --input-type=commonjs -e '
          const fs = require("fs");
          const wrapper = JSON.parse(fs.readFileSync(process.argv[1] + "/package.json"));
          const version = (wrapper.optionalDependencies || {})[process.argv[2]];
          if (version) console.log(process.argv[2] + "@" + version);
        ' "${'$'}1" "${'$'}2" 2>/dev/null
      }
      # Install the platform package the wrapper declares and copy its native
      # binary into the wrapper layout the launcher expects.
      recover_opencode_platform() {
        opencode_recover_root="${'$'}1"
        opencode_recover_wrapper="${'$'}opencode_recover_root/lib/node_modules/opencode-ai"
        opencode_platform=${'$'}(opencode_platform_name)
        opencode_spec=${'$'}(opencode_platform_spec "${'$'}opencode_recover_wrapper" "${'$'}opencode_platform") || true
        if [ -z "${'$'}opencode_spec" ]; then
          case "${'$'}opencode_platform" in
            *-musl)
              opencode_platform="${'$'}{opencode_platform%-musl}"
              opencode_spec=${'$'}(opencode_platform_spec "${'$'}opencode_recover_wrapper" "${'$'}opencode_platform") || true
              ;;
          esac
        fi
        [ -n "${'$'}opencode_spec" ] || return 1
        opencode_platform_dir="${'$'}opencode_recover_root/lib/node_modules/${'$'}{opencode_spec%@*}"
        if [ ! -f "${'$'}opencode_platform_dir/package.json" ]; then
          npm install --global --force --prefix "${'$'}opencode_recover_root" --include=optional \
            --no-package-lock --no-audit --no-fund --no-progress --loglevel=error "${'$'}opencode_spec" || return 1
        fi
        opencode_native=
        for candidate in "${'$'}opencode_platform_dir/bin/opencode" "${'$'}opencode_platform_dir/bin/opencode.exe"; do
          if [ -f "${'$'}candidate" ]; then opencode_native="${'$'}candidate"; break; fi
        done
        [ -n "${'$'}opencode_native" ] || return 1
        mkdir -p "${'$'}opencode_recover_wrapper/bin" || return 1
        # A wrapper that shipped a non-runnable bin/opencode must not shadow it.
        rm -f "${'$'}opencode_recover_wrapper/bin/opencode" "${'$'}opencode_recover_wrapper/bin/opencode.exe" || return 1
        cp "${'$'}opencode_native" "${'$'}opencode_recover_wrapper/bin/opencode.exe" || return 1
        chmod u+rx "${'$'}opencode_recover_wrapper/bin/opencode.exe" || return 1
      }
      stage_opencode_package() (
        opencode_stage="${'$'}HOME/.local/.opencode-stage"
        rm -rf "${'$'}opencode_stage" || exit 1
        mkdir -p "${'$'}opencode_stage" || exit 1
        trap 'rm -rf "${'$'}opencode_stage"' EXIT
        npm install --global --force --prefix "${'$'}opencode_stage" --include=optional \
          --no-package-lock --no-audit --no-fund --no-progress --loglevel=error opencode-ai || exit 1
        opencode_wrapper="${'$'}opencode_stage/lib/node_modules/opencode-ai"
        if ! opencode_works_at "${'$'}opencode_wrapper"; then
          recover_opencode_platform "${'$'}opencode_stage" || exit 1
        fi
        opencode_works_at "${'$'}opencode_wrapper" || exit 1
        mkdir -p "${'$'}{opencode_package%/opencode-ai}" || exit 1
        rm -rf "${'$'}opencode_previous" || exit 1
        # Keep the durable backup after success: running sessions may still use it.
        if [ -e "${'$'}opencode_package" ]; then mv "${'$'}opencode_package" "${'$'}opencode_previous" || exit 1; fi
        trap 'restore_opencode_package; rm -rf "${'$'}opencode_stage"' EXIT
        trap 'exit 1' HUP INT TERM
        mv "${'$'}opencode_wrapper" "${'$'}opencode_package" || exit 1
        if ! mark_opencode_ready; then
          rm -rf "${'$'}opencode_package"
          restore_opencode_package
          exit 1
        fi
      )
      prune_opencode_backup() (
        exec 8>"${'$'}HOME/.cache/horus/opencode-install.lock"
        flock -n 8 || exit 0
        if opencode_stamped; then rm -rf "${'$'}opencode_previous"; fi
      )
      install_opencode_package() (
        mkdir -p "${'$'}HOME/.cache/horus" || exit 1
        # App provisioning and SSH share this lock, including crash recovery.
        exec 8>"${'$'}HOME/.cache/horus/opencode-install.lock"
        flock 8 || exit 1
        restore_opencode_package || exit 1
        if opencode_stamped; then exit 0; fi
        # A healthy payload without its marker is restamped offline, not removed.
        if opencode_works_at "${'$'}opencode_package"; then
          mark_opencode_ready || exit 1
          exit 0
        fi
        if ! stage_opencode_package; then
          printf '%s\n' 'OpenCode recovery failed; the previous package was preserved. Check connectivity/free storage and retry.' >&2
          exit 1
        fi
      )
    """.trimIndent()
    private val TOOLCHAIN_PROVISION_SCRIPT = """
      set -eu
      export HOME=/root
      export PATH=/root/.local/bin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
      target=${'$'}{HORUS_TOOLCHAIN_TARGET:-shell}
      case "${'$'}target" in
        shell|github|claude|codex|opencode) ;;
        *) exit 20 ;;
      esac
      printf '%s\n' "HORUS_INSTALL_TARGET=${'$'}target"
      mkdir -p /root/.local/bin /root/.cache/horus
      $APK_MIRROR_SCRIPT
      $PACKAGE_MANAGER_SCRIPT
      provision_stage=/root/.cache/horus/provision-stage
      mark_provision_stage() {
        printf '%s\n' "${'$'}1" > "${'$'}provision_stage"
        printf '%s\n' "HORUS_INSTALL_STAGE=${'$'}1"
      }
      # Copies a command's output to its log file and the terminal through a
      # pipe, so the last lines (usually the error) reach the screen before
      # the exit. A `tail -f` on the file polls once a second and was killed
      # before it read them, which left failure screens with no log.
      run_logged() {
        log_file=${'$'}1
        shift
        log_pipe=${'$'}log_file.pipe
        rm -f "${'$'}log_pipe"
        if ! mkfifo "${'$'}log_pipe" 2>/dev/null; then
          # No pipe: keep the log file, without live output.
          "${'$'}@" >"${'$'}log_file" 2>&1 &
          command_pid=${'$'}!
          set +e
          wait "${'$'}command_pid"
          command_status=${'$'}?
          set -e
          tail -n 40 "${'$'}log_file" 2>/dev/null || true
        else
          tee "${'$'}log_file" < "${'$'}log_pipe" &
          tee_pid=${'$'}!
          "${'$'}@" >"${'$'}log_pipe" 2>&1 &
          command_pid=${'$'}!
          set +e
          wait "${'$'}command_pid"
          command_status=${'$'}?
          set -e
          # tee ends once every writer closes the pipe. A daemon the command
          # left running may hold it open, so give up on it after 5 seconds.
          ( sleep 5; kill "${'$'}tee_pid" 2>/dev/null ) >/dev/null 2>&1 &
          tee_watchdog=${'$'}!
          wait "${'$'}tee_pid" 2>/dev/null || true
          kill "${'$'}tee_watchdog" 2>/dev/null || true
          wait "${'$'}tee_watchdog" 2>/dev/null || true
          rm -f "${'$'}log_pipe"
        fi
        if [ "${'$'}command_status" -ne 0 ]; then
          printf '%s\n' "HORUS_INSTALL_COMMAND_EXIT=${'$'}command_status"
        fi
        return "${'$'}command_status"
      }
      # The installers download silently (curl -s, npm --no-progress), so
      # report how much has landed in their download directories instead,
      # as a percentage when the expected size is known.
      download_kb() {
        du -sk "${'$'}@" 2>/dev/null | awk '{ total += ${'$'}1 } END { print total + 0 }'
      }
      # Size of the Claude Code binary install.sh is about to fetch, from the
      # same manifest it reads. Prints nothing when the manifest is unreachable.
      claude_download_kb() {
        claude_base=https://downloads.claude.ai/claude-code-releases
        claude_arch=${'$'}(uname -m)
        if [ "${'$'}claude_arch" = aarch64 ]; then claude_arch=arm64; fi
        # install.sh fetches the musl build on Alpine and the glibc one elsewhere.
        claude_libc=
        if [ -e /lib/ld-musl-aarch64.so.1 ] || [ -e /lib/ld-musl-x86_64.so.1 ]; then claude_libc=-musl; fi
        claude_version=${'$'}(curl --connect-timeout 15 --max-time 30 -fsSL "${'$'}claude_base/latest" 2>/dev/null) || return 0
        curl --connect-timeout 15 --max-time 30 -fsSL "${'$'}claude_base/${'$'}claude_version/manifest.json" 2>/dev/null |
          tr -d ' \n' |
          sed -n "s/.*\"linux-${'$'}claude_arch${'$'}claude_libc\":{[^{}]*\"size\":\([0-9]*\).*/\1/p" |
          awk '${'$'}1 > 0 { printf "%d\n", (${'$'}1 + 1023) / 1024 }' || true
      }
      # Compressed size of every tarball `npm install <package>` downloads:
      # the package plus the optional platform packages npm keeps on this
      # os/cpu/libc. Prints nothing when the registry does not say.
      npm_download_kb() {
        node -e '
          const registry = (process.env.npm_config_registry || "https://registry.npmjs.org").replace(/\/+${'$'}/, "");
          const libc = process.report.getReport().header.glibcVersionRuntime ? "glibc" : "musl";
          const fits = (list, value) => !list || list.length === 0 || list.includes(value) ||
            (list.every(entry => entry.startsWith("!")) && !list.includes("!" + value));
          const timeout = () => AbortSignal.timeout(15000);
          const manifest = async (name, version) => {
            const response = await fetch(registry + "/" + name.replace("/", "%2f") + "/" + version, {signal: timeout()});
            if (!response.ok) throw new Error("manifest " + response.status);
            return response.json();
          };
          const tarballBytes = async url => {
            const response = await fetch(url, {headers: {Range: "bytes=0-0"}, signal: timeout()});
            const total = /\/(\d+)${'$'}/.exec(response.headers.get("content-range") || "");
            await response.body?.cancel();
            if (!total) throw new Error("no size for " + url);
            return Number(total[1]);
          };
          (async () => {
            const root = await manifest(process.argv[1], "latest");
            const optional = await Promise.all(Object.entries(root.optionalDependencies || {}).map(([alias, spec]) => {
              if (!spec.startsWith("npm:")) return manifest(alias, spec);
              const at = spec.lastIndexOf("@");
              return manifest(spec.slice(4, at), spec.slice(at + 1));
            }));
            const kept = [root, ...optional.filter(doc =>
              fits(doc.os, process.platform) && fits(doc.cpu, process.arch) && fits(doc.libc, libc))];
            const sizes = await Promise.all(kept.map(doc => tarballBytes(doc.dist.tarball)));
            console.log(Math.ceil(sizes.reduce((sum, size) => sum + size, 0) / 1024));
          })().catch(() => {});
        ' "${'$'}1" 2>/dev/null || true
      }
      watch_download() {
        watch_label=${'$'}1
        watch_total_kb=${'$'}{2:-0}
        shift 2
        watch_base=${'$'}(download_kb "${'$'}@")
        watch_last=0
        while sleep 2; do
          watch_kb=${'$'}(( ${'$'}(download_kb "${'$'}@") - watch_base ))
          if [ "${'$'}watch_total_kb" -gt 0 ]; then
            watch_percent=${'$'}(( watch_kb * 100 / watch_total_kb ))
            if [ "${'$'}watch_percent" -gt 100 ]; then watch_percent=100; fi
            if [ "${'$'}watch_percent" -gt "${'$'}watch_last" ]; then
              printf '%s\n' "Downloading ${'$'}{watch_label}… ${'$'}{watch_percent}% (${'$'}(( watch_kb / 1024 )) of ${'$'}(( watch_total_kb / 1024 )) MB)"
              watch_last=${'$'}watch_percent
            fi
          else
            watch_mb=${'$'}(( watch_kb / 1024 ))
            if [ "${'$'}watch_mb" -gt "${'$'}watch_last" ]; then
              printf '%s\n' "Downloading ${'$'}{watch_label}… ${'$'}{watch_mb} MB"
              watch_last=${'$'}watch_mb
            fi
          fi
        done
      }
      run_downloading() {
        download_label=${'$'}1
        download_total_kb=${'$'}2
        download_dirs=${'$'}3
        shift 3
        # Word splitting on download_dirs is intended: it lists directories.
        watch_download "${'$'}download_label" "${'$'}download_total_kb" ${'$'}download_dirs &
        watch_pid=${'$'}!
        if run_logged "${'$'}@"; then download_status=0; else download_status=${'$'}?; fi
        kill "${'$'}watch_pid" 2>/dev/null || true
        wait "${'$'}watch_pid" 2>/dev/null || true
        return "${'$'}download_status"
      }
      mark_provision_stage start

      if [ "${'$'}target" = shell ]; then
        if ! command -v zsh >/dev/null 2>&1 || ! command -v git >/dev/null 2>&1 ||
          [ ! -x /usr/bin/rg ] || [ ! -x /usr/bin/curl ] || [ ! -x /usr/bin/jq ] ||
          [ ! -x /usr/bin/python3 ] || [ ! -e /lib/ld-linux-aarch64.so.1 ] ||
          [ ! -x /usr/bin/less ] || [ ! -e /usr/share/zoneinfo/UTC ] || ! has_python_venv; then
          mark_provision_stage apk
          if ! pkg_install /root/.cache/horus/alpine-bootstrap.log ${'$'}pkgs_shell; then
            # PRoot cannot create zsh's versioned hardlink (bin/zsh-5.9) on
            # this Android filesystem. Continue only when both the shell and
            # base utilities extracted successfully for visible clone sessions.
            if ! command -v zsh >/dev/null 2>&1 || ! zsh -fc ':' >/dev/null 2>&1 || ! command -v git >/dev/null 2>&1 || ! git --version >/dev/null 2>&1 ||
              ! /usr/bin/rg --version >/dev/null 2>&1 || ! /usr/bin/curl --version >/dev/null 2>&1 ||
              ! /usr/bin/jq --version >/dev/null 2>&1 || ! /usr/bin/python3 --version >/dev/null 2>&1 ||
              [ ! -e /lib/ld-linux-aarch64.so.1 ]; then
              mark_provision_stage apk_failed
              exit 21
            fi
          fi
        fi
      elif [ "${'$'}target" = github ]; then
        if ! command -v zsh >/dev/null 2>&1 || ! command -v gh >/dev/null 2>&1 || [ ! -f /root/.cache/horus/github.ready ]; then
          mark_provision_stage github
          if ! pkg_install /root/.cache/horus/github-install.log ${'$'}pkgs_github; then
            if ! command -v zsh >/dev/null 2>&1 || ! zsh -fc ':' >/dev/null 2>&1 || ! command -v gh >/dev/null 2>&1 || ! gh --version >/dev/null 2>&1; then
              mark_provision_stage github_failed
              exit 26
            fi
          fi
        fi
      elif [ "${'$'}target" = claude ]; then
        if ! command -v zsh >/dev/null 2>&1 || ! command -v bash >/dev/null 2>&1 || ! command -v curl >/dev/null 2>&1 || [ ! -f /root/.cache/horus/claude.ready ]; then
          mark_provision_stage apk
          if ! pkg_install /root/.cache/horus/alpine-bootstrap.log ${'$'}pkgs_claude; then
            if ! command -v zsh >/dev/null 2>&1 || ! zsh -fc ':' >/dev/null 2>&1 || ! command -v bash >/dev/null 2>&1 || ! command -v curl >/dev/null 2>&1; then
              mark_provision_stage apk_failed
              exit 21
            fi
          fi
        fi
      else
        # Debian's npm is installed separately below, so only apk's counts here.
        if ! command -v zsh >/dev/null 2>&1 || ! command -v node >/dev/null 2>&1 || { [ "${'$'}horus_pkg" = apk ] && ! command -v npm >/dev/null 2>&1; }; then
          mark_provision_stage apk
          if ! pkg_install /root/.cache/horus/alpine-bootstrap.log ${'$'}pkgs_node; then
            if ! command -v zsh >/dev/null 2>&1 || ! zsh -fc ':' >/dev/null 2>&1 || ! command -v node >/dev/null 2>&1 || { [ "${'$'}horus_pkg" = apk ] && ! command -v npm >/dev/null 2>&1; }; then
              mark_provision_stage apk_failed
              exit 21
            fi
          fi
        fi
        # Also replaces the npm 9 that pre-release Debian builds installed with apt.
        if [ "${'$'}horus_pkg" = apt ] && ! has_upstream_npm; then
          mark_provision_stage apk
          if ! install_upstream_npm >>/root/.cache/horus/alpine-bootstrap.log 2>&1; then
            mark_provision_stage apk_failed
            exit 21
          fi
        fi
      fi
      # Codex's background server reads process start times with
      # `ps -p PID -o lstart=`, which BusyBox ps does not support.
      if [ "${'$'}target" = codex ] && ! ps -p "${'$'}${'$'}" -o lstart= >/dev/null 2>&1; then
        mark_provision_stage apk
        if ! pkg_install /root/.cache/horus/alpine-bootstrap.log ${'$'}pkgs_procps; then
          mark_provision_stage apk_failed
          exit 21
        fi
      fi
      # sudo's root helper is Python. The Bare terminal installs it with its
      # tools; install it with whatever opens first, so sudo works from the
      # first sign-in. A failure leaves sudo unavailable, not the app.
      if [ "${'$'}target" != shell ] && [ ! -x /usr/bin/python3 ]; then
        pkg_install /root/.cache/horus/alpine-bootstrap.log python3 || true
      fi
      mark_provision_stage base_ready

      target_marker=/root/.cache/horus/${'$'}target.ready
      if [ "${'$'}target" = claude ] && { [ ! -f "${'$'}target_marker" ] || [ ! -x /root/.local/bin/claude ]; }; then
        mark_provision_stage claude
        install_claude() {
          rm -f /root/.local/bin/claude /root/.cache/horus/claude-install.sh
          curl --connect-timeout 15 --max-time 300 -fsSL https://claude.ai/install.sh -o /root/.cache/horus/claude-install.sh
          bash /root/.cache/horus/claude-install.sh
          test -x /root/.local/bin/claude
        }
        if ! run_downloading 'Claude Code' "${'$'}(claude_download_kb)" /root/.claude/downloads /root/.cache/horus/claude-install.log install_claude; then
          mark_provision_stage claude_failed
          exit 22
        fi
      fi
      $CODEX_INSTALL_SCRIPT
      if [ "${'$'}target" = codex ] && codex_stamped; then
        prune_codex_backup || true
      elif [ "${'$'}target" = codex ]; then
        mark_provision_stage codex
        if ! run_downloading Codex "${'$'}(npm_download_kb @openai/codex)" /root/.npm /root/.cache/horus/codex-install.log install_codex_package; then
          mark_provision_stage codex_failed
          exit 23
        fi
      fi
      $OPENCODE_INSTALL_SCRIPT
      if [ "${'$'}target" = opencode ] && opencode_stamped; then
        prune_opencode_backup || true
      elif [ "${'$'}target" = opencode ]; then
        mark_provision_stage opencode
        if ! run_downloading OpenCode "${'$'}(npm_download_kb opencode-ai)" /root/.npm /root/.cache/horus/opencode-install.log install_opencode_package; then
          mark_provision_stage opencode_failed
          exit 24
        fi
      fi

      if [ "${'$'}target" != shell ] && [ "${'$'}target" != github ]; then
        # npm's global bin links are not consistent across Alpine/PRoot and
        # published entrypoints can lose their execute bit. Rebuild only the
        # launcher requested by this first app launch.
        mark_provision_stage normalize
        if [ "${'$'}target" = claude ]; then
          if ! {
            claude_launcher=/root/.local/bin/claude
            if [ -L "${'$'}claude_launcher" ]; then
              claude_target="${'$'}(readlink -f "${'$'}claude_launcher" 2>/dev/null || true)"
              case "${'$'}claude_target" in
                /root/*)
                  claude_relative="${'$'}{claude_target#/root/}"
                  rm -f "${'$'}claude_launcher"
                  cat > "${'$'}claude_launcher" <<EOF
#!/bin/sh
exec "\${'$'}HOME/${'$'}claude_relative" "\${'$'}@"
EOF
                  ;;
                /*)
                  rm -f "${'$'}claude_launcher"
                  cat > "${'$'}claude_launcher" <<EOF
#!/bin/sh
exec "${'$'}claude_target" "\${'$'}@"
EOF
                  ;;
                *)
                  exit 25
                  ;;
              esac
            fi
            test ! -L "${'$'}claude_launcher"
            if [ ! -x /root/.local/bin/claude ]; then chmod u+rx /root/.local/bin/claude; fi
            test -x /root/.local/bin/claude
          }; then
            mark_provision_stage normalize_failed
            exit 25
          fi
        elif [ "${'$'}target" = codex ]; then
          if ! {
            codex_entry=/root/.local/lib/node_modules/@openai/codex/bin/codex.js
            if [ ! -x "${'$'}codex_entry" ]; then chmod u+rx "${'$'}codex_entry"; fi
            test "${'$'}(head -n 1 "${'$'}codex_entry" 2>/dev/null || true)" = '#!/usr/bin/env node'
            codex_launcher=/root/.local/bin/codex
            codex_expected="${'$'}(printf '%s\n' '#!/bin/sh' 'exec /usr/bin/node "${'$'}HOME/.local/lib/node_modules/@openai/codex/bin/codex.js" "${'$'}@"')"
            if [ ! -f "${'$'}codex_launcher" ] || [ -L "${'$'}codex_launcher" ] || [ ! -x "${'$'}codex_launcher" ] || [ "${'$'}(cat "${'$'}codex_launcher" 2>/dev/null || true)" != "${'$'}codex_expected" ]; then
              rm -f "${'$'}codex_launcher"
              printf '%s\n' '#!/bin/sh' 'exec /usr/bin/node "${'$'}HOME/.local/lib/node_modules/@openai/codex/bin/codex.js" "${'$'}@"' > "${'$'}codex_launcher"
              chmod u+rx "${'$'}codex_launcher"
            fi
          }; then
            mark_provision_stage normalize_failed
            exit 25
          fi
        else
          if ! {
            opencode_entry=/root/.local/lib/node_modules/opencode-ai/bin/opencode
            if [ ! -f "${'$'}opencode_entry" ]; then
              opencode_entry=/root/.local/lib/node_modules/opencode-ai/bin/opencode.exe
            fi
            test -f "${'$'}opencode_entry"
            test -x "${'$'}opencode_entry"
            opencode_relative="${'$'}{opencode_entry#/root/}"
            opencode_launcher=/root/.local/bin/opencode
            opencode_expected=${'$'}(printf '#!/bin/sh\nexec "${'$'}HOME/%s" "${'$'}@"\n' "${'$'}opencode_relative")
            if [ ! -f "${'$'}opencode_launcher" ] || [ -L "${'$'}opencode_launcher" ] || [ ! -x "${'$'}opencode_launcher" ] || [ "${'$'}(cat "${'$'}opencode_launcher" 2>/dev/null || true)" != "${'$'}opencode_expected" ]; then
              rm -f "${'$'}opencode_launcher"
              # OpenCode's postinstall selects a native Linux binary named
              # opencode.exe on Alpine; it must not be passed to Node.
              cat > "${'$'}opencode_launcher" <<EOF
#!/bin/sh
exec "\${'$'}HOME/${'$'}opencode_relative" "\${'$'}@"
EOF
              chmod u+rx "${'$'}opencode_launcher"
            fi
          }; then
            mark_provision_stage normalize_failed
            exit 25
          fi
        fi
      fi

      mark_provision_stage verify
      if [ "${'$'}target" = shell ]; then
        test -x /bin/zsh || test -x /usr/bin/zsh
        git --version >/dev/null 2>&1
        /usr/bin/rg --version >/dev/null 2>&1
        /usr/bin/curl --version >/dev/null 2>&1
        /usr/bin/jq --version >/dev/null 2>&1
        /usr/bin/python3 --version >/dev/null 2>&1
        test -e /lib/ld-linux-aarch64.so.1
      elif [ "${'$'}target" = github ]; then
        test -x /bin/gh || test -x /usr/bin/gh
      elif [ "${'$'}target" = claude ]; then
        test -x /root/.local/bin/claude
      elif [ "${'$'}target" = codex ]; then
        if ! codex_stamped; then
          mark_provision_stage codex_failed
          exit 23
        fi
      else
        if ! opencode_stamped; then
          mark_provision_stage opencode_failed
          exit 24
        fi
        test -x /root/.local/bin/opencode
      fi
      if [ "${'$'}target" = opencode ]; then
        : # install_opencode_package already recorded validated readiness atomically.
      elif [ "${'$'}target" = codex ]; then
        : # install_codex_package already recorded validated readiness atomically.
      elif [ "${'$'}target" != shell ]; then
        printf '%s\n' ready > "${'$'}target_marker"
      fi
      mark_provision_stage ready
      printf '%s\n\033[1A\033[2K' HORUS_TOOLCHAIN_READY
    """.trimIndent()
    private val TOOLCHAIN_SESSION_SCRIPT = """
      set -eu
      session_username="${'$'}1"
      session_uid="${'$'}2"
      session_gid="${'$'}3"
      session_command="${'$'}4"
      session_pwd="${'$'}5"
      export HORUS_GUEST_USERNAME="${'$'}session_username"
      export HORUS_SESSION_COMMAND="${'$'}session_command"
      export HORUS_SESSION_PWD="${'$'}session_pwd"
      if [ "${'$'}{HORUS_SKIP_TOOLCHAIN_PROVISION:-0}" != 1 ]; then
        $TOOLCHAIN_PROVISION_SCRIPT
      else
        $APK_MIRROR_SCRIPT
        printf '%s\n\033[1A\033[2K' HORUS_TOOLCHAIN_READY
      fi
      # The install log has done its job; start the app on a clean screen
      # with no scrollback, as apps like Claude Code draw inline below it.
      printf '\033[H\033[2J\033[3J'
      # Markers stay exact output lines for the app's scanners; the trailing
      # cursor-up + erase-line keeps them off the user's screen.
      printf '%s\n\033[1A\033[2K' "HORUS_INSTALL_HANDOFF=${'$'}session_username"
      if [ "${'$'}session_username" = root ]; then
        exec /bin/sh -c "${'$'}session_command"
      fi
      chown "${'$'}session_uid:${'$'}session_gid" /root "/home/${'$'}session_username"
      chmod 700 /root "/home/${'$'}session_username"
      if [ -d /workspace ]; then
        chgrp "${'$'}session_gid" /workspace
        chmod 2770 /workspace
      fi
      # sudo cannot become root under PRoot once the session drops to its
      # user; a helper started now, while still root, runs its commands.
      if [ "${'$'}{HORUS_ROOT_HELPER:-0}" = 1 ] && [ -x /usr/bin/python3 ] && [ -f $GUEST_ROOT_HELPER_PATH ]; then
        mkdir -p /run/horus-sudo
        export HORUS_SUDO_SOCKET="/run/horus-sudo/s${'$'}${'$'}"
        /usr/bin/python3 $GUEST_ROOT_HELPER_PATH "${'$'}HORUS_SUDO_SOCKET" "${'$'}session_uid" </dev/null >/dev/null 2>>/run/horus-sudo/helper.log &
      fi
      unset HORUS_ROOT_HELPER
      user_script='
        cd "${'$'}HORUS_SESSION_PWD"
        umask 0002
        export HOME="/home/${'$'}HORUS_GUEST_USERNAME"
        export PWD="${'$'}HORUS_SESSION_PWD"
        export PATH="/home/${'$'}HORUS_GUEST_USERNAME/.local/bin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        export XDG_CONFIG_HOME="${'$'}HOME/.config"
        export XDG_DATA_HOME="${'$'}HOME/.local/share"
        export XDG_CACHE_HOME="${'$'}HOME/.cache"
        export USER="${'$'}HORUS_GUEST_USERNAME" LOGNAME="${'$'}HORUS_GUEST_USERNAME"
        if [ "${'$'}{HORUS_TOOLCHAIN_TARGET:-}" = codex ]; then
          export NPM_CONFIG_PREFIX="${'$'}HOME/.local"
          export npm_config_prefix="${'$'}HOME/.local"
        fi
        unset HORUS_BOOTSTRAP
        exec /bin/sh -c "${'$'}HORUS_SESSION_COMMAND"
      '
      if [ -x /bin/busybox ]; then
        exec /bin/busybox su -p -c "${'$'}user_script" "${'$'}session_username"
      fi
      # Debian has no BusyBox; util-linux setpriv changes ids without PAM,
      # which PRoot's emulated root cannot satisfy.
      exec setpriv --reuid="${'$'}session_uid" --regid="${'$'}session_gid" --clear-groups -- /bin/sh -c "${'$'}user_script"
    """.trimIndent()
    // The embedded Codex script supplies its own indent, including its first
    // line, so the outer trimIndent() keeps heredoc terminators at column zero.
    private val REMOTE_ACCESS_SCRIPT = """
      set -eu
      user="${'$'}1"
      uid="${'$'}2"
      gid="${'$'}3"
      port="${'$'}4"
      home="/home/${'$'}user"
      export HOME=/root
      export PATH=/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
      stage() { printf 'HORUS_REMOTE_STAGE=%s\n' "${'$'}1"; }
      mkdir -p /root/.cache/horus
      if [ ! -x /usr/sbin/dropbear ] || [ ! -x /usr/bin/dropbearkey ] ||
        { [ ! -x /usr/lib/ssh/sftp-server ] && [ ! -x /usr/lib/openssh/sftp-server ]; } ||
        [ ! -x /usr/bin/scp ] || [ ! -x /usr/bin/rsync ] || ! command -v zsh >/dev/null 2>&1 ||
        [ ! -x /usr/bin/python3 ]; then
        stage installing
        if [ -x /usr/bin/apt-get ]; then
          mkdir -p /etc/apt/apt.conf.d
          [ -f /etc/apt/apt.conf.d/99horus ] ||
            printf '%s\n' 'APT::Sandbox::User "root";' 'Acquire::Retries "3";' 'Dpkg::Use-Pty "0";' 'APT::Install-Recommends "0";' 'APT::Install-Suggests "0";' > /etc/apt/apt.conf.d/99horus
          remote_install() {
            DEBIAN_FRONTEND=noninteractive apt-get update &&
              DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends dropbear-bin openssh-client openssh-sftp-server python3 rsync zsh
          }
        else
          # python3 runs sudo's root helper for SSH logins.
          remote_install() { apk add --no-cache --no-progress dropbear openssh-client-default openssh-sftp-server python3 rsync zsh; }
        fi
        if ! remote_install; then
          if [ ! -x /usr/sbin/dropbear ] || [ ! -x /usr/bin/dropbearkey ]; then
            stage install_failed
            exit 30
          fi
        fi
      fi
      if [ ! -e /usr/lib/ssh/sftp-server ] && [ -x /usr/lib/openssh/sftp-server ]; then
        mkdir -p /usr/lib/ssh
        ln -s /usr/lib/openssh/sftp-server /usr/lib/ssh/sftp-server
      fi
      # The app binds the phone's one host key here; without it, 1.0.4's
      # per-home key.
      keydir="${'$'}{5:-${'$'}home/.config/horus}"
      hostkey="${'$'}keydir/ssh_host_ed25519_key"
      mkdir -p "${'$'}keydir" "${'$'}home/.ssh" "${'$'}home/.cache/horus"
      if [ ! -s "${'$'}hostkey" ]; then
        rm -f "${'$'}hostkey" "${'$'}hostkey.new"
        # dropbearkey publishes with link(2), which PRoot turns into a
        # symlink chain. Copy the key in so the app finds a plain file.
        if ! dropbearkey -t ed25519 -f "${'$'}hostkey.new" >/dev/null 2>&1 ||
          ! cat "${'$'}hostkey.new" > "${'$'}hostkey.tmp" || ! mv -f "${'$'}hostkey.tmp" "${'$'}hostkey"; then
          rm -f "${'$'}hostkey.new" "${'$'}hostkey.tmp"
          stage hostkey_failed
          exit 31
        fi
        rm -f "${'$'}hostkey.new" "${'$'}hostkey.new.pub"
      fi
      touch "${'$'}home/.ssh/authorized_keys"
      chown "${'$'}uid:${'$'}gid" "${'$'}home" "${'$'}home/.ssh" "${'$'}home/.ssh/authorized_keys" "${'$'}keydir" "${'$'}hostkey" 2>/dev/null || true
      # dropbear refuses keys in group- or world-writable directories.
      chmod 700 "${'$'}home" "${'$'}home/.ssh"
      chmod 600 "${'$'}home/.ssh/authorized_keys" "${'$'}hostkey"
      if [ -d /workspace ]; then
        chgrp "${'$'}gid" /workspace 2>/dev/null || true
        chmod 2770 /workspace 2>/dev/null || true
      fi
      # dropbear hard-codes PATH=/usr/bin:/bin for commands. Give SSH logins
      # a shell that sets the full PATH (agents live in ~/.local/bin) and
      # starts zsh for interactive logins.
      login_shell=/usr/local/bin/horus-ssh-shell
      mkdir -p /usr/local/bin
      cat > "${'$'}login_shell.tmp" <<'HORUS_SHELL'
      #!/bin/sh
      export PATH="${'$'}HOME/.local/bin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
      export LANG="${'$'}{LANG:-C.UTF-8}"
${DOTNET_ENV_SCRIPT.prependIndent("      ")}
      # A script loses dropbear's "-" login marker in ${'$'}0; an interactive
      # login is the call without arguments (commands arrive as -c ...).
      if [ "${'$'}#" -eq 0 ]; then
        # The host key no longer changes with the user, so say who this is.
        horus_os=${'$'}(. /etc/os-release 2>/dev/null && printf '%s' "${'$'}PRETTY_NAME")
        printf 'Horus: %s on %s\n' "${'$'}(id -un)" "${'$'}{horus_os:-Linux}" >&2
        if command -v zsh >/dev/null 2>&1; then exec zsh -l; fi
        exec /bin/sh -l
      fi
      exec /bin/sh "${'$'}@"
      HORUS_SHELL
      chmod 755 "${'$'}login_shell.tmp"
      mv -f "${'$'}login_shell.tmp" "${'$'}login_shell"
      grep -qx "${'$'}login_shell" /etc/shells 2>/dev/null || echo "${'$'}login_shell" >> /etc/shells
      sed -i "s#^\(${'$'}user:[^:]*:${'$'}uid:${'$'}gid:[^:]*:[^:]*:\).*#\1${'$'}login_shell#" /etc/passwd
      # The agents install on first use, like the app's tiles do. Stubs in
      # /usr/local/bin install the real launcher into ~/.local/bin, which
      # comes first on PATH, then hand over to it.
      agent_installer=/usr/local/lib/horus/install-agent
      mkdir -p /usr/local/lib/horus
      cat > "${'$'}agent_installer.tmp" <<'HORUS_INSTALLER'
      #!/bin/sh
      set -u
      tool="${'$'}1"
      shift
      launcher="${'$'}HOME/.local/bin/${'$'}tool"
      cache="${'$'}HOME/.cache/horus"
      case "${'$'}tool" in
        claude) name="Claude Code" ;;
        codex) name="Codex" ;;
        opencode) name="OpenCode" ;;
        *) printf 'horus: unknown agent %s\n' "${'$'}tool" >&2; exit 2 ;;
      esac
      # Prefer the app's own install, run in its harness home, so SSH shares
      # the app's login, settings, and trusted folders.
      app_home=${'$'}(sed -n "s#^${'$'}tool=##p" $GUEST_AGENT_HOMES_PATH 2>/dev/null)
      if [ -n "${'$'}app_home" ] && [ -x "${'$'}app_home/.local/bin/${'$'}tool" ]; then
        export HOME="${'$'}app_home" XDG_CONFIG_HOME="${'$'}app_home/.config" XDG_DATA_HOME="${'$'}app_home/.local/share" XDG_CACHE_HOME="${'$'}app_home/.cache"
        export PATH="${'$'}app_home/.local/bin:${'$'}PATH"
        if [ "${'$'}tool" = codex ]; then export NPM_CONFIG_PREFIX="${'$'}app_home/.local" npm_config_prefix="${'$'}app_home/.local"; fi
        exec "${'$'}app_home/.local/bin/${'$'}tool" "${'$'}@"
      fi
      # On Debian the user cannot run apt; Horus's app tiles install the
      # system packages as root, and these need only what is already there.
      system_packages() {
        if command -v apk >/dev/null 2>&1; then apk add --no-cache --no-progress "${'$'}@"; else true; fi
      }
      install_claude() {
        system_packages bash curl ca-certificates git libgcc libstdc++ &&
          curl --connect-timeout 15 --max-time 300 -fsSL https://claude.ai/install.sh -o "${'$'}cache/claude-install.sh" &&
          bash "${'$'}cache/claude-install.sh"
      }
      # Debian's Node.js comes from the app tile's root provisioning. Say so
      # rather than blaming the connection.
      node_packages() {
        if test -x /usr/bin/node && command -v npm >/dev/null 2>&1; then return 0; fi
        system_packages bash ca-certificates git nodejs npm libgcc libstdc++ || return 1
        if test -x /usr/bin/node && command -v npm >/dev/null 2>&1; then return 0; fi
        printf '%s needs Node.js, which an SSH login cannot install here. Open %s once in the Horus app, then try again.\n' "${'$'}name" "${'$'}name" >&2
        exit 1
      }
      # npm's bin links lose their execute bit under PRoot; launch the entry
      # points through small scripts instead, as the app does.
      write_launcher() {
        rm -f "${'$'}launcher" &&
          printf '#!/bin/sh\nexec %s "%s" "$@"\n' "${'$'}1" "${'$'}2" > "${'$'}launcher" &&
          chmod 755 "${'$'}launcher"
      }
${CODEX_INSTALL_SCRIPT.prependIndent("      ")}
${OPENCODE_INSTALL_SCRIPT.prependIndent("      ")}
      install_codex() {
        node_packages && install_codex_package && write_launcher /usr/bin/node '${'$'}HOME/.local/lib/node_modules/@openai/codex/bin/codex.js'
      }
      install_opencode() {
        node_packages || return 1
        install_opencode_package || return 1
        # The package may pick a native binary named opencode.exe (it does on Alpine).
        entry="${'$'}HOME/.local/lib/node_modules/opencode-ai/bin/opencode"
        [ -f "${'$'}entry" ] || entry="${'$'}entry.exe"
        test -f "${'$'}entry" && chmod u+rx "${'$'}entry" && write_launcher '' "${'$'}entry"
      }
      agent_ready() {
        test -x "${'$'}launcher" || return 1
        case "${'$'}tool" in
          codex) codex_stamped ;;
          opencode) opencode_stamped ;;
          *) true ;;
        esac
      }
      if ! agent_ready; then
        mkdir -p "${'$'}HOME/.local/bin" "${'$'}cache"
        # Two sessions typing the same command install it once.
        exec 9>"${'$'}cache/${'$'}tool-launch.lock"
        flock 9
        if ! agent_ready; then
          printf '%s is not installed yet. Installing it now (first run only, needs internet)...\n' "${'$'}name" >&2
          if ! "install_${'$'}tool" >&2 || [ ! -x "${'$'}launcher" ]; then
            printf 'Could not install %s. Check the internet connection and try again.\n' "${'$'}name" >&2
            exit 127
          fi
          printf '%s is installed.\n' "${'$'}name" >&2
        fi
        exec 9>&-
      fi
      exec "${'$'}launcher" "${'$'}@"
      HORUS_INSTALLER
      chmod 755 "${'$'}agent_installer.tmp"
      mv -f "${'$'}agent_installer.tmp" "${'$'}agent_installer"
      for agent in claude codex opencode; do
        stub="/usr/local/bin/${'$'}agent"
        # Never replace a real install someone put here (npm -g does).
        if [ -e "${'$'}stub" ] && ! grep -q '^# horus-agent-stub$' "${'$'}stub" 2>/dev/null; then continue; fi
        printf '#!/bin/sh\n# horus-agent-stub\nexec %s %s "$@"\n' "${'$'}agent_installer" "${'$'}agent" > "${'$'}stub.tmp"
        chmod 755 "${'$'}stub.tmp"
        mv -f "${'$'}stub.tmp" "${'$'}stub"
      done
      # Claude turns cross-session messaging off (with a warning) when it
      # cannot find a uid map, which Android kernels lack; the app passes an
      # explicit socket. Do the same for interactive SSH logins only.
      mkdir -p /etc/zsh/zshrc.d
      cat > /etc/zsh/zshrc.d/horus-ssh.zsh.tmp <<'HORUS_ZSH'
${ZSH_EXIT_SCRIPT.prependIndent("      ")}
      if [[ -n "${'$'}{SSH_CONNECTION-}" ]]; then
        claude() {
          case " ${'$'}* " in
            *" --messaging-socket-path"*) command claude "${'$'}@" ;;
            *) command claude --messaging-socket-path "/tmp/claude-messaging-${'$'}(id -u)/${'$'}${'$'}-${'$'}RANDOM.sock" "${'$'}@" ;;
          esac
        }
      fi
      HORUS_ZSH
      mv -f /etc/zsh/zshrc.d/horus-ssh.zsh.tmp /etc/zsh/zshrc.d/horus-ssh.zsh
      # Debian's zsh reads only /etc/zsh/zshrc, not zshrc.d; load it there.
      if [ -x /usr/bin/apt-get ] && [ -f /etc/zsh/zshrc ] && ! grep -q 'horus-zshrc.d' /etc/zsh/zshrc; then
        printf '%s\n' '# horus-zshrc.d: load the snippets Horus keeps in /etc/zsh/zshrc.d' \
          'for horus_rc in /etc/zsh/zshrc.d/*.zsh(N); do source "${'$'}horus_rc"; done; unset horus_rc' >> /etc/zsh/zshrc
      fi
      # sudo for SSH logins, as for the app's own terminal (see sudo-helper).
      if [ -x /usr/bin/python3 ] && [ -f $GUEST_ROOT_HELPER_PATH ]; then
        mkdir -p /run/horus-sudo
        /usr/bin/python3 $GUEST_ROOT_HELPER_PATH /run/horus-sudo/ssh "${'$'}uid" </dev/null >/dev/null 2>>/run/horus-sudo/helper.log &
      fi
      stage serving
      # Everything below execs in place, so this pid becomes dropbear's; the
      # app kills it and its sessions on stop (PRoot does not take them down).
      printf 'HORUS_REMOTE_PID=%s\n' "${'$'}${'$'}"
      export HORUS_REMOTE_PORT="${'$'}port" HORUS_REMOTE_HOSTKEY="${'$'}hostkey"
      # dropbear runs as the profile user, so that is the only account it can
      # log in. -s/-w: keys only, never root. Bound to loopback, so computers
      # reach it only through adb forward.
      # dropbear re-execs itself per connection with fexecve(), which PRoot
      # cannot follow. It skips that when it cannot open argv[0], so start it
      # by bare name from / and let each connection run in the forked child.
      dropbear_script='cd / && PATH=/usr/sbin:/usr/bin:/sbin:/bin exec dropbear -F -E -s -w -K 30 -p "127.0.0.1:${'$'}HORUS_REMOTE_PORT" -r "${'$'}HORUS_REMOTE_HOSTKEY" -P "${'$'}HOME/.cache/horus/dropbear.pid"'
      if [ -x /bin/busybox ]; then
        exec /bin/busybox su -s /bin/sh -c "${'$'}dropbear_script" "${'$'}user"
      fi
      exec setpriv --reuid="${'$'}uid" --regid="${'$'}gid" --clear-groups -- env HOME="${'$'}home" USER="${'$'}user" LOGNAME="${'$'}user" SHELL=/bin/sh /bin/sh -c "${'$'}dropbear_script"
    """.trimIndent()
    private const val RESOLV_CONF_DIRECTORY = "etc"
    private const val RESOLV_CONF_NAME = "resolv.conf"
    private const val MAX_DNS_SERVERS = 4
    private val DEVICE_FILES = listOf("null", "zero", "random", "urandom", "tty")

    /** A real terminal type: several target CLIs need more than "dumb". */
    const val DEFAULT_TERM = "xterm-256color"
    private val USERNAME_PATTERN = Regex("[A-Za-z0-9._-]{1,32}")

    /**
     * BusyBox top's global proc reads; per-process files are never replaced.
     * Fallback values are intentionally synthetic and non-authoritative: they
     * make the applet parse and render live process rows, but do not claim
     * real global CPU, memory, or load metrics.
     */
    /**
     * Stand-ins for global /proc files Android hides from apps. /proc/stat
     * carries every line /proc/stat parsers require, and a real `btime`:
     * tools such as Codex turn a process's start time (in ticks since boot,
     * from /proc/<pid>/stat) into a date with it, and fail without it.
     */
    internal fun procGlobalFiles(bootTimeSeconds: Long): List<Pair<String, String>> = listOf(
      "stat" to """
        cpu 0 0 0 0 0 0 0 0 0 0
        cpu0 0 0 0 0 0 0 0 0 0 0
        intr 0
        ctxt 0
        btime $bootTimeSeconds
        processes 0
        procs_running 1
        procs_blocked 0
        softirq 0 0 0 0 0 0 0 0 0 0 0
      """.trimIndent() + "\n",
      "loadavg" to "0.00 0.00 0.00 0/0 0\n",
    )

    /**
     * Read once per process so every session sees the same value; computing
     * it again could land on the neighbouring second.
     */
    private val DEVICE_BOOT_TIME_SECONDS: Long by lazy {
      runCatching { (System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()) / 1000L }.getOrDefault(0L)
    }
  }
}
