import {NativeEventEmitter} from 'react-native';
import nativeTerminalRuntime from '../../native/NativeTerminalRuntime';
import type {Spec, TerminalSessionSignal, TerminalToolchainTarget} from '../../native/NativeTerminalRuntime';
import {TERMINAL_SESSION_EVENT_NAME} from '../../native/NativeTerminalRuntime';
import {
  isValidTerminalRuntimeErrorCode,
} from '../distroContract';
import {
  buildResizeSessionRequest,
  buildAcknowledgeSessionOutputRequest,
  buildDetachTerminalSessionRequest,
  buildListTerminalSessionsRequest,
  buildSignalSessionRequest,
  buildStartSessionRequest,
  buildStopSessionRequest,
  buildStopAllTerminalSessionsRequest,
  buildSubscribeSessionEventsRequest,
  buildWriteSessionInputRequest,
  decodeBase64,
  isResizeSessionResponse,
  isAcknowledgeSessionOutputResponse,
  isDetachTerminalSessionResponse,
  isListTerminalSessionsResponse,
  isSignalSessionResponse,
  isStartSessionResponse,
  isStopSessionResponse,
  isStopAllTerminalSessionsResponse,
  isSubscribeSessionEventsResponse,
  isTerminalSessionEvent,
  isTerminalSessionExitEvent,
  isTerminalSessionOutputEvent,
  isWriteSessionInputResponse,
  isValidTerminalSessionId,
  isValidRequestId,
  type TerminalSessionOperationErrorCode,
  type TrustedSessionOperation,
  type TrustedSessionAck,
  type TrustedSessionList,
  type TrustedSessionSnapshot,
  type TrustedSessionStart,
  type TrustedSessionStop,
  type TrustedSessionWrite,
  type ActiveTerminalSession,
  type TerminalSessionStopReason,
} from './sessionContract';

/**
 * JS view of the native PTY session surface. This layer owns event ordering:
 * every output event carries a per-session monotonic `seq`, and this client
 * delivers bytes in exactly that order, surfacing a gap instead of silently
 * corrupting the stream. The native module stays the authority; every wire
 * response and event is validated here before callers see it.
 *
 * Teardown is explicit: `detach` per session and `dispose` for the client
 * remove every listener, so a React unmount cannot leak subscriptions (the
 * focused tests run under --detectOpenHandles).
 */

export type TerminalSessionOutputChunk = Readonly<{
  sessionId: string;
  seq: number;
  bytes: Uint8Array;
}>;

export type TerminalSessionExitView = Readonly<{
  sessionId: string;
  reason: string;
  exitCode?: number;
  signal?: string;
}>;

export type TerminalSessionAttachment = {
  /** Ordered output bytes; gaps never reach this handler. */
  onOutput: (chunk: TerminalSessionOutputChunk) => void;
  /** Delivered exactly once, after every ordered output chunk. */
  onExit: (exit: TerminalSessionExitView) => void;
  /** Malformed events and sequence gaps are reported before delivery resumes. */
  onProtocolError: (error: TerminalSessionProtocolError) => void;
};

export type TerminalSessionProtocolError = Readonly<{
  sessionId: string;
  kind:
    | 'invalid_event'
    | 'sequence_gap'
    | 'invalid_base64'
    | 'duplicate_exit'
    | 'ack_failure'
    | 'output_gap';
  detail: string;
}>;

// Native sends the bounded replay tail before its subscribe response. Keep
// room for that window, 32 in-flight live chunks, and a session-end event.
const MAX_PENDING_ATTACH_EVENTS = 4_096 + 32 + 1;
const ACK_BATCH_SIZE = 8;
const ACK_BATCH_DELAY_MS = 24;

export type SessionEventSubscription = (handler: (event: unknown) => void) => () => void;

function defaultSubscription(runtime: Spec | null): SessionEventSubscription {
  if (runtime === null) {
    return () => () => undefined;
  }
  const emitter = new NativeEventEmitter(runtime);
  return handler => {
    const subscription = emitter.addListener(
      TERMINAL_SESSION_EVENT_NAME,
      handler as (event: unknown) => void,
    );
    return () => subscription.remove();
  };
}

export class TerminalSessionClient {
  private readonly runtime: Spec | null;
  private readonly subscribe: SessionEventSubscription;
  private readonly attachments = new Map<
    string,
    {
      handler: TerminalSessionAttachment;
      lastSeq: number;
      lastAcknowledgedSeq: number;
      exited: boolean;
      subscribing: boolean;
      pendingEvents: unknown[];
      pendingAckSeq: number;
      ackInFlight: boolean;
      ackTimer: ReturnType<typeof setTimeout> | null;
      ackFlushScheduled: boolean;
    }
  >();
  private readonly ackDisabled = new Set<string>();
  private ackRequestCounter = 0;
  private emitterDetach: (() => void) | null = null;

  constructor(
    runtime: Spec | null = nativeTerminalRuntime,
    subscribe: SessionEventSubscription | null = null,
  ) {
    this.runtime = runtime;
    this.subscribe = subscribe ?? defaultSubscription(runtime);
  }

  async startSession(
    requestId: string,
    options?: {rows?: number; columns?: number; command?: string; toolchain?: TerminalToolchainTarget; countsAgainstSessionLimit?: boolean},
  ): Promise<TrustedSessionStart> {
    return this.invoke(
      () => buildStartSessionRequest(requestId, options),
      (runtime, request) => runtime.startSession(request),
      response => isStartSessionResponse(response, requestId),
      response => ({
        kind: 'success',
        sessionId: response.sessionId!,
        pid: response.pid!,
        rows: response.rows!,
        columns: response.columns!,
      }),
    );
  }

  async listTerminalSessions(requestId: string): Promise<TrustedSessionList> {
    return this.invoke(
      () => buildListTerminalSessionsRequest(requestId),
      (runtime, request) => runtime.listTerminalSessions(request),
      response => isListTerminalSessionsResponse(response, requestId),
      response => ({kind: 'success', sessions: response.sessions! as readonly ActiveTerminalSession[]}),
    );
  }

  async detachTerminalSession(
    requestId: string,
    sessionId: string,
  ): Promise<TrustedSessionOperation> {
    return this.invoke(
      () => buildDetachTerminalSessionRequest(requestId, sessionId),
      (runtime, request) => runtime.detachTerminalSession(request),
      response => isDetachTerminalSessionResponse(response, requestId),
      appliedView,
    );
  }

  async stopAllTerminalSessions(
    requestId: string,
    reason: TerminalSessionStopReason,
  ): Promise<TrustedSessionOperation> {
    return this.invoke(
      () => buildStopAllTerminalSessionsRequest(requestId, reason),
      (runtime, request) => runtime.stopAllTerminalSessions(request),
      response => isStopAllTerminalSessionsResponse(response, requestId),
      appliedView,
    );
  }

  async writeSessionInput(
    requestId: string,
    sessionId: string,
    bytes: Uint8Array,
  ): Promise<TrustedSessionWrite> {
    return this.invoke(
      () => buildWriteSessionInputRequest(requestId, sessionId, bytes),
      (runtime, request) => runtime.writeSessionInput(request),
      response => isWriteSessionInputResponse(response, requestId, bytes.length),
      response => ({kind: 'success', bytesWritten: response.bytesWritten!}),
    );
  }

  async resizeSession(
    requestId: string,
    sessionId: string,
    rows: number,
    columns: number,
  ): Promise<TrustedSessionOperation> {
    return this.invoke(
      () => buildResizeSessionRequest(requestId, sessionId, rows, columns),
      (runtime, request) => runtime.resizeSession(request),
      response => isResizeSessionResponse(response, requestId, rows, columns),
      appliedView,
    );
  }

  async signalSession(
    requestId: string,
    sessionId: string,
    signal: TerminalSessionSignal,
  ): Promise<TrustedSessionOperation> {
    return this.invoke(
      () => buildSignalSessionRequest(requestId, sessionId, signal),
      (runtime, request) => runtime.signalSession(request),
      response => isSignalSessionResponse(response, requestId, signal),
      appliedView,
    );
  }

  async stopSession(
    requestId: string,
    sessionId: string,
    reason: string,
  ): Promise<TrustedSessionStop> {
    return this.invoke(
      () => buildStopSessionRequest(requestId, sessionId, reason),
      (runtime, request) => runtime.stopSession(request),
      response => isStopSessionResponse(response, requestId, sessionId),
      response => {
        const clean =
          response.remainingProcessCount === 0 && response.stoppedWithinDeadline === true;
        return {
          kind: clean ? ('success' as const) : ('incomplete' as const),
          sessionId: response.sessionId!,
          ...(response.exitCode !== undefined ? {exitCode: response.exitCode} : {}),
          ...(response.signal !== undefined ? {signal: response.signal} : {}),
          exitReason: response.exitReason!,
          remainingProcessCount: response.remainingProcessCount!,
          stoppedWithinDeadline: response.stoppedWithinDeadline!,
        };
      },
    );
  }

  async subscribeSessionStatus(
    requestId: string,
    sessionId: string,
    afterSeq = 0,
  ): Promise<TrustedSessionSnapshot> {
    return this.invoke(
      () => buildSubscribeSessionEventsRequest(requestId, sessionId, afterSeq),
      (runtime, request) => runtime.subscribeSessionEvents(request),
      response => isSubscribeSessionEventsResponse(response, requestId, sessionId, afterSeq),
      response => ({
        kind: 'success',
        sessionId: response.sessionId!,
        sessionState: response.sessionState!,
        firstAvailableSeq: response.firstAvailableSeq!,
        lastEmittedSeq: response.lastEmittedSeq!,
        replayAvailable: response.replayAvailable!,
        ...(response.exitCode !== undefined ? {exitCode: response.exitCode} : {}),
        ...(response.signal !== undefined ? {signal: response.signal} : {}),
        ...(response.exitReason !== undefined ? {exitReason: response.exitReason} : {}),
      }),
    );
  }

  async acknowledgeSessionOutput(
    requestId: string,
    sessionId: string,
    seq: number,
  ): Promise<TrustedSessionAck> {
    return this.invoke(
      () => buildAcknowledgeSessionOutputRequest(requestId, sessionId, seq),
      (runtime, request) => runtime.acknowledgeSessionOutput(request),
      response => isAcknowledgeSessionOutputResponse(response, requestId, sessionId, seq),
      response => ({
        kind: 'success',
        sessionId: response.sessionId!,
        acknowledgedSeq: response.acknowledgedSeq!,
        outstandingChunks: response.outstandingChunks!,
      }),
    );
  }

  /**
   * Shared RPC path, in a fixed order: build the request, require the runtime,
   * call native, validate the wire response, then map a native error code or
   * the success body. Anything unexpected becomes a typed error view.
   */
  private async invoke<Request, Response extends RuntimeResponse, Result>(
    buildRequest: () => Request | null,
    call: (runtime: Spec, request: Request) => Promise<unknown>,
    isValid: (response: unknown) => response is Response,
    toResult: (response: Response) => Result,
  ): Promise<Result | SessionErrorView> {
    const request = buildRequest();
    if (request === null) return errorView('invalid_request');
    const runtime = this.runtime;
    if (runtime === null) return errorView('unavailable');
    let response: unknown;
    try {
      response = await call(runtime, request);
    } catch {
      return errorView('internal_error');
    }
    if (!isValid(response)) {
      return errorView('invalid_response');
    }
    if (response.status === 'error') {
      return isValidTerminalRuntimeErrorCode(response.errorCode)
        ? errorView(response.errorCode)
        : errorView('invalid_response');
    }
    return toResult(response);
  }

  /** Attaches before opening native delivery, preventing the initial prompt from racing the listener. */
  async attachAndSubscribe(
    requestId: string,
    sessionId: string,
    attachment: TerminalSessionAttachment,
    afterSeq = 0,
  ): Promise<TrustedSessionSnapshot> {
    const detach = this.attach(sessionId, attachment);
    const attaching = this.attachments.get(sessionId);
    if (attaching !== undefined) attaching.subscribing = true;
    const outcome = await this.subscribeSessionStatus(requestId, sessionId, afterSeq);
    if (outcome.kind === 'error') {
      detach();
      return outcome;
    }
    const attached = this.attachments.get(sessionId);
    if (attached !== undefined) {
      const buffered = attached.pendingEvents.splice(0);
      const firstBufferedSeq = buffered.reduce<number | undefined>((first, event) =>
        isTerminalSessionOutputEvent(event) && event.sessionId === sessionId && event.seq > afterSeq
          ? first === undefined ? event.seq : Math.min(first, event.seq)
          : first,
      undefined);
      const firstAvailable = Math.min(outcome.firstAvailableSeq, firstBufferedSeq ?? outcome.firstAvailableSeq);
      const effectiveCursor = afterSeq > outcome.lastEmittedSeq ? 0 : afterSeq;
      attached.lastSeq = Math.max(effectiveCursor, firstAvailable - 1);
      attached.lastAcknowledgedSeq = attached.lastSeq;
      attached.subscribing = false;
      if (firstAvailable > effectiveCursor + 1) {
        this.reportProtocolError(attached, {
          sessionId,
          kind: 'output_gap',
          detail: `first available seq=${firstAvailable}`,
        });
      }
      this.replayPendingEvents(sessionId, buffered, attached.lastSeq);
    }
    return outcome;
  }

  /** Starts ordered delivery for one session; the returned function detaches. */
  attach(sessionId: string, attachment: TerminalSessionAttachment): () => void {
    if (!isValidTerminalSessionId(sessionId)) {
      throw new Error('invalid terminal session id');
    }
    if (
      attachment === null ||
      typeof attachment !== 'object' ||
      typeof attachment.onOutput !== 'function' ||
      typeof attachment.onExit !== 'function' ||
      typeof attachment.onProtocolError !== 'function'
    ) {
      throw new Error('invalid terminal session attachment');
    }
    if (this.attachments.has(sessionId)) {
      throw new Error(`session ${sessionId} already has an attachment`);
    }
    const entry = {
      handler: attachment,
      lastSeq: 0,
      lastAcknowledgedSeq: 0,
      exited: false,
      subscribing: false,
      pendingEvents: [] as unknown[],
      pendingAckSeq: 0,
      ackInFlight: false,
      ackTimer: null as ReturnType<typeof setTimeout> | null,
      ackFlushScheduled: false,
    };
    this.attachments.set(sessionId, entry);
    if (this.emitterDetach === null) {
      try {
        const detach = this.subscribe(event => this.handleEvent(event));
        if (typeof detach !== 'function') {
          throw new Error('session event subscription did not return a disposer');
        }
        let detached = false;
        this.emitterDetach = () => {
          if (detached) return;
          detached = true;
          detach();
        };
      } catch (error) {
        this.attachments.delete(sessionId);
        throw error;
      }
    }
    return () => {
      // An old disposer must not detach a newer attachment for the same id.
      if (this.attachments.get(sessionId) !== entry) return;
      this.attachments.delete(sessionId);
      this.clearAckTimer(entry);
      this.ackDisabled.delete(sessionId);
      void this.detachTerminalSession(this.nextControlRequestId('detach'), sessionId);
      if (this.attachments.size === 0) {
        this.detachEmitter();
      }
    };
  }

  /** Rebinds the single native event listener after a bridge reconnect. */
  reconnect(): boolean {
    if (this.attachments.size === 0) return false;
    this.detachEmitter();
    try {
      const detach = this.subscribe(event => this.handleEvent(event));
      if (typeof detach !== 'function') {
        throw new Error('session event subscription did not return a disposer');
      }
      let detached = false;
      this.emitterDetach = () => {
        if (detached) return;
        detached = true;
        detach();
      };
      return true;
    } catch {
      return false;
    }
  }

  /** Removes every attachment and the emitter subscription. */
  dispose(): void {
    const sessionIds = [...this.attachments.keys()];
    const entries = [...this.attachments.values()];
    this.attachments.clear();
    entries.forEach(entry => this.clearAckTimer(entry));
    this.ackDisabled.clear();
    this.detachEmitter();
    sessionIds.forEach(sessionId => {
      void this.detachTerminalSession(this.nextControlRequestId('detach'), sessionId);
    });
  }

  private detachEmitter(): void {
    const detach = this.emitterDetach;
    this.emitterDetach = null;
    try {
      detach?.();
    } catch {
      // A bridge teardown race must not leave the client in a half-detached
      // state or make React unmount throw.
    }
  }

  private handleEvent(event: unknown): void {
    const eventSessionId =
      typeof event === 'object' && event !== null && !Array.isArray(event) &&
      typeof (event as Record<string, unknown>).sessionId === 'string'
        ? (event as Record<string, unknown>).sessionId
        : null;
    if (eventSessionId === null || !isValidTerminalSessionId(eventSessionId)) return;
    const attached = this.attachments.get(eventSessionId);
    if (attached === undefined) return;
    if (attached.subscribing) {
      if (attached.pendingEvents.length < MAX_PENDING_ATTACH_EVENTS) {
        attached.pendingEvents.push(event);
      }
      return;
    }
    if (!isTerminalSessionEvent(event)) {
      const record = event as Record<string, unknown>;
      this.reportProtocolError(attached, {
        sessionId: eventSessionId,
        kind:
          record.type === 'output' &&
          typeof record.base64 === 'string' &&
          decodeBase64(record.base64) === null
            ? 'invalid_base64'
            : 'invalid_event',
        detail: 'event did not match the bounded session event contract',
      });
      this.ackDisabled.add(eventSessionId);
      return;
    }
    if (attached.exited) {
      this.reportProtocolError(attached, {
        sessionId: event.sessionId,
        kind: 'duplicate_exit',
        detail: 'event arrived after the exit event',
      });
      return;
    }
    if (isTerminalSessionOutputEvent(event)) {
      const bytes = decodeBase64(event.base64);
      if (bytes === null) {
        this.reportProtocolError(attached, {
          sessionId: event.sessionId,
          kind: 'invalid_base64',
          detail: `seq=${event.seq}`,
        });
        this.ackDisabled.add(event.sessionId);
        return;
      }
      if (event.seq <= attached.lastSeq) {
        // Reattachment can deliver an output frame from both live delivery and
        // the replay ring. It has already been rendered and acknowledged, so
        // stale or duplicate frames are harmless and should not look like a gap.
        return;
      }
      if (event.seq !== attached.lastSeq + 1) {
        this.reportProtocolError(attached, {
          sessionId: event.sessionId,
          kind: 'sequence_gap',
          detail: `expected seq=${attached.lastSeq + 1} got seq=${event.seq}`,
        });
        // Drop the first chunk after a gap, then continue from its sequence.
        // Acknowledging that chunk keeps a damaged UI stream from stalling the PTY.
        if (event.seq > attached.lastSeq) {
          attached.lastSeq = event.seq;
          this.scheduleOutputAck(attached, event.sessionId, event.seq);
        }
        return;
      }
      attached.lastSeq = event.seq;
      try {
        attached.handler.onOutput({sessionId: event.sessionId, seq: event.seq, bytes});
      } catch {
        // Consumer failures must not tear down the native event subscription.
      }
      this.scheduleOutputAck(attached, event.sessionId, event.seq);
      return;
    }
    if (isTerminalSessionExitEvent(event)) {
      attached.exited = true;
      try {
        attached.handler.onExit({
          sessionId: event.sessionId,
          reason: event.reason,
          ...(event.exitCode !== undefined ? {exitCode: event.exitCode} : {}),
          ...(event.signal !== undefined ? {signal: event.signal} : {}),
        });
      } catch {
        // Consumer failures must not prevent exactly-once exit bookkeeping.
      }
    }
  }

  private replayPendingEvents(
    sessionId: string,
    events: unknown[],
    afterSeq: number,
  ): void {
    const outputs = new Map<number, unknown>();
    const otherEvents: unknown[] = [];
    events.forEach(event => {
      if (isTerminalSessionOutputEvent(event) && event.sessionId === sessionId) {
        if (event.seq > afterSeq && !outputs.has(event.seq)) outputs.set(event.seq, event);
      } else {
        otherEvents.push(event);
      }
    });
    [...outputs.entries()]
      .sort(([left], [right]) => left - right)
      .forEach(([, event]) => this.handleEvent(event));
    otherEvents.forEach(event => this.handleEvent(event));
  }

  private nextControlRequestId(prefix: string): string {
    this.ackRequestCounter =
      this.ackRequestCounter >= Number.MAX_SAFE_INTEGER
        ? 1
        : this.ackRequestCounter + 1;
    return `${prefix}-${this.ackRequestCounter.toString(36)}`;
  }

  private reportProtocolError(
    attached: {handler: TerminalSessionAttachment; lastSeq: number; exited: boolean},
    error: TerminalSessionProtocolError,
  ): void {
    try {
      attached.handler.onProtocolError(error);
    } catch {
      // Consumer failures must not tear down the native event subscription.
    }
  }

  private scheduleOutputAck(
    attached: {
      handler: TerminalSessionAttachment;
      lastSeq: number;
      lastAcknowledgedSeq: number;
      exited: boolean;
      pendingAckSeq: number;
      ackInFlight: boolean;
      ackTimer: ReturnType<typeof setTimeout> | null;
      ackFlushScheduled: boolean;
    },
    sessionId: string,
    seq: number,
  ): void {
    if (this.ackDisabled.has(sessionId)) return;
    attached.pendingAckSeq = Math.max(attached.pendingAckSeq, seq);
    if (attached.pendingAckSeq - attached.lastAcknowledgedSeq >= ACK_BATCH_SIZE) {
      if (!attached.ackFlushScheduled) {
        attached.ackFlushScheduled = true;
        void Promise.resolve().then(() => {
          attached.ackFlushScheduled = false;
          this.clearAckTimer(attached);
          void this.flushOutputAck(attached, sessionId);
        });
      }
      return;
    }
    if (attached.ackTimer === null) {
      attached.ackTimer = setTimeout(() => {
        attached.ackTimer = null;
        void this.flushOutputAck(attached, sessionId);
      }, ACK_BATCH_DELAY_MS);
    }
  }

  private async flushOutputAck(
    attached: {
      handler: TerminalSessionAttachment;
      lastSeq: number;
      lastAcknowledgedSeq: number;
      exited: boolean;
      pendingAckSeq: number;
      ackInFlight: boolean;
      ackTimer: ReturnType<typeof setTimeout> | null;
      ackFlushScheduled: boolean;
    },
    sessionId: string,
  ): Promise<void> {
    if (
      this.ackDisabled.has(sessionId) ||
      this.attachments.get(sessionId) !== attached ||
      attached.ackInFlight ||
      attached.pendingAckSeq <= attached.lastAcknowledgedSeq
    ) return;
    const seq = attached.pendingAckSeq;
    attached.ackInFlight = true;
    const outcome = await this.acknowledgeSessionOutput(
      this.nextAckRequestId(),
      sessionId,
      seq,
    );
    attached.ackInFlight = false;
    if (this.attachments.get(sessionId) !== attached) return;
    if (outcome.kind !== 'success') {
      this.ackDisabled.add(sessionId);
      this.clearAckTimer(attached);
      this.reportProtocolError(attached, {
        sessionId,
        kind: 'ack_failure',
        detail: `ack seq=${seq} failed: ${outcome.errorCode}`,
      });
      return;
    }
    if (outcome.acknowledgedSeq < seq) {
      this.ackDisabled.add(sessionId);
      this.clearAckTimer(attached);
      this.reportProtocolError(attached, {
        sessionId,
        kind: 'ack_failure',
        detail: `ack seq=${seq} returned seq=${outcome.acknowledgedSeq}`,
      });
      return;
    }
    attached.lastAcknowledgedSeq = Math.max(attached.lastAcknowledgedSeq, outcome.acknowledgedSeq);
    if (attached.pendingAckSeq > attached.lastAcknowledgedSeq) {
      this.scheduleOutputAck(attached, sessionId, attached.pendingAckSeq);
    }
  }

  private clearAckTimer(attached: {ackTimer: ReturnType<typeof setTimeout> | null}): void {
    if (attached.ackTimer === null) return;
    clearTimeout(attached.ackTimer);
    attached.ackTimer = null;
  }

  private nextAckRequestId(): string {
    this.ackRequestCounter =
      this.ackRequestCounter >= Number.MAX_SAFE_INTEGER
        ? 1
        : this.ackRequestCounter + 1;
    return `ack-${this.ackRequestCounter.toString(36)}`;
  }
}

type RuntimeResponse = {status: 'success' | 'error'; errorCode?: unknown};
type SessionErrorView = {kind: 'error'; errorCode: TerminalSessionOperationErrorCode};

function errorView(errorCode: TerminalSessionOperationErrorCode): SessionErrorView {
  return {kind: 'error', errorCode};
}

function appliedView(): {kind: 'applied'} {
  return {kind: 'applied'};
}

export {isValidRequestId as isValidTerminalSessionRequestId};
