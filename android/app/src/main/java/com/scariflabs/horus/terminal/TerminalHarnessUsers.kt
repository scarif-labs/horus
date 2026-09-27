package com.scariflabs.horus.terminal

/** Stable, separate guest identities for the interactive provider CLIs. */
internal data class TerminalHarnessUser(
  val username: String,
  val uid: Int,
  val gid: Int,
  val homeKey: String,
)

internal object TerminalHarnessUsers {
  const val SHARED_WORKSPACE_GID = 1000

  private val harnessIds = mapOf(
    TerminalRuntimeContract.TOOLCHAIN_TARGET_CLAUDE to 61_001,
    TerminalRuntimeContract.TOOLCHAIN_TARGET_CODEX to 61_002,
    TerminalRuntimeContract.TOOLCHAIN_TARGET_OPENCODE to 61_003,
  )

  fun forTarget(target: String?, profileUsername: String): TerminalHarnessUser? {
    val uid = harnessIds[target] ?: return null
    val safeTarget = requireNotNull(target)
    var username = "harness_$safeTarget"
    var suffix = 1
    while (username == profileUsername) {
      username = "harness_${safeTarget}_$suffix"
      suffix += 1
    }
    return TerminalHarnessUser(
      username = username,
      uid = uid,
      gid = SHARED_WORKSPACE_GID,
      homeKey = safeTarget,
    )
  }
}
