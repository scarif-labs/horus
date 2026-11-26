# agnoster without Oh My Zsh: powerline segments for user, path and git
# branch, then a ❯ on its own line so long paths fit a phone screen.
horus_theme_precmd() {
  if [[ -n $horus_git ]]; then
    horus_git_segment="%K{green}%F{blue}%F{black}  $horus_git %k%F{green}%f"
  else
    horus_git_segment="%k%F{blue}%f"
  fi
}
(( ${precmd_functions[(I)horus_theme_precmd]} )) || precmd_functions+=(horus_theme_precmd)
PROMPT=$'%K{black}%F{white} %n@%m %K{blue}%F{black} %~ $horus_git_segment\n%(?.%F{blue}.%F{red})❯%f '
