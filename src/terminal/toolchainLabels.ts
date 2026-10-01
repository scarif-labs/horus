import type {TerminalToolchainTarget} from '../native/NativeTerminalRuntime';
import type {ActiveTerminalSession} from './session/sessionContract';

function toolchainName(target: TerminalToolchainTarget, shellLabel: string): string {
  if (target === 'claude') return 'Claude Code';
  if (target === 'codex') return 'Codex';
  if (target === 'opencode') return 'OpenCode';
  if (target === 'github') return 'GitHub CLI';
  return shellLabel;
}

/** Label used while a toolchain is being installed or started. */
export function toolchainInstallLabel(target: TerminalToolchainTarget): string {
  return toolchainName(target, 'Alpine shell');
}

/** Title shown for a running session in recents and the session-limit list. */
export function sessionTitle(session: ActiveTerminalSession): string {
  return toolchainName(session.toolchain, 'Bare terminal');
}
