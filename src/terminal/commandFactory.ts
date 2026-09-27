/** Fixed, non-secret guest commands used by launcher actions. */
const restoreEcho = 'stty echo 2>/dev/null || true';
const requireZsh = "if ! command -v zsh >/dev/null 2>&1; then printf '%s\\n' 'zsh is not available yet; close and reopen this terminal to retry' >&2; exit 127; fi";

export function buildZshCommand(command?: string): string {
  if (command === undefined) return `${restoreEcho}; ${requireZsh}; exec zsh -l`;
  const quoted = command.replace(/'/g, "'\\''");
  return `${restoreEcho}; ${requireZsh}; exec zsh -lic '${quoted}'`;
}

/**
 * Claude Code invocation with an explicit messaging socket. Android kernels
 * without CONFIG_USER_NS have no /proc/self/uid_map, which Claude reads as an
 * unmapped user namespace and then turns cross-session messaging off. An
 * explicit path skips that check. Run it with `exec` so `$$` is Claude's own
 * pid and concurrent sessions never share a socket.
 */
export const CLAUDE_COMMAND = 'claude --messaging-socket-path "/tmp/claude-messaging-$(id -u)/$$.sock"';

/** Runs a fixed guest script without loading interactive or login startup files. */
export function buildZshScriptCommand(command: string): string {
  const quoted = command.replace(/'/g, "'\\''");
  return `${restoreEcho}; ${requireZsh}; exec zsh -fc '${quoted}'`;
}

export function shellQuote(value: string): string {
  return `'${value.replace(/'/g, "'\\''")}'`;
}
