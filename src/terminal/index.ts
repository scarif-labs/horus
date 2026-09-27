/**
 * Public surface of the Alpine terminal runtime namespace. Files in this
 * namespace own the terminal contracts and remain isolated from unrelated
 * app features; scripts/scan-terminal-boundary.js enforces that boundary.
 */

export {
  detectHermesEngine,
  getTerminalCapabilityReport,
  type TerminalCapabilityReport,
  type TerminalCapabilityReportResult,
} from './capabilities';

export {
  buildInstallRootfsRequest,
  buildResetRuntimeRequest,
  isInstallRootfsResponse,
  isResetRuntimeResponse,
  isTerminalRuntimeStatusResponse as isTerminalRuntimeWireStatus,
  isValidTerminalRuntimeErrorCode,
  isValidRequestId,
  isValidRootfsId,
  isValidSha256Digest,
  PINNED_ALPINE_RELEASE,
  PINNED_PROBE_MARKERS,
  PINNED_ROOTFS_ID,
  PINNED_ROOTFS_SHA256,
  PINNED_ROOTFS_SIZE_BYTES,
  TERMINAL_RUNTIME_SCHEMA_VERSION,
  TERMINAL_RUNTIME_STATE_NOT_INSTALLED,
  TERMINAL_RUNTIME_STATE_READY,
  TERMINAL_RUNTIME_VERSION,
  type TerminalRuntimeOperationErrorCode,
  type TrustedResetOutcome,
  type TrustedInstallOutcome,
} from './distroContract';

export type {TerminalResetScope} from '../native/NativeTerminalRuntime';

export {
  ALPINE_TERMINAL_POC_EVIDENCE_SCHEMA,
  isAlpineGateRecord,
  type AlpineGateCheck,
  type AlpineGateRecord,
  type AlpineGateStatus,
  type AlpineCheckStatus,
  type AlpineGateDevice,
  type AlpineGateRuntime,
} from './evidence/gateRecord';

export {
  installRootfs,
  readTerminalDebugLog,
  resetRuntime,
  isTerminalRuntimeStatusResponse,
  readTerminalRuntimeStatus,
  type TerminalRuntimeErrorCode,
  type TerminalRuntimeStatusSuccess,
  type TerminalRuntimeStatusView,
  type TerminalDebugLogView,
} from './runtimeStatus';

export {
  decodeBase64,
  encodeBase64,
  buildResizeSessionRequest,
  buildSignalSessionRequest,
  buildStartSessionRequest,
  buildStopSessionRequest,
  buildSubscribeSessionEventsRequest,
  buildWriteSessionInputRequest,
  buildAcknowledgeSessionOutputRequest,
  isResizeSessionResponse,
  isSignalSessionResponse,
  isStartSessionResponse,
  isStopSessionResponse,
  isSubscribeSessionEventsResponse,
  isTerminalSessionEvent,
  isTerminalSessionExitEvent,
  isTerminalSessionOutputEvent,
  isWriteSessionInputResponse,
  isAcknowledgeSessionOutputResponse,
  isValidTerminalSessionColumns,
  isValidTerminalSessionId,
  isValidTerminalSessionRows,
  isValidTerminalSessionStopReason,
  TERMINAL_SESSION_DEFAULT_COLUMNS,
  TERMINAL_SESSION_DEFAULT_ROWS,
  TERMINAL_SESSION_EVENT_NAME,
  TERMINAL_SESSION_MAX_COLUMNS,
  TERMINAL_SESSION_MAX_INPUT_BASE64_CHARS,
  TERMINAL_SESSION_MAX_INPUT_BYTES,
  TERMINAL_SESSION_MAX_ROWS,
  TERMINAL_SESSION_MAX_OUTPUT_BASE64_CHARS,
  TERMINAL_SESSION_OUTPUT_WINDOW,
  TERMINAL_SESSION_MIN_COLUMNS,
  TERMINAL_SESSION_MIN_ROWS,
  TERMINAL_SESSION_SIGNALS,
  TERMINAL_SESSION_STOP_REASONS,
  type TerminalSessionErrorCode,
  type TerminalSessionOperationErrorCode,
  type TerminalSessionSignal,
  type TerminalSessionStopReason,
  type TrustedSessionOperation,
  type TrustedSessionSnapshot,
  type TrustedSessionStart,
  type TrustedSessionStop,
  type TrustedSessionWrite,
  type TrustedSessionAck,
} from './session/sessionContract';

export {
  TerminalSessionClient,
  isValidTerminalSessionRequestId,
  type TerminalSessionAttachment,
  type TerminalSessionExitView,
  type TerminalSessionOutputChunk,
  type TerminalSessionProtocolError,
  type SessionEventSubscription,
} from './session/sessionClient';

export {TerminalScreen, type TerminalScreenProps} from './TerminalScreen';
export {
  TerminalCellBuffer,
  type TerminalFrame,
  type TerminalSize,
} from './terminalBuffer';
export {
  terminalMouseWheelSequence,
  type TerminalMouseEncoding,
  type TerminalMouseWheel,
  type TerminalMouseWheelDirection,
} from './terminalMouse';

export * from './toolchain';
export * from './compatibility';
