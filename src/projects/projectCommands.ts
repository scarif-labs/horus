import {CLAUDE_COMMAND, buildZshCommand, buildZshScriptCommand, shellQuote} from '../terminal/commandFactory';
import type {MetroLaunchTarget} from '../ui/MetroHomeScreen';

export const WORKSPACE_PROJECTS_DIRECTORY = '/workspace/projects';

export function workspaceProjectDirectory(name: string): string | undefined {
  return /^[A-Za-z0-9._-]{1,48}$/.test(name) && name !== '.' && name !== '..'
    ? `${WORKSPACE_PROJECTS_DIRECTORY}/${name}`
    : undefined;
}

export function harnessSessionTarget(harness: MetroLaunchTarget, name: string, directory: string): MetroLaunchTarget {
  const toolchain = harness.toolchain ?? 'shell';
  const command = toolchain === 'opencode' ? 'opencode' : toolchain === 'claude' ? CLAUDE_COMMAND : 'codex';
  const script = `mkdir -p ${shellQuote(directory)} && cd ${shellQuote(directory)} && exec ${command}`;
  return {
    title: harness.title,
    eyebrow: name,
    command: toolchain === 'opencode' ? buildZshScriptCommand(script) : buildZshCommand(script),
    toolchain,
    returnTo: 'home',
  };
}

const NORMALIZE_GIT_REMOTE_FUNCTION = [
  'normalize_git_remote() {',
  '  remote="$1"',
  '  case "$remote" in',
  '    https://*) remote="${remote#https://}" ;;',
  '    ssh://*) remote="${remote#ssh://}"; remote="${remote#git@}" ;;',
  '    git@*) remote="${remote#git@}"; host="${remote%%:*}"; path="${remote#*:}"; remote="$host/$path" ;;',
  '    *) return 1 ;;',
  '  esac',
  '  host="${remote%%/*}"',
  '  path="${remote#*/}"',
  '  host=$(printf \'%s\' "$host" | tr \'[:upper:]\' \'[:lower:]\')',
  '  path="${path%/}"',
  '  path="${path%.git}"',
  '  if [ "$host" = github.com ]; then path=$(printf \'%s\' "$path" | tr \'[:upper:]\' \'[:lower:]\'); fi',
  '  printf \'%s/%s\\n\' "$host" "$path"',
  '}',
].join('\n');

/**
 * Prints a completion marker as its own output line for the terminal's
 * scanner, then moves up and erases it so the user never sees it.
 */
export function printHiddenMarker(marker: string): string {
  return `printf '\\n%s\\n\\033[1A\\033[2K' ${shellQuote(marker)}`;
}

export function buildProjectCloneCommand(source: string, directory: string, marker: string, kind: 'github' | 'url'): string {
  const expectedOrigin = kind === 'github' ? `https://github.com/${source}.git` : source;
  const cloneCommand = kind === 'github'
    ? `gh repo clone ${shellQuote(source)} ${shellQuote(directory)}`
    : `git clone -- ${shellQuote(source)} ${shellQuote(directory)}`;
  const quotedDirectory = shellQuote(directory);
  return [
    'set -e',
    NORMALIZE_GIT_REMOTE_FUNCTION,
    `mkdir -p ${shellQuote(WORKSPACE_PROJECTS_DIRECTORY)}`,
    `if [ -e ${quotedDirectory} ] || [ -L ${quotedDirectory} ]; then`,
    `  destination_root=$(cd ${quotedDirectory} 2>/dev/null && pwd -P || true)`,
    `  existing_root=$(git -C ${quotedDirectory} rev-parse --show-toplevel 2>/dev/null || true)`,
    `  existing_origin=$(git -C ${quotedDirectory} remote get-url origin 2>/dev/null || true)`,
    `  if [ ! -L ${quotedDirectory} ] && [ -n "$destination_root" ] && [ "$existing_root" = "$destination_root" ] && [ -n "$existing_origin" ] && [ "$(normalize_git_remote "$existing_origin" 2>/dev/null || true)" = "$(normalize_git_remote ${shellQuote(expectedOrigin)})" ]; then`,
    `    printf '%s\\n' 'Using the existing checkout of this repository.'`,
    `    ${printHiddenMarker(marker)}`,
    '    exit 0',
    '  fi',
    `  printf '%s\\n' 'That workspace folder already exists but is not a checkout of this repository. It was left unchanged.' >&2`,
    '  exit 17',
    'fi',
    cloneCommand,
    `${printHiddenMarker(marker)}`,
  ].join('\n');
}
