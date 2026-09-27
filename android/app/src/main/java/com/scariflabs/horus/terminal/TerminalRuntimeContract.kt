package com.scariflabs.horus.terminal

/**
 * Pure contract for the Alpine terminal runtime native module
 * It keeps request validation and the pinned
 * phase constants free of Android framework types so unit tests cover every
 * rule. Phase 1 extended the Phase 0 snapshot with rootfs install, status,
 * and scoped reset; Phase 2 adds the native PTY session surface.
 */
object TerminalRuntimeContract {
  const val MODULE_NAME = "TerminalRuntime"
  const val SCHEMA_VERSION = 4
  const val RUNTIME_VERSION = "p2-pty"
  const val RUNTIME_STATE_NOT_INSTALLED = "not_installed"
  const val RUNTIME_STATE_READY = "ready"
  const val STORAGE_ROOT_DIR_NAME = "horus"
  const val UNKNOWN = "unknown"

  private val RUNTIME_STATES = setOf(RUNTIME_STATE_NOT_INSTALLED, RUNTIME_STATE_READY)
  private val RESET_SCOPES = setOf(
    RESET_SCOPE_ROOTFS,
    RESET_SCOPE_HOME,
    RESET_SCOPE_WORKSPACE,
    RESET_SCOPE_ALL_USER_DATA,
  )
  private val REQUEST_ID_PATTERN = Regex("^[A-Za-z0-9._:-]{1,64}$")

  const val RESET_SCOPE_ROOTFS = "rootfs"
  const val RESET_SCOPE_HOME = "home"
  const val RESET_SCOPE_WORKSPACE = "workspace"
  const val RESET_SCOPE_ALL_USER_DATA = "all-user-data"
  const val TOOLCHAIN_TARGET_SHELL = "shell"
  const val TOOLCHAIN_TARGET_GITHUB = "github"
  const val TOOLCHAIN_TARGET_CLAUDE = "claude"
  const val TOOLCHAIN_TARGET_CODEX = "codex"
  const val TOOLCHAIN_TARGET_OPENCODE = "opencode"

  private val TOOLCHAIN_TARGETS = setOf(
    TOOLCHAIN_TARGET_SHELL,
    TOOLCHAIN_TARGET_GITHUB,
    TOOLCHAIN_TARGET_CLAUDE,
    TOOLCHAIN_TARGET_CODEX,
    TOOLCHAIN_TARGET_OPENCODE,
  )

  fun isValidRuntimeState(state: String?): Boolean = RUNTIME_STATES.contains(state)

  fun isValidRequestId(requestId: String?): Boolean =
    requestId != null && REQUEST_ID_PATTERN.matches(requestId)

  fun isValidResetScope(scope: String?): Boolean = RESET_SCOPES.contains(scope)

  fun isValidToolchainTarget(target: String?): Boolean = TOOLCHAIN_TARGETS.contains(target)

  fun primaryAbi(supportedAbis: Array<String>?): String =
    supportedAbis?.firstOrNull { it.isNotBlank() } ?: UNKNOWN

  /**
   * The documented app-private storage root (<filesDir>/horus). Phase 0
   * computes but never creates it; the Phase 1 installer owns creation.
   */
  fun storageRoot(filesDirPath: String?): String =
    filesDirPath?.takeIf { it.isNotBlank() }?.let { path -> "$path/$STORAGE_ROOT_DIR_NAME" } ?: UNKNOWN

  /**
   * Assembles the no-op status snapshot from explicit inputs so the module
   * stays a thin bridge mapping and connected tests can verify the exact
   * logic against live device values without constructing a React context.
   */
  fun buildStatusSnapshot(
    supportedAbis: Array<String>?,
    apiLevel: Int,
    appVersion: String?,
    filesDirPath: String?,
  ): TerminalRuntimeStatus = TerminalRuntimeStatus(
    abi = primaryAbi(supportedAbis),
    apiLevel = apiLevel,
    appVersion = appVersion ?: UNKNOWN,
    storageRoot = storageRoot(filesDirPath),
  )
}

data class TerminalRuntimeStatus(
  val schemaVersion: Int = TerminalRuntimeContract.SCHEMA_VERSION,
  val runtimeState: String = TerminalRuntimeContract.RUNTIME_STATE_NOT_INSTALLED,
  val runtimeVersion: String = TerminalRuntimeContract.RUNTIME_VERSION,
  val abi: String,
  val apiLevel: Int,
  val appVersion: String,
  val storageRoot: String,
)
