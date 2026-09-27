import {NativeModules} from 'react-native';

export type RemoteAccessState = 'stopped' | 'starting' | 'installing' | 'running' | 'failed';

export type RemoteComputer = Readonly<{
  fingerprint: string;
  label: string;
  /** False for keys the user added to ~/.ssh/authorized_keys by hand. */
  managed: boolean;
}>;

export type RemoteAccessSnapshot = Readonly<{
  enabled: boolean;
  state: RemoteAccessState;
  detail: string;
  port: number;
  computers: readonly RemoteComputer[];
}>;

export type RemoteAccessResult = Readonly<
  | {kind: 'success'; snapshot: RemoteAccessSnapshot}
  | {kind: 'error'}
>;

export type RemoteAccessModule = Readonly<{
  getRemoteAccess: () => Promise<unknown>;
  setRemoteAccessEnabled: (enabled: boolean) => Promise<unknown>;
  revokeRemoteComputer: (fingerprint: string) => Promise<unknown>;
}>;

const STATES: readonly RemoteAccessState[] = ['stopped', 'starting', 'installing', 'running', 'failed'];

function nativeRemoteAccessModule(): RemoteAccessModule | null {
  const module = NativeModules.HorusDevice as Partial<RemoteAccessModule> | undefined;
  if (
    module === undefined ||
    typeof module.getRemoteAccess !== 'function' ||
    typeof module.setRemoteAccessEnabled !== 'function' ||
    typeof module.revokeRemoteComputer !== 'function'
  ) return null;
  return module as RemoteAccessModule;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function parseComputer(value: unknown): RemoteComputer | null {
  if (!isRecord(value) || typeof value.fingerprint !== 'string' || typeof value.label !== 'string') return null;
  return {fingerprint: value.fingerprint, label: value.label, managed: value.managed === true};
}

export function parseRemoteAccess(value: unknown): RemoteAccessResult {
  if (!isRecord(value) || value.status !== 'success') return {kind: 'error'};
  const state = STATES.find(candidate => candidate === value.state);
  if (
    typeof value.enabled !== 'boolean' ||
    state === undefined ||
    typeof value.port !== 'number' ||
    !Array.isArray(value.computers)
  ) return {kind: 'error'};
  const computers = value.computers.map(parseComputer);
  if (computers.some(computer => computer === null)) return {kind: 'error'};
  return {
    kind: 'success',
    snapshot: {
      enabled: value.enabled,
      state,
      detail: typeof value.detail === 'string' ? value.detail : '',
      port: value.port,
      computers: computers as RemoteComputer[],
    },
  };
}

async function call(run: (module: RemoteAccessModule) => Promise<unknown>, module: RemoteAccessModule | null): Promise<RemoteAccessResult> {
  if (module === null) return {kind: 'error'};
  try {
    return parseRemoteAccess(await run(module));
  } catch {
    return {kind: 'error'};
  }
}

export function readRemoteAccess(module = nativeRemoteAccessModule()): Promise<RemoteAccessResult> {
  return call(native => native.getRemoteAccess(), module);
}

export function setRemoteAccessEnabled(enabled: boolean, module = nativeRemoteAccessModule()): Promise<RemoteAccessResult> {
  return call(native => native.setRemoteAccessEnabled(enabled), module);
}

export function revokeRemoteComputer(fingerprint: string, module = nativeRemoteAccessModule()): Promise<RemoteAccessResult> {
  return call(native => native.revokeRemoteComputer(fingerprint), module);
}

/** One plain sentence for the settings screen. */
export function remoteAccessStatusLabel(snapshot: RemoteAccessSnapshot): string {
  if (!snapshot.enabled) return 'Off. Nothing is listening.';
  switch (snapshot.state) {
    case 'running':
      return `On. Paired computers can connect over USB (port ${snapshot.port} on the phone only).`;
    case 'installing':
      return 'Installing the SSH server…';
    case 'starting':
      return 'Starting…';
    case 'failed':
      return snapshot.detail === 'install_failed'
        ? 'Could not install the SSH server. Check the internet connection and turn it on again.'
        : 'The SSH server stopped unexpectedly. Turn it off and on again.';
    default:
      return 'On. The server starts when Horus is open.';
  }
}
