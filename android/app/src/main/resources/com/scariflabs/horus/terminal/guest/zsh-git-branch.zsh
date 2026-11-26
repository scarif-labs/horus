# The current git branch, read from .git/HEAD without running git: a fork
# per prompt is slow under PRoot. Sets horus_git (escaped for the prompt),
# empty outside a repository.
horus_git_branch() {
  local dir=$PWD git head
  horus_git=
  while :; do
    git=$dir/.git
    if [[ -f $git ]]; then
      head=$(<$git)
      git=${head#gitdir: }
      [[ $git == /* ]] || git=$dir/$git
    fi
    if [[ -r $git/HEAD ]]; then
      head=$(<$git/HEAD)
      if [[ $head == 'ref: refs/heads/'* ]]; then head=${head#ref: refs/heads/}; else head=${head[1,7]}; fi
      horus_git=${head//\%/%%}
      return
    fi
    [[ -n $dir && $dir != / ]] || return
    dir=${dir%/*}
  done
}
(( ${precmd_functions[(I)horus_git_branch]} )) || precmd_functions+=(horus_git_branch)
