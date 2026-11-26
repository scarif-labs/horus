# robbyrussell, Oh My Zsh's default: arrow (red after a failed command),
# directory name, and git branch.
horus_theme_precmd() {
  horus_git_segment=
  [[ -z $horus_git ]] || horus_git_segment=" %B%F{blue}git:(%F{red}$horus_git%F{blue})%f%b"
}
(( ${precmd_functions[(I)horus_theme_precmd]} )) || precmd_functions+=(horus_theme_precmd)
PROMPT='%(?:%B%F{green}➜:%B%F{red}➜)%f%b %F{cyan}%c%f$horus_git_segment '
