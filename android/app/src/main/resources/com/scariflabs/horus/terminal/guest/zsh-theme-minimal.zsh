# minimal: path, git branch, and a ❯ that turns red after a failed command.
horus_theme_precmd() {
  horus_git_segment=
  [[ -z $horus_git ]] || horus_git_segment=" %F{242}$horus_git%f"
}
(( ${precmd_functions[(I)horus_theme_precmd]} )) || precmd_functions+=(horus_theme_precmd)
PROMPT='%F{blue}%~%f$horus_git_segment %(?.%F{magenta}.%F{red})❯%f '
