/**
 * Evidence schema for the Alpine terminal PoC.
 * One JSON gate record per gate, sanitized, with no credential-bearing
 * content. Host gate scripts build records; this module is the single
 * definition of the wire shape so JS consumers and validators agree.
 */

export const ALPINE_TERMINAL_POC_EVIDENCE_SCHEMA = 'alpine-terminal-poc/v1';

export const ALPINE_GATE_STATUSES = ['passed', 'failed', 'blocked'] as const;
export type AlpineGateStatus = (typeof ALPINE_GATE_STATUSES)[number];

export const ALPINE_CHECK_STATUSES = ['passed', 'failed', 'blocked', 'skipped'] as const;
export type AlpineCheckStatus = (typeof ALPINE_CHECK_STATUSES)[number];

export type AlpineGateCheck = Readonly<{
  id: string;
  status: AlpineCheckStatus;
  exitCode?: number;
  marker?: string;
}>;

export type AlpineGateDevice = Readonly<{
  model: string;
  androidApi: number;
  abi: string;
}>;

export type AlpineGateRuntime = Readonly<{
  prootVersion?: string;
  rootfsId?: string;
  rootfsSha256?: string;
}>;

export type AlpineGateRecord = Readonly<{
  schema: typeof ALPINE_TERMINAL_POC_EVIDENCE_SCHEMA;
  gate: string;
  status: AlpineGateStatus;
  appCommit: string;
  device?: AlpineGateDevice;
  runtime?: AlpineGateRuntime;
  checks: readonly AlpineGateCheck[];
  credentialsPresent: false;
}>;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isNonEmptyString(value: unknown): value is string {
  return typeof value === 'string' && value.length > 0;
}

function isIn<T extends readonly string[]>(
  value: unknown,
  allowed: T,
): value is T[number] {
  return typeof value === 'string' && (allowed as readonly string[]).includes(value);
}

function isOptional(value: unknown, present: (v: unknown) => boolean): boolean {
  return value === undefined || present(value);
}

function isGateCheck(value: unknown): value is AlpineGateCheck {
  if (!isRecord(value)) {
    return false;
  }
  return (
    isNonEmptyString(value.id) &&
    isIn(value.status, ALPINE_CHECK_STATUSES) &&
    isOptional(value.exitCode, v => typeof v === 'number' && Number.isSafeInteger(v)) &&
    isOptional(value.marker, isNonEmptyString)
  );
}

function isGateDevice(value: unknown): value is AlpineGateDevice {
  if (!isRecord(value)) {
    return false;
  }
  return (
    isNonEmptyString(value.model) &&
    typeof value.androidApi === 'number' &&
    Number.isSafeInteger(value.androidApi) &&
    value.androidApi > 0 &&
    isNonEmptyString(value.abi)
  );
}

function isGateRuntime(value: unknown): value is AlpineGateRuntime {
  if (!isRecord(value)) {
    return false;
  }
  return (
    isOptional(value.prootVersion, isNonEmptyString) &&
    isOptional(value.rootfsId, isNonEmptyString) &&
    isOptional(value.rootfsSha256, v => isNonEmptyString(v) && /^[0-9a-f]{64}$/.test(v))
  );
}

/**
 * Structural validator for exported gate records. It accepts only records
 * that declare `credentialsPresent: false`; evidence claiming credentials are
 * present is invalid by definition.
 */
export function isAlpineGateRecord(value: unknown): value is AlpineGateRecord {
  if (!isRecord(value)) {
    return false;
  }
  if (value.schema !== ALPINE_TERMINAL_POC_EVIDENCE_SCHEMA) {
    return false;
  }
  if (!isNonEmptyString(value.gate) || !isIn(value.status, ALPINE_GATE_STATUSES)) {
    return false;
  }
  if (!isNonEmptyString(value.appCommit) || value.credentialsPresent !== false) {
    return false;
  }
  if (!isOptional(value.device, isGateDevice) || !isOptional(value.runtime, isGateRuntime)) {
    return false;
  }
  return Array.isArray(value.checks) && value.checks.every(isGateCheck);
}
