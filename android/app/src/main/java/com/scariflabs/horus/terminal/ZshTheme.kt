package com.scariflabs.horus.terminal

/**
 * The guest zsh prompts a user can pick in Settings. Each is a small inline
 * script under `guest/`, so none needs Oh My Zsh, git, or a download.
 */
enum class ZshTheme(val id: String, private val showsGitBranch: Boolean) {
  DUELLJ("duellj", showsGitBranch = false),
  ROBBYRUSSELL("robbyrussell", showsGitBranch = true),
  MINIMAL("minimal", showsGitBranch = true),
  AGNOSTER("agnoster", showsGitBranch = true),
  ;

  /** The zshrc lines that set this theme's prompt. */
  fun script(): String = buildString {
    if (showsGitBranch) append(resource("zsh-git-branch.zsh").trimEnd()).append('\n')
    append(resource("zsh-theme-$id.zsh").trimEnd())
  }

  companion object {
    val DEFAULT = DUELLJ

    fun fromId(id: String?): ZshTheme? = entries.firstOrNull { it.id == id }

    private fun resource(name: String): String =
      requireNotNull(ZshTheme::class.java.getResourceAsStream("/com/scariflabs/horus/terminal/guest/$name")) {
        "missing guest resource $name"
      }.use { it.readBytes().toString(Charsets.UTF_8) }
  }
}
