import nativeTerminalRuntime from '../native/NativeTerminalRuntime';
import type {
  TerminalDebugLogResponse,
  InstallRootfsRequest,
  InstallRootfsResponse,
  ProvisionToolchainResponse,
  ResetRuntimeResponse,
  Spec,
  TerminalToolchainTarget,
  TerminalResetScope,
  TerminalRuntimeStatusResponse,
} from '../native/NativeTerminalRuntime';

/**
 * Typed JS view of the terminal runtime status call. The native module stays
 * the authority; this layer only validates the wire response and collapses it
 * into a discriminated union so callers never see a half-shaped success.
 * Phase 1 extends the Phase 0 capability snapshot with install state.
 */

import {
  buildInstallRootfsRequest,
  buildResetRuntimeRequest,
  isInstallRootfsResponse,
  isResetRuntimeResponse,
  isValidTerminalRuntimeErrorCode,
  isTerminalRuntimeStatusResponse as isDistroTerminalRuntimeStatusResponse,
  TERMINAL_RUNTIME_SCHEMA_VERSION,
  type TerminalRuntimeOperationErrorCode,
  type TrustedInstallOutcome,
  type TrustedResetOutcome,
} from './distroContract';

export {TERMINAL_RUNTIME_SCHEMA_VERSION};

export type TerminalRuntimeErrorCode =
  | 'unavailable'
  | 'invalid_response'
  | 'internal_error';

export type TerminalRuntimeStatusSuccess = Readonly<{
  kind: 'success';
  schemaVersion: number;
  runtimeState: 'not_installed' | 'ready';
  runtimeVersion: string;
  abi: string;
  apiLevel: number;
  appVersion: string;
  storageRoot: string;
  prootAvailable: boolean;
  prootVersion: string;
  installedVersionIds: readonly string[];
  activeRootfsId?: string;
  activeAlpineRelease?: string;
  activeRootfsSha256?: string;
  activeInstalledAt?: string;
}>;

export type TerminalRuntimeStatusView = Readonly<
  | TerminalRuntimeStatusSuccess
  | {kind: 'error'; errorCode: TerminalRuntimeErrorCode}
>;

export type TerminalDebugLogView = Readonly<
  | {kind: 'success'; path: string; externalPath?: string; bytes: number; tail: string}
  | {kind: 'error'; errorCode: 'unavailable' | 'invalid_response' | 'internal_error'}
>;

type TerminalDebugLogWireResponse =
  | (TerminalDebugLogResponse & {status: 'success'; path: string; bytes: number; tail: string})
  | (TerminalDebugLogResponse & {status: 'error'; errorCode: 'internal_error'});

function isTerminalDebugLogWireResponse(value: unknown): value is TerminalDebugLogWireResponse {
  if (value === null || typeof value !== 'object') return false;
  const response = value as Partial<TerminalDebugLogResponse>;
  if (response.status === 'error') {
    return response.errorCode === 'internal_error';
  }
  return (
    response.status === 'success' &&
    typeof response.path === 'string' &&
    response.path.length > 0 &&
    (response.externalPath === undefined || (
      typeof response.externalPath === 'string' && response.externalPath.length > 0
    )) &&
    typeof response.bytes === 'number' &&
    Number.isFinite(response.bytes) &&
    response.bytes >= 0 &&
    response.bytes <= 256 * 1024 &&
    typeof response.tail === 'string' &&
    response.tail.length <= 32 * 1024
  );
}

/** Reads the bounded native diagnostic tail without exposing PTY output. */
export async function readTerminalDebugLog(
  runtime: Pick<Spec, 'getDebugLog'> | null = nativeTerminalRuntime,
): Promise<TerminalDebugLogView> {
  if (runtime === null) return {kind: 'error', errorCode: 'unavailable'};
  let response: unknown;
  try {
    response = await runtime.getDebugLog();
  } catch {
    return {kind: 'error', errorCode: 'internal_error'};
  }
  if (!isTerminalDebugLogWireResponse(response)) return {kind: 'error', errorCode: 'invalid_response'};
  if (response.status === 'error') return {kind: 'error', errorCode: 'internal_error'};
  return {
    kind: 'success',
    path: response.path,
    ...(response.externalPath !== undefined ? {externalPath: response.externalPath} : {}),
    bytes: response.bytes,
    tail: response.tail,
  };
}

type TerminalRuntimeStatusWireSuccess = {
  status: 'success';
  schemaVersion: number;
  runtimeState: 'not_installed' | 'ready';
  runtimeVersion: string;
  abi: string;
  apiLevel: number;
  appVersion: string;
  storageRoot: string;
  prootAvailable: boolean;
  prootVersion: string;
  installedVersionIds: string[];
  activeRootfsId?: string;
  activeAlpineRelease?: string;
  activeRootfsSha256?: string;
  activeInstalledAt?: string;
};

/**
 * A success must carry the complete capability snapshot. The required-field
 * wire type keeps the parsed view free of undefined after narrowing.
 */
function isTerminalRuntimeStatusWireSuccess(
  value: unknown,
): value is TerminalRuntimeStatusWireSuccess {
  return (
    isDistroTerminalRuntimeStatusResponse(value) &&
    value.status === 'success'
  );
}

/**
 * Structural check for the flattened wire response. Anything that is neither
 * a complete success nor the declared error shape is invalid; callers must
 * not guess a half-shaped response into a success view.
 */
export function isTerminalRuntimeStatusResponse(
  value: unknown,
): value is TerminalRuntimeStatusResponse {
  return isDistroTerminalRuntimeStatusResponse(value);
}

/**
 * Reads the runtime status. The native module is injectable only so host
 * tests can exercise the missing-module path; production callers use the
 * default.
 */
export async function readTerminalRuntimeStatus(
  runtime: Spec | null = nativeTerminalRuntime,
): Promise<TerminalRuntimeStatusView> {
  if (runtime === null) {
    return {kind: 'error', errorCode: 'unavailable'};
  }
  let response: unknown;
  try {
    response = await runtime.getRuntimeStatus();
  } catch {
    return {kind: 'error', errorCode: 'internal_error'};
  }
  if (!isTerminalRuntimeStatusResponse(response)) {
    return {kind: 'error', errorCode: 'invalid_response'};
  }
  if (response.status === 'error') {
    return {kind: 'error', errorCode: 'internal_error'};
  }
  if (isTerminalRuntimeStatusWireSuccess(response)) {
    const success = response as TerminalRuntimeStatusWireSuccess;
    return {
      kind: 'success',
      schemaVersion: success.schemaVersion,
      runtimeState: success.runtimeState,
      runtimeVersion: success.runtimeVersion,
      abi: success.abi,
      apiLevel: success.apiLevel,
      appVersion: success.appVersion,
      storageRoot: success.storageRoot,
      prootAvailable: success.prootAvailable,
      prootVersion: success.prootVersion,
      installedVersionIds: success.installedVersionIds,
      ...(success.activeRootfsId !== undefined ? {activeRootfsId: success.activeRootfsId} : {}),
      ...(success.activeAlpineRelease !== undefined ? {activeAlpineRelease: success.activeAlpineRelease} : {}),
      ...(success.activeRootfsSha256 !== undefined ? {activeRootfsSha256: success.activeRootfsSha256} : {}),
      ...(success.activeInstalledAt !== undefined ? {activeInstalledAt: success.activeInstalledAt} : {}),
    };
  }
  return {kind: 'error', errorCode: 'invalid_response'};
}

type ValidatedInstallSuccess = InstallRootfsResponse & {
  status: 'success';
  rootfsId: string;
  archiveSha256: string;
  archiveBytes: number;
  extractionFiles: number;
  probeExitCode: number;
  probeMarkers: string[];
  reusedCache: boolean;
  durationMs: number;
};

type ValidatedResetSuccess = ResetRuntimeResponse & {
  status: 'success';
  removedVersionIds: string[];
  homeRemoved: boolean;
  workspacesRemoved: boolean;
};

function operationError(
  requestId: string,
  errorCode: TerminalRuntimeOperationErrorCode,
): {kind: 'error'; requestId: string; errorCode: TerminalRuntimeOperationErrorCode} {
  return {kind: 'error', requestId, errorCode};
}

/** Installs the pinned rootfs and returns only a fully validated outcome. */
export async function installRootfs(
  requestId: string,
  runtime: Pick<Spec, 'installRootfs'> | null = nativeTerminalRuntime,
): Promise<TrustedInstallOutcome> {
  return runRootfsInstall(requestId, runtime === null ? null : request => runtime.installRootfs(request));
}

/**
 * Lets the user pick a rootfs archive they downloaded in a browser. Native
 * verifies it against the same pinned size and digest as a download.
 */
export async function importRootfs(
  requestId: string,
  runtime: Pick<Spec, 'importRootfs'> | null = nativeTerminalRuntime,
): Promise<TrustedInstallOutcome> {
  return runRootfsInstall(requestId, runtime === null ? null : request => runtime.importRootfs(request));
}

async function runRootfsInstall(
  requestId: string,
  call: ((request: InstallRootfsRequest) => Promise<unknown>) | null,
): Promise<TrustedInstallOutcome> {
  const request = buildInstallRootfsRequest(requestId);
  if (request === null) return operationError(requestId, 'invalid_request');
  if (call === null) return operationError(requestId, 'unavailable');

  let response: unknown;
  try {
    response = await call(request);
  } catch {
    return operationError(requestId, 'internal_error');
  }
  if (!isInstallRootfsResponse(response) || response.requestId !== requestId) {
    return operationError(requestId, 'invalid_response');
  }
  if (response.status === 'error') {
    return isValidTerminalRuntimeErrorCode(response.errorCode)
      ? operationError(requestId, response.errorCode)
      : operationError(requestId, 'invalid_response');
  }
  const success = response as ValidatedInstallSuccess;
  return {
    kind: 'success',
    requestId,
    rootfsId: success.rootfsId,
    archiveSha256: success.archiveSha256,
    archiveBytes: success.archiveBytes,
    extractionFiles: success.extractionFiles,
    probeExitCode: success.probeExitCode,
    probeMarkers: success.probeMarkers,
    reusedCache: success.reusedCache,
    durationMs: success.durationMs,
  };
}

/** Provisions only the shell or harness requested for the first launch. */
export async function provisionAlpineToolchain(
  requestId: string,
  target: TerminalToolchainTarget,
  runtime: Pick<Spec, 'provisionToolchain'> | null = nativeTerminalRuntime,
): Promise<{kind: 'success'; requestId: string} | {kind: 'error'; requestId: string; errorCode: TerminalRuntimeOperationErrorCode}> {
  if (runtime === null) return operationError(requestId, 'unavailable');
  let response: ProvisionToolchainResponse;
  try {
    response = await runtime.provisionToolchain({requestId, target});
  } catch {
    return operationError(requestId, 'internal_error');
  }
  if (response.requestId !== requestId || (response.status !== 'success' && response.status !== 'error')) {
    return operationError(requestId, 'invalid_response');
  }
  if (response.status === 'error') {
    return operationError(
      requestId,
      isValidTerminalRuntimeErrorCode(response.errorCode) ? response.errorCode : 'invalid_response',
    );
  }
  return {kind: 'success', requestId};
}

/** Resets only the requested scope and rejects mismatched bridge responses. */
export async function resetRuntime(
  requestId: string,
  scope: TerminalResetScope,
  runtime: Pick<Spec, 'resetRuntime'> | null = nativeTerminalRuntime,
): Promise<TrustedResetOutcome> {
  const request = buildResetRuntimeRequest(requestId, scope);
  if (request === null) return operationError(requestId, 'invalid_request');
  if (runtime === null) return operationError(requestId, 'unavailable');

  let response: unknown;
  try {
    response = await runtime.resetRuntime(request);
  } catch {
    return operationError(requestId, 'internal_error');
  }
  if (!isResetRuntimeResponse(response) || response.requestId !== requestId) {
    return operationError(requestId, 'invalid_response');
  }
  if (response.status === 'error') {
    return isValidTerminalRuntimeErrorCode(response.errorCode)
      ? operationError(requestId, response.errorCode)
      : operationError(requestId, 'invalid_response');
  }
  const success = response as ValidatedResetSuccess;
  if (scope === 'rootfs' && (success.homeRemoved || success.workspacesRemoved)) {
    return operationError(requestId, 'invalid_response');
  }
  if (scope === 'home' && (!success.homeRemoved || success.workspacesRemoved)) {
    return operationError(requestId, 'invalid_response');
  }
  if (scope === 'workspace' && (success.homeRemoved || !success.workspacesRemoved)) {
    return operationError(requestId, 'invalid_response');
  }
  if (scope === 'all-user-data' && (!success.homeRemoved || !success.workspacesRemoved)) {
    return operationError(requestId, 'invalid_response');
  }
  return {
    kind: 'success',
    requestId,
    removedVersionIds: success.removedVersionIds,
    homeRemoved: success.homeRemoved,
    workspacesRemoved: success.workspacesRemoved,
  };
}
