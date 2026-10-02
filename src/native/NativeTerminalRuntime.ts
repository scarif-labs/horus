import {TurboModuleRegistry, type TurboModule} from 'react-native';

/** Values mirrored from TerminalRuntimeContract.kt. */
export const TERMINAL_RUNTIME_MODULE_NAME = 'TerminalRuntime' as const;
export const TERMINAL_RUNTIME_SCHEMA_VERSION = 4 as const;
export const TERMINAL_RUNTIME_VERSION = 'p2-pty' as const;
export const TERMINAL_RUNTIME_STATE_NOT_INSTALLED = 'not_installed' as const;
export const TERMINAL_RUNTIME_STATE_READY = 'ready' as const;
export const TERMINAL_RUNTIME_RESET_SCOPES = [
  'rootfs',
  'home',
  'workspace',
  'all-user-data',
] as const;

/** Single bridge event name; every session event carries its sessionId. */
export const TERMINAL_SESSION_EVENT_NAME = 'terminalSessionEvents' as const;
/** Maximum number of output chunks native may hold ahead of JS ACKs. */
export const TERMINAL_SESSION_OUTPUT_WINDOW = 32 as const;

/** Signals the bridge may deliver to a session's process group. */
export const TERMINAL_SESSION_SIGNALS = [
  'sighup',
  'sigint',
  'sigquit',
  'sigterm',
  'sigkill',
] as const;

/**
 * Native surface for the Alpine terminal runtime
 * (phases 0–2). Phase 0 added the no-op capability snapshot; Phase 1 added
 * rootfs install (download → verify → extract → probe → promote), richer
 * status, and scoped reset; Phase 2 adds the native PTY session surface:
 * start/write/resize/signal/stop plus session events. The module owns paths,
 * processes, and deadlines; request fields are length-limited and validated
 * on both sides of the bridge.
 */

/**
 * Runtime states known so far. Sessions carry their own running/exited
 * state; `installing` is not reported by getRuntimeStatus because installs
 * are synchronous request/response operations, not ambient state.
 */
export type TerminalRuntimeState =
  | 'not_installed'
  | 'ready';

export const TERMINAL_RUNTIME_ERROR_CODES = [
  'internal_error',
  'invalid_request',
  'runtime_unavailable',
  'install_in_progress',
  'download_failed',
  'import_failed',
  'import_cancelled',
  'digest_mismatch',
  'extraction_failed',
  'probe_failed',
  'promote_failed',
  'session_not_found',
  'session_limit_reached',
  'session_exited',
  'session_spawn_failed',
  'session_write_failed',
  'toolchain_install_failed',
  'toolchain_incomplete',
  'toolchain_network',
  'toolchain_node_version',
  'toolchain_permissions',
  'toolchain_github_install_failed',
  'toolchain_codex_install_failed',
  'toolchain_opencode_install_failed',
  'toolchain_normalization_failed',
] as const;

export type TerminalRuntimeErrorCode =
  | 'internal_error'
  | 'invalid_request'
  | 'runtime_unavailable'
  | 'install_in_progress'
  | 'download_failed'
  | 'import_failed'
  | 'import_cancelled'
  | 'digest_mismatch'
  | 'extraction_failed'
  | 'probe_failed'
  | 'promote_failed'
  | 'session_not_found'
  | 'session_limit_reached'
  | 'session_exited'
  | 'session_spawn_failed'
  | 'session_write_failed'
  | 'toolchain_install_failed'
  | 'toolchain_incomplete'
  | 'toolchain_network'
  | 'toolchain_node_version'
  | 'toolchain_permissions'
  | 'toolchain_github_install_failed'
  | 'toolchain_codex_install_failed'
  | 'toolchain_opencode_install_failed'
  | 'toolchain_normalization_failed';

export type TerminalRuntimeStatusResponse = {
  status: 'success' | 'error';
  schemaVersion?: number;
  runtimeState?: TerminalRuntimeState;
  runtimeVersion?: string;
  abi?: string;
  apiLevel?: number;
  appVersion?: string;
  storageRoot?: string;
  prootAvailable?: boolean;
  prootVersion?: string;
  activeRootfsId?: string;
  activeAlpineRelease?: string;
  activeRootfsSha256?: string;
  activeInstalledAt?: string;
  installedVersionIds?: string[];
  errorCode?: TerminalRuntimeErrorCode;
};

export type SessionSettingsResponse = {
  status: 'success' | 'error';
  maxConcurrentSessions?: number;
  minConcurrentSessions?: number;
  maxSupportedConcurrentSessions?: number;
  errorCode?: TerminalRuntimeErrorCode;
};

export type SetSessionLimitRequest = {
  maxConcurrentSessions: number;
};

export type TerminalDebugLogResponse = {
  status: 'success' | 'error';
  path?: string;
  externalPath?: string;
  bytes?: number;
  tail?: string;
  errorCode?: TerminalRuntimeErrorCode;
};

export type InstallRootfsRequest = {
  requestId: string;
  /** Optional; must match the pinned catalog id when present. */
  rootfsId?: string;
};

export type InstallRootfsResponse = {
  requestId: string;
  status: 'success' | 'error';
  rootfsId?: string;
  archiveSha256?: string;
  archiveBytes?: number;
  extractionFiles?: number;
  probeExitCode?: number;
  probeMarkers?: string[];
  reusedCache?: boolean;
  durationMs?: number;
  errorCode?: TerminalRuntimeErrorCode;
};

export type ProvisionToolchainRequest = {
  requestId: string;
  target: TerminalToolchainTarget;
};

export const TERMINAL_TOOLCHAIN_TARGETS = [
  'shell',
  'github',
  'claude',
  'codex',
  'opencode',
] as const;

export type TerminalToolchainTarget = 'shell' | 'github' | 'claude' | 'codex' | 'opencode';

export type ProvisionToolchainResponse = {
  requestId: string;
  status: 'success' | 'error';
  errorCode?: TerminalRuntimeErrorCode;
};

export type TerminalResetScope =
  | 'rootfs'
  | 'home'
  | 'workspace'
  | 'all-user-data';

export type ResetRuntimeRequest = {
  requestId: string;
  scope: TerminalResetScope;
};

export type ResetRuntimeResponse = {
  requestId: string;
  status: 'success' | 'error';
  removedVersionIds?: string[];
  homeRemoved?: boolean;
  workspacesRemoved?: boolean;
  errorCode?: TerminalRuntimeErrorCode;
};

export type TerminalSessionSignal =
  | 'sighup'
  | 'sigint'
  | 'sigquit'
  | 'sigterm'
  | 'sigkill';

export type TerminalSessionStopReason =
  | 'user_stop'
  | 'screen_detach'
  | 'screen_lock'
  | 'bridge_invalidated'
  | 'runtime_reset';

export type TerminalSessionSummary = {
  sessionId: string;
  toolchain: TerminalToolchainTarget;
  startedAtMs: number;
};

export type ListTerminalSessionsRequest = {
  requestId: string;
};

export type ListTerminalSessionsResponse = {
  requestId: string;
  status: 'success' | 'error';
  sessions?: TerminalSessionSummary[];
  errorCode?: TerminalRuntimeErrorCode;
};

export type DetachTerminalSessionRequest = {
  requestId: string;
  sessionId: string;
};

export type DetachTerminalSessionResponse = {
  requestId: string;
  status: 'success' | 'error';
  errorCode?: TerminalRuntimeErrorCode;
};

export type UpdateUnlockGrantRequest = {
  requestId: string;
  /** 'grant' | 'revoke' | 'background' | 'resume' */
  op: string;
};

export type UpdateUnlockGrantResponse = {
  requestId: string;
  status: 'success' | 'error';
  unlocked?: boolean;
  errorCode?: TerminalRuntimeErrorCode;
};

export type StopAllTerminalSessionsRequest = {
  requestId: string;
  reason: TerminalSessionStopReason;
};

export type StopAllTerminalSessionsResponse = {
  requestId: string;
  status: 'success' | 'error';
  errorCode?: TerminalRuntimeErrorCode;
};

export type StartSessionRequest = {
  requestId: string;
  /** Optional pty seed size; the module enforces 2..250 rows. */
  rows?: number;
  /** Optional pty seed size; the module enforces 2..500 columns. */
  columns?: number;
  /** Fixed launcher command executed by the native zsh session boundary. */
  command?: string;
  /** Optional target whose idempotent installer runs visibly before the shell. */
  toolchain?: TerminalToolchainTarget;
  /** Bounded background utility PTYs can opt out of the user session quota. */
  countsAgainstSessionLimit?: boolean;
};

export type StartSessionResponse = {
  requestId: string;
  status: 'success' | 'error';
  sessionId?: string;
  pid?: number;
  rows?: number;
  columns?: number;
  errorCode?: TerminalRuntimeErrorCode;
};

export type WriteSessionInputRequest = {
  requestId: string;
  sessionId: string;
  /** Base64 of the raw bytes to forward without line buffering. */
  base64: string;
};

export type WriteSessionInputResponse = {
  requestId: string;
  status: 'success' | 'error';
  bytesWritten?: number;
  errorCode?: TerminalRuntimeErrorCode;
};

export type ResizeSessionRequest = {
  requestId: string;
  sessionId: string;
  rows: number;
  columns: number;
};

export type ResizeSessionResponse = {
  requestId: string;
  status: 'success' | 'error';
  rows?: number;
  columns?: number;
  errorCode?: TerminalRuntimeErrorCode;
};

export type SignalSessionRequest = {
  requestId: string;
  sessionId: string;
  signal: TerminalSessionSignal;
};

export type SignalSessionResponse = {
  requestId: string;
  status: 'success' | 'error';
  signal?: TerminalSessionSignal;
  errorCode?: TerminalRuntimeErrorCode;
};

export type StopSessionRequest = {
  requestId: string;
  sessionId: string;
  /** Bounded machine-readable reason such as `user_stop` or `bridge_invalidated`. */
  reason: string;
};

export type StopSessionResponse = {
  requestId: string;
  status: 'success' | 'error';
  sessionId?: string;
  exitCode?: number;
  signal?: string;
  exitReason?: string;
  remainingProcessCount?: number;
  stoppedWithinDeadline?: boolean;
  errorCode?: TerminalRuntimeErrorCode;
};

export type TerminalSessionEventName = 'terminalSessionEvents';

export type SubscribeSessionEventsRequest = {
  requestId: string;
  sessionId: string;
  /** Highest contiguous output sequence already rendered by this attachment. */
  afterSeq?: number;
};

export type SubscribeSessionEventsResponse = {
  requestId: string;
  status: 'success' | 'error';
  sessionId?: string;
  eventName?: TerminalSessionEventName;
  sessionState?: 'running' | 'exited';
  firstAvailableSeq?: number;
  lastEmittedSeq?: number;
  replayAvailable?: boolean;
  exitCode?: number;
  signal?: string;
  exitReason?: string;
  errorCode?: TerminalRuntimeErrorCode;
};

export type AcknowledgeSessionOutputRequest = {
  requestId: string;
  sessionId: string;
  seq: number;
};

export type AcknowledgeSessionOutputResponse = {
  requestId: string;
  status: 'success' | 'error';
  sessionId?: string;
  acknowledgedSeq?: number;
  outstandingChunks?: number;
  errorCode?: TerminalRuntimeErrorCode;
};

export type GuestFileRootName = 'home' | 'workspace';

export type GuestFileErrorCode =
  | 'internal_error'
  | 'invalid_request'
  | 'invalid_path'
  | 'not_found'
  | 'too_large'
  | 'command_failed';

export type ListGuestDirectoryRequest = {
  requestId: string;
  root: GuestFileRootName;
  /** Components below the root; native re-validates every one. */
  path: string[];
};

export type GuestDirectoryEntry = {
  name: string;
  kind: 'directory' | 'file' | 'symlink' | 'other';
  sizeBytes: number;
};

export type ListGuestDirectoryResponse = {
  requestId: string;
  status: 'success' | 'error';
  entries?: GuestDirectoryEntry[];
  truncated?: boolean;
  hiddenInvalidNameCount?: number;
  errorCode?: GuestFileErrorCode;
};

export type ReadGuestFileRequest = {
  requestId: string;
  root: GuestFileRootName;
  path: string[];
};

export type ReadGuestFileResponse = {
  requestId: string;
  status: 'success' | 'error';
  /** Base64 of at most 64 KiB + 1 raw bytes; JS performs the UTF-8 checks. */
  base64?: string;
  sizeBytes?: number;
  errorCode?: GuestFileErrorCode;
};

export type ExportGuestDirectoryRequest = {
  requestId: string;
  root: GuestFileRootName;
  path: string[];
};

export type ExportGuestDirectoryErrorCode =
  | 'internal_error'
  | 'invalid_request'
  | 'invalid_path'
  | 'not_found'
  | 'too_large'
  | 'command_failed'
  | 'permission_denied'
  | 'busy';

export type ExportGuestDirectoryResponse = {
  requestId: string;
  status: 'success' | 'error';
  /** Shared-storage folder the files were written to, e.g. Download/Horus/src-20260928-143205. */
  destination?: string;
  fileCount?: number;
  byteCount?: number;
  /** Symlinks, special files, undecodable names, and files that failed to copy. */
  skippedCount?: number;
  errorCode?: ExportGuestDirectoryErrorCode;
};

/**
 * Event payloads delivered on TERMINAL_SESSION_EVENT_NAME via the device
 * event emitter (they do not pass through codegen). Output chunks carry a
 * per-session monotonic `seq` so consumers can verify ordering and detect
 * gaps; the exit event is emitted exactly once per session.
 */
export type TerminalSessionOutputEvent = {
  type: 'output';
  sessionId: string;
  seq: number;
  base64: string;
};

export type TerminalSessionExitEvent = {
  type: 'exit';
  sessionId: string;
  reason: string;
  exitCode?: number;
  signal?: string;
};

export type TerminalSessionEvent =
  | TerminalSessionOutputEvent
  | TerminalSessionExitEvent;

export interface Spec extends TurboModule {
  getRuntimeStatus(): Promise<TerminalRuntimeStatusResponse>;
  getSessionSettings(): Promise<SessionSettingsResponse>;
  setSessionLimit(request: SetSessionLimitRequest): Promise<SessionSettingsResponse>;
  /** Returns the bounded, credential-free persisted native diagnostic tail. */
  getDebugLog(): Promise<TerminalDebugLogResponse>;
  installRootfs(request: InstallRootfsRequest): Promise<InstallRootfsResponse>;
  /** Opens the system file picker and installs the archive the user picks. */
  importRootfs(request: InstallRootfsRequest): Promise<InstallRootfsResponse>;
  provisionToolchain(request: ProvisionToolchainRequest): Promise<ProvisionToolchainResponse>;
  resetRuntime(request: ResetRuntimeRequest): Promise<ResetRuntimeResponse>;
  startSession(request: StartSessionRequest): Promise<StartSessionResponse>;
  listTerminalSessions(request: ListTerminalSessionsRequest): Promise<ListTerminalSessionsResponse>;
  writeSessionInput(request: WriteSessionInputRequest): Promise<WriteSessionInputResponse>;
  resizeSession(request: ResizeSessionRequest): Promise<ResizeSessionResponse>;
  signalSession(request: SignalSessionRequest): Promise<SignalSessionResponse>;
  stopSession(request: StopSessionRequest): Promise<StopSessionResponse>;
  stopAllTerminalSessions(request: StopAllTerminalSessionsRequest): Promise<StopAllTerminalSessionsResponse>;
  subscribeSessionEvents(request: SubscribeSessionEventsRequest): Promise<SubscribeSessionEventsResponse>;
  detachTerminalSession(request: DetachTerminalSessionRequest): Promise<DetachTerminalSessionResponse>;
  acknowledgeSessionOutput(request: AcknowledgeSessionOutputRequest): Promise<AcknowledgeSessionOutputResponse>;
  /**
   * Updates the terminal service's in-memory unlock grant, which outlives a
   * reclaimed UI process while sessions keep the service alive. Never
   * persisted; `resume` reports whether the grant is still valid.
   */
  updateUnlockGrant(request: UpdateUnlockGrantRequest): Promise<UpdateUnlockGrantResponse>;
  /** Returns, once, the session id of a tapped Horus notification, or null. */
  consumeLaunchSessionId(): Promise<string | null>;
  /**
   * Resolves the trusted URL drawn at a zero-based content row/column of a
   * native session's current frame, or null. Call only on a tap.
   */
  terminalLinkAt(sessionId: string, row: number, column: number): Promise<string | null>;
  /** The text of an exited native session's last screen, once; or null. */
  takeExitScreen(sessionId: string): Promise<string | null>;
  /** The clipboard's text, or null when it holds none (or too much). */
  readClipboardText(): Promise<string | null>;
  /** Whether the app in a native session turned on bracketed paste. */
  isBracketedPaste(sessionId: string): Promise<boolean>;
  /** Lists one guest home/workspace directory directly from app-private storage. */
  listGuestDirectory(request: ListGuestDirectoryRequest): Promise<ListGuestDirectoryResponse>;
  /** Reads a capped regular-file preview directly from app-private storage. */
  readGuestFile(request: ReadGuestFileRequest): Promise<ReadGuestFileResponse>;
  /** Copies a guest directory's regular files into the shared Download/Horus folder. */
  exportGuestDirectory(request: ExportGuestDirectoryRequest): Promise<ExportGuestDirectoryResponse>;
  /** No-op required by NativeEventEmitter; events are emitted natively. */
  addListener(eventName: string): void;
  /** No-op required by NativeEventEmitter; events are emitted natively. */
  removeListeners(count: number): void;
}

export default TurboModuleRegistry.get<Spec>('TerminalRuntime') ?? null;
