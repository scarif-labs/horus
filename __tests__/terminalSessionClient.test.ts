import {TerminalSessionClient} from '../src/terminal/session/sessionClient';
import {encodeBase64} from '../src/terminal/session/sessionContract';
import type {Spec} from '../src/native/NativeTerminalRuntime';
import {decodeTestBase64} from '../src/testSupport/base64';

/**
 * Host tests for the JS session client against a fake native runtime. The
 * fake runtime plus fake event subscription stand in for the bridge; the
 * suite runs under --runInBand --detectOpenHandles in the phase gate, so any
 * dangling listener or pending promise fails the phase.
 */

type RecordedCall = {method: string; request: unknown};

class FakeRuntime {
  calls: RecordedCall[] = [];
  startedSessions = 0;
  stoppedSessions: string[] = [];
  acknowledgedSequences: number[] = [];
  eventHandlers: Array<(event: unknown) => void> = [];

  startResponse: unknown = {
    requestId: '',
    status: 'success',
    sessionId: 's-1-1',
    pid: 4242,
    rows: 24,
    columns: 80,
  };

  readonly spec: Spec = {
    getRuntimeStatus: async () => ({status: 'error', errorCode: 'internal_error'}),
    installRootfs: async () => ({requestId: '', status: 'error', errorCode: 'internal_error'}),
    resetRuntime: async () => ({requestId: '', status: 'error', errorCode: 'internal_error'}),
    startSession: async (request: {requestId: string; rows?: number; columns?: number}) => {
      this.calls.push({method: 'startSession', request});
      this.startedSessions += 1;
      const response = this.injectRequestId(this.startResponse, request.requestId);
      if (
        typeof response === 'object' &&
        response !== null &&
        (response as Record<string, unknown>).status === 'success'
      ) {
        return {
          ...(response as Record<string, unknown>),
          rows: request.rows ?? (response as Record<string, unknown>).rows,
          columns: request.columns ?? (response as Record<string, unknown>).columns,
        };
      }
      return response;
    },
    writeSessionInput: async (request: {requestId: string; base64: string}) => {
      this.calls.push({method: 'writeSessionInput', request});
      return {
        requestId: request.requestId,
        status: 'success',
        bytesWritten: decodeTestBase64(request.base64).length,
      };
    },
    resizeSession: async (request: {requestId: string; rows: number; columns: number}) => {
      this.calls.push({method: 'resizeSession', request});
      return {requestId: request.requestId, status: 'success', rows: request.rows, columns: request.columns};
    },
    signalSession: async (request: {requestId: string; signal: string}) => {
      this.calls.push({method: 'signalSession', request});
      return {requestId: request.requestId, status: 'success', signal: request.signal};
    },
    stopSession: async (request: {requestId: string; sessionId: string}) => {
      this.calls.push({method: 'stopSession', request});
      this.stoppedSessions.push(request.sessionId);
      return {
        requestId: request.requestId,
        status: 'success',
        sessionId: request.sessionId,
        exitCode: 0,
        exitReason: 'user_stop',
        remainingProcessCount: 0,
        stoppedWithinDeadline: true,
      };
    },
    subscribeSessionEvents: async (request: {requestId: string; sessionId: string}) => {
      this.calls.push({method: 'subscribeSessionEvents', request});
      return {
        requestId: request.requestId,
        status: 'success',
        sessionId: request.sessionId,
        eventName: 'terminalSessionEvents',
        sessionState: 'running',
        firstAvailableSeq: 1,
        lastEmittedSeq: 0,
        replayAvailable: true,
      };
    },
    acknowledgeSessionOutput: async (request: {requestId: string; sessionId: string; seq: number}) => {
      this.acknowledgedSequences.push(request.seq);
      return {
        requestId: request.requestId,
        status: 'success',
        sessionId: request.sessionId,
        acknowledgedSeq: request.seq,
        outstandingChunks: 0,
      };
    },
    addListener: (_eventName: string) => undefined,
    removeListeners: (_count: number) => undefined,
  } as unknown as Spec;

  emit(event: unknown): void {
    for (const handler of [...this.eventHandlers]) {
      handler(event);
    }
  }

  private injectRequestId(response: unknown, requestId: string): unknown {
    if (typeof response === 'object' && response !== null) {
      return {...response, requestId};
    }
    return response;
  }
}

function fakeSubscription(runtime: FakeRuntime) {
  return {
    subscribe: (handler: (event: unknown) => void) => {
      runtime.eventHandlers.push(handler);
      return () => {
        const index = runtime.eventHandlers.indexOf(handler);
        if (index >= 0) runtime.eventHandlers.splice(index, 1);
      };
    },
  };
}

function recorder() {
  const output: Array<{seq: number; bytes: Uint8Array}> = [];
  const exits: unknown[] = [];
  const errors: unknown[] = [];
  return {
    output,
    exits,
    errors,
    attachment: {
      onOutput: (chunk: {seq: number; bytes: Uint8Array}) => output.push(chunk),
      onExit: (exit: unknown) => exits.push(exit),
      onProtocolError: (error: unknown) => errors.push(error),
    },
  };
}

describe('TerminalSessionClient operations', () => {
  it('starts a session and validates the wire response', async () => {
    const runtime = new FakeRuntime();
    const client = new TerminalSessionClient(runtime.spec, fakeSubscription(runtime).subscribe);
    const outcome = await client.startSession('start-1', {rows: 40, columns: 120});
    expect(outcome).toEqual({
      kind: 'success',
      sessionId: 's-1-1',
      pid: 4242,
      rows: 40,
      columns: 120,
    });
    expect(runtime.calls).toEqual([
      {method: 'startSession', request: {requestId: 'start-1', rows: 40, columns: 120}},
    ]);
  });

  it('maps bridge errors and malformed responses to trusted error views', async () => {
    const runtime = new FakeRuntime();
    runtime.startResponse = {requestId: '', status: 'error', errorCode: 'session_limit_reached'};
    const client = new TerminalSessionClient(runtime.spec, fakeSubscription(runtime).subscribe);
    expect(await client.startSession('start-1')).toEqual({
      kind: 'error',
      errorCode: 'session_limit_reached',
    });
    runtime.startResponse = {requestId: '', status: 'success', sessionId: 's-1-1'};
    expect(await client.startSession('start-2')).toEqual({
      kind: 'error',
      errorCode: 'invalid_response',
    });
    expect(await client.startSession('bad id!')).toEqual({
      kind: 'error',
      errorCode: 'invalid_request',
    });
  });

  it('requires native acknowledgements to match the requested operation', async () => {
    const runtime = new FakeRuntime();
    runtime.spec.writeSessionInput = async request => ({
      requestId: request.requestId,
      status: 'success',
      bytesWritten: 2,
    });
    runtime.spec.resizeSession = async request => ({
      requestId: request.requestId,
      status: 'success',
      rows: request.rows + 1,
      columns: request.columns,
    });
    runtime.spec.signalSession = async request => ({
      requestId: request.requestId,
      status: 'success',
      signal: request.signal === 'sigint' ? 'sigterm' : request.signal,
    });
    runtime.spec.stopSession = async request => ({
      requestId: request.requestId,
      status: 'success',
      sessionId: 's-other',
      exitReason: 'user_stop',
      remainingProcessCount: 0,
      stoppedWithinDeadline: true,
    });
      runtime.spec.subscribeSessionEvents = async request => ({
      requestId: request.requestId,
      status: 'success',
      sessionId: 's-other',
      eventName: 'terminalSessionEvents',
      sessionState: 'running',
      firstAvailableSeq: 1,
      lastEmittedSeq: 0,
      replayAvailable: true,
    });
    const client = new TerminalSessionClient(runtime.spec, fakeSubscription(runtime).subscribe);
    expect(await client.writeSessionInput('w-1', 's-1', new Uint8Array([1]))).toEqual({
      kind: 'error',
      errorCode: 'invalid_response',
    });
    expect(await client.resizeSession('r-1', 's-1', 24, 80)).toEqual({
      kind: 'error',
      errorCode: 'invalid_response',
    });
    expect(await client.signalSession('i-1', 's-1', 'sigint')).toEqual({
      kind: 'error',
      errorCode: 'invalid_response',
    });
    expect(await client.stopSession('s-1', 's-1', 'user_stop')).toEqual({
      kind: 'error',
      errorCode: 'invalid_response',
    });
    expect(await client.subscribeSessionStatus('sub-1', 's-1')).toEqual({
      kind: 'error',
      errorCode: 'invalid_response',
    });
  });

  it('does not label a bounded but non-clean stop as success', async () => {
    const runtime = new FakeRuntime();
    runtime.spec.stopSession = async request => ({
      requestId: request.requestId,
      status: 'success',
      sessionId: request.sessionId,
      exitReason: 'user_stop',
      remainingProcessCount: 1,
      stoppedWithinDeadline: false,
    });
    const client = new TerminalSessionClient(runtime.spec, fakeSubscription(runtime).subscribe);
    await expect(client.stopSession('stop-1', 's-1', 'user_stop')).resolves.toEqual({
      kind: 'incomplete',
      sessionId: 's-1',
      exitReason: 'user_stop',
      remainingProcessCount: 1,
      stoppedWithinDeadline: false,
    });
  });

  it('rejects writes, signals, and stops with invalid requests before the bridge', async () => {
    const runtime = new FakeRuntime();
    const client = new TerminalSessionClient(runtime.spec, fakeSubscription(runtime).subscribe);
    expect(await client.writeSessionInput('w-1', '../bad', new Uint8Array([1]))).toEqual({
      kind: 'error',
      errorCode: 'invalid_request',
    });
    expect(await client.signalSession('w-1', 's-1', 'nope' as 'sigint')).toEqual({
      kind: 'error',
      errorCode: 'invalid_request',
    });
    expect(await client.stopSession('w-1', 's-1', 'not a reason')).toEqual({
      kind: 'error',
      errorCode: 'invalid_request',
    });
    expect(runtime.calls).toEqual([]);
  });

  it('reports the unavailable runtime without throwing', async () => {
    const client = new TerminalSessionClient(null, () => () => undefined);
    expect(await client.startSession('start-1')).toEqual({kind: 'error', errorCode: 'unavailable'});
    expect(await client.stopSession('stop-1', 's-1', 'user_stop')).toEqual({
      kind: 'error',
      errorCode: 'unavailable',
    });
  });
});

describe('TerminalSessionClient event stream', () => {
  it('coalesces delivered output chunks into one cumulative acknowledgement', async () => {
    const runtime = new FakeRuntime();
    const client = new TerminalSessionClient(runtime.spec, fakeSubscription(runtime).subscribe);
    const seen = recorder();
    client.attach('s-1-1', seen.attachment);

    runtime.emit({type: 'output', sessionId: 's-1-1', seq: 1, base64: 'aGk='});
    runtime.emit({type: 'output', sessionId: 's-1-1', seq: 2, base64: 'aGk='});
    await new Promise<void>(resolve => setTimeout(resolve, 30));

    expect(runtime.acknowledgedSequences).toEqual([2]);
    client.dispose();
  });

  it('reports an ACK failure and stops acknowledging a damaged stream', async () => {
    const runtime = new FakeRuntime();
    runtime.spec.acknowledgeSessionOutput = async request => ({
      requestId: request.requestId,
      status: 'error',
      errorCode: 'session_exited',
    });
    const client = new TerminalSessionClient(runtime.spec, fakeSubscription(runtime).subscribe);
    const seen = recorder();
    client.attach('s-1-1', seen.attachment);

    runtime.emit({type: 'output', sessionId: 's-1-1', seq: 1, base64: 'aGk='});
    await Promise.resolve();
    await new Promise<void>(resolve => setTimeout(resolve, 30));
    runtime.emit({type: 'output', sessionId: 's-1-1', seq: 2, base64: 'aGk='});
    await new Promise<void>(resolve => setTimeout(resolve, 30));

    expect(seen.errors).toEqual([
      {
        sessionId: 's-1-1',
        kind: 'ack_failure',
        detail: 'ack seq=1 failed: session_exited',
      },
    ]);
    expect(runtime.acknowledgedSequences).toEqual([]);
    client.dispose();
  });

  it('attaches before subscribing so the initial buffered output has a listener', async () => {
    const runtime = new FakeRuntime();
    const client = new TerminalSessionClient(runtime.spec, fakeSubscription(runtime).subscribe);
    const seen = recorder();
    await expect(client.attachAndSubscribe('sub-1', 's-1-1', seen.attachment)).resolves.toEqual({
      kind: 'success',
      sessionId: 's-1-1',
      sessionState: 'running',
      firstAvailableSeq: 1,
      lastEmittedSeq: 0,
      replayAvailable: true,
    });
    expect(runtime.calls).toContainEqual({
      method: 'subscribeSessionEvents',
      request: {requestId: 'sub-1', sessionId: 's-1-1'},
    });
    runtime.emit({type: 'output', sessionId: 's-1-1', seq: 1, base64: 'aGk='});
    expect(seen.output).toHaveLength(1);
    client.dispose();
  });

  it('reports an initial replay gap and resumes acknowledgements from available output', async () => {
    const runtime = new FakeRuntime();
    runtime.spec.subscribeSessionEvents = async request => ({
      requestId: request.requestId,
      status: 'success',
      sessionId: request.sessionId,
      eventName: 'terminalSessionEvents',
      sessionState: 'running',
      firstAvailableSeq: 7,
      lastEmittedSeq: 8,
      replayAvailable: false,
    });
    const client = new TerminalSessionClient(runtime.spec, fakeSubscription(runtime).subscribe);
    const seen = recorder();
    await expect(client.attachAndSubscribe('sub-1', 's-1-1', seen.attachment)).resolves.toEqual({
      kind: 'success',
      sessionId: 's-1-1',
      sessionState: 'running',
      firstAvailableSeq: 7,
      lastEmittedSeq: 8,
      replayAvailable: false,
    });
    expect(seen.errors).toEqual([
      {sessionId: 's-1-1', kind: 'output_gap', detail: 'first available seq=7'},
    ]);
    runtime.emit({type: 'output', sessionId: 's-1-1', seq: 7, base64: 'aGk='});
    runtime.emit({type: 'output', sessionId: 's-1-1', seq: 8, base64: 'aGk='});
    await new Promise<void>(resolve => setTimeout(resolve, 30));
    expect(seen.output.map(chunk => chunk.seq)).toEqual([7, 8]);
    expect(runtime.acknowledgedSequences).toEqual([8]);
    client.dispose();
  });

  it('buffers the full native replay window before the subscribe response', async () => {
    const runtime = new FakeRuntime();
    runtime.spec.subscribeSessionEvents = async request => {
      for (let seq = 1; seq <= 174; seq += 1) {
        runtime.emit({
          type: 'output',
          sessionId: request.sessionId,
          seq,
          base64: encodeBase64(new TextEncoder().encode(`line-${seq}\r\n`)),
        });
      }
      return {
        requestId: request.requestId,
        status: 'success',
        sessionId: request.sessionId,
        eventName: 'terminalSessionEvents',
        sessionState: 'running',
        firstAvailableSeq: 1,
        lastEmittedSeq: 174,
        replayAvailable: true,
      };
    };
    const client = new TerminalSessionClient(runtime.spec, fakeSubscription(runtime).subscribe);
    const seen = recorder();

    await expect(client.attachAndSubscribe('sub-1', 's-1-1', seen.attachment)).resolves.toMatchObject({
      kind: 'success',
      firstAvailableSeq: 1,
      lastEmittedSeq: 174,
      replayAvailable: true,
    });
    expect(seen.output.map(chunk => chunk.seq)).toEqual(
      Array.from({length: 174}, (_, index) => index + 1),
    );
    expect(seen.errors).toEqual([]);
    await new Promise<void>(resolve => setTimeout(resolve, 30));
    expect(runtime.acknowledgedSequences).toEqual([174]);
    client.dispose();
  });

  it('clears a delayed acknowledgement when an attachment is disposed', async () => {
    const runtime = new FakeRuntime();
    const client = new TerminalSessionClient(runtime.spec, fakeSubscription(runtime).subscribe);
    const seen = recorder();
    client.attach('s-1-1', seen.attachment);

    runtime.emit({type: 'output', sessionId: 's-1-1', seq: 1, base64: 'aGk='});
    client.dispose();
    await new Promise<void>(resolve => setTimeout(resolve, 30));

    expect(runtime.acknowledgedSequences).toEqual([]);
  });

  it('delivers ordered output bytes and exactly one exit per attachment', () => {
    const runtime = new FakeRuntime();
    const client = new TerminalSessionClient(runtime.spec, fakeSubscription(runtime).subscribe);
    const seen = recorder();
    const detach = client.attach('s-1-1', seen.attachment);

    runtime.emit({type: 'output', sessionId: 's-1-1', seq: 1, base64: encodeBase64(new Uint8Array([65]))});
    runtime.emit({type: 'output', sessionId: 's-1-1', seq: 2, base64: encodeBase64(new Uint8Array([66]))});
    runtime.emit({type: 'output', sessionId: 'other-session', seq: 1, base64: 'aGk='});
    runtime.emit({type: 'exit', sessionId: 's-1-1', reason: 'process_exit', exitCode: 0});
    runtime.emit({type: 'exit', sessionId: 's-1-1', reason: 'process_exit', exitCode: 0});

    expect(seen.output.map(chunk => Array.from(chunk.bytes))).toEqual([[65], [66]]);
    expect(seen.exits).toEqual([{sessionId: 's-1-1', reason: 'process_exit', exitCode: 0}]);
    expect(seen.errors).toEqual([
      {sessionId: 's-1-1', kind: 'duplicate_exit', detail: 'event arrived after the exit event'},
    ]);
    detach();
  });

  it('surfaces a sequence gap once and resynchronizes without corrupting order', () => {
    const runtime = new FakeRuntime();
    const client = new TerminalSessionClient(runtime.spec, fakeSubscription(runtime).subscribe);
    const seen = recorder();
    client.attach('s-1-1', seen.attachment);

    runtime.emit({type: 'output', sessionId: 's-1-1', seq: 1, base64: encodeBase64(new Uint8Array([65]))});
    runtime.emit({type: 'output', sessionId: 's-1-1', seq: 3, base64: encodeBase64(new Uint8Array([67]))});
    runtime.emit({type: 'output', sessionId: 's-1-1', seq: 4, base64: encodeBase64(new Uint8Array([68]))});

    expect(seen.output.map(chunk => Array.from(chunk.bytes))).toEqual([[65], [68]]);
    expect(seen.errors).toEqual([
      {sessionId: 's-1-1', kind: 'sequence_gap', detail: 'expected seq=2 got seq=3'},
    ]);
    client.dispose();
  });

  it('drops duplicate output frames without reporting a sequence gap', () => {
    const runtime = new FakeRuntime();
    const client = new TerminalSessionClient(runtime.spec, fakeSubscription(runtime).subscribe);
    const seen = recorder();
    client.attach('s-1-1', seen.attachment);

    runtime.emit({type: 'output', sessionId: 's-1-1', seq: 1, base64: 'aGk='});
    runtime.emit({type: 'output', sessionId: 's-1-1', seq: 1, base64: 'aGk='});
    runtime.emit({type: 'output', sessionId: 's-1-1', seq: 2, base64: 'aGk='});

    expect(seen.output.map(chunk => chunk.seq)).toEqual([1, 2]);
    expect(seen.errors).toEqual([]);
    client.dispose();
  });

  it('drops malformed events without touching handlers', () => {
    const runtime = new FakeRuntime();
    const client = new TerminalSessionClient(runtime.spec, fakeSubscription(runtime).subscribe);
    const seen = recorder();
    client.attach('s-1-1', seen.attachment);

    runtime.emit({type: 'output', sessionId: 's-1-1', seq: 1});
    runtime.emit('not-an-object');
    runtime.emit({type: 'output', sessionId: 's-1-1', seq: 1, base64: '!!!'});
    runtime.emit({type: 'output', sessionId: 's-1-1', seq: 1, base64: 'QUJD='});
    runtime.emit({type: 'output', sessionId: 's-1-1', seq: 1, base64: encodeBase64(new Uint8Array([65]))});

    expect(seen.output.map(chunk => Array.from(chunk.bytes))).toEqual([[65]]);
    expect(seen.errors).toEqual([
      {
        sessionId: 's-1-1',
        kind: 'invalid_event',
        detail: 'event did not match the bounded session event contract',
      },
      {
        sessionId: 's-1-1',
        kind: 'invalid_base64',
        detail: 'event did not match the bounded session event contract',
      },
      {
        sessionId: 's-1-1',
        kind: 'invalid_base64',
        detail: 'event did not match the bounded session event contract',
      },
    ]);
    client.dispose();
  });

  it('supports repeated attach/detach cycles without leaking handlers', () => {
    const runtime = new FakeRuntime();
    const {subscribe} = fakeSubscription(runtime);
    const client = new TerminalSessionClient(runtime.spec, subscribe);
    for (let cycle = 0; cycle < 3; cycle++) {
      const seen = recorder();
      const detach = client.attach('s-1-1', seen.attachment);
      runtime.emit({type: 'output', sessionId: 's-1-1', seq: 1, base64: encodeBase64(new Uint8Array([65]))});
      expect(seen.output).toHaveLength(1);
      detach();
      expect(runtime.eventHandlers).toHaveLength(0);
      runtime.emit({type: 'output', sessionId: 's-1-1', seq: 2, base64: 'aGk='});
      expect(seen.output).toHaveLength(1);
    }
    expect(() => client.attach('s-1-1', recorder().attachment)).not.toThrow();
    client.dispose();
  });

  it('refuses a second attachment for the same session', () => {
    const runtime = new FakeRuntime();
    const client = new TerminalSessionClient(runtime.spec, fakeSubscription(runtime).subscribe);
    const detach = client.attach('s-1-1', recorder().attachment);
    expect(() => client.attach('s-1-1', recorder().attachment)).toThrow(/already has an attachment/);
    detach();
    client.dispose();
  });

  it('keeps stale disposers from detaching a replacement and reconnects cleanly', () => {
    const runtime = new FakeRuntime();
    const subscription = fakeSubscription(runtime);
    const client = new TerminalSessionClient(runtime.spec, subscription.subscribe);
    const first = recorder();
    const staleDetach = client.attach('s-1-1', first.attachment);
    staleDetach();
    const second = recorder();
    const detach = client.attach('s-1-1', second.attachment);
    staleDetach();
    runtime.emit({type: 'output', sessionId: 's-1-1', seq: 1, base64: 'aGk='});
    expect(second.output).toHaveLength(1);
    expect(client.reconnect()).toBe(true);
    expect(runtime.eventHandlers).toHaveLength(1);
    runtime.emit({type: 'output', sessionId: 's-1-1', seq: 2, base64: 'aGk='});
    expect(second.output.map(chunk => chunk.seq)).toEqual([1, 2]);
    detach();
    expect(runtime.eventHandlers).toHaveLength(0);
    client.dispose();
  });

  it('rejects invalid attachment ids and rolls back failed subscriptions', () => {
    const runtime = new FakeRuntime();
    const client = new TerminalSessionClient(runtime.spec, () => {
      throw new Error('disconnected');
    });
    expect(() => client.attach('../bad', recorder().attachment)).toThrow(/invalid terminal session id/);
    expect(() => client.attach('s-1-1', recorder().attachment)).toThrow(/disconnected/);
    expect(() => client.attach('s-1-1', recorder().attachment)).toThrow(/disconnected/);
  });
});

describe('TerminalSessionClient repeated start/stop cycles', () => {
  it('completes start → write → resize → signal → stop twice with clean teardown', async () => {
    const runtime = new FakeRuntime();
    const client = new TerminalSessionClient(runtime.spec, fakeSubscription(runtime).subscribe);

    for (let cycle = 0; cycle < 2; cycle++) {
      const start = await client.startSession(`start-${cycle}`);
      if (start.kind !== 'success') throw new Error('start failed');
      const seen = recorder();
      const detach = client.attach(start.sessionId, seen.attachment);
      runtime.emit({type: 'output', sessionId: start.sessionId, seq: 1, base64: 'aGk='});
      runtime.emit({type: 'exit', sessionId: start.sessionId, reason: 'process_exit', exitCode: 0});
      expect(await client.writeSessionInput(`w-${cycle}`, start.sessionId, new Uint8Array([10]))).toEqual({
        kind: 'success',
        bytesWritten: 1,
      });
      expect(await client.resizeSession(`r-${cycle}`, start.sessionId, 40, 120)).toEqual({kind: 'applied'});
      expect(await client.signalSession(`i-${cycle}`, start.sessionId, 'sigint')).toEqual({kind: 'applied'});
      const stop = await client.stopSession(`s-${cycle}`, start.sessionId, 'user_stop');
      expect(stop).toEqual({
        kind: 'success',
        sessionId: start.sessionId,
        exitCode: 0,
        exitReason: 'user_stop',
        remainingProcessCount: 0,
        stoppedWithinDeadline: true,
      });
      expect(seen.exits).toHaveLength(1);
      detach();
    }

    expect(runtime.startedSessions).toBe(2);
    expect(runtime.stoppedSessions).toEqual(['s-1-1', 's-1-1']);
    client.dispose();
    expect(runtime.eventHandlers).toHaveLength(0);
  });
});
