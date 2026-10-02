import React from 'react';
import ReactTestRenderer from 'react-test-renderer';
import {ActivityIndicator, Keyboard, KeyboardAvoidingView, Linking, StyleSheet, Text, TextInput, Vibration} from 'react-native';
import {TerminalGrid} from '../src/terminal/TerminalGrid';
import type {Spec} from '../src/native/NativeTerminalRuntime';
import {TerminalSessionClient} from '../src/terminal/session/sessionClient';
import {TerminalScreen} from '../src/terminal/TerminalScreen';
import {decodeTestBase64, encodeTestBase64} from '../src/testSupport/base64';

function terminalRows(renderer: ReactTestRenderer.ReactTestRenderer) {
  const seen = new Set<string>();
  return renderer.root.findAll(node => {
    const testID = node.props.testID;
    if (typeof testID !== 'string' || !testID.startsWith('terminal-row-') || seen.has(testID)) return false;
    seen.add(testID);
    return true;
  });
}

function rowText(renderer: ReactTestRenderer.ReactTestRenderer | undefined, row = 0): string {
  const rows = terminalRows(renderer!);
  return rows[row].props.accessibilityLabel.trimEnd();
}

function terminalCell(renderer: ReactTestRenderer.ReactTestRenderer, row: number, column: number) {
  const rows = terminalRows(renderer);
  return rows[row].findAll(node => typeof node.props.testID === 'string' &&
    node.props.testID.startsWith('terminal-cell-') && node.props.testID.endsWith(`-${column}`))[0];
}

function terminalLink(renderer: ReactTestRenderer.ReactTestRenderer, row: number, linkIndex: number) {
  const rows = terminalRows(renderer);
  return rows[row].findAll(node => typeof node.props.testID === 'string' &&
    node.props.testID.startsWith('terminal-link-') && node.props.testID.endsWith(`-${linkIndex}`))[0];
}

function cursorLeft(renderer: ReactTestRenderer.ReactTestRenderer | undefined): number {
  return StyleSheet.flatten(renderer!.root.findByProps({testID: 'terminal-cursor'}).props.style).left;
}

beforeEach(() => jest.useFakeTimers());
afterEach(async () => {
  await jest.runAllTimersAsync();
  expect(jest.getTimerCount()).toBe(0);
  jest.useRealTimers();
  jest.restoreAllMocks();
});

function fakeRuntime(): {spec: Spec; emit: (event: unknown) => void; listeners: Array<(event: unknown) => void>; stopCalls: string[]} {
  const listeners: Array<(event: unknown) => void> = [];
  const stopCalls: string[] = [];
  const spec = {
    getRuntimeStatus: async () => runtimeStatus(),
    installRootfs: async () => ({requestId: '', status: 'error', errorCode: 'internal_error'}),
    provisionToolchain: jest.fn().mockImplementation(async (request: {requestId: string}) => ({requestId: request.requestId, status: 'success'})),
    resetRuntime: async () => ({requestId: '', status: 'error', errorCode: 'internal_error'}),
    startSession: async (request: {requestId: string; rows?: number; columns?: number}) => ({requestId: request.requestId, status: 'success', sessionId: 's-1-1', pid: 1, rows: request.rows ?? 24, columns: request.columns ?? 80}),
    writeSessionInput: async (request: {requestId: string; base64: string}) => ({requestId: request.requestId, status: 'success', bytesWritten: decodeTestBase64(request.base64).length}),
    resizeSession: async (request: {requestId: string; rows: number; columns: number}) => ({requestId: request.requestId, status: 'success', rows: request.rows, columns: request.columns}),
    signalSession: async (request: {requestId: string; signal: string}) => ({requestId: request.requestId, status: 'success', signal: request.signal}),
    listTerminalSessions: async (request: {requestId: string}) => ({requestId: request.requestId, status: 'success', sessions: []}),
    stopSession: async (request: {requestId: string; sessionId: string}) => { stopCalls.push(request.sessionId); return {requestId: request.requestId, status: 'success', sessionId: request.sessionId, exitCode: 0, exitReason: 'user_stop', remainingProcessCount: 0, stoppedWithinDeadline: true}; },
    subscribeSessionEvents: async (request: {requestId: string; sessionId: string}) => ({requestId: request.requestId, status: 'success', sessionId: request.sessionId, eventName: 'terminalSessionEvents', sessionState: 'running', firstAvailableSeq: 1, lastEmittedSeq: 0, replayAvailable: true}),
    acknowledgeSessionOutput: async (request: {requestId: string; sessionId: string; seq: number}) => ({requestId: request.requestId, status: 'success', sessionId: request.sessionId, acknowledgedSeq: request.seq, outstandingChunks: 0}),
    addListener: () => undefined,
    removeListeners: () => undefined,
  } as unknown as Spec;
  return {spec, listeners, stopCalls, emit: event => [...listeners].forEach(listener => listener(event))};
}

function runtimeStatus(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    status: 'success',
    schemaVersion: 4,
    runtimeState: 'ready',
    runtimeVersion: 'p2-pty',
    abi: 'arm64-v8a',
    apiLevel: 35,
    appVersion: '0.0.1',
    storageRoot: '/data/user/0/com.scariflabs.horus/files/horus',
    prootAvailable: true,
    prootVersion: '5.4.0',
    installedVersionIds: ['alpine-3.24.0-aarch64'],
    activeRootfsId: 'alpine-3.24.0-aarch64',
    activeAlpineRelease: '3.24.0',
    activeRootfsSha256: '4b8cd66a6688b2a87276c39843ed89c3a06d9534fc6a5823c586aff2696c1f2a',
    activeInstalledAt: '2026-09-08T00:00:00.000Z',
    ...overrides,
  };
}

async function flushAsync(): Promise<void> {
  for (let index = 0; index < 8; index += 1) await Promise.resolve();
  await jest.runAllTimersAsync();
}

function terminalTouch(pageX: number, pageY: number): {nativeEvent: {identifier: string; pageX: number; pageY: number; changedTouches: Array<{identifier: string; pageX: number; pageY: number}>; touches: Array<{identifier: string; pageX: number; pageY: number}>}} {
  const touch = {identifier: 'terminal-touch', pageX, pageY};
  return {nativeEvent: {...touch, changedTouches: [touch], touches: [touch]}};
}

describe('TerminalScreen', () => {
  test('installs a missing runtime, refreshes status, then starts', async () => {
    const runtime = fakeRuntime();
    const statuses = [
      runtimeStatus({runtimeState: 'not_installed', installedVersionIds: [], activeRootfsId: undefined, activeAlpineRelease: undefined, activeRootfsSha256: undefined, activeInstalledAt: undefined}),
      runtimeStatus(),
    ];
    runtime.spec.getRuntimeStatus = jest.fn().mockImplementation(async () => statuses.shift());
    runtime.spec.installRootfs = jest.fn().mockResolvedValue({
      requestId: 'terminal-install-1', status: 'success', rootfsId: 'alpine-3.24.0-aarch64',
      archiveSha256: '4b8cd66a6688b2a87276c39843ed89c3a06d9534fc6a5823c586aff2696c1f2a',
      archiveBytes: 4043766, extractionFiles: 1, probeExitCode: 0,
      probeMarkers: ['alpine_probe_begin', 'aarch64', '3.24.0', '/root', '/sbin/apk', '/bin/sh', 'alpine_probe_end'],
      reusedCache: false, durationMs: 1,
    });
    runtime.spec.startSession = jest.fn().mockResolvedValue({requestId: 'start-1', status: 'success', sessionId: 's-1-1', pid: 1, rows: 24, columns: 80});
    const client = new TerminalSessionClient(runtime.spec, handler => {
      runtime.listeners.push(handler);
      return () => { const index = runtime.listeners.indexOf(handler); if (index >= 0) runtime.listeners.splice(index, 1); };
    });
    let renderer: ReactTestRenderer.ReactTestRenderer | undefined;
    await ReactTestRenderer.act(async () => { renderer = ReactTestRenderer.create(<TerminalScreen client={client} runtime={runtime.spec} />); });
    await ReactTestRenderer.act(async () => { await flushAsync(); });
    expect(runtime.spec.installRootfs).toHaveBeenCalledTimes(1);
    expect(runtime.spec.provisionToolchain).not.toHaveBeenCalled();
    expect(runtime.spec.startSession).toHaveBeenCalledTimes(1);
    expect(runtime.spec.startSession).toHaveBeenCalledWith(expect.objectContaining({toolchain: 'shell'}));
    await ReactTestRenderer.act(async () => { renderer?.unmount(); });
  });

  test('starts the selected harness with its installer attached to the visible session', async () => {
    const runtime = fakeRuntime();
    runtime.spec.startSession = jest.fn(runtime.spec.startSession);
    let renderer: ReactTestRenderer.ReactTestRenderer | undefined;
    await ReactTestRenderer.act(async () => { renderer = ReactTestRenderer.create(<TerminalScreen client={new TerminalSessionClient(runtime.spec)} runtime={runtime.spec} toolchain="codex" sessionCommand="codex" />); });
    await ReactTestRenderer.act(async () => { await flushAsync(); });
    expect(runtime.spec.provisionToolchain).not.toHaveBeenCalled();
    expect(runtime.spec.startSession).toHaveBeenCalledTimes(1);
    expect(runtime.spec.startSession).toHaveBeenCalledWith(expect.objectContaining({command: 'codex', toolchain: 'codex'}));
    await ReactTestRenderer.act(async () => { renderer?.unmount(); });
  });

  test('shows an over-limit replay gap as a notice instead of a terminal error', async () => {
    const runtime = fakeRuntime();
    runtime.spec.subscribeSessionEvents = jest.fn(async (request: {requestId: string; sessionId: string}) => ({
      requestId: request.requestId,
      status: 'success' as const,
      sessionId: request.sessionId,
      eventName: 'terminalSessionEvents' as const,
      sessionState: 'running' as const,
      firstAvailableSeq: 174,
      lastEmittedSeq: 174,
      replayAvailable: false,
    }));
    const client = new TerminalSessionClient(runtime.spec, handler => {
      runtime.listeners.push(handler);
      return () => { const index = runtime.listeners.indexOf(handler); if (index >= 0) runtime.listeners.splice(index, 1); };
    });
    let renderer: ReactTestRenderer.ReactTestRenderer | undefined;
    await ReactTestRenderer.act(async () => {
      renderer = ReactTestRenderer.create(
        <TerminalScreen client={client} runtime={runtime.spec} existingSessionId="s-1-1" stopSessionOnUnmount={false} />,
      );
      await flushAsync();
    });

    expect(renderer?.root.findByProps({testID: 'terminal-history-gap'}).props.children).toBe(
      'Some earlier terminal output is unavailable.',
    );
    expect(renderer?.root.findAllByProps({testID: 'terminal-error'})).toHaveLength(0);
    expect(renderer?.root.findByProps({testID: 'terminal-input'}).props.editable).toBe(true);

    await ReactTestRenderer.act(async () => { renderer?.unmount(); await flushAsync(); });
    expect(runtime.listeners).toHaveLength(0);
  });

  test('replays the full retained history when resuming a session', async () => {
    const runtime = fakeRuntime();
    const transcript = Array.from({length: 174}, (_, index) => `line-${index + 1}\r\n`).join('');
    runtime.spec.writeSessionInput = jest.fn(runtime.spec.writeSessionInput);
    runtime.spec.subscribeSessionEvents = jest.fn(async (request: {requestId: string; sessionId: string}) => {
      runtime.emit({type: 'output', sessionId: request.sessionId, seq: 1, base64: encodeTestBase64(transcript)});
      return {
        requestId: request.requestId,
        status: 'success' as const,
        sessionId: request.sessionId,
        eventName: 'terminalSessionEvents' as const,
        sessionState: 'running' as const,
        firstAvailableSeq: 1,
        lastEmittedSeq: 1,
        replayAvailable: true,
      };
    });
    const client = new TerminalSessionClient(runtime.spec, handler => {
      runtime.listeners.push(handler);
      return () => { const index = runtime.listeners.indexOf(handler); if (index >= 0) runtime.listeners.splice(index, 1); };
    });
    let renderer: ReactTestRenderer.ReactTestRenderer | undefined;
    await ReactTestRenderer.act(async () => {
      renderer = ReactTestRenderer.create(
        <TerminalScreen client={client} runtime={runtime.spec} existingSessionId="s-1-1" stopSessionOnUnmount={false} />,
      );
      await flushAsync();
    });
    await ReactTestRenderer.act(async () => { await flushAsync(); });

    expect(renderer?.root.findAllByProps({testID: 'terminal-history-gap'})).toHaveLength(0);
    expect(renderer?.root.findAllByProps({testID: 'terminal-error'})).toHaveLength(0);

    const frame = renderer?.root.findByType(TerminalGrid).props.frame;
    expect(frame.lines[0].text.trimEnd()).toBe('line-1');
    expect(frame.lines[173].text.trimEnd()).toBe('line-174');

    await ReactTestRenderer.act(async () => {
      runtime.emit({type: 'output', sessionId: 's-1-1', seq: 2, base64: encodeTestBase64('line-175\r\n')});
      await flushAsync();
    });
    expect(renderer?.root.findByType(TerminalGrid).props.frame.lines[174].text.trimEnd()).toBe('line-175');

    await ReactTestRenderer.act(async () => {
      renderer?.root.findByProps({testID: 'terminal-input'}).props.onChangeText('pwd');
      await flushAsync();
    });
    expect((runtime.spec.writeSessionInput as jest.Mock).mock.calls.map(call => decodeTestBase64(call[0].base64))).toContainEqual(
      new TextEncoder().encode('pwd'),
    );

    await ReactTestRenderer.act(async () => { renderer?.unmount(); await flushAsync(); });
    expect(runtime.listeners).toHaveLength(0);
  });

  test('resumes a session after replaying more than 256 small output events', async () => {
    const runtime = fakeRuntime();
    const replayChunks = 300;
    runtime.spec.subscribeSessionEvents = jest.fn(async (request: {requestId: string; sessionId: string}) => {
      for (let seq = 1; seq <= replayChunks; seq += 1) {
        runtime.emit({type: 'output', sessionId: request.sessionId, seq, base64: encodeTestBase64('x')});
      }
      return {
        requestId: request.requestId,
        status: 'success' as const,
        sessionId: request.sessionId,
        eventName: 'terminalSessionEvents' as const,
        sessionState: 'running' as const,
        firstAvailableSeq: 1,
        lastEmittedSeq: replayChunks,
        replayAvailable: true,
      };
    });
    const client = new TerminalSessionClient(runtime.spec, handler => {
      runtime.listeners.push(handler);
      return () => { const index = runtime.listeners.indexOf(handler); if (index >= 0) runtime.listeners.splice(index, 1); };
    });
    let renderer: ReactTestRenderer.ReactTestRenderer | undefined;
    await ReactTestRenderer.act(async () => {
      renderer = ReactTestRenderer.create(
        <TerminalScreen client={client} runtime={runtime.spec} existingSessionId="s-1-1" stopSessionOnUnmount={false} />,
      );
      await flushAsync();
    });
    await ReactTestRenderer.act(async () => { await flushAsync(); });

    expect(renderer?.root.findAllByProps({testID: 'terminal-error'})).toHaveLength(0);
    const frame = renderer?.root.findByType(TerminalGrid).props.frame;
    expect(frame.lines.map((line: {text: string}) => line.text.trimEnd()).filter(Boolean).join('')).toBe('x'.repeat(replayChunks));

    await ReactTestRenderer.act(async () => { renderer?.unmount(); await flushAsync(); });
    expect(runtime.listeners).toHaveLength(0);
  });

  test('keeps OpenCode startup visible until its first nonblank alternate-screen frame', async () => {
    const runtime = fakeRuntime();
    const client = new TerminalSessionClient(runtime.spec, handler => {
      runtime.listeners.push(handler);
      return () => { const index = runtime.listeners.indexOf(handler); if (index >= 0) runtime.listeners.splice(index, 1); };
    });
    let renderer!: ReactTestRenderer.ReactTestRenderer;
    await ReactTestRenderer.act(async () => {
      renderer = ReactTestRenderer.create(
        <TerminalScreen client={client} runtime={runtime.spec} runtimeReady toolchain="opencode" sessionCommand="opencode" />,
      );
      await flushAsync();
    });
    expect(renderer.root.findByType(TerminalGrid).props.frame).toBeUndefined();
    expect(renderer.root.findAllByProps({testID: 'terminal-output-grid'})).toHaveLength(0);

    await ReactTestRenderer.act(async () => {
      runtime.emit({type: 'output', sessionId: 's-1-1', seq: 1, base64: encodeTestBase64('HORUS_TOOLCHAIN_')});
      runtime.emit({type: 'output', sessionId: 's-1-1', seq: 2, base64: encodeTestBase64('READY\n')});
      await flushAsync();
    });
    await ReactTestRenderer.act(async () => {
      runtime.emit({type: 'output', sessionId: 's-1-1', seq: 3, base64: encodeTestBase64('\u001b[?1049h\u001b[2J\u001b[H')});
      await flushAsync();
    });
    expect(renderer.root.findByType(TerminalGrid).props.frame.alternate).toBe(true);
    expect(renderer.root.findAllByProps({testID: 'terminal-startup-progress'}).length).toBeGreaterThan(0);
    expect(renderer.root.findAllByProps({testID: 'harness-mark-opencode'}).length).toBeGreaterThan(0);

    await ReactTestRenderer.act(async () => {
      runtime.emit({type: 'output', sessionId: 's-1-1', seq: 4, base64: encodeTestBase64('OpenCode')});
      await flushAsync();
    });
    expect(renderer.root.findAllByProps({testID: 'terminal-startup-progress'})).toHaveLength(0);
    expect(rowText(renderer)).toContain('OpenCode');
    await ReactTestRenderer.act(async () => { renderer.unmount(); await flushAsync(); });
  });

  test('starts GitHub with its installer attached to the visible session', async () => {
    const runtime = fakeRuntime();
    runtime.spec.startSession = jest.fn(runtime.spec.startSession);
    let renderer: ReactTestRenderer.ReactTestRenderer | undefined;
    await ReactTestRenderer.act(async () => {
      renderer = ReactTestRenderer.create(
        <TerminalScreen
          client={new TerminalSessionClient(runtime.spec)}
          runtime={runtime.spec}
          toolchain="github"
          sessionCommand="gh auth login"
        />,
      );
      await flushAsync();
    });
    expect(runtime.spec.provisionToolchain).not.toHaveBeenCalled();
    expect(runtime.spec.startSession).toHaveBeenCalledWith(expect.objectContaining({command: expect.stringContaining('gh auth login'), toolchain: 'github'}));
    await ReactTestRenderer.act(async () => { renderer?.unmount(); });
  });

  test('waits for the visible workspace command to exit before completing its handoff', async () => {
    const runtime = fakeRuntime();
    const onCompletion = jest.fn();
    const marker = 'HORUS_PROJECT_CLONE_COMPLETE_launcher-clone-1';
    const client = new TerminalSessionClient(runtime.spec, handler => {
      runtime.listeners.push(handler);
      return () => { runtime.listeners.splice(runtime.listeners.indexOf(handler), 1); };
    });
    let renderer!: ReactTestRenderer.ReactTestRenderer;
    await ReactTestRenderer.act(async () => {
      renderer = ReactTestRenderer.create(
        <TerminalScreen
          client={client}
          completionMarker={marker}
          onCompletion={onCompletion}
          runtime={runtime.spec}
          runtimeReady
          sessionCommand="git clone"
          stopSessionOnUnmount={false}
        />,
      );
      await flushAsync();
    });

    await ReactTestRenderer.act(async () => {
      runtime.emit({type: 'output', sessionId: 's-1-1', seq: 1, base64: encodeTestBase64('HORUS_TOOLCHAIN_READY\n')});
      runtime.emit({type: 'output', sessionId: 's-1-1', seq: 2, base64: encodeTestBase64(`${marker.slice(0, 20)}`)});
      runtime.emit({type: 'output', sessionId: 's-1-1', seq: 3, base64: encodeTestBase64(`${marker.slice(20)}\n`)});
      await flushAsync();
    });
    expect(onCompletion).not.toHaveBeenCalled();

    await ReactTestRenderer.act(async () => {
      runtime.emit({type: 'exit', sessionId: 's-1-1', reason: 'process_exit', exitCode: 0});
      await flushAsync();
    });
    expect(onCompletion).toHaveBeenCalledTimes(1);

    await ReactTestRenderer.act(async () => { renderer.unmount(); await flushAsync(); });
    expect(runtime.listeners).toHaveLength(0);
  });

  test('shows install progress instead of the apk transcript on first launch, with the log one tap away', async () => {
    const runtime = fakeRuntime();
    const client = new TerminalSessionClient(runtime.spec, handler => {
      runtime.listeners.push(handler);
      return () => { runtime.listeners.splice(runtime.listeners.indexOf(handler), 1); };
    });
    let renderer!: ReactTestRenderer.ReactTestRenderer;
    await ReactTestRenderer.act(async () => {
      renderer = ReactTestRenderer.create(
        <TerminalScreen client={client} runtime={runtime.spec} runtimeReady sessionCommand="codex" toolchain="codex" />,
      );
      await flushAsync();
    });
    const emitText = async (seq: number, text: string) => {
      await ReactTestRenderer.act(async () => {
        runtime.emit({type: 'output', sessionId: 's-1-1', seq, base64: encodeTestBase64(text)});
        await flushAsync();
      });
    };
    const has = (testID: string) => renderer.root.findAllByProps({testID}).length > 0;
    const stepIsActive = (index: number) =>
      renderer.root.findByProps({testID: `terminal-install-step-${index}`}).findAllByType(ActivityIndicator).length > 0;

    expect(has('terminal-install-progress')).toBe(false);
    await emitText(1, 'HORUS_INSTALL_TARGET=codex\nHORUS_INSTALL_STAGE=start\nHORUS_INSTALL_STAGE=apk\n(1/7) Installing ada-libs\n');
    expect(has('terminal-install-progress')).toBe(true);
    expect(stepIsActive(1)).toBe(true);

    // One toggle, in one spot, switches between progress and log both ways.
    const toggle = () => renderer.root.findByProps({testID: 'terminal-install-log-toggle'});
    expect(toggle().props.accessibilityLabel).toBe('Show install log');
    await ReactTestRenderer.act(async () => { toggle().props.onPress(); });
    expect(has('terminal-install-progress')).toBe(false);
    expect(toggle().props.accessibilityLabel).toBe('Show install progress');
    await ReactTestRenderer.act(async () => { toggle().props.onPress(); });
    expect(has('terminal-install-progress')).toBe(true);
    expect(has('harness-mark-codex')).toBe(true);

    await emitText(2, 'HORUS_INSTALL_STAGE=base_ready\nHORUS_INSTALL_STAGE=codex\n');
    expect(stepIsActive(2)).toBe(true);

    // A failed stage hands the screen back to the transcript and its error.
    await emitText(3, 'HORUS_INSTALL_STAGE=codex_failed\n');
    expect(has('terminal-install-progress')).toBe(false);
    await ReactTestRenderer.act(async () => { renderer.unmount(); await flushAsync(); });
  });

  test('shows install progress when returning to a session that is still installing', async () => {
    const runtime = fakeRuntime();
    const client = new TerminalSessionClient(runtime.spec, handler => {
      runtime.listeners.push(handler);
      return () => { runtime.listeners.splice(runtime.listeners.indexOf(handler), 1); };
    });
    let renderer!: ReactTestRenderer.ReactTestRenderer;
    await ReactTestRenderer.act(async () => {
      renderer = ReactTestRenderer.create(
        <TerminalScreen client={client} existingSessionId="s-1-1" runtime={runtime.spec} runtimeReady toolchain="codex" />,
      );
      await flushAsync();
    });
    await ReactTestRenderer.act(async () => {
      runtime.emit({type: 'output', sessionId: 's-1-1', seq: 1, base64: encodeTestBase64('HORUS_INSTALL_STAGE=start\nHORUS_INSTALL_STAGE=codex\n')});
      await flushAsync();
    });
    expect(renderer.root.findAllByProps({testID: 'terminal-install-progress'}).length).toBeGreaterThan(0);

    await ReactTestRenderer.act(async () => {
      runtime.emit({type: 'output', sessionId: 's-1-1', seq: 2, base64: encodeTestBase64('HORUS_INSTALL_STAGE=ready\nHORUS_TOOLCHAIN_READY\n')});
      await flushAsync();
    });
    expect(renderer.root.findAllByProps({testID: 'terminal-install-progress'})).toHaveLength(0);
    await ReactTestRenderer.act(async () => { renderer.unmount(); await flushAsync(); });
  });

  test('completes a workspace handoff when clone output is replayed from an already exited session', async () => {
    const runtime = fakeRuntime();
    const onCompletion = jest.fn();
    const marker = 'HORUS_PROJECT_CLONE_COMPLETE_launcher-clone-fast';
    runtime.spec.subscribeSessionEvents = jest.fn(async (request: {requestId: string; sessionId: string}) => {
      runtime.emit({type: 'output', sessionId: request.sessionId, seq: 1, base64: encodeTestBase64('HORUS_TOOLCHAIN_READY\n')});
      runtime.emit({type: 'output', sessionId: request.sessionId, seq: 2, base64: encodeTestBase64(`${marker}\n`)});
      return {
        requestId: request.requestId,
        status: 'success' as const,
        sessionId: request.sessionId,
        eventName: 'terminalSessionEvents' as const,
        sessionState: 'exited' as const,
        firstAvailableSeq: 1,
        lastEmittedSeq: 2,
        replayAvailable: true,
        exitCode: 0,
        exitReason: 'user_stop',
      };
    });
    const client = new TerminalSessionClient(runtime.spec, handler => {
      runtime.listeners.push(handler);
      return () => { runtime.listeners.splice(runtime.listeners.indexOf(handler), 1); };
    });
    let renderer!: ReactTestRenderer.ReactTestRenderer;
    await ReactTestRenderer.act(async () => {
      renderer = ReactTestRenderer.create(
        <TerminalScreen
          client={client}
          completionMarker={marker}
          onCompletion={onCompletion}
          runtime={runtime.spec}
          runtimeReady
          sessionCommand="git clone"
          stopSessionOnUnmount={false}
        />,
      );
      await flushAsync();
    });

    expect(onCompletion).toHaveBeenCalledTimes(1);
    expect(runtime.listeners).toHaveLength(0);
    await ReactTestRenderer.act(async () => { renderer.unmount(); await flushAsync(); });
  });

  test('reports a workspace command failure when the PTY cannot start', async () => {
    const runtime = fakeRuntime();
    runtime.spec.startSession = jest.fn().mockImplementation(async (request: {requestId: string}) => ({
      requestId: request.requestId,
      status: 'error',
      errorCode: 'session_limit_reached',
    }));
    const onCommandFailure = jest.fn();
    const marker = 'HORUS_PROJECT_CLONE_COMPLETE_launcher-clone-limit';
    let renderer!: ReactTestRenderer.ReactTestRenderer;
    await ReactTestRenderer.act(async () => {
      renderer = ReactTestRenderer.create(
        <TerminalScreen
          client={new TerminalSessionClient(runtime.spec)}
          completionMarker={marker}
          onCommandFailure={onCommandFailure}
          runtime={runtime.spec}
          runtimeReady
          sessionCommand="git clone"
        />,
      );
      await flushAsync();
    });

    expect(onCommandFailure).toHaveBeenCalledTimes(1);
    expect(renderer.root.findByProps({testID: 'session-limit-warning-message'}).props.children).toBe(
      'Android closes apps that use too much memory, so Horus limits how many terminals and AI apps run at once, including ones left running in the background. Stop one below to start this one.',
    );
    expect(renderer.root.findByProps({testID: 'session-limit-warning-title'}).props.children).toBe('Too many apps running');
    expect(renderer.root.findByProps({testID: 'session-limit-warning-settings-hint'}).props.children).toBe(
      'You can change the limit in Settings under Concurrent apps.',
    );
    expect(renderer.root.findByProps({testID: 'session-limit-warning-overlay'}).props.style).toMatchObject({
      alignItems: 'center',
      justifyContent: 'center',
      position: 'absolute',
    });
    expect(renderer.root.findAllByProps({testID: 'terminal-error'})).toHaveLength(0);
    await ReactTestRenderer.act(async () => { renderer.unmount(); await flushAsync(); });
  });

  test('hands a split GitHub device-login URL to the Android browser once', async () => {
    const runtime = fakeRuntime();
    runtime.spec.writeSessionInput = jest.fn(runtime.spec.writeSessionInput);
    const openGithubDeviceLogin = jest.fn().mockResolvedValue(undefined);
    const client = new TerminalSessionClient(runtime.spec, handler => {
      runtime.listeners.push(handler);
      return () => {
        const index = runtime.listeners.indexOf(handler);
        if (index >= 0) runtime.listeners.splice(index, 1);
      };
    });
    let renderer!: ReactTestRenderer.ReactTestRenderer;
    await ReactTestRenderer.act(async () => {
      renderer = ReactTestRenderer.create(
        <TerminalScreen
          client={client}
          onGithubDeviceLogin={openGithubDeviceLogin}
          runtime={runtime.spec}
          sessionCommand="gh auth login"
          toolchain="github"
        />,
      );
      await flushAsync();
    });
    await ReactTestRenderer.act(async () => {
      runtime.emit({type: 'output', sessionId: 's-1-1', seq: 1, base64: encodeTestBase64('Press Enter to open https://github.com/login/de')});
      runtime.emit({type: 'output', sessionId: 's-1-1', seq: 2, base64: encodeTestBase64('vice')});
      await flushAsync();
    });
    expect(openGithubDeviceLogin).toHaveBeenCalledTimes(1);
    expect(openGithubDeviceLogin).toHaveBeenCalledWith('https://github.com/login/device');
    expect((runtime.spec.writeSessionInput as jest.Mock).mock.calls.map(call => decodeTestBase64(call[0].base64))).toEqual([new TextEncoder().encode('\r')]);
    await ReactTestRenderer.act(async () => {
      runtime.emit({type: 'output', sessionId: 's-1-1', seq: 3, base64: encodeTestBase64(' in your browser…')});
      await flushAsync();
    });
    expect(openGithubDeviceLogin).toHaveBeenCalledTimes(1);
    expect(runtime.spec.writeSessionInput).toHaveBeenCalledTimes(1);
    await ReactTestRenderer.act(async () => { renderer.unmount(); await flushAsync(); });
  });

  test('does not send the browser confirmation after the terminal has unmounted', async () => {
    const runtime = fakeRuntime();
    runtime.spec.writeSessionInput = jest.fn(runtime.spec.writeSessionInput);
    let finishBrowserHandoff: (() => void) | undefined;
    const openGithubDeviceLogin = jest.fn(() => new Promise<void>(resolve => { finishBrowserHandoff = resolve; }));
    const client = new TerminalSessionClient(runtime.spec, handler => {
      runtime.listeners.push(handler);
      return () => {
        const index = runtime.listeners.indexOf(handler);
        if (index >= 0) runtime.listeners.splice(index, 1);
      };
    });
    let renderer!: ReactTestRenderer.ReactTestRenderer;
    await ReactTestRenderer.act(async () => {
      renderer = ReactTestRenderer.create(
        <TerminalScreen
          client={client}
          onGithubDeviceLogin={openGithubDeviceLogin}
          runtime={runtime.spec}
          sessionCommand="gh auth login"
          toolchain="github"
        />,
      );
      await flushAsync();
    });
    await ReactTestRenderer.act(async () => {
      runtime.emit({type: 'output', sessionId: 's-1-1', seq: 1, base64: encodeTestBase64('https://github.com/login/device')});
      await Promise.resolve();
    });
    expect(openGithubDeviceLogin).toHaveBeenCalledTimes(1);
    await ReactTestRenderer.act(async () => {
      renderer.unmount();
      await Promise.resolve();
    });
    await ReactTestRenderer.act(async () => {
      finishBrowserHandoff?.();
      await flushAsync();
    });
    expect(runtime.spec.writeSessionInput).not.toHaveBeenCalled();
    expect(runtime.listeners).toHaveLength(0);
  });

  test('does not start when native status is unavailable or install fails', async () => {
    const unavailable = fakeRuntime();
    unavailable.spec.startSession = jest.fn(unavailable.spec.startSession);
    let renderer: ReactTestRenderer.ReactTestRenderer | undefined;
    await ReactTestRenderer.act(async () => { renderer = ReactTestRenderer.create(<TerminalScreen client={new TerminalSessionClient(unavailable.spec)} runtime={null} />); });
    await ReactTestRenderer.act(async () => { await flushAsync(); });
    expect(unavailable.spec.startSession).not.toHaveBeenCalled();
    expect(renderer?.root.findByProps({testID: 'terminal-error'}).props.children).toBe('unavailable');
    await ReactTestRenderer.act(async () => { renderer?.unmount(); });

    const failed = fakeRuntime();
    failed.spec.startSession = jest.fn(failed.spec.startSession);
    failed.spec.getRuntimeStatus = jest.fn().mockResolvedValue(runtimeStatus({runtimeState: 'not_installed', installedVersionIds: [], activeRootfsId: undefined, activeAlpineRelease: undefined, activeRootfsSha256: undefined, activeInstalledAt: undefined}));
    failed.spec.installRootfs = jest.fn().mockImplementation(async (request: {requestId: string}) => ({requestId: request.requestId, status: 'error', errorCode: 'download_failed'}));
    await ReactTestRenderer.act(async () => { renderer = ReactTestRenderer.create(<TerminalScreen client={new TerminalSessionClient(failed.spec)} runtime={failed.spec} />); });
    await ReactTestRenderer.act(async () => { await flushAsync(); });
    expect(failed.spec.startSession).not.toHaveBeenCalled();
    expect(renderer?.root.findByProps({testID: 'terminal-error'}).props.children).toBe('download_failed');
    await ReactTestRenderer.act(async () => { renderer?.unmount(); });
  });

  test('shows how to free a session slot when the native session limit is reached', async () => {
    const runtime = fakeRuntime();
    const onHome = jest.fn();
    runtime.spec.startSession = jest.fn().mockImplementation(async (request: {requestId: string}) => ({
      requestId: request.requestId,
      status: 'error',
      errorCode: 'session_limit_reached',
    }));
    const client = new TerminalSessionClient(runtime.spec);
    let renderer!: ReactTestRenderer.ReactTestRenderer;
    await ReactTestRenderer.act(async () => {
      renderer = ReactTestRenderer.create(
        <TerminalScreen
          client={client}
          onHome={onHome}
          runtime={runtime.spec}
          runtimeReady
          toolchain="shell"
        />,
      );
      await flushAsync();
    });

    expect(renderer.root.findByProps({testID: 'session-limit-warning-message'}).props.children).toBe(
      'Android closes apps that use too much memory, so Horus limits how many terminals and AI apps run at once, including ones left running in the background. Stop one below to start this one.',
    );
    expect(renderer.root.findByProps({testID: 'terminal-home'})).toBeDefined();
    const menuButton = renderer.root.findByProps({testID: 'terminal-home'});
    expect(menuButton.props.accessibilityLabel).toBe('Back to home');
    expect(menuButton.findByType(Text).props.children).toBe('←');
    await ReactTestRenderer.act(async () => { menuButton.props.onPress(); });
    expect(onHome).toHaveBeenCalledTimes(1);
    await ReactTestRenderer.act(async () => { renderer.unmount(); });
  });

  test('shows active session details and retries after terminating one', async () => {
    const runtime = fakeRuntime();
    const startResponses = [
      {status: 'error' as const, errorCode: 'session_limit_reached' as const},
      {status: 'success' as const, sessionId: 's-2-1', pid: 2, rows: 24, columns: 80},
    ];
    runtime.spec.startSession = jest.fn().mockImplementation(async (request: {requestId: string}) => ({
      requestId: request.requestId,
      ...startResponses.shift(),
    }));
    runtime.spec.listTerminalSessions = jest.fn().mockImplementation(async (request: {requestId: string}) => ({
      requestId: request.requestId,
      status: 'success' as const,
      sessions: [{sessionId: 's-1-1', toolchain: 'opencode' as const, startedAtMs: Date.now() - 3 * 60_000}],
    }));
    const client = new TerminalSessionClient(runtime.spec, handler => {
      runtime.listeners.push(handler);
      return () => { const index = runtime.listeners.indexOf(handler); if (index >= 0) runtime.listeners.splice(index, 1); };
    });
    let renderer!: ReactTestRenderer.ReactTestRenderer;
    await ReactTestRenderer.act(async () => {
      renderer = ReactTestRenderer.create(
        <TerminalScreen client={client} runtime={runtime.spec} runtimeReady toolchain="codex" />,
      );
      await flushAsync();
    });

    expect(renderer.root.findByProps({testID: 'session-limit-session-title-s-1-1'}).props.children).toBe('OpenCode');
    expect(renderer.root.findByProps({testID: 'session-limit-session-meta-s-1-1'}).props.children).toEqual(expect.arrayContaining([expect.stringContaining('ACTIVE PTY')]));
    await ReactTestRenderer.act(async () => {
      renderer.root.findByProps({testID: 'session-limit-terminate-s-1-1'}).props.onPress();
      await flushAsync();
    });
    expect(runtime.stopCalls).toContain('s-1-1');
    expect(runtime.spec.startSession).toHaveBeenCalledTimes(2);
    expect(renderer.root.findAllByProps({testID: 'session-limit-warning'})).toHaveLength(0);
    expect(renderer.root.findByProps({testID: 'terminal-input'}).props.editable).toBe(true);
    await ReactTestRenderer.act(async () => { renderer.unmount(); await flushAsync(); });
  });

  test('starts the PTY immediately after app bootstrap without repeating runtime status', async () => {
    const runtime = fakeRuntime();
    runtime.spec.getRuntimeStatus = jest.fn().mockRejectedValue(new Error('status must not run'));
    runtime.spec.startSession = jest.fn(runtime.spec.startSession);
    let renderer: ReactTestRenderer.ReactTestRenderer | undefined;
    await ReactTestRenderer.act(async () => {
      renderer = ReactTestRenderer.create(
        <TerminalScreen
          client={new TerminalSessionClient(runtime.spec)}
          runtime={runtime.spec}
          runtimeReady
        />,
      );
      await flushAsync();
    });
    expect(runtime.spec.getRuntimeStatus).not.toHaveBeenCalled();
    expect(runtime.spec.startSession).toHaveBeenCalledTimes(1);
    expect(renderer?.root.findByProps({testID: 'terminal-input'}).props.editable).toBe(true);
    await ReactTestRenderer.act(async () => { renderer?.unmount(); await flushAsync(); });
  });

  test('starts immediately, sends direct terminal input, exposes terminal keys, and cleans up', async () => {
    const runtime = fakeRuntime();
    runtime.spec.writeSessionInput = jest.fn(runtime.spec.writeSessionInput);
    const client = new TerminalSessionClient(runtime.spec, handler => {
      runtime.listeners.push(handler);
      return () => { const index = runtime.listeners.indexOf(handler); if (index >= 0) runtime.listeners.splice(index, 1); };
    });
    let renderer: ReactTestRenderer.ReactTestRenderer | undefined;
    await ReactTestRenderer.act(async () => { renderer = ReactTestRenderer.create(<TerminalScreen client={client} runtime={runtime.spec} />); });
    await ReactTestRenderer.act(async () => { await flushAsync(); });
    expect(renderer?.root.findByProps({testID: 'terminal-input'}).props).toMatchObject({
      editable: true,
      returnKeyType: 'send',
      submitBehavior: 'submit',
    });
    expect(renderer?.root.findByProps({testID: 'terminal-key-esc'})).toBeDefined();
    expect(renderer?.root.findByProps({testID: 'terminal-key-tab'})).toBeDefined();
    expect(renderer?.root.findByProps({testID: 'terminal-key-alt'})).toBeDefined();
    expect(renderer?.root.findByProps({testID: 'terminal-key-ctrl'})).toBeDefined();
    expect(renderer?.root.findAllByProps({testID: 'terminal-key-home'})).toHaveLength(0);
    expect(renderer?.root.findByProps({testID: 'terminal-keyboard-toggle'})).toBeDefined();
    expect(renderer?.root.findByProps({testID: 'terminal-key-end'})).toBeDefined();
    expect(renderer?.root.findByProps({testID: 'terminal-key-return'})).toBeDefined();
    expect(renderer?.root.findAllByProps({testID: 'terminal-key-page-up'})).toHaveLength(0);
    expect(renderer?.root.findAllByProps({testID: 'terminal-key-page-down'})).toHaveLength(0);
    expect(renderer?.root.findByProps({testID: 'terminal-key-arrow-up'})).toBeDefined();
    expect(renderer?.root.findByProps({testID: 'terminal-key-arrow-left'})).toBeDefined();
    expect(renderer?.root.findByProps({testID: 'terminal-key-arrow-down'})).toBeDefined();
    expect(renderer?.root.findByProps({testID: 'terminal-key-arrow-right'})).toBeDefined();
    expect(renderer?.root.findAllByProps({testID: 'terminal-ctrl-c'})).toHaveLength(0);
    expect(renderer?.root.findAllByType('button')).toHaveLength(0);
    await ReactTestRenderer.act(async () => {
      runtime.emit({type: 'output', sessionId: 's-1-1', seq: 1, base64: encodeTestBase64('hello\n')});
      await flushAsync();
    });
    expect(rowText(renderer)).toBe('hello');
    const terminalGrid = renderer!.root.findByProps({testID: 'terminal-output-grid'});
    expect(terminalGrid.props.initialNumToRender).toBeGreaterThan(0);
    expect(terminalGrid.props.maxToRenderPerBatch).toBe(10);
    expect(terminalGrid.props.windowSize).toBe(3);
    await ReactTestRenderer.act(async () => {
      let text = '';
      for (const key of 'echo hi') {
        const input = renderer!.root.findByProps({testID: 'terminal-input'});
        input.props.onKeyPress({nativeEvent: {key}});
        text += key;
        input.props.onChangeText(text);
      }
      await Promise.resolve();
    });
    await ReactTestRenderer.act(async () => { renderer?.root.findByProps({testID: 'terminal-input'}).props.onSubmitEditing(); await Promise.resolve(); });
    await ReactTestRenderer.act(async () => { renderer?.root.findByProps({testID: 'terminal-input'}).props.onKeyPress({nativeEvent: {key: 'Backspace'}}); await Promise.resolve(); });
    const writes = (runtime.spec.writeSessionInput as jest.Mock).mock.calls.map(call => decodeTestBase64(call[0].base64));
    expect(writes).toEqual([
      ...Array.from('echo hi', key => new TextEncoder().encode(key)),
      new TextEncoder().encode('\r'),
      new Uint8Array([0x7f]),
    ]);
    await ReactTestRenderer.act(async () => { renderer?.unmount(); });
    expect(runtime.listeners).toHaveLength(0);
  });

  test('offers keyboard hiding in the key strip and does not reopen it after layout changes', async () => {
    const runtime = fakeRuntime();
    runtime.spec.resizeSession = jest.fn(runtime.spec.resizeSession);
    const keyboardListeners: Partial<Record<'keyboardDidShow' | 'keyboardDidHide', (event: never) => void>> = {};
    const keyboardSubscriptions: Array<{event: string; remove: jest.Mock}> = [];
    jest.spyOn(Keyboard, 'addListener').mockImplementation((event, listener) => {
      const remove = jest.fn();
      if (event === 'keyboardDidShow' || event === 'keyboardDidHide') {
        keyboardListeners[event] = listener as (event: never) => void;
        keyboardSubscriptions.push({event, remove});
      }
      return {remove};
    });
    const dismiss = jest.spyOn(Keyboard, 'dismiss').mockImplementation(() => undefined);
    const client = new TerminalSessionClient(runtime.spec, handler => {
      runtime.listeners.push(handler);
      return () => { runtime.listeners.splice(runtime.listeners.indexOf(handler), 1); };
    });
    let renderer!: ReactTestRenderer.ReactTestRenderer;
    await ReactTestRenderer.act(async () => {
      renderer = ReactTestRenderer.create(<TerminalScreen client={client} runtime={runtime.spec} />);
      await flushAsync();
    });
    expect(renderer.root.findByType(KeyboardAvoidingView).props.keyboardVerticalOffset).toBe(0);
    const initialKeyboardToggle = renderer.root.findByProps({testID: 'terminal-keyboard-toggle'});
    expect(initialKeyboardToggle.props.accessibilityLabel).toBe('Show keyboard');
    expect(initialKeyboardToggle.findByType(Text).props.children).toBe('SHOW');
    const inputElement = renderer.root.findByProps({testID: 'terminal-input'});
    const input = renderer.root.findByType(TextInput).instance;
    await ReactTestRenderer.act(async () => { inputElement.props.onLayout(); });
    const focus = jest.spyOn(input, 'focus').mockClear();
    const terminalOutput = renderer.root.findByProps({testID: 'terminal-output'});
    await ReactTestRenderer.act(async () => {
      terminalOutput.props.onLayout({nativeEvent: {layout: {width: 400, height: 190}}});
      await flushAsync();
    });
    expect(runtime.spec.resizeSession).toHaveBeenLastCalledWith(expect.objectContaining({rows: 10, columns: 50}));

    await ReactTestRenderer.act(async () => {
      keyboardListeners.keyboardDidShow?.({} as never);
      terminalOutput.props.onLayout({nativeEvent: {layout: {width: 400, height: 95}}});
      await flushAsync();
    });
    expect(runtime.spec.resizeSession).toHaveBeenLastCalledWith(expect.objectContaining({rows: 5, columns: 50}));
    const hideButton = renderer.root.findByProps({testID: 'terminal-keyboard-toggle'});
    expect(hideButton.props.accessibilityLabel).toBe('Hide keyboard');
    expect(hideButton.findByType(Text).props.children).toBe('HIDE');
    const blur = jest.spyOn(input, 'blur').mockClear();
    await ReactTestRenderer.act(async () => { hideButton.props.onPress(); });
    expect(dismiss).toHaveBeenCalledTimes(1);
    expect(blur).toHaveBeenCalledTimes(1);

    await ReactTestRenderer.act(async () => {
      keyboardListeners.keyboardDidHide?.({} as never);
      renderer.root.findByProps({testID: 'terminal-input'}).props.onLayout();
      await flushAsync();
    });
    expect(runtime.spec.resizeSession).toHaveBeenLastCalledWith(expect.objectContaining({rows: 10, columns: 50}));
    const showButton = renderer.root.findByProps({testID: 'terminal-keyboard-toggle'});
    expect(showButton.props.accessibilityLabel).toBe('Show keyboard');
    expect(showButton.findByType(Text).props.children).toBe('SHOW');
    expect(focus).not.toHaveBeenCalled();
    await ReactTestRenderer.act(async () => {
      const touchTarget = renderer.root.findByProps({testID: 'terminal-output'});
      touchTarget.props.onTouchStart(terminalTouch(40, 90));
      touchTarget.props.onTouchEnd(terminalTouch(40, 90));
      await flushAsync();
    });
    expect(focus).toHaveBeenCalled();

    await ReactTestRenderer.act(async () => { renderer.unmount(); await flushAsync(); });
    expect(keyboardSubscriptions.map(({event}) => event)).toContain('keyboardDidShow');
    expect(keyboardSubscriptions.map(({event}) => event)).toContain('keyboardDidHide');
    keyboardSubscriptions.forEach(({remove}) => expect(remove).toHaveBeenCalledTimes(1));
  });

  test('uses the two-row return key to submit terminal input', async () => {
    const runtime = fakeRuntime();
    runtime.spec.writeSessionInput = jest.fn(runtime.spec.writeSessionInput);
    const vibrate = jest.spyOn(Vibration, 'vibrate').mockImplementation(() => undefined);
    const client = new TerminalSessionClient(runtime.spec, handler => {
      runtime.listeners.push(handler);
      return () => { const index = runtime.listeners.indexOf(handler); if (index >= 0) runtime.listeners.splice(index, 1); };
    });
    let renderer!: ReactTestRenderer.ReactTestRenderer;
    await ReactTestRenderer.act(async () => {
      renderer = ReactTestRenderer.create(<TerminalScreen client={client} runtime={runtime.spec} />);
      await flushAsync();
    });

    const returnKey = renderer.root.findByProps({testID: 'terminal-key-return'});
    expect(StyleSheet.flatten(returnKey.props.style)).toMatchObject({borderTopColor: '#090C0D', borderTopWidth: 1, height: 42, width: 96});
    expect(StyleSheet.flatten(renderer.root.findByProps({testID: 'terminal-key-return-upper'}).props.style)).toMatchObject({height: 42, width: 72});
    expect(StyleSheet.flatten(renderer.root.findByProps({testID: 'terminal-key-return-notch'}).props.style)).toMatchObject({height: 1, left: 0, top: 0, width: 24});
    const returnPressTarget = renderer.root.findAll(node => node.props.testID === 'terminal-key-return' && typeof node.props.onPressIn === 'function');
    expect(returnPressTarget.length).toBeGreaterThan(0);
    await ReactTestRenderer.act(async () => {
      returnPressTarget[0].props.onPressIn({} as never);
    });
    expect(vibrate).toHaveBeenCalledWith(8);
    await ReactTestRenderer.act(async () => {
      returnKey.props.onPress();
      await flushAsync();
    });
    const writes = (runtime.spec.writeSessionInput as jest.Mock).mock.calls.map(call => decodeTestBase64(call[0].base64));
    expect(writes).toEqual([new TextEncoder().encode('\r')]);

    await ReactTestRenderer.act(async () => { renderer.unmount(); await flushAsync(); });
  });

  test('sends arrows, escape, and tab, with sticky Ctrl and Alt modifiers', async () => {
    const runtime = fakeRuntime();
    runtime.spec.writeSessionInput = jest.fn(runtime.spec.writeSessionInput);
    const client = new TerminalSessionClient(runtime.spec, handler => {
      runtime.listeners.push(handler);
      return () => { const index = runtime.listeners.indexOf(handler); if (index >= 0) runtime.listeners.splice(index, 1); };
    });
    let renderer!: ReactTestRenderer.ReactTestRenderer;
    await ReactTestRenderer.act(async () => {
      renderer = ReactTestRenderer.create(<TerminalScreen client={client} runtime={runtime.spec} />);
      await flushAsync();
    });
    const press = async (testID: string) => {
      await ReactTestRenderer.act(async () => {
        renderer.root.findByProps({testID}).props.onPress();
        await flushAsync();
      });
    };
    const input = () => renderer.root.findByProps({testID: 'terminal-input'});

    await press('terminal-key-arrow-up');
    await press('terminal-key-ctrl');
    expect(StyleSheet.flatten(renderer.root.findByProps({children: 'CTRL'}).props.style)).toMatchObject({color: '#090C0D'});
    await press('terminal-key-arrow-left');
    await ReactTestRenderer.act(async () => {
      input().props.onChangeText('c');
      await flushAsync();
    });
    await press('terminal-key-alt');
    expect(StyleSheet.flatten(renderer.root.findByProps({children: 'ALT'}).props.style)).toMatchObject({color: '#090C0D'});
    await press('terminal-key-arrow-down');
    await press('terminal-key-ctrl');
    await ReactTestRenderer.act(async () => {
      input().props.onChangeText('ca');
      await flushAsync();
    });
    await press('terminal-key-tab');
    await press('terminal-key-esc');
    await press('terminal-key-alt');
    expect(StyleSheet.flatten(renderer.root.findByProps({children: 'ALT'}).props.style)).toMatchObject({color: '#F2F4F5'});
    await press('terminal-key-arrow-right');

    const writes = (runtime.spec.writeSessionInput as jest.Mock).mock.calls.map(call => new TextDecoder().decode(decodeTestBase64(call[0].base64)));
    expect(writes).toEqual(['\u001b[A', '\u001b[1;5D', '\u0003', '\u001b[1;7B', '\u001b' + 'a', '\u001b\t', '\u001b\u001b', '\u001b[C']);
    await ReactTestRenderer.act(async () => { renderer.unmount(); await flushAsync(); });
  });

  test('opens and closes Codex transcript history from the key strip', async () => {
    const runtime = fakeRuntime();
    runtime.spec.writeSessionInput = jest.fn(runtime.spec.writeSessionInput);
    const client = new TerminalSessionClient(runtime.spec, handler => {
      runtime.listeners.push(handler);
      return () => { runtime.listeners.splice(runtime.listeners.indexOf(handler), 1); };
    });
    let renderer!: ReactTestRenderer.ReactTestRenderer;
    await ReactTestRenderer.act(async () => {
      renderer = ReactTestRenderer.create(<TerminalScreen client={client} runtime={runtime.spec} toolchain="codex" />);
      await flushAsync();
    });
    const press = async (testID: string) => {
      await ReactTestRenderer.act(async () => {
        renderer.root.findByProps({testID}).props.onPress();
        await flushAsync();
      });
    };

    expect(renderer.root.findByProps({testID: 'terminal-key-transcript-history'}).props.accessibilityLabel).toBe('Open transcript history');
    await press('terminal-key-transcript-history');
    expect(renderer.root.findByProps({testID: 'terminal-key-transcript-history'}).props.accessibilityLabel).toBe('Close transcript history');
    await press('terminal-key-transcript-history');
    await press('terminal-key-esc');
    expect(renderer.root.findByProps({testID: 'terminal-key-transcript-history'}).props.accessibilityLabel).toBe('Open transcript history');
    const writes = (runtime.spec.writeSessionInput as jest.Mock).mock.calls.map(call => new TextDecoder().decode(decodeTestBase64(call[0].base64)));
    expect(writes).toEqual(['\u0014', '\u0014', '\u001b']);

    await ReactTestRenderer.act(async () => { renderer.unmount(); await flushAsync(); });
  });

  test('writes each key once and renders split zsh redraws as one character', async () => {
    const runtime = fakeRuntime();
    runtime.spec.writeSessionInput = jest.fn(runtime.spec.writeSessionInput);
    const client = new TerminalSessionClient(runtime.spec, handler => {
      runtime.listeners.push(handler);
      return () => { runtime.listeners.splice(runtime.listeners.indexOf(handler), 1); };
    });
    let renderer: ReactTestRenderer.ReactTestRenderer | undefined;
    await ReactTestRenderer.act(async () => {
      renderer = ReactTestRenderer.create(<TerminalScreen client={client} runtime={runtime.spec} />);
      await flushAsync();
    });
    let seq = 0;
    const output = async (text: string) => {
      await ReactTestRenderer.act(async () => {
        runtime.emit({type: 'output', sessionId: 's-1-1', seq: ++seq, base64: encodeTestBase64(text)});
        await flushAsync();
      });
    };
    let inputText = '';
    const typeKey = async (key: string) => {
      await ReactTestRenderer.act(async () => {
        const input = renderer!.root.findByProps({testID: 'terminal-input'});
        input.props.onKeyPress({nativeEvent: {key}});
        inputText = key === 'Backspace' ? inputText.slice(0, -1) : inputText + key;
        input.props.onChangeText(inputText);
        input.props.onChangeText(inputText); // Replayed identical native value.
        await flushAsync();
      });
    };
    await output('%   \r \r\u001b[J$ ');
    await typeKey('a');
    await output('a\b');
    await output('a');
    expect(rowText(renderer)).toBe('$ a');
    expect(cursorLeft(renderer)).toBe(3 * 8);
    await typeKey('a');
    await output('a');
    expect(rowText(renderer)).toBe('$ aa');
    expect(cursorLeft(renderer)).toBe(4 * 8);
    await typeKey('Backspace');
    await output('\b\u001b[K');
    expect(rowText(renderer)).toBe('$ a');
    expect(cursorLeft(renderer)).toBe(3 * 8);
    const writes = (runtime.spec.writeSessionInput as jest.Mock).mock.calls.map(call => Array.from(decodeTestBase64(call[0].base64)));
    expect(writes).toEqual([[0x61], [0x61], [0x7f]]);
    await ReactTestRenderer.act(async () => {
      const input = renderer!.root.findByProps({testID: 'terminal-input'});
      input.props.onSubmitEditing();
      input.props.onChangeText('');
      // Paste/IME batch commits do not necessarily emit individual keypresses.
      input.props.onChangeText('echo INPUT_CHECK_ROUNDTRIP_OK');
      await flushAsync();
    });
    expect((runtime.spec.writeSessionInput as jest.Mock).mock.calls.slice(-2).map(call => new TextDecoder().decode(decodeTestBase64(call[0].base64)))).toEqual(['\r', 'echo INPUT_CHECK_ROUNDTRIP_OK']);
    await ReactTestRenderer.act(async () => { renderer?.unmount(); await flushAsync(); });
    expect(runtime.listeners).toHaveLength(0);
    expect(runtime.stopCalls).toEqual(['s-1-1']);
  });

  test('stops a session if event subscription cannot be established', async () => {
    const runtime = fakeRuntime();
    runtime.spec.subscribeSessionEvents = async request => ({
      requestId: request.requestId,
      status: 'error',
      errorCode: 'internal_error',
    });
    const client = new TerminalSessionClient(runtime.spec, handler => {
      runtime.listeners.push(handler);
      return () => { const index = runtime.listeners.indexOf(handler); if (index >= 0) runtime.listeners.splice(index, 1); };
    });
    let renderer: ReactTestRenderer.ReactTestRenderer | undefined;
    await ReactTestRenderer.act(async () => { renderer = ReactTestRenderer.create(<TerminalScreen client={client} runtime={runtime.spec} />); });
    await ReactTestRenderer.act(async () => { await Promise.resolve(); await Promise.resolve(); });
    expect(runtime.stopCalls).toEqual(['s-1-1']);
    await ReactTestRenderer.act(async () => { renderer?.unmount(); });
    expect(runtime.listeners).toHaveLength(0);
  });

  test('renders positioned TUI cells and overlays the cursor without inserting a character', async () => {
    const runtime = fakeRuntime();
    const client = new TerminalSessionClient(runtime.spec, handler => {
      runtime.listeners.push(handler);
      return () => { runtime.listeners.splice(runtime.listeners.indexOf(handler), 1); };
    });
    let renderer!: ReactTestRenderer.ReactTestRenderer;
    await ReactTestRenderer.act(async () => {
      renderer = ReactTestRenderer.create(<TerminalScreen client={client} runtime={runtime.spec} />);
      await flushAsync();
    });
    await ReactTestRenderer.act(async () => {
      runtime.emit({type: 'output', sessionId: 's-1-1', seq: 1, base64: encodeTestBase64('\x1b[2J\x1b[HWelcome\x1b[1;9Hto\x1b[1;12H\x1b[31mCodex\x1b[2;3H界X')});
      await flushAsync();
    });
    expect(rowText(renderer)).toBe('Welcome to Codex');
    expect(renderer.root.findAll(node => typeof node.props.testID === 'string' && node.props.testID.startsWith('terminal-cell-')).length).toBeLessThan(15);
    const codex = terminalCell(renderer, 0, 11);
    expect(codex.props.children).toBe('Codex');
    expect(codex.props.numberOfLines).toBe(1);
    expect(codex.props.allowFontScaling).toBe(false);
    expect(StyleSheet.flatten(codex.props.style)).toMatchObject({left: 11 * 8, width: 5 * 8, color: '#cd0000', position: 'absolute'});
    const wide = terminalCell(renderer, 1, 2);
    expect(StyleSheet.flatten(wide.props.style).width).toBe(16);
    expect(StyleSheet.flatten(terminalCell(renderer, 1, 4).props.style).left).toBe(32);
    expect(cursorLeft(renderer)).toBe(5 * 8);
    expect(rowText(renderer, 1)).toBe('  界X');
    await ReactTestRenderer.act(async () => {
      runtime.emit({type: 'output', sessionId: 's-1-1', seq: 2, base64: encodeTestBase64('\x1b[?1049h\x1b[HOpenCode\x1b[?25l')});
      await flushAsync();
    });
    expect(rowText(renderer)).toBe('OpenCode');
    expect(renderer.root.findByProps({testID: 'terminal-output-grid'}).props.scrollEnabled).toBe(false);
    expect(renderer.root.findAllByProps({testID: 'terminal-cursor'})).toHaveLength(0);
    await ReactTestRenderer.act(async () => {
      runtime.emit({type: 'output', sessionId: 's-1-1', seq: 3, base64: encodeTestBase64('\x1b[?1049l\x1b[?25h')});
      await flushAsync();
    });
    expect(rowText(renderer)).toBe('Welcome to Codex');
    expect(renderer.root.findByProps({testID: 'terminal-output-grid'}).props.scrollEnabled).toBe(true);
    expect(cursorLeft(renderer)).toBe(5 * 8);
    await ReactTestRenderer.act(async () => { renderer.unmount(); await flushAsync(); });
  });

  test('renders trusted URLs as clickable links and opens them externally', async () => {
    const runtime = fakeRuntime();
    const openURL = jest.spyOn(Linking, 'openURL').mockResolvedValue(undefined);
    const client = new TerminalSessionClient(runtime.spec, handler => {
      runtime.listeners.push(handler);
      return () => { runtime.listeners.splice(runtime.listeners.indexOf(handler), 1); };
    });
    let renderer!: ReactTestRenderer.ReactTestRenderer;
    await ReactTestRenderer.act(async () => {
      renderer = ReactTestRenderer.create(<TerminalScreen client={client} runtime={runtime.spec} />);
      await flushAsync();
    });
    await ReactTestRenderer.act(async () => {
      runtime.emit({
        type: 'output',
        sessionId: 's-1-1',
        seq: 1,
        base64: encodeTestBase64('https://claude.ai/oauth/authorize\nhttp://localhost:3000\nhttps://example.com\n'),
      });
      await flushAsync();
    });

    const hostedLink = terminalLink(renderer, 0, 0);
    const localLink = terminalLink(renderer, 1, 0);
    expect(hostedLink.props.accessibilityRole).toBe('link');
    expect(hostedLink.props.accessibilityLabel).toBe('https://claude.ai/oauth/authorize');
    expect(localLink.props.accessibilityLabel).toBe('http://localhost:3000');
    expect(terminalLink(renderer, 2, 0)).toBeUndefined();
    expect(StyleSheet.flatten(terminalCell(renderer, 0, 0).props.style)).toMatchObject({
      color: '#80EB12',
      textDecorationLine: 'underline',
    });
    await ReactTestRenderer.act(async () => {
      hostedLink.props.onPress();
      await flushAsync();
    });
    await ReactTestRenderer.act(async () => {
      localLink.props.onPress();
      await flushAsync();
    });
    expect(openURL.mock.calls).toEqual([
      ['https://claude.ai/oauth/authorize'],
      ['http://localhost:3000'],
    ]);
    await ReactTestRenderer.act(async () => { renderer.unmount(); await flushAsync(); });
    expect(runtime.listeners).toHaveLength(0);
  });

  test('keeps a long trusted URL clickable across wrapped terminal rows', async () => {
    const runtime = fakeRuntime();
    const openURL = jest.spyOn(Linking, 'openURL').mockResolvedValue(undefined);
    const url = 'https://claude.ai/oauth/authorize?code=' + 'a'.repeat(100);
    const client = new TerminalSessionClient(runtime.spec, handler => {
      runtime.listeners.push(handler);
      return () => { runtime.listeners.splice(runtime.listeners.indexOf(handler), 1); };
    });
    let renderer!: ReactTestRenderer.ReactTestRenderer;
    await ReactTestRenderer.act(async () => {
      renderer = ReactTestRenderer.create(<TerminalScreen client={client} runtime={runtime.spec} />);
      await flushAsync();
    });
    await ReactTestRenderer.act(async () => {
      runtime.emit({type: 'output', sessionId: 's-1-1', seq: 1, base64: encodeTestBase64(url)});
      await flushAsync();
    });

    const firstRowLink = terminalLink(renderer, 0, 0);
    const wrappedRowLink = terminalLink(renderer, 1, 0);
    expect(firstRowLink.props.accessibilityLabel).toBe(url);
    expect(wrappedRowLink.props.accessibilityLabel).toBe(url);
    await ReactTestRenderer.act(async () => {
      wrappedRowLink.props.onPress();
      await flushAsync();
    });
    expect(openURL).toHaveBeenCalledWith(url);
    await ReactTestRenderer.act(async () => { renderer.unmount(); await flushAsync(); });
    expect(runtime.listeners).toHaveLength(0);
  });

  test('uses measured glyph width and viewport height for PTY/grid resize including the keyboard', async () => {
    const runtime = fakeRuntime();
    runtime.spec.resizeSession = jest.fn(runtime.spec.resizeSession);
    let renderer!: ReactTestRenderer.ReactTestRenderer;
    await ReactTestRenderer.act(async () => {
      renderer = ReactTestRenderer.create(<TerminalScreen client={new TerminalSessionClient(runtime.spec)} runtime={runtime.spec} />);
      await flushAsync();
    });
    const layout = async (width: number, height: number) => {
      await ReactTestRenderer.act(async () => {
        renderer.root.findByProps({testID: 'terminal-output'}).props.onLayout({nativeEvent: {layout: {width, height}}});
      });
      await ReactTestRenderer.act(flushAsync);
      await ReactTestRenderer.act(flushAsync);
    };
    await ReactTestRenderer.act(async () => {
      renderer.root.findByProps({testID: 'terminal-font-measure'}).props.onTextLayout({nativeEvent: {lines: [{width: 200}]}});
    });
    await layout(400, 190);
    expect(runtime.spec.resizeSession).toHaveBeenLastCalledWith(expect.objectContaining({rows: 10, columns: 40}));
    expect(renderer.root.findByType(TerminalGrid).props.frame).toMatchObject({rows: 10, columns: 40});
    await layout(400, 95);
    expect(runtime.spec.resizeSession).toHaveBeenLastCalledWith(expect.objectContaining({rows: 5, columns: 40}));
    expect(renderer.root.findByType(TerminalGrid).props.frame).toMatchObject({rows: 5, columns: 40});
    await layout(600, 190);
    expect(runtime.spec.resizeSession).toHaveBeenLastCalledWith(expect.objectContaining({rows: 10, columns: 60}));
    const calls = (runtime.spec.resizeSession as jest.Mock).mock.calls.length;
    await layout(600, 190);
    expect(runtime.spec.resizeSession).toHaveBeenCalledTimes(calls);
    await ReactTestRenderer.act(async () => { renderer.unmount(); await flushAsync(); });
    expect(runtime.stopCalls).toEqual(['s-1-1']);
  });

  test('forwards terminal device replies using the unchanged input contract and preserves final exit output', async () => {
    const runtime = fakeRuntime();
    runtime.spec.writeSessionInput = jest.fn(runtime.spec.writeSessionInput);
    const client = new TerminalSessionClient(runtime.spec, handler => {
      runtime.listeners.push(handler);
      return () => { runtime.listeners.splice(runtime.listeners.indexOf(handler), 1); };
    });
    let renderer!: ReactTestRenderer.ReactTestRenderer;
    await ReactTestRenderer.act(async () => {
      renderer = ReactTestRenderer.create(<TerminalScreen client={client} runtime={runtime.spec} />);
      await flushAsync();
    });
    await ReactTestRenderer.act(async () => {
      runtime.emit({type: 'output', sessionId: 's-1-1', seq: 1, base64: encodeTestBase64('\x1b[3;4H\x1b[6n')});
      await flushAsync();
    });
    expect((runtime.spec.writeSessionInput as jest.Mock).mock.calls.map(call => new TextDecoder().decode(decodeTestBase64(call[0].base64)))).toEqual(['\x1b[3;4R']);
    await ReactTestRenderer.act(async () => {
      runtime.emit({type: 'output', sessionId: 's-1-1', seq: 2, base64: encodeTestBase64('done')});
      runtime.emit({type: 'exit', sessionId: 's-1-1', reason: 'process_exit', exitCode: 0});
      await flushAsync();
    });
    expect(rowText(renderer, 2)).toBe('   done');
    expect(renderer.root.findByProps({testID: 'terminal-input'}).props.editable).toBe(false);
    expect(renderer.root.findAllByProps({testID: 'terminal-cursor'})).toHaveLength(0);
    await ReactTestRenderer.act(async () => { renderer.unmount(); await flushAsync(); });
    expect(runtime.listeners).toHaveLength(0);
  });

  test('keeps scrolling gestures from opening the keyboard and pages harness chats in the alternate screen', async () => {
    const runtime = fakeRuntime();
    runtime.spec.writeSessionInput = jest.fn(runtime.spec.writeSessionInput);
    const client = new TerminalSessionClient(runtime.spec, handler => {
      runtime.listeners.push(handler);
      return () => { runtime.listeners.splice(runtime.listeners.indexOf(handler), 1); };
    });
    let renderer!: ReactTestRenderer.ReactTestRenderer;
    await ReactTestRenderer.act(async () => {
      renderer = ReactTestRenderer.create(<TerminalScreen client={client} runtime={runtime.spec} toolchain="codex" />);
      await flushAsync();
    });

    const input = renderer.root.findByType(TextInput).instance;
    const focus = jest.spyOn(input, 'focus').mockClear();
    expect(renderer.root.findByProps({testID: 'terminal-input'}).props.pointerEvents).toBe('none');
    expect(renderer.root.findAllByProps({testID: 'terminal-output-grid'})).toHaveLength(0);
    await ReactTestRenderer.act(async () => {
      runtime.emit({type: 'output', sessionId: 's-1-1', seq: 1, base64: encodeTestBase64('$')});
      await flushAsync();
    });
    const output = () => renderer.root.findByProps({testID: 'terminal-output'});
    expect(renderer.root.findByProps({testID: 'terminal-output-grid'}).props.scrollEnabled).toBe(true);

    await ReactTestRenderer.act(async () => {
      output().props.onTouchStart(terminalTouch(40, 180));
      output().props.onTouchEnd(terminalTouch(40, 90));
      await flushAsync();
    });
    expect(focus).not.toHaveBeenCalled();
    expect(runtime.spec.writeSessionInput).not.toHaveBeenCalled();

    await ReactTestRenderer.act(async () => {
      runtime.emit({type: 'output', sessionId: 's-1-1', seq: 2, base64: encodeTestBase64('\x1b[?1049h\x1b[HChat\x1b[?25l')});
      await flushAsync();
    });
    expect(renderer.root.findByProps({testID: 'terminal-output-grid'}).props.scrollEnabled).toBe(false);

    await ReactTestRenderer.act(async () => {
      output().props.onTouchStart(terminalTouch(40, 90));
      output().props.onTouchMove(terminalTouch(40, 120));
      await flushAsync();
    });
    await ReactTestRenderer.act(async () => {
      jest.advanceTimersByTime(120);
      output().props.onTouchMove(terminalTouch(40, 240));
      await flushAsync();
      output().props.onTouchEnd(terminalTouch(40, 240));
      await flushAsync();
      output().props.onTouchStart(terminalTouch(40, 180));
      output().props.onTouchEnd(terminalTouch(40, 90));
      await flushAsync();
    });
    const writes = (runtime.spec.writeSessionInput as jest.Mock).mock.calls.map(call => decodeTestBase64(call[0].base64));
    expect(writes).toEqual([
      new TextEncoder().encode('\u0014'),
      new TextEncoder().encode('\x1b[5~'),
      new TextEncoder().encode('\x1b[5~'),
      new TextEncoder().encode('\x1b[6~'),
    ]);
    expect(focus).not.toHaveBeenCalled();

    await ReactTestRenderer.act(async () => {
      output().props.onTouchStart(terminalTouch(40, 90));
      output().props.onTouchEnd(terminalTouch(41, 91));
      await flushAsync();
    });
    expect(focus).toHaveBeenCalled();
    await ReactTestRenderer.act(async () => { renderer.unmount(); await flushAsync(); });
  });

  test('unmounts with output parsing and keyboard focus pending without late writes or handles', async () => {
    const runtime = fakeRuntime();
    runtime.spec.writeSessionInput = jest.fn(runtime.spec.writeSessionInput);
    const client = new TerminalSessionClient(runtime.spec, handler => {
      runtime.listeners.push(handler);
      return () => { runtime.listeners.splice(runtime.listeners.indexOf(handler), 1); };
    });
    let renderer!: ReactTestRenderer.ReactTestRenderer;
    await ReactTestRenderer.act(async () => {
      renderer = ReactTestRenderer.create(<TerminalScreen client={client} runtime={runtime.spec} />);
      await flushAsync();
    });
    const input = renderer.root.findByType(TextInput).instance;
    const focus = jest.spyOn(input, 'focus').mockClear();
    const blur = jest.spyOn(input, 'blur').mockClear();
    await ReactTestRenderer.act(async () => {
      const output = renderer.root.findByProps({testID: 'terminal-output'});
      output.props.onTouchStart(terminalTouch(40, 90));
      output.props.onTouchEnd(terminalTouch(40, 90));
      runtime.emit({type: 'output', sessionId: 's-1-1', seq: 1, base64: encodeTestBase64('pending\x1b[6n')});
      renderer.unmount();
    });
    const focusCalls = focus.mock.calls.length;
    await ReactTestRenderer.act(flushAsync);
    expect(blur).toHaveBeenCalledTimes(1);
    expect(focus).toHaveBeenCalledTimes(focusCalls);
    expect(runtime.spec.writeSessionInput).not.toHaveBeenCalled();
    expect(runtime.listeners).toHaveLength(0);
    expect(runtime.stopCalls).toEqual(['s-1-1']);
    expect(jest.getTimerCount()).toBe(0);
  });

  test('reports resize failure instead of continuing with mismatched PTY geometry', async () => {
    const runtime = fakeRuntime();
    runtime.spec.resizeSession = jest.fn(async request => ({requestId: request.requestId, status: 'error', errorCode: 'internal_error'} as const));
    let renderer!: ReactTestRenderer.ReactTestRenderer;
    await ReactTestRenderer.act(async () => {
      renderer = ReactTestRenderer.create(<TerminalScreen client={new TerminalSessionClient(runtime.spec)} runtime={runtime.spec} />);
      await flushAsync();
    });
    await ReactTestRenderer.act(async () => {
      renderer.root.findByProps({testID: 'terminal-output'}).props.onLayout({nativeEvent: {layout: {width: 320, height: 190}}});
    });
    await ReactTestRenderer.act(flushAsync);
    expect(renderer.root.findByProps({testID: 'terminal-error'}).props.children).toBe('terminal_resize_failed');
    expect(renderer.root.findByProps({testID: 'terminal-input'}).props.editable).toBe(false);
    expect(runtime.stopCalls).toEqual(['s-1-1']);
    await ReactTestRenderer.act(async () => { renderer.unmount(); await flushAsync(); });
  });

});
