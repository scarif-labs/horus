import type {
  ActivateRootfsRequest,
  ActivateRootfsResponse,
  InstallRootfsRequest,
  InstallRootfsResponse,
  ResetRuntimeRequest,
  ResetRuntimeResponse,
  TerminalResetScope,
  TerminalRuntimeStatusResponse,
  TerminalRuntimeErrorCode as NativeTerminalRuntimeErrorCode,
} from '../native/NativeTerminalRuntime';
import {
  TERMINAL_RUNTIME_ERROR_CODES,
  TERMINAL_RUNTIME_RESET_SCOPES,
  TERMINAL_RUNTIME_SCHEMA_VERSION as NATIVE_TERMINAL_RUNTIME_SCHEMA_VERSION,
  TERMINAL_RUNTIME_STATE_NOT_INSTALLED as NATIVE_TERMINAL_RUNTIME_STATE_NOT_INSTALLED,
  TERMINAL_RUNTIME_STATE_READY as NATIVE_TERMINAL_RUNTIME_STATE_READY,
  TERMINAL_RUNTIME_VERSION as NATIVE_TERMINAL_RUNTIME_VERSION,
} from '../native/NativeTerminalRuntime';

/**
 * JS-side validation for the Phase 1 rootfs contract. Mirrors the pinned
 * catalog constants the native side enforces; the native module stays the
 * authority, this layer only rejects malformed requests and validates wire
 * responses before callers see them.
 */

export const TERMINAL_RUNTIME_SCHEMA_VERSION = NATIVE_TERMINAL_RUNTIME_SCHEMA_VERSION;
export const TERMINAL_RUNTIME_VERSION = NATIVE_TERMINAL_RUNTIME_VERSION;
export const TERMINAL_RUNTIME_STATE_NOT_INSTALLED = NATIVE_TERMINAL_RUNTIME_STATE_NOT_INSTALLED;
export const TERMINAL_RUNTIME_STATE_READY = NATIVE_TERMINAL_RUNTIME_STATE_READY;
export const PINNED_ROOTFS_ID = 'alpine-3.24.0-aarch64';
export const PINNED_ROOTFS_SHA256 =
  '4b8cd66a6688b2a87276c39843ed89c3a06d9534fc6a5823c586aff2696c1f2a';
export const PINNED_ALPINE_RELEASE = '3.24.0';
export const PINNED_ROOTFS_SIZE_BYTES = 4_043_766;
/** Shown to the user when the in-app download fails, so they can fetch it in a browser. */
export const PINNED_ROOTFS_URL =
  'https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/aarch64/alpine-minirootfs-3.24.0-aarch64.tar.gz';
export const PINNED_PROBE_MARKERS = [
  'alpine_probe_begin',
  'aarch64',
  '3.24.0',
  '/root',
  '/sbin/apk',
  '/bin/sh',
  'alpine_probe_end',
] as const;

/** One installable distribution; mirrors RootfsCatalogEntry on the native side. */
export type RootfsCatalogEntry = Readonly<{
  distro: string;
  rootfsId: string;
  release: string;
  sha256: string;
  sizeBytes: number;
  url: string;
  probeMarkers: readonly string[];
}>;

export const ROOTFS_DISTRO_ALPINE = 'alpine';
export const ROOTFS_DISTRO_DEBIAN = 'debian';

export const ALPINE_ROOTFS: RootfsCatalogEntry = {
  distro: ROOTFS_DISTRO_ALPINE,
  rootfsId: PINNED_ROOTFS_ID,
  release: PINNED_ALPINE_RELEASE,
  sha256: PINNED_ROOTFS_SHA256,
  sizeBytes: PINNED_ROOTFS_SIZE_BYTES,
  url: PINNED_ROOTFS_URL,
  probeMarkers: PINNED_PROBE_MARKERS,
};

/** Debian 13 slim, recompressed to xz and hosted as a Horus release asset (see RootfsCatalog.kt). */
export const DEBIAN_ROOTFS: RootfsCatalogEntry = {
  distro: ROOTFS_DISTRO_DEBIAN,
  rootfsId: 'debian-13.7-20261005-aarch64',
  release: '13.7',
  sha256: 'c4a40504d8eddd7d47125d129296352d3758b1d0ce958bd517b44631aee74eb5',
  sizeBytes: 17_386_584,
  url: 'https://github.com/scarif-labs/horus/releases/download/rootfs-debian-13.7-20261005/debian-trixie-slim-13.7-arm64.tar.xz',
  probeMarkers: [
    'debian_probe_begin',
    'aarch64',
    '13.7',
    '/root',
    '/usr/bin/apt-get',
    '/usr/bin/sh',
    'debian_probe_end',
  ],
};

/** Every distribution the app can install. Alpine is the default. */
export const ROOTFS_CATALOG: readonly RootfsCatalogEntry[] = [ALPINE_ROOTFS, DEBIAN_ROOTFS];
export const DEFAULT_ROOTFS: RootfsCatalogEntry = ALPINE_ROOTFS;

export function rootfsCatalogEntry(rootfsId: unknown): RootfsCatalogEntry | undefined {
  return ROOTFS_CATALOG.find(entry => entry.rootfsId === rootfsId);
}

/** The pinned rootfs for a distribution: one per distribution. */
export function rootfsForDistro(distro: string): RootfsCatalogEntry | undefined {
  return ROOTFS_CATALOG.find(entry => entry.distro === distro);
}

const REQUEST_ID_PATTERN = /^[A-Za-z0-9._:-]{1,64}$/;
const ROOTFS_ID_PATTERN = /^[a-z0-9][a-z0-9.-]{0,63}$/;
const SHA256_PATTERN = /^[a-f0-9]{64}$/;
const ISO_INSTANT_PATTERN =
  /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{3,9})?Z$/;
const RESET_SCOPES: readonly TerminalResetScope[] = TERMINAL_RUNTIME_RESET_SCOPES;

export type TerminalRuntimeOperationErrorCode =
  | NativeTerminalRuntimeErrorCode
  | 'unavailable'
  | 'invalid_response';

export type TerminalRuntimeErrorCode = NativeTerminalRuntimeErrorCode;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function nonEmptyString(value: unknown): value is string {
  return typeof value === 'string' && value.length > 0;
}

function isSafeNonNegativeInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0;
}

function isSafePositiveInteger(value: unknown): value is number {
  return isSafeNonNegativeInteger(value) && value > 0;
}

function isKnownErrorCode(value: unknown): value is TerminalRuntimeErrorCode {
  return (
    typeof value === 'string' &&
    (TERMINAL_RUNTIME_ERROR_CODES as readonly string[]).includes(value)
  );
}

function hasOnlyKeys(value: Record<string, unknown>, keys: readonly string[]): boolean {
  return Object.keys(value).every(key => keys.includes(key));
}

function isIsoInstant(value: unknown): value is string {
  return (
    typeof value === 'string' &&
    ISO_INSTANT_PATTERN.test(value) &&
    Number.isFinite(Date.parse(value))
  );
}

function isStringArray(value: unknown): value is string[] {
  return Array.isArray(value) && value.every(item => typeof item === 'string');
}

function isValidInstalledVersionIds(value: unknown): value is string[] {
  return (
    Array.isArray(value) &&
    value.every(isValidRootfsId) &&
    new Set(value).size === value.length
  );
}

export function isValidRequestId(value: unknown): value is string {
  return typeof value === 'string' && REQUEST_ID_PATTERN.test(value);
}

export function isValidRootfsId(value: unknown): value is string {
  return typeof value === 'string' && ROOTFS_ID_PATTERN.test(value);
}

export function isValidSha256Digest(value: unknown): value is string {
  return typeof value === 'string' && SHA256_PATTERN.test(value);
}

export function isValidTerminalRuntimeErrorCode(
  value: unknown,
): value is TerminalRuntimeErrorCode {
  return isKnownErrorCode(value);
}

export function buildInstallRootfsRequest(
  requestId: string,
  rootfsId: string = DEFAULT_ROOTFS.rootfsId,
  makeActive?: boolean,
): InstallRootfsRequest | null {
  if (!isValidRequestId(requestId)) return null;
  if (rootfsCatalogEntry(rootfsId) === undefined) return null;
  return makeActive === undefined ? {requestId, rootfsId} : {requestId, rootfsId, makeActive};
}

export function buildActivateRootfsRequest(requestId: string, rootfsId: string): ActivateRootfsRequest | null {
  if (!isValidRequestId(requestId) || rootfsCatalogEntry(rootfsId) === undefined) return null;
  return {requestId, rootfsId};
}

export function isActivateRootfsResponse(value: unknown): value is ActivateRootfsResponse {
  if (!isRecord(value) || !isValidRequestId(value.requestId)) return false;
  if (value.status === 'error') {
    return hasOnlyKeys(value, ['requestId', 'status', 'errorCode']) && isKnownErrorCode(value.errorCode);
  }
  return (
    value.status === 'success' &&
    hasOnlyKeys(value, ['requestId', 'status', 'rootfsId']) &&
    rootfsCatalogEntry(value.rootfsId) !== undefined
  );
}

export function buildResetRuntimeRequest(
  requestId: string,
  scope: TerminalResetScope,
): ResetRuntimeRequest | null {
  if (!isValidRequestId(requestId)) return null;
  if (!RESET_SCOPES.includes(scope)) return null;
  return {requestId, scope};
}

export function isTerminalRuntimeStatusResponse(
  value: unknown,
): value is TerminalRuntimeStatusResponse {
  if (!isRecord(value)) return false;
  if (value.status === 'error') {
    return (
      hasOnlyKeys(value, ['status', 'errorCode']) &&
      value.errorCode === 'internal_error'
    );
  }
  if (value.status !== 'success') return false;
  if (
    !hasOnlyKeys(value, [
      'status',
      'schemaVersion',
      'runtimeState',
      'runtimeVersion',
      'abi',
      'apiLevel',
      'appVersion',
      'storageRoot',
      'prootAvailable',
      'prootVersion',
      'activeRootfsId',
      'activeDistro',
      'activeRelease',
      'activeAlpineRelease',
      'activeRootfsSha256',
      'activeInstalledAt',
      'installedVersionIds',
    ]) ||
    value.schemaVersion !== TERMINAL_RUNTIME_SCHEMA_VERSION ||
    (value.runtimeState !== TERMINAL_RUNTIME_STATE_NOT_INSTALLED &&
      value.runtimeState !== TERMINAL_RUNTIME_STATE_READY) ||
    value.runtimeVersion !== TERMINAL_RUNTIME_VERSION ||
    !nonEmptyString(value.abi) ||
    !isSafePositiveInteger(value.apiLevel) ||
    !nonEmptyString(value.appVersion) ||
    !nonEmptyString(value.storageRoot) ||
    typeof value.prootAvailable !== 'boolean' ||
    !nonEmptyString(value.prootVersion) ||
    !isValidInstalledVersionIds(value.installedVersionIds)
  ) {
    return false;
  }

  const installedVersionIds = value.installedVersionIds;
  const activeKeys = [
    value.activeRootfsId,
    value.activeDistro,
    value.activeRelease,
    value.activeRootfsSha256,
    value.activeInstalledAt,
  ];
  if (value.runtimeState !== TERMINAL_RUNTIME_STATE_READY) {
    return !activeKeys.some(item => item !== undefined) && value.activeAlpineRelease === undefined;
  }
  const entry = rootfsCatalogEntry(value.activeRootfsId);
  if (entry === undefined || !activeKeys.every(item => item !== undefined)) return false;
  // activeAlpineRelease is set for Alpine only, and then always agrees with activeRelease.
  const alpineReleaseOk =
    entry.distro === ROOTFS_DISTRO_ALPINE
      ? value.activeAlpineRelease === entry.release
      : value.activeAlpineRelease === undefined;
  return (
    value.activeDistro === entry.distro &&
    value.activeRelease === entry.release &&
    alpineReleaseOk &&
    isValidSha256Digest(value.activeRootfsSha256) &&
    value.activeRootfsSha256 === entry.sha256 &&
    isIsoInstant(value.activeInstalledAt) &&
    installedVersionIds.includes(entry.rootfsId)
  );
}

export function isInstallRootfsResponse(value: unknown): value is InstallRootfsResponse {
  if (!isRecord(value)) return false;
  if (!isValidRequestId(value.requestId)) return false;
  if (value.status === 'error') {
    return (
      hasOnlyKeys(value, ['requestId', 'status', 'errorCode']) &&
      isKnownErrorCode(value.errorCode)
    );
  }
  if (value.status !== 'success') return false;
  const entry = rootfsCatalogEntry(value.rootfsId);
  if (entry === undefined) return false;
  return (
    hasOnlyKeys(value, [
      'requestId',
      'status',
      'rootfsId',
      'archiveSha256',
      'archiveBytes',
      'extractionFiles',
      'probeExitCode',
      'probeMarkers',
      'reusedCache',
      'durationMs',
    ]) &&
    isValidRootfsId(value.rootfsId) &&
    isValidSha256Digest(value.archiveSha256) &&
    value.archiveSha256 === entry.sha256 &&
    value.archiveBytes === entry.sizeBytes &&
    isSafePositiveInteger(value.extractionFiles) &&
    value.probeExitCode === 0 &&
    Array.isArray(value.probeMarkers) &&
    value.probeMarkers.length === entry.probeMarkers.length &&
    value.probeMarkers.every((marker, index) => marker === entry.probeMarkers[index]) &&
    typeof value.reusedCache === 'boolean' &&
    isSafeNonNegativeInteger(value.durationMs)
  );
}

export function isResetRuntimeResponse(value: unknown): value is ResetRuntimeResponse {
  if (!isRecord(value)) return false;
  if (!isValidRequestId(value.requestId)) return false;
  if (value.status === 'error') {
    return (
      hasOnlyKeys(value, ['requestId', 'status', 'errorCode']) &&
      isKnownErrorCode(value.errorCode)
    );
  }
  if (value.status !== 'success') return false;
  return (
    hasOnlyKeys(value, [
      'requestId',
      'status',
      'removedVersionIds',
      'homeRemoved',
      'workspacesRemoved',
    ]) &&
    isStringArray(value.removedVersionIds) &&
    value.removedVersionIds.every(isValidRootfsId) &&
    new Set(value.removedVersionIds).size === value.removedVersionIds.length &&
    typeof value.homeRemoved === 'boolean' &&
    typeof value.workspacesRemoved === 'boolean'
  );
}

/** The install result a caller may trust, in one discriminated shape. */
export type TrustedInstallOutcome = Readonly<
  | {
      kind: 'success';
      requestId: string;
      rootfsId: string;
      archiveSha256: string;
      archiveBytes: number;
      extractionFiles: number;
      probeExitCode: number;
      probeMarkers: string[];
      reusedCache: boolean;
      durationMs: number;
    }
  | {
      kind: 'error';
      requestId: string;
      errorCode: TerminalRuntimeOperationErrorCode;
    }
>;

export type TrustedResetOutcome = Readonly<
  | {
      kind: 'success';
      requestId: string;
      removedVersionIds: string[];
      homeRemoved: boolean;
      workspacesRemoved: boolean;
    }
  | {
      kind: 'error';
      requestId: string;
      errorCode: TerminalRuntimeOperationErrorCode;
    }
>;
