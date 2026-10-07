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
) {

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
    )
    if (provision) return launch
    return launch.copy(environment = launch.environment + "HORUS_SKIP_TOOLCHAIN_PROVISION=1")
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
  ): LaunchSpec {
    require(guestUsername != GUEST_ROOT_USER) { "remote access needs a non-root profile user" }
    require(port in 1025..65535) { "remote access port is invalid" }
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
      ),
      forceRootIdentity = true,
      // Root's own /root stays the rootfs directory, so logins see their
      // home only as /home/<user>.
      bindHomeAtRoot = false,
      // SSH logins allocate their own pseudo-terminals inside the guest.
      extraDeviceBinds = listOf(File(hostDevDir, "ptmx") to "$GUEST_DEV/ptmx", File(hostDevDir, "pts") to "$GUEST_DEV/pts"),
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
    val home = File(rootfsDir, "home/$username")
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
        val migrated = migrateLegacyProfileAccount(passwdLines, groupLines, shadowLines)
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
  }

  private data class GuestAccountLines(
    val passwd: List<String>,
    val group: List<String>,
    val shadow: List<String>,
  )

  private fun isFixedProfileIdentity(username: String, uid: Int, gid: Int): Boolean =
    username == PROFILE_USERNAME && uid == GUEST_UID && gid == GUEST_GID

  /** Renames only the exact account row format written by the former profile flow. */
  private fun migrateLegacyProfileAccount(
    passwdLines: List<String>,
    groupLines: List<String>,
    shadowLines: List<String>,
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
      fields[6] != "/bin/sh" ||
      passwdLines.count { it.substringBefore(':') == legacyUsername } != 1
    ) {
      throw IllegalStateException("legacy profile account does not match the prior format")
    }
    if (passwdLines.any { it.substringBefore(':') == PROFILE_USERNAME && it != uidRows.single() }) {
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
    val profileGroupIndexes = groupLines.indices.filter { groupLines[it].substringBefore(':') == PROFILE_USERNAME }
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
        if (row == index) listOf(PROFILE_USERNAME, groupFields[1], groupFields[2], "").joinToString(":") else line
      }
    } else if (profileGroupIndexes.isNotEmpty()) {
      // A prior interrupted migration may already have renamed the group but
      // not committed the passwd row. Accept only the exact renamed form.
      val profileGroup = profileGroupIndexes.singleOrNull()?.let(groupLines::get)
      if (
        profileGroupIndexes.size != 1 ||
        gidRows.size != 1 ||
        profileGroup != "$PROFILE_USERNAME:x:$GUEST_GID:"
      ) {
        throw IllegalStateException("fixed profile group conflicts with migration")
      }
      migratedGroups = groupLines
    } else {
      if (gidRows.size > 1) throw IllegalStateException("legacy profile group id is ambiguous")
    }

    val legacyShadowIndexes = shadowLines.indices.filter { shadowLines[it].substringBefore(':') == legacyUsername }
    val profileShadowIndexes = shadowLines.indices.filter { shadowLines[it].substringBefore(':') == PROFILE_USERNAME }
    if (legacyShadowIndexes.size > 1 || profileShadowIndexes.size > 1 ||
      (legacyShadowIndexes.isNotEmpty() && profileShadowIndexes.isNotEmpty())
    ) {
      throw IllegalStateException("legacy profile shadow entry is ambiguous")
    }
    val lockedShadowEntry = "$PROFILE_USERNAME:!:19000:0:99999:7:::"
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
        listOf(PROFILE_USERNAME, fields[1], fields[2], fields[3], PROFILE_USERNAME, "/home/$PROFILE_USERNAME", fields[6]).joinToString(":")
      } else {
        line
      }
    }
    return GuestAccountLines(migratedPasswd, migratedGroups, migratedShadow)
  }

  private fun guestHomePathFor(username: String): String = "/home/$username"

  /**
   * Give every interactive guest the same small zsh theme. Alpine's pinned
   * minirootfs does not include zsh, so the profile is ready for the first
   * `apk add zsh` and automatically hands future login shells to zsh once it
   * exists. User content outside these bounded blocks is preserved.
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

  private fun openCodePayloadStamp(guestHomeDir: File): String? {
    val entry = listOf(
      File(guestHomeDir, ".local/lib/node_modules/opencode-ai/bin/opencode"),
      File(guestHomeDir, ".local/lib/node_modules/opencode-ai/bin/opencode.exe"),
    ).firstOrNull { it.isFile && it.canExecute() } ?: return null
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
    if (target == TerminalRuntimeContract.TOOLCHAIN_TARGET_SHELL) return hasAlpineBaseUtilities(rootfsDir)
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
    val rootfsNpm = File(rootfsDir, "usr/bin/npm").let { it.isFile && it.canExecute() }
    val launcher = when (target) {
      TerminalRuntimeContract.TOOLCHAIN_TARGET_CLAUDE -> "claude"
      TerminalRuntimeContract.TOOLCHAIN_TARGET_CODEX -> "codex"
      TerminalRuntimeContract.TOOLCHAIN_TARGET_OPENCODE -> "opencode"
      else -> return false
    }
    val markerReady = if (target == TerminalRuntimeContract.TOOLCHAIN_TARGET_OPENCODE) {
      val stamp = openCodePayloadStamp(guestHomeDir)
      stamp != null && readyMarker.isFile && readyMarker.length() in 1..128 &&
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

  private fun hasAlpineBaseUtilities(rootfsDir: File): Boolean =
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
    private const val PROFILE_USERNAME = TerminalGuestIdentity.PROFILE_USERNAME
    private val GUEST_ACCOUNT_MUTATION_LOCK = Any()
    const val GUEST_PATH = "/root/.local/bin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
    const val GUEST_LANG = "C.UTF-8"
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
    private val ZSHRC_BLOCK = """
      $ZSHRC_BEGIN
      setopt prompt_subst
      setopt transient_rprompt
      umask 0002
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
      - Codex runs in Horus on Android inside an Alpine Linux ARM64 guest through PRoot.
      - Use Alpine's `apk` package manager; do not assume `apt`, `systemd`, or a full GNU userland. Check available commands and options because many utilities may be BusyBox applets.
      - Codex runs as the dedicated non-root `harness_codex` user with its own persistent home; projects are available under `/workspace`.
      - Horus provisions system dependencies as root before launching Codex. If a system package is missing, report it instead of assuming `apk add` will work from the session.
      - Codex's Linux sandbox is unavailable under Android PRoot. Horus launches the CLI with `--sandbox danger-full-access`; do not assume Codex's own sandbox isolates commands from files accessible to this harness user.
      $CODEX_AGENT_INSTRUCTIONS_END
    """.trimIndent()
    private val CLAUDE_AGENT_INSTRUCTIONS_BLOCK = """
      $CLAUDE_AGENT_INSTRUCTIONS_BEGIN
      ## Runtime environment
      - Claude Code runs in Horus on Android inside an Alpine Linux ARM64 guest through PRoot.
      - Use Alpine's `apk` package manager; do not assume `apt`, `systemd`, or a full GNU userland. Check available commands and options because many utilities may be BusyBox applets.
      - Claude Code runs as the dedicated non-root `harness_claude` user with its own persistent home; projects are available under `/workspace`.
      - Horus provisions system dependencies as root before launching Claude Code. If a system package is missing, report it instead of assuming `apk add` will work from the session.
      - Commands run through Android PRoot. Do not assume Linux namespaces or kernel sandboxing isolate them from files accessible to the Horus app; PRoot's emulated user IDs are not a hard security boundary.
      $CLAUDE_AGENT_INSTRUCTIONS_END
    """.trimIndent()
    private val OPENCODE_AGENT_INSTRUCTIONS_BLOCK = """
      $OPENCODE_AGENT_INSTRUCTIONS_BEGIN
      ## Runtime environment
      - OpenCode runs in Horus on Android inside an Alpine Linux ARM64 guest through PRoot.
      - Use Alpine's `apk` package manager; do not assume `apt`, `systemd`, or a full GNU userland. Check available commands and options because many utilities may be BusyBox applets.
      - OpenCode runs as the dedicated non-root `harness_opencode` user with its own persistent home; projects are available under `/workspace`.
      - Horus provisions system dependencies as root before launching OpenCode. If a system package is missing, report it instead of assuming `apk add` will work from the session.
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
      provision_stage=/root/.cache/horus/provision-stage
      mark_provision_stage() {
        printf '%s\n' "${'$'}1" > "${'$'}provision_stage"
        printf '%s\n' "HORUS_INSTALL_STAGE=${'$'}1"
      }
      run_logged() {
        log_file=${'$'}1
        shift
        : > "${'$'}log_file"
        "${'$'}@" >"${'$'}log_file" 2>&1 &
        command_pid=${'$'}!
        tail -n +1 -f "${'$'}log_file" &
        tail_pid=${'$'}!
        set +e
        wait "${'$'}command_pid"
        command_status=${'$'}?
        set -e
        kill "${'$'}tail_pid" 2>/dev/null || true
        wait "${'$'}tail_pid" 2>/dev/null || true
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
        claude_version=${'$'}(curl --connect-timeout 15 --max-time 30 -fsSL "${'$'}claude_base/latest" 2>/dev/null) || return 0
        curl --connect-timeout 15 --max-time 30 -fsSL "${'$'}claude_base/${'$'}claude_version/manifest.json" 2>/dev/null |
          tr -d ' \n' |
          sed -n "s/.*\"linux-${'$'}claude_arch-musl\":{[^{}]*\"size\":\([0-9]*\).*/\1/p" |
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
          [ ! -x /usr/bin/less ] || [ ! -e /usr/share/zoneinfo/UTC ]; then
          mark_provision_stage apk
          if ! run_logged /root/.cache/horus/alpine-bootstrap.log apk add --no-cache --no-progress ca-certificates curl gcompat git jq less openssh-client-default python3 ripgrep tzdata zsh; then
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
          if ! run_logged /root/.cache/horus/github-install.log apk add --no-cache --no-progress ca-certificates git openssh-client-default github-cli zsh; then
            if ! command -v zsh >/dev/null 2>&1 || ! zsh -fc ':' >/dev/null 2>&1 || ! command -v gh >/dev/null 2>&1 || ! gh --version >/dev/null 2>&1; then
              mark_provision_stage github_failed
              exit 26
            fi
          fi
        fi
      elif [ "${'$'}target" = claude ]; then
        if ! command -v zsh >/dev/null 2>&1 || ! command -v bash >/dev/null 2>&1 || ! command -v curl >/dev/null 2>&1 || [ ! -f /root/.cache/horus/claude.ready ]; then
          mark_provision_stage apk
          if ! run_logged /root/.cache/horus/alpine-bootstrap.log apk add --no-cache --no-progress bash curl ca-certificates git openssh-client-default zsh libgcc libstdc++; then
            if ! command -v zsh >/dev/null 2>&1 || ! zsh -fc ':' >/dev/null 2>&1 || ! command -v bash >/dev/null 2>&1 || ! command -v curl >/dev/null 2>&1; then
              mark_provision_stage apk_failed
              exit 21
            fi
          fi
        fi
      elif ! command -v zsh >/dev/null 2>&1 || ! command -v node >/dev/null 2>&1 || ! command -v npm >/dev/null 2>&1; then
        mark_provision_stage apk
        if ! run_logged /root/.cache/horus/alpine-bootstrap.log apk add --no-cache --no-progress bash ca-certificates git openssh-client-default nodejs npm zsh libgcc libstdc++; then
          if ! command -v zsh >/dev/null 2>&1 || ! zsh -fc ':' >/dev/null 2>&1 || ! command -v node >/dev/null 2>&1 || ! command -v npm >/dev/null 2>&1; then
            mark_provision_stage apk_failed
            exit 21
          fi
        fi
      fi
      # Codex's background server reads process start times with
      # `ps -p PID -o lstart=`, which BusyBox ps does not support.
      if [ "${'$'}target" = codex ] && ! ps -p "${'$'}${'$'}" -o lstart= >/dev/null 2>&1; then
        mark_provision_stage apk
        if ! run_logged /root/.cache/horus/alpine-bootstrap.log apk add --no-cache --no-progress procps-ng; then
          mark_provision_stage apk_failed
          exit 21
        fi
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
      codex_entry=/root/.local/lib/node_modules/@openai/codex/bin/codex.js
      if [ "${'$'}target" = codex ] && {
        [ ! -f "${'$'}target_marker" ] ||
        [ ! -f "${'$'}codex_entry" ] ||
        [ "${'$'}(head -n 1 "${'$'}codex_entry" 2>/dev/null || true)" != '#!/usr/bin/env node' ];
      }; then
        mark_provision_stage codex
        if ! run_downloading Codex "${'$'}(npm_download_kb @openai/codex)" /root/.npm /root/.cache/horus/codex-install.log npm install --global --force --prefix /root/.local --no-package-lock --no-audit --no-fund --no-progress --loglevel=error @openai/codex; then
          mark_provision_stage codex_failed
          exit 23
        fi
      fi
      opencode_entry=/root/.local/lib/node_modules/opencode-ai/bin/opencode
      if [ ! -f "${'$'}opencode_entry" ]; then
        opencode_entry=/root/.local/lib/node_modules/opencode-ai/bin/opencode.exe
      fi
      opencode_payload_valid=false
      opencode_payload_stamp=
      if [ "${'$'}target" = opencode ] && [ -f "${'$'}opencode_entry" ]; then
        if [ ! -x "${'$'}opencode_entry" ]; then chmod u+rx "${'$'}opencode_entry" || true; fi
        if [ -x "${'$'}opencode_entry" ]; then
          opencode_payload_stamp=${'$'}(stat -L -c '%s:%Y:%i:%d' "${'$'}opencode_entry" 2>/dev/null || true)
          if [ -n "${'$'}opencode_payload_stamp" ] && [ "${'$'}(cat "${'$'}target_marker" 2>/dev/null || true)" = "ready:${'$'}opencode_payload_stamp" ]; then
            # The ready stamp records the payload already validated after
            # install. Avoid starting a second OpenCode process on every tap.
            opencode_payload_valid=true
          elif "${'$'}opencode_entry" --version >/dev/null 2>&1; then
            opencode_payload_valid=true
          fi
        fi
      fi
      if [ "${'$'}target" = opencode ] && {
        [ ! -f "${'$'}target_marker" ] || [ "${'$'}opencode_payload_valid" != true ];
      }; then
        mark_provision_stage opencode
        if ! run_downloading OpenCode "${'$'}(npm_download_kb opencode-ai)" /root/.npm /root/.cache/horus/opencode-install.log npm install --global --force --prefix /root/.local --no-package-lock --no-audit --no-fund --no-progress --loglevel=error opencode-ai; then
          mark_provision_stage opencode_failed
          exit 24
        fi
        opencode_entry=/root/.local/lib/node_modules/opencode-ai/bin/opencode
        if [ ! -f "${'$'}opencode_entry" ]; then
          opencode_entry=/root/.local/lib/node_modules/opencode-ai/bin/opencode.exe
        fi
        if ! { test -f "${'$'}opencode_entry" && { [ -x "${'$'}opencode_entry" ] || chmod u+rx "${'$'}opencode_entry"; } && "${'$'}opencode_entry" --version >/dev/null 2>&1; }; then
          mark_provision_stage opencode_failed
          exit 24
        fi
        opencode_payload_stamp=${'$'}(stat -L -c '%s:%Y:%i:%d' "${'$'}opencode_entry" 2>/dev/null || true)
        opencode_payload_valid=true
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
        test -x /root/.local/bin/codex
      else
        test -x /root/.local/bin/opencode
      fi
      if [ "${'$'}target" = opencode ] && [ -n "${'$'}opencode_payload_stamp" ]; then
        printf 'ready:%s\n' "${'$'}opencode_payload_stamp" > "${'$'}target_marker"
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
      exec /bin/busybox su -p -c '
        cd "${'$'}HORUS_SESSION_PWD"
        umask 0002
        export HOME="/home/${'$'}HORUS_GUEST_USERNAME"
        export PWD="${'$'}HORUS_SESSION_PWD"
        export PATH="/home/${'$'}HORUS_GUEST_USERNAME/.local/bin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        export XDG_CONFIG_HOME="${'$'}HOME/.config"
        export XDG_DATA_HOME="${'$'}HOME/.local/share"
        export XDG_CACHE_HOME="${'$'}HOME/.cache"
        if [ "${'$'}{HORUS_TOOLCHAIN_TARGET:-}" = codex ]; then
          export NPM_CONFIG_PREFIX="${'$'}HOME/.local"
          export npm_config_prefix="${'$'}HOME/.local"
        fi
        unset HORUS_BOOTSTRAP
        exec /bin/sh -c "${'$'}HORUS_SESSION_COMMAND"
      ' "${'$'}session_username"
    """.trimIndent()
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
      if [ ! -x /usr/sbin/dropbear ] || [ ! -x /usr/bin/dropbearkey ] || [ ! -x /usr/lib/ssh/sftp-server ] ||
        [ ! -x /usr/bin/scp ] || [ ! -x /usr/bin/rsync ] || ! command -v zsh >/dev/null 2>&1; then
        stage installing
        if ! apk add --no-cache --no-progress dropbear openssh-client-default openssh-sftp-server rsync zsh; then
          if [ ! -x /usr/sbin/dropbear ] || [ ! -x /usr/bin/dropbearkey ]; then
            stage install_failed
            exit 30
          fi
        fi
      fi
      keydir="${'$'}home/.config/horus"
      hostkey="${'$'}keydir/ssh_host_ed25519_key"
      mkdir -p "${'$'}keydir" "${'$'}home/.ssh" "${'$'}home/.cache/horus"
      if [ ! -s "${'$'}hostkey" ]; then
        rm -f "${'$'}hostkey"
        if ! dropbearkey -t ed25519 -f "${'$'}hostkey" >/dev/null 2>&1; then
          stage hostkey_failed
          exit 31
        fi
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
      # A script loses dropbear's "-" login marker in ${'$'}0; an interactive
      # login is the call without arguments (commands arrive as -c ...).
      if [ "${'$'}#" -eq 0 ]; then
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
      install_claude() {
        apk add --no-cache --no-progress bash curl ca-certificates git libgcc libstdc++ &&
          curl --connect-timeout 15 --max-time 300 -fsSL https://claude.ai/install.sh -o "${'$'}cache/claude-install.sh" &&
          bash "${'$'}cache/claude-install.sh"
      }
      install_npm_agent() {
        apk add --no-cache --no-progress bash ca-certificates git nodejs npm libgcc libstdc++ &&
          npm install --global --force --prefix "${'$'}HOME/.local" --no-package-lock --no-audit --no-fund --no-progress --loglevel=error "${'$'}1"
      }
      # npm's bin links lose their execute bit under PRoot; launch the entry
      # points through small scripts instead, as the app does.
      write_launcher() {
        rm -f "${'$'}launcher" &&
          printf '#!/bin/sh\nexec %s "%s" "$@"\n' "${'$'}1" "${'$'}2" > "${'$'}launcher" &&
          chmod 755 "${'$'}launcher"
      }
      install_codex() {
        entry="${'$'}HOME/.local/lib/node_modules/@openai/codex/bin/codex.js"
        install_npm_agent @openai/codex && test -f "${'$'}entry" && write_launcher /usr/bin/node "${'$'}entry"
      }
      install_opencode() {
        install_npm_agent opencode-ai || return 1
        # On Alpine the package picks a native binary named opencode.exe.
        entry="${'$'}HOME/.local/lib/node_modules/opencode-ai/bin/opencode"
        [ -f "${'$'}entry" ] || entry="${'$'}entry.exe"
        test -f "${'$'}entry" && chmod u+rx "${'$'}entry" && write_launcher '' "${'$'}entry"
      }
      if [ ! -x "${'$'}launcher" ]; then
        mkdir -p "${'$'}HOME/.local/bin" "${'$'}cache"
        # Two sessions typing the same command install it once.
        exec 9>"${'$'}cache/${'$'}tool-install.lock"
        flock 9
        if [ ! -x "${'$'}launcher" ]; then
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
      exec /bin/busybox su -s /bin/sh -c 'cd / && PATH=/usr/sbin:/usr/bin:/sbin:/bin exec dropbear -F -E -s -w -K 30 -p "127.0.0.1:${'$'}HORUS_REMOTE_PORT" -r "${'$'}HORUS_REMOTE_HOSTKEY" -P "${'$'}HOME/.cache/horus/dropbear.pid"' "${'$'}user"
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
