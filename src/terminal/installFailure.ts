import type {TerminalToolchainTarget} from '../native/NativeTerminalRuntime';
import {toolchainInstallLabel} from './toolchainLabels';

/** Why a first-run install stopped, as far as Horus can tell. */
export type InstallFailureKind = 'offline' | 'network' | 'storage' | 'permissions' | 'node_version' | 'packages' | 'unknown';

export type InstallFailure = Readonly<{
  kind: InstallFailureKind;
  /** The last provisioning stage, e.g. `codex_failed`. */
  stage?: string;
  /** The end of the install log, cleaned for reading. */
  log?: string;
}>;

// Output kept while an install runs. The end of the log explains a failure.
export const INSTALL_LOG_TAIL_CHARS = 8000;
const SHOWN_LOG_LINES = 40;

const NETWORK_PATTERN = new RegExp([
  'ENOTFOUND', 'EAI_AGAIN', 'ENETUNREACH', 'ECONNREFUSED', 'ECONNRESET', 'ETIMEDOUT', 'EHOSTUNREACH',
  'fetch failed', 'network request to', 'Could not resolve host', 'Could not resolve',
  'Temporary failure (in name )?resolution', 'Network is unreachable', 'Failed to fetch',
  'Connection timed out', 'Failed to connect', 'unable to (select packages|fetch)', 'temporary error',
].join('|'), 'i');

/** Classifies a failed install from its log, its last stage, and whether Android sees a network. */
export function classifyInstallFailure(log: string, stage: string | undefined, online: boolean | undefined): InstallFailureKind {
  if (online === false) return 'offline';
  if (/ENOSPC|No space left on device/i.test(log)) return 'storage';
  if (NETWORK_PATTERN.test(log)) return 'network';
  if (/EBADENGINE/i.test(log)) return 'node_version';
  if (/EACCES|permission denied/i.test(log)) return 'permissions';
  if (stage === 'apk_failed' || stage === 'github_failed') return 'packages';
  return 'unknown';
}

const ANSI_PATTERN = /\u001b\[[0-?]*[ -/]*[@-~]|\u001b\][^\u0007\u001b]*(?:\u0007|\u001b\\)|\u001b[@-Z\\-_]/g;
const PROGRESS_LINE = /^(Downloading|Installing) [^\n]*… (\d+%|\d+ MB)/;

/** The readable end of an install log: no escapes, markers, or progress ticks. */
export function cleanInstallLog(raw: string): string | undefined {
  const lines = raw
    .replace(ANSI_PATTERN, '')
    .split('\n')
    // A carriage return redraws its line; keep what was drawn last.
    .map(line => (line.split('\r').filter(part => part.length > 0).pop() ?? '').trimEnd())
    .filter(line => line.length > 0 && !line.startsWith('HORUS_'));
  // Progress lines repeat; only the last one says how far it got.
  const kept = lines.filter((line, index) => !PROGRESS_LINE.test(line) || !lines.slice(index + 1).some(next => PROGRESS_LINE.test(next)));
  const tail = kept.slice(-SHOWN_LOG_LINES).join('\n').trim();
  return tail.length > 0 ? tail : undefined;
}

export type InstallFailureCopy = Readonly<{title: string; reason: string; advice: string}>;

/** What the failure screen says, in plain words. */
export function installFailureCopy(failure: InstallFailure, toolchain: TerminalToolchainTarget): InstallFailureCopy {
  const label = toolchainInstallLabel(toolchain);
  const title = `Couldn’t install ${label}`;
  // Claude Code always downloads from Anthropic; the others can use a mirror.
  const mirror = toolchain === 'claude' ? '' : ' You can also pick another mirror in Settings › Downloads.';
  switch (failure.kind) {
    case 'offline':
      return {title, reason: 'Your phone is offline.', advice: `Horus downloads ${label} the first time you open it. Turn on Wi-Fi or mobile data, then try again.`};
    case 'network':
      return {title, reason: 'The download didn’t get through.', advice: `Check that your connection works and isn’t blocked by a VPN, firewall, or captive portal, then try again.${mirror}`};
    case 'storage':
      return {title, reason: 'Your phone is out of storage.', advice: `Free up some space, then try again. ${label} needs a few hundred MB.`};
    case 'permissions':
      return {title, reason: 'Horus couldn’t write the files it needs.', advice: 'Try again. If it keeps failing, report it with the log below.'};
    case 'node_version':
      return {title, reason: `${label} needs a newer Node.js than this Linux has.`, advice: 'Report it with the log below so Horus can ship a compatible version.'};
    case 'packages':
      return {title, reason: 'The system packages didn’t install.', advice: 'This is usually the connection or the package mirror. Try again, or pick another mirror in Settings › Downloads.'};
    case 'unknown':
      return {title, reason: 'The installer stopped before it finished.', advice: 'Try again. If it keeps failing, report it with the log below.'};
  }
}
