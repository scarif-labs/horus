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

/** Coarse running time for a session, e.g. "just started", "5 min", "2 hr 3 min". */
export function formatSessionAge(startedAtMs: number): string {
  const minutes = Math.max(0, Math.floor((Date.now() - startedAtMs) / 60_000));
  if (minutes < 1) return 'just started';
  if (minutes === 1) return '1 min';
  if (minutes < 60) return `${minutes} min`;
  const hours = Math.floor(minutes / 60);
  const remaining = minutes % 60;
  return remaining === 0 ? `${hours} hr` : `${hours} hr ${remaining} min`;
}
