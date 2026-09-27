import {readTerminalRuntimeStatus} from './runtimeStatus';

/**
 * Small capability report for the Alpine terminal PoC:
 * ABI, Android API, app version, Hermes status, storage path, and the active
 * runtime version in one typed snapshot. The Hermes probe is local to this
 * namespace so the terminal runtime stays independent of the legacy
 * diagnostics modules.
 */

type HermesGlobal = typeof globalThis & {
  HermesInternal?: unknown;
};

export function detectHermesEngine(): 'hermes' | 'unknown' {
  return (globalThis as HermesGlobal).HermesInternal == null ? 'unknown' : 'hermes';
}

export type TerminalCapabilityReport = Readonly<{
  abi: string;
  androidApi: number;
  appVersion: string;
  engine: 'hermes' | 'unknown';
  storageRoot: string;
  runtimeState: 'not_installed' | 'ready';
  runtimeVersion: string;
  prootAvailable: boolean;
}>;

export type TerminalCapabilityReportResult = Readonly<
  | {kind: 'available'; report: TerminalCapabilityReport}
  | {kind: 'unavailable'; errorCode: 'unavailable' | 'invalid_response' | 'internal_error'}
>;

export async function getTerminalCapabilityReport(): Promise<TerminalCapabilityReportResult> {
  const status = await readTerminalRuntimeStatus();
  if (status.kind === 'error') {
    return {kind: 'unavailable', errorCode: status.errorCode};
  }
  return {
    kind: 'available',
    report: {
      abi: status.abi,
      androidApi: status.apiLevel,
      appVersion: status.appVersion,
      engine: detectHermesEngine(),
      storageRoot: status.storageRoot,
      runtimeState: status.runtimeState,
      runtimeVersion: status.runtimeVersion,
      prootAvailable: status.prootAvailable,
    },
  };
}
