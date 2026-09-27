import type {
  DetachTerminalSessionRequest,
  DetachTerminalSessionResponse,
  ListTerminalSessionsRequest,
  ListTerminalSessionsResponse,
  ResizeSessionRequest,
  ResizeSessionResponse,
  AcknowledgeSessionOutputRequest,
  AcknowledgeSessionOutputResponse,
  SignalSessionRequest,
  SignalSessionResponse,
  StartSessionRequest,
  StartSessionResponse,
  StopSessionRequest,
  StopSessionResponse,
  StopAllTerminalSessionsRequest,
  StopAllTerminalSessionsResponse,
  SubscribeSessionEventsRequest,
  SubscribeSessionEventsResponse,
  TerminalRuntimeErrorCode as NativeTerminalRuntimeErrorCode,
  TerminalSessionEvent,
  TerminalSessionExitEvent,
  TerminalSessionOutputEvent,
  TerminalSessionSignal,
  TerminalSessionSummary,
  TerminalToolchainTarget,
  WriteSessionInputRequest,
  WriteSessionInputResponse,
} from '../../native/NativeTerminalRuntime';
import {
  TERMINAL_RUNTIME_ERROR_CODES,
  TERMINAL_SESSION_EVENT_NAME,
  TERMINAL_SESSION_SIGNALS,
  TERMINAL_SESSION_OUTPUT_WINDOW,
  TERMINAL_TOOLCHAIN_TARGETS,
} from '../../native/NativeTerminalRuntime';
import {isValidRequestId} from '../distroContract';

export {isValidRequestId};
export type {TerminalSessionSignal};

/**
 * JS-side validation for the Phase 2 native PTY session contract. Mirrors
 * the bounded-input rules TerminalSessionContract.kt enforces; the native
 * module stays the authority, this layer rejects malformed requests and
 * validates wire responses and events before callers see them.
 */

export {
  TERMINAL_SESSION_EVENT_NAME,
  TERMINAL_SESSION_SIGNALS,
};

export const TERMINAL_SESSION_MIN_ROWS = 2;
export const TERMINAL_SESSION_MAX_ROWS = 250;
export const TERMINAL_SESSION_MIN_COLUMNS = 2;
export const TERMINAL_SESSION_MAX_COLUMNS = 500;
export const TERMINAL_SESSION_MAX_INPUT_BYTES = 16 * 1024;
export const TERMINAL_SESSION_MAX_INPUT_BASE64_CHARS =
  Math.floor((TERMINAL_SESSION_MAX_INPUT_BYTES + 2) / 3) * 4;
/** Reader chunks are 8 KiB on the native side; events may not exceed that. */
export const TERMINAL_SESSION_MAX_OUTPUT_BYTES = 8 * 1024;
export const TERMINAL_SESSION_MAX_OUTPUT_BASE64_CHARS =
  Math.floor((TERMINAL_SESSION_MAX_OUTPUT_BYTES + 2) / 3) * 4;
export {TERMINAL_SESSION_OUTPUT_WINDOW};
export const TERMINAL_SESSION_DEFAULT_ROWS = 24;
export const TERMINAL_SESSION_DEFAULT_COLUMNS = 80;
export const TERMINAL_SESSION_MAX_COMMAND_LENGTH = 4096;
export const TERMINAL_SESSION_DEFAULT_ACTIVE = 1;
export const TERMINAL_SESSION_MAX_ACTIVE = 4;

/** Machine-readable stop reasons (native enforces the same shape). */
export const TERMINAL_SESSION_STOP_REASONS = [
  'user_stop',
  'screen_detach',
  'screen_lock',
  'bridge_invalidated',
  'runtime_reset',
] as const;
export type TerminalSessionStopReason =
  (typeof TERMINAL_SESSION_STOP_REASONS)[number];

export type ActiveTerminalSession = Readonly<TerminalSessionSummary>;
export type TrustedSessionList = Readonly<
  | {kind: 'success'; sessions: readonly ActiveTerminalSession[]}
  | {kind: 'error'; errorCode: TerminalSessionOperationErrorCode}
>;

const SESSION_ID_PATTERN = /^[a-z0-9][a-z0-9-]{1,63}$/;
const STOP_REASON_PATTERN = /^[a-z0-9_]{1,64}$/;
const SIGNAL_NAME_PATTERN = /^[a-z0-9_]{1,32}$/;
const CANONICAL_BASE64_PATTERN = /^[A-Za-z0-9+/]+={0,2}$/;

export type TerminalSessionOperationErrorCode =
  | NativeTerminalRuntimeErrorCode
  | 'unavailable'
  | 'invalid_response';

export type TerminalSessionErrorCode = NativeTerminalRuntimeErrorCode;

const SESSION_ERROR_CODES: readonly string[] = [
  ...TERMINAL_RUNTIME_ERROR_CODES,
];

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function nonEmptyString(value: unknown): value is string {
  return typeof value === 'string' && value.length > 0;
}

function isSafeIntegerInRange(
  value: unknown,
  minimum: number,
  maximum: number,
): value is number {
  return (
    typeof value === 'number' &&
    Number.isSafeInteger(value) &&
    value >= minimum &&
    value <= maximum
  );
}

function isKnownErrorCode(value: unknown): value is TerminalSessionErrorCode {
  return (
    typeof value === 'string' && SESSION_ERROR_CODES.includes(value)
  );
}

function isTerminalSessionSignal(value: unknown): value is TerminalSessionSignal {
  return (
    typeof value === 'string' &&
    (TERMINAL_SESSION_SIGNALS as readonly string[]).includes(value)
  );
}

function isValidSessionSignalName(value: unknown): value is string {
  return typeof value === 'string' && SIGNAL_NAME_PATTERN.test(value);
}

function hasValidExitFields(value: Record<string, unknown>): boolean {
  if (value.exitCode !== undefined && !isSafeIntegerInRange(value.exitCode, 0, 255)) {
    return false;
  }
  if (value.signal !== undefined && !isValidSessionSignalName(value.signal)) {
    return false;
  }
  return true;
}

function hasOnlyKeys(
  value: Record<string, unknown>,
  keys: readonly string[],
): boolean {
  return (
    Object.keys(value).every(key => keys.includes(key)) &&
    Object.values(value).every(item => item !== undefined)
  );
}

function isValidSessionError(
  value: Record<string, unknown>,
  requestId: string,
): boolean {
  return (
    hasOnlyKeys(value, ['requestId', 'status', 'errorCode']) &&
    isValidRequestId(requestId) &&
    isValidRequestId(value.requestId) &&
    value.requestId === requestId &&
    value.status === 'error' &&
    isKnownErrorCode(value.errorCode)
  );
}

export function isValidTerminalSessionId(value: unknown): value is string {
  return typeof value === 'string' && SESSION_ID_PATTERN.test(value);
}

export function isValidTerminalSessionStopReason(value: unknown): value is string {
  return typeof value === 'string' && STOP_REASON_PATTERN.test(value);
}

export function isValidTerminalSessionRows(value: unknown): value is number {
  return isSafeIntegerInRange(
    value,
    TERMINAL_SESSION_MIN_ROWS,
    TERMINAL_SESSION_MAX_ROWS,
  );
}

export function isValidTerminalSessionColumns(value: unknown): value is number {
  return isSafeIntegerInRange(
    value,
    TERMINAL_SESSION_MIN_COLUMNS,
    TERMINAL_SESSION_MAX_COLUMNS,
  );
}

/** Strict canonical base64 (padded, no whitespace); null when malformed. */
export function decodeBase64(text: string): Uint8Array | null {
  if (typeof text !== 'string' || text.length === 0 || text.length % 4 !== 0) {
    return null;
  }
  const unpaddedLength = text.replace(/=+$/, '').length;
  const padding = text.length - unpaddedLength;
  if (padding > 2) return null;
  let carry = 0;
  let bits = 0;
  const bytes: number[] = [];
  let seenPadding = false;
  for (let index = 0; index < text.length; index++) {
    const character = text[index];
    if (character === '=') {
      seenPadding = true;
      continue;
    }
    if (seenPadding) return null; // data after padding
    const digit = BASE64_ALPHABET.indexOf(character);
    if (digit < 0) return null;
    carry = (carry << 6) | digit;
    bits += 6;
    if (bits >= 8) {
      bits -= 8;
      bytes.push((carry >> bits) & 0xff);
    }
  }
  // Canonical base64 requires unused bits in the final sextet to be zero.
  // Without this check strings such as `AB==` decode to a byte while carrying
  // a second, non-canonical representation of the same value.
  if (padding > 0) {
    const finalDigit = BASE64_ALPHABET.indexOf(text[unpaddedLength - 1]);
    const unusedBits = padding === 2 ? 4 : 2;
    if (finalDigit < 0 || (finalDigit & ((1 << unusedBits) - 1)) !== 0) {
      return null;
    }
  }
  // The trailing partial group must carry exactly the padding-implied bytes.
  const expectedLength = (text.length / 4) * 3 - padding;
  return bytes.length === expectedLength ? new Uint8Array(bytes) : null;
}

export function encodeBase64(bytes: Uint8Array): string {
  let out = '';
  for (let index = 0; index < bytes.length; index += 3) {
    const b0 = bytes[index];
    const b1 = index + 1 < bytes.length ? bytes[index + 1] : 0;
    const b2 = index + 2 < bytes.length ? bytes[index + 2] : 0;
    const triple = (b0 << 16) | (b1 << 8) | b2;
    out += BASE64_ALPHABET[(triple >> 18) & 0x3f];
    out += BASE64_ALPHABET[(triple >> 12) & 0x3f];
    out += index + 1 < bytes.length ? BASE64_ALPHABET[(triple >> 6) & 0x3f] : '=';
    out += index + 2 < bytes.length ? BASE64_ALPHABET[triple & 0x3f] : '=';
  }
  return out;
}

const BASE64_ALPHABET =
  'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';

export function buildStartSessionRequest(
  requestId: string,
  options?: {rows?: number; columns?: number; command?: string; toolchain?: TerminalToolchainTarget; countsAgainstSessionLimit?: boolean},
): StartSessionRequest | null {
  if (!isValidRequestId(requestId)) return null;
  const rows = options?.rows ?? TERMINAL_SESSION_DEFAULT_ROWS;
  const columns = options?.columns ?? TERMINAL_SESSION_DEFAULT_COLUMNS;
  if (!isValidTerminalSessionRows(rows) || !isValidTerminalSessionColumns(columns)) {
    return null;
  }
  if (options?.command !== undefined &&
    (options.command.length === 0 || options.command.length > TERMINAL_SESSION_MAX_COMMAND_LENGTH)) {
    return null;
  }
  if (options?.toolchain !== undefined && !TERMINAL_TOOLCHAIN_TARGETS.includes(options.toolchain)) return null;
  if (options?.countsAgainstSessionLimit !== undefined && typeof options.countsAgainstSessionLimit !== 'boolean') return null;
  return {
    requestId,
    rows,
    columns,
    ...(options?.command === undefined ? {} : {command: options.command}),
    ...(options?.toolchain === undefined ? {} : {toolchain: options.toolchain}),
    ...(options?.countsAgainstSessionLimit === undefined ? {} : {countsAgainstSessionLimit: options.countsAgainstSessionLimit}),
  };
}

export function buildWriteSessionInputRequest(
  requestId: string,
  sessionId: string,
  bytes: Uint8Array,
): WriteSessionInputRequest | null {
  if (!isValidRequestId(requestId)) return null;
  if (!isValidTerminalSessionId(sessionId)) return null;
  if (!(bytes instanceof Uint8Array) || bytes.length === 0 || bytes.length > TERMINAL_SESSION_MAX_INPUT_BYTES) {
    return null;
  }
  return {requestId, sessionId, base64: encodeBase64(bytes)};
}

export function buildResizeSessionRequest(
  requestId: string,
  sessionId: string,
  rows: number,
  columns: number,
): ResizeSessionRequest | null {
  if (!isValidRequestId(requestId)) return null;
  if (!isValidTerminalSessionId(sessionId)) return null;
  if (!isValidTerminalSessionRows(rows) || !isValidTerminalSessionColumns(columns)) {
    return null;
  }
  return {requestId, sessionId, rows, columns};
}

export function buildSignalSessionRequest(
  requestId: string,
  sessionId: string,
  signal: TerminalSessionSignal,
): SignalSessionRequest | null {
  if (!isValidRequestId(requestId)) return null;
  if (!isValidTerminalSessionId(sessionId)) return null;
  if (!isTerminalSessionSignal(signal)) return null;
  return {requestId, sessionId, signal};
}

export function buildStopSessionRequest(
  requestId: string,
  sessionId: string,
  reason: string,
): StopSessionRequest | null {
  if (!isValidRequestId(requestId)) return null;
  if (!isValidTerminalSessionId(sessionId)) return null;
  if (!isValidTerminalSessionStopReason(reason)) return null;
  return {requestId, sessionId, reason};
}

export function buildListTerminalSessionsRequest(
  requestId: string,
): ListTerminalSessionsRequest | null {
  if (!isValidRequestId(requestId)) return null;
  return {requestId};
}

export function buildDetachTerminalSessionRequest(
  requestId: string,
  sessionId: string,
): DetachTerminalSessionRequest | null {
  if (!isValidRequestId(requestId)) return null;
  if (!isValidTerminalSessionId(sessionId)) return null;
  return {requestId, sessionId};
}

export function buildStopAllTerminalSessionsRequest(
  requestId: string,
  reason: TerminalSessionStopReason,
): StopAllTerminalSessionsRequest | null {
  if (!isValidRequestId(requestId) || !isValidTerminalSessionStopReason(reason)) return null;
  return {requestId, reason};
}

export function buildSubscribeSessionEventsRequest(
  requestId: string,
  sessionId: string,
  afterSeq = 0,
): SubscribeSessionEventsRequest | null {
  if (!isValidRequestId(requestId)) return null;
  if (!isValidTerminalSessionId(sessionId)) return null;
  if (!isSafeIntegerInRange(afterSeq, 0, Number.MAX_SAFE_INTEGER)) return null;
  return {requestId, sessionId, ...(afterSeq === 0 ? {} : {afterSeq})};
}

export function buildAcknowledgeSessionOutputRequest(
  requestId: string,
  sessionId: string,
  seq: number,
): AcknowledgeSessionOutputRequest | null {
  if (!isValidRequestId(requestId)) return null;
  if (!isValidTerminalSessionId(sessionId)) return null;
  if (!isSafeIntegerInRange(seq, 1, Number.MAX_SAFE_INTEGER)) return null;
  return {requestId, sessionId, seq};
}

export function isStartSessionResponse(value: unknown, requestId: string): value is StartSessionResponse {
  if (!isRecord(value) || !isValidRequestId(requestId) || !isValidRequestId(value.requestId) || value.requestId !== requestId) return false;
  if (value.status === 'error') return isValidSessionError(value, requestId);
  if (value.status !== 'success') return false;
  return (
    hasOnlyKeys(value, ['requestId', 'status', 'sessionId', 'pid', 'rows', 'columns']) &&
    isValidTerminalSessionId(value.sessionId) &&
    isSafeIntegerInRange(value.pid, 1, Number.MAX_SAFE_INTEGER) &&
    isValidTerminalSessionRows(value.rows) &&
    isValidTerminalSessionColumns(value.columns)
  );
}

export function isWriteSessionInputResponse(
  value: unknown,
  requestId: string,
  expectedBytes?: number,
): value is WriteSessionInputResponse {
  if (!isRecord(value) || !isValidRequestId(requestId) || !isValidRequestId(value.requestId) || value.requestId !== requestId) return false;
  if (value.status === 'error') return isValidSessionError(value, requestId);
  if (value.status !== 'success') return false;
  return (
    hasOnlyKeys(value, ['requestId', 'status', 'bytesWritten']) &&
    isSafeIntegerInRange(value.bytesWritten, 1, TERMINAL_SESSION_MAX_INPUT_BYTES) &&
    (expectedBytes === undefined || value.bytesWritten === expectedBytes)
  );
}

export function isResizeSessionResponse(
  value: unknown,
  requestId: string,
  expectedRows?: number,
  expectedColumns?: number,
): value is ResizeSessionResponse {
  if (!isRecord(value) || !isValidRequestId(requestId) || !isValidRequestId(value.requestId) || value.requestId !== requestId) return false;
  if (value.status === 'error') return isValidSessionError(value, requestId);
  if (value.status !== 'success') return false;
  return (
    hasOnlyKeys(value, ['requestId', 'status', 'rows', 'columns']) &&
    isValidTerminalSessionRows(value.rows) &&
    isValidTerminalSessionColumns(value.columns) &&
    (expectedRows === undefined || value.rows === expectedRows) &&
    (expectedColumns === undefined || value.columns === expectedColumns)
  );
}

export function isSignalSessionResponse(
  value: unknown,
  requestId: string,
  expectedSignal?: TerminalSessionSignal,
): value is SignalSessionResponse {
  if (!isRecord(value) || !isValidRequestId(requestId) || !isValidRequestId(value.requestId) || value.requestId !== requestId) return false;
  if (value.status === 'error') return isValidSessionError(value, requestId);
  if (value.status !== 'success') return false;
  return (
    hasOnlyKeys(value, ['requestId', 'status', 'signal']) &&
    isTerminalSessionSignal(value.signal) &&
    (expectedSignal === undefined || value.signal === expectedSignal)
  );
}

export function isStopSessionResponse(
  value: unknown,
  requestId: string,
  expectedSessionId?: string,
): value is StopSessionResponse {
  if (!isRecord(value) || !isValidRequestId(requestId) || !isValidRequestId(value.requestId) || value.requestId !== requestId) return false;
  if (value.status === 'error') return isValidSessionError(value, requestId);
  if (value.status !== 'success') return false;
  if (
    !hasOnlyKeys(value, [
      'requestId',
      'status',
      'sessionId',
      'exitCode',
      'signal',
      'exitReason',
      'remainingProcessCount',
      'stoppedWithinDeadline',
    ]) ||
    !isValidTerminalSessionId(value.sessionId) ||
    (expectedSessionId !== undefined && value.sessionId !== expectedSessionId) ||
    !nonEmptyString(value.exitReason) ||
    !isValidTerminalSessionStopReason(value.exitReason) ||
    !isSafeIntegerInRange(value.remainingProcessCount, 0, Number.MAX_SAFE_INTEGER) ||
    typeof value.stoppedWithinDeadline !== 'boolean' ||
    !hasValidExitFields(value)
  ) {
    return false;
  }
  return true;
}

export function isListTerminalSessionsResponse(
  value: unknown,
  requestId: string,
): value is ListTerminalSessionsResponse {
  if (!isRecord(value) || !isValidRequestId(requestId) || !isValidRequestId(value.requestId) || value.requestId !== requestId) return false;
  if (value.status === 'error') return isValidSessionError(value, requestId);
  if (value.status !== 'success') return false;
  if (!hasOnlyKeys(value, ['requestId', 'status', 'sessions']) || !Array.isArray(value.sessions) || value.sessions.length > TERMINAL_SESSION_MAX_ACTIVE) {
    return false;
  }
  const seenIds = new Set<string>();
  return value.sessions.every(session => {
    if (!isRecord(session) || !hasOnlyKeys(session, ['sessionId', 'toolchain', 'startedAtMs'])) return false;
    if (!isValidTerminalSessionId(session.sessionId) || seenIds.has(session.sessionId)) return false;
    if (typeof session.toolchain !== 'string' || !TERMINAL_TOOLCHAIN_TARGETS.includes(session.toolchain as TerminalToolchainTarget)) return false;
    if (!isSafeIntegerInRange(session.startedAtMs, 0, Number.MAX_SAFE_INTEGER)) return false;
    seenIds.add(session.sessionId);
    return true;
  });
}

export function isDetachTerminalSessionResponse(
  value: unknown,
  requestId: string,
): value is DetachTerminalSessionResponse {
  if (!isRecord(value) || !isValidRequestId(requestId) || !isValidRequestId(value.requestId) || value.requestId !== requestId) return false;
  if (value.status === 'error') return isValidSessionError(value, requestId);
  return value.status === 'success' && hasOnlyKeys(value, ['requestId', 'status']);
}

export function isStopAllTerminalSessionsResponse(
  value: unknown,
  requestId: string,
): value is StopAllTerminalSessionsResponse {
  if (!isRecord(value) || !isValidRequestId(requestId) || !isValidRequestId(value.requestId) || value.requestId !== requestId) return false;
  if (value.status === 'error') return isValidSessionError(value, requestId);
  return value.status === 'success' && hasOnlyKeys(value, ['requestId', 'status']);
}

export function isSubscribeSessionEventsResponse(
  value: unknown,
  requestId: string,
  expectedSessionId?: string,
  afterSeq = 0,
): value is SubscribeSessionEventsResponse {
  if (!isRecord(value) || !isValidRequestId(requestId) || !isValidRequestId(value.requestId) || value.requestId !== requestId) return false;
  if (value.status === 'error') return isValidSessionError(value, requestId);
  if (value.status !== 'success') return false;
  if (
    !hasOnlyKeys(value, [
      'requestId',
      'status',
      'sessionId',
      'eventName',
      'sessionState',
      'firstAvailableSeq',
      'lastEmittedSeq',
      'replayAvailable',
      'exitCode',
      'signal',
      'exitReason',
    ]) ||
    !isValidTerminalSessionId(value.sessionId) ||
    (expectedSessionId !== undefined && value.sessionId !== expectedSessionId) ||
    value.eventName !== TERMINAL_SESSION_EVENT_NAME ||
    (value.sessionState !== 'running' && value.sessionState !== 'exited') ||
    !isSafeIntegerInRange(value.firstAvailableSeq, 1, Number.MAX_SAFE_INTEGER) ||
    !isSafeIntegerInRange(value.lastEmittedSeq, 0, Number.MAX_SAFE_INTEGER) ||
    typeof value.replayAvailable !== 'boolean' ||
    value.firstAvailableSeq > value.lastEmittedSeq + 1 ||
    !isSafeIntegerInRange(afterSeq, 0, Number.MAX_SAFE_INTEGER) ||
    (value.replayAvailable && afterSeq <= value.lastEmittedSeq && value.firstAvailableSeq !== afterSeq + 1) ||
    (value.replayAvailable && afterSeq > value.lastEmittedSeq && value.firstAvailableSeq !== value.lastEmittedSeq + 1)
  ) {
    return false;
  }
  if (!hasValidExitFields(value)) {
    return false;
  }
  if (value.sessionState === 'running') {
    return (
      value.exitCode === undefined &&
      value.signal === undefined &&
      value.exitReason === undefined
    );
  }
  if (
    !nonEmptyString(value.exitReason) ||
    !isValidTerminalSessionStopReason(value.exitReason)
  ) {
    return false;
  }
  return true;
}

export function isAcknowledgeSessionOutputResponse(
  value: unknown,
  requestId: string,
  expectedSessionId?: string,
  expectedSeq?: number,
): value is AcknowledgeSessionOutputResponse {
  if (
    !isRecord(value) ||
    !isValidRequestId(requestId) ||
    !isValidRequestId(value.requestId) ||
    value.requestId !== requestId
  ) {
    return false;
  }
  if (value.status === 'error') return isValidSessionError(value, requestId);
  if (value.status !== 'success') return false;
  return (
    hasOnlyKeys(value, [
      'requestId',
      'status',
      'sessionId',
      'acknowledgedSeq',
      'outstandingChunks',
    ]) &&
    isValidTerminalSessionId(value.sessionId) &&
    (expectedSessionId === undefined || value.sessionId === expectedSessionId) &&
    isSafeIntegerInRange(value.acknowledgedSeq, 1, Number.MAX_SAFE_INTEGER) &&
    (expectedSeq === undefined || value.acknowledgedSeq >= expectedSeq) &&
    isSafeIntegerInRange(value.outstandingChunks, 0, TERMINAL_SESSION_OUTPUT_WINDOW)
  );
}

export function isTerminalSessionOutputEvent(
  value: unknown,
): value is TerminalSessionOutputEvent {
  return (
    isRecord(value) &&
    hasOnlyKeys(value, ['type', 'sessionId', 'seq', 'base64']) &&
    value.type === 'output' &&
    isValidTerminalSessionId(value.sessionId) &&
    isSafeIntegerInRange(value.seq, 1, Number.MAX_SAFE_INTEGER) &&
    typeof value.base64 === 'string' &&
    value.base64.length > 0 &&
    value.base64.length <= TERMINAL_SESSION_MAX_OUTPUT_BASE64_CHARS &&
    value.base64.length % 4 === 0 &&
    CANONICAL_BASE64_PATTERN.test(value.base64) &&
    (() => {
      const bytes = decodeBase64(value.base64);
      return bytes !== null && bytes.length > 0 && bytes.length <= 8 * 1024;
    })()
  );
}

export function isTerminalSessionExitEvent(
  value: unknown,
): value is TerminalSessionExitEvent {
  if (
    !isRecord(value) ||
    !hasOnlyKeys(value, ['type', 'sessionId', 'reason', 'exitCode', 'signal']) ||
    value.type !== 'exit' ||
    !isValidTerminalSessionId(value.sessionId) ||
    !nonEmptyString(value.reason) ||
    !isValidTerminalSessionStopReason(value.reason) ||
    !hasValidExitFields(value)
  ) {
    return false;
  }
  return true;
}

export function isTerminalSessionEvent(value: unknown): value is TerminalSessionEvent {
  return isTerminalSessionOutputEvent(value) || isTerminalSessionExitEvent(value);
}

/** The operation results a caller may trust, in one discriminated shape. */
export type TrustedSessionOperation = Readonly<
  | {kind: 'applied'}
  | {kind: 'error'; errorCode: TerminalSessionOperationErrorCode}
>;

export type TrustedSessionStart = Readonly<
  | {kind: 'success'; sessionId: string; pid: number; rows: number; columns: number}
  | {kind: 'error'; errorCode: TerminalSessionOperationErrorCode}
>;

export type TrustedSessionWrite = Readonly<
  | {kind: 'success'; bytesWritten: number}
  | {kind: 'error'; errorCode: TerminalSessionOperationErrorCode}
>;

export type TrustedSessionStop = Readonly<
  | {
      kind: 'success';
      sessionId: string;
      exitCode?: number;
      signal?: string;
      exitReason: string;
      remainingProcessCount: number;
      stoppedWithinDeadline: boolean;
    }
  | {
      /** Native completed its bounded attempt but observed a non-clean teardown. */
      kind: 'incomplete';
      sessionId: string;
      exitCode?: number;
      signal?: string;
      exitReason: string;
      remainingProcessCount: number;
      stoppedWithinDeadline: boolean;
    }
  | {kind: 'error'; errorCode: TerminalSessionOperationErrorCode}
>;

export type TrustedSessionSnapshot = Readonly<
  | {
      kind: 'success';
      sessionId: string;
      sessionState: 'running' | 'exited';
      firstAvailableSeq: number;
      lastEmittedSeq: number;
      replayAvailable: boolean;
      exitCode?: number;
      signal?: string;
      exitReason?: string;
    }
  | {kind: 'error'; errorCode: TerminalSessionOperationErrorCode}
>;

export type TrustedSessionAck = Readonly<
  | {
      kind: 'success';
      sessionId: string;
      acknowledgedSeq: number;
      outstandingChunks: number;
    }
  | {kind: 'error'; errorCode: TerminalSessionOperationErrorCode}
>;
