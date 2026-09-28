import type {Spec} from '../src/native/NativeTerminalRuntime';
import {buildZshScriptCommand, shellQuote} from '../src/terminal/commandFactory';
import {TerminalSessionClient} from '../src/terminal/session/sessionClient';
import {encodeTestBase64} from '../src/testSupport/base64';
import {
  buildListGuestDirectoryCommand,
  buildReadGuestTextFileCommand,
  exportGuestDirectory,
  GUEST_FILE_LIST_LIMIT,
  GUEST_FILE_QUERY_TIMEOUT_MS,
  type GuestFileNativeRuntime,
  listGuestDirectory,
  mapNativeGuestDirectoryResponse,
  mapNativeGuestExportResponse,
  mapNativeGuestFileResponse,
  parseGuestDirectoryOutput,
  parseGuestTextFileOutput,
  readGuestTextFile,
} from '../src/files/fileExplorer';

const marker = 'qtest';
const begin = `HORUS_FILE_QUERY_${marker}_BEGIN`;
const end = `HORUS_FILE_QUERY_${marker}_END`;

type FakeRuntime = {
  spec: Spec;
  listeners: Array<(event: unknown) => void>;
  emit: (event: unknown) => void;
  stopCalls: string[];
};

function fakeRuntime(): FakeRuntime {
  const listeners: Array<(event: unknown) => void> = [];
  const stopCalls: string[] = [];
  const spec = {
    getRuntimeStatus: async () => ({status: 'success', runtimeState: 'ready', prootAvailable: true}),
    installRootfs: async () => ({requestId: '', status: 'error', errorCode: 'internal_error'}),
    provisionToolchain: async (request: {requestId: string}) => ({requestId: request.requestId, status: 'success'}),
    resetRuntime: async () => ({requestId: '', status: 'error', errorCode: 'internal_error'}),
    startSession: jest.fn().mockImplementation(async (request: {requestId: string; rows?: number; columns?: number}) => ({requestId: request.requestId, status: 'success', sessionId: 's-1-1', pid: 1, rows: request.rows ?? 24, columns: request.columns ?? 80})),
    writeSessionInput: async (request: {requestId: string; base64: string}) => ({requestId: request.requestId, status: 'success', bytesWritten: atob(request.base64).length}),
    resizeSession: async (request: {requestId: string; rows: number; columns: number}) => ({requestId: request.requestId, status: 'success', rows: request.rows, columns: request.columns}),
    signalSession: async (request: {requestId: string; signal: string}) => ({requestId: request.requestId, status: 'success', signal: request.signal}),
    stopSession: async (request: {requestId: string; sessionId: string}) => {
      stopCalls.push(request.sessionId);
      return {requestId: request.requestId, status: 'success', sessionId: request.sessionId, exitCode: 0, exitReason: 'screen_detach', remainingProcessCount: 0, stoppedWithinDeadline: true};
    },
    subscribeSessionEvents: async (request: {requestId: string; sessionId: string}) => ({requestId: request.requestId, status: 'success', sessionId: request.sessionId, eventName: 'terminalSessionEvents', sessionState: 'running', firstAvailableSeq: 1, lastEmittedSeq: 0, replayAvailable: true}),
    acknowledgeSessionOutput: async (request: {requestId: string; sessionId: string; seq: number}) => ({requestId: request.requestId, status: 'success', sessionId: request.sessionId, acknowledgedSeq: request.seq, outstandingChunks: 0}),
    addListener: () => undefined,
    removeListeners: () => undefined,
  } as unknown as Spec;
  return {
    spec,
    listeners,
    stopCalls,
    emit: event => [...listeners].forEach(listener => listener(event)),
  };
}

function outputEvent(sessionId: string, seq: number, value: string): unknown {
  return {type: 'output', sessionId, seq, base64: encodeTestBase64(value)};
}

async function flushPromises(): Promise<void> {
  for (let index = 0; index < 12; index += 1) await Promise.resolve();
}

function connectedClient(runtime: FakeRuntime): TerminalSessionClient {
  return new TerminalSessionClient(runtime.spec, handler => {
    runtime.listeners.push(handler);
    return () => {
      const index = runtime.listeners.indexOf(handler);
      if (index >= 0) runtime.listeners.splice(index, 1);
    };
  });
}

function currentQueryMarker(runtime: FakeRuntime): string {
  const calls = (runtime.spec.startSession as unknown as jest.Mock).mock.calls as Array<[
    {command: string},
  ]>;
  const command = calls[calls.length - 1]?.[0].command ?? '';
  return command.match(/HORUS_FILE_QUERY_(q[A-Za-z0-9-]+)_BEGIN/)?.[1] ?? 'missing';
}

function shellQuoteForOuterCommand(value: string): string {
  return shellQuote(value).replace(/'/g, "'\\''");
}

describe('guest file explorer protocol', () => {
  afterEach(() => {
    jest.useRealTimers();
  });

  test('builds root-anchored commands and shell-quotes each path component', () => {
    const component = "code'; touch nope";
    const homeCommand = buildListGuestDirectoryCommand('home', [component], marker);
    const workspaceCommand = buildReadGuestTextFileCommand('workspace', ['docs', 'notes.md'], marker);

    expect(homeCommand).toContain('target="$HOME"');
    expect(homeCommand).toContain('query_status=ok');
    expect(workspaceCommand).toContain('query_status=ok');
    expect(homeCommand).not.toMatch(/(?:^|[\s;])status=/);
    expect(workspaceCommand).not.toMatch(/(?:^|[\s;])status=/);
    expect(homeCommand).toContain('exec zsh -fc');
    expect(homeCommand).not.toContain('exec zsh -lic');
    expect(homeCommand).toContain('find "$target" -mindepth 1 -maxdepth 1 -print0');
    expect(homeCommand).toContain(shellQuoteForOuterCommand(component));
    expect(homeCommand).toContain('[ -L "$entry" ]');
    expect(workspaceCommand).toContain('/workspace');
    expect(workspaceCommand).toContain('head -c 65537');
    expect(workspaceCommand).toContain('HORUS_FILE_QUERY_qtest_BEGIN');
    expect(() => buildListGuestDirectoryCommand('home', ['../escape'], marker)).toThrow();
  });

  test('keeps fixed guest scripts independent of interactive startup files', () => {
    const command = buildZshScriptCommand("printf '%s\\n' 'safe'");
    expect(command).toContain('exec zsh -fc ');
    expect(command).not.toContain('zsh -lic');
  });

  test('parses exact marker-delimited listings, dotfiles, kinds, and truncation', () => {
    const output = [
      'startup noise',
      '\u001b[32m' + begin + '\u001b[0m',
      `${encodeTestBase64('.env')}\tfile\t12`,
      `${encodeTestBase64('folder')}\tdirectory\t0`,
      `${encodeTestBase64('shortcut')}\tsymlink\t0`,
      `${encodeTestBase64('λ.txt')}\tfile\t3`,
      `${encodeTestBase64(new Uint8Array([0xff]))}\tfile\t1`,
      `${end}|ok|0`,
      'trailing prompt noise',
    ].join('\n');

    expect(parseGuestDirectoryOutput(output, marker)).toEqual({
      kind: 'success',
      entries: [
        {name: '.env', kind: 'file', sizeBytes: 12},
        {name: 'folder', kind: 'directory', sizeBytes: 0},
        {name: 'shortcut', kind: 'symlink', sizeBytes: 0},
        {name: 'λ.txt', kind: 'file', sizeBytes: 3},
      ],
      truncated: false,
      hiddenInvalidNameCount: 1,
    });

    const full = Array.from({length: GUEST_FILE_LIST_LIMIT}, (_, index) =>
      `${encodeTestBase64(`entry-${index}`)}\tfile\t1`,
    );
    expect(parseGuestDirectoryOutput([begin, ...full, `${end}|ok|1`].join('\n'), marker)).toMatchObject({
      kind: 'success',
      truncated: true,
    });
  });

  test('decodes valid UTF-8 with a TextDecoder that does not support fatal mode', () => {
    const NativeTextDecoder = globalThis.TextDecoder;
    class TextDecoderWithoutFatalOption extends NativeTextDecoder {
      constructor(label?: string, options?: TextDecoderOptions) {
        super(label, options);
        if (options?.fatal === true) throw new TypeError('fatal option is unsupported');
      }
    }

    globalThis.TextDecoder = TextDecoderWithoutFatalOption;
    try {
      expect(parseGuestDirectoryOutput([
        begin,
        `${encodeTestBase64('README.md')}\tfile\t12`,
        `${end}|ok|0`,
      ].join('\n'), marker)).toEqual({
        kind: 'success',
        entries: [{name: 'README.md', kind: 'file', sizeBytes: 12}],
        truncated: false,
        hiddenInvalidNameCount: 0,
      });
    } finally {
      globalThis.TextDecoder = NativeTextDecoder;
    }
  });

  test('rejects incomplete, duplicated, malformed, and out-of-bound listing records', () => {
    expect(parseGuestDirectoryOutput(`${begin}\n${end}|ok|0`, marker)).toEqual({kind: 'success', entries: [], truncated: false, hiddenInvalidNameCount: 0});
    expect(parseGuestDirectoryOutput(`${begin}\n${end}|ok|0\n${begin}`, marker)).toEqual({kind: 'error', errorCode: 'invalid_output'});
    expect(parseGuestDirectoryOutput(`${begin}\n${end}|ok|0\n${end}`, marker)).toEqual({kind: 'error', errorCode: 'invalid_output'});
    expect(parseGuestDirectoryOutput(`${begin}\nnot-base64\tfile\t1\n${end}|ok|0`, marker)).toEqual({kind: 'error', errorCode: 'invalid_output'});
    expect(parseGuestDirectoryOutput(`${begin}\n${encodeTestBase64('../escape')}\tfile\t1\n${end}|ok|0`, marker)).toEqual({kind: 'error', errorCode: 'invalid_output'});
    expect(parseGuestDirectoryOutput(`${begin}\n${end}|not_found|0`, marker)).toEqual({kind: 'error', errorCode: 'not_found'});
    expect(parseGuestDirectoryOutput(`${begin}\n${end}|ok|1`, marker)).toEqual({kind: 'error', errorCode: 'invalid_output'});
  });

  test('parses UTF-8 text previews and rejects invalid UTF-8, binary, malformed base64, and oversized data', () => {
    const text = 'hello 🌙\n';
    const size = new TextEncoder().encode(text).byteLength;
    expect(parseGuestTextFileOutput(`${begin}\nDATA|${encodeTestBase64(text)}\n${end}|ok|${size}`, marker)).toEqual({kind: 'success', content: text, sizeBytes: size});
    expect(parseGuestTextFileOutput(`${begin}\nDATA|/w==\n${end}|ok|1`, marker)).toEqual({kind: 'error', errorCode: 'binary_or_invalid_text'});
    expect(parseGuestTextFileOutput(`${begin}\nDATA|AA==\n${end}|ok|1`, marker)).toEqual({kind: 'error', errorCode: 'binary_or_invalid_text'});
    expect(parseGuestTextFileOutput(`${begin}\nDATA|bad!\n${end}|ok|1`, marker)).toEqual({kind: 'error', errorCode: 'invalid_output'});
    expect(parseGuestTextFileOutput(`${begin}\n${end}|too_large|65537`, marker)).toEqual({kind: 'error', errorCode: 'too_large'});
    const oversized = encodeTestBase64(new Uint8Array(64 * 1024 + 1));
    expect(parseGuestTextFileOutput(`${begin}\nDATA|${oversized}\n${end}|ok|65537`, marker)).toEqual({kind: 'error', errorCode: 'too_large'});
  });
});

describe('guest file explorer PTY queries', () => {
  afterEach(() => {
    jest.useRealTimers();
  });

  test('lists a directory through PTY and disposes the query client after natural exit', async () => {
    const runtime = fakeRuntime();
    const client = connectedClient(runtime);
    const resultPromise = listGuestDirectory('workspace', ['projects'], client);
    await flushPromises();

    expect((runtime.spec.startSession as unknown as jest.Mock)).toHaveBeenCalledWith(expect.objectContaining({toolchain: 'shell', command: expect.stringContaining('/workspace'), countsAgainstSessionLimit: false}));
    const queryMarker = currentQueryMarker(runtime);
    runtime.emit(outputEvent('s-1-1', 1, [
      `HORUS_FILE_QUERY_${queryMarker}_BEGIN`,
      `${encodeTestBase64('.hidden')}\tfile\t5`,
      `HORUS_FILE_QUERY_${queryMarker}_END|ok|0`,
    ].join('\n')));
    runtime.emit({type: 'exit', sessionId: 's-1-1', reason: 'exited', exitCode: 0});

    await expect(resultPromise).resolves.toEqual({kind: 'success', entries: [{name: '.hidden', kind: 'file', sizeBytes: 5}], truncated: false, hiddenInvalidNameCount: 0});
    expect(runtime.stopCalls).toEqual([]);
    expect(runtime.listeners).toHaveLength(0);
  });

  test('reads a file through PTY and maps guest path errors safely', async () => {
    const runtime = fakeRuntime();
    const client = connectedClient(runtime);
    const resultPromise = readGuestTextFile('home', ['projects', 'readme.txt'], client);
    await flushPromises();
    const queryMarker = currentQueryMarker(runtime);
    const text = 'safe preview\n';
    const size = new TextEncoder().encode(text).byteLength;
    runtime.emit(outputEvent('s-1-1', 1, [
      `HORUS_FILE_QUERY_${queryMarker}_BEGIN`,
      `DATA|${encodeTestBase64(text)}`,
      `HORUS_FILE_QUERY_${queryMarker}_END|ok|${size}`,
    ].join('\n')));
    runtime.emit({type: 'exit', sessionId: 's-1-1', reason: 'exited', exitCode: 0});
    await expect(resultPromise).resolves.toEqual({kind: 'success', content: text, sizeBytes: size});
    expect(runtime.stopCalls).toEqual([]);

    const missingRuntime = fakeRuntime();
    const missingPromise = listGuestDirectory('home', [], connectedClient(missingRuntime));
    await flushPromises();
    const missingMarker = currentQueryMarker(missingRuntime);
    missingRuntime.emit(outputEvent('s-1-1', 1, [
      `HORUS_FILE_QUERY_${missingMarker}_BEGIN`,
      `HORUS_FILE_QUERY_${missingMarker}_END|not_found|0`,
    ].join('\n')));
    missingRuntime.emit({type: 'exit', sessionId: 's-1-1', reason: 'exited', exitCode: 0});
    await expect(missingPromise).resolves.toEqual({kind: 'error', errorCode: 'not_found'});
    expect(missingRuntime.stopCalls).toEqual([]);
  });

  test('rejects invalid paths before starting a PTY session', async () => {
    const runtime = fakeRuntime();
    const client = connectedClient(runtime);
    await expect(listGuestDirectory('home', ['..'], client)).resolves.toEqual({kind: 'error', errorCode: 'invalid_path'});
    await expect(readGuestTextFile('workspace', [], client)).resolves.toEqual({kind: 'error', errorCode: 'invalid_path'});
    await expect(listGuestDirectory('home', ["'".repeat(200), "'".repeat(200)], client)).resolves.toEqual({kind: 'error', errorCode: 'invalid_path'});
    expect((runtime.spec.startSession as unknown as jest.Mock)).not.toHaveBeenCalled();
  });

  test('times out, stops the PTY session, removes listeners, and clears its timer', async () => {
    jest.useFakeTimers();
    const runtime = fakeRuntime();
    const client = connectedClient(runtime);
    const resultPromise = listGuestDirectory('home', [], client);
    await flushPromises();
    expect(runtime.listeners).toHaveLength(1);

    await jest.advanceTimersByTimeAsync(GUEST_FILE_QUERY_TIMEOUT_MS);
    await expect(resultPromise).resolves.toEqual({kind: 'error', errorCode: 'timeout'});
    expect(runtime.stopCalls).toEqual(['s-1-1']);
    expect(runtime.listeners).toHaveLength(0);
    expect(jest.getTimerCount()).toBe(0);
  });
});

describe('guest file explorer native direct-storage queries', () => {
  function nativeRuntime(overrides: {
    list?: (request: {requestId: string; root: string; path: string[]}) => Promise<unknown>;
    read?: (request: {requestId: string; root: string; path: string[]}) => Promise<unknown>;
  } = {}): {
    listGuestDirectory: jest.Mock & GuestFileNativeRuntime['listGuestDirectory'];
    readGuestFile: jest.Mock & GuestFileNativeRuntime['readGuestFile'];
  } {
    return {
      listGuestDirectory: jest.fn(overrides.list ?? (async (request: {requestId: string}) => ({
        requestId: request.requestId,
        status: 'success',
        entries: [
          {name: '.env', kind: 'file', sizeBytes: 12},
          {name: 'folder', kind: 'directory', sizeBytes: 0},
          {name: 'shortcut', kind: 'symlink', sizeBytes: 0},
          {name: 'fifo', kind: 'other', sizeBytes: 0},
        ],
        truncated: false,
        hiddenInvalidNameCount: 1,
      }))),
      readGuestFile: jest.fn(overrides.read ?? (async (request: {requestId: string}) => ({
        requestId: request.requestId,
        status: 'success',
        base64: encodeTestBase64('hello 🌙\n'),
        sizeBytes: new TextEncoder().encode('hello 🌙\n').byteLength,
      }))),
    } as unknown as ReturnType<typeof nativeRuntime>;
  }

  test('lists through the native module without starting a PTY and disposes a supplied client', async () => {
    const pty = fakeRuntime();
    const client = connectedClient(pty);
    const dispose = jest.spyOn(client, 'dispose');
    const runtime = nativeRuntime();

    await expect(listGuestDirectory('workspace', ['projects', 'λ'], client, runtime)).resolves.toEqual({
      kind: 'success',
      entries: [
        {name: '.env', kind: 'file', sizeBytes: 12},
        {name: 'folder', kind: 'directory', sizeBytes: 0},
        {name: 'shortcut', kind: 'symlink', sizeBytes: 0},
        {name: 'fifo', kind: 'other', sizeBytes: 0},
      ],
      truncated: false,
      hiddenInvalidNameCount: 1,
    });
    expect(runtime.listGuestDirectory).toHaveBeenCalledWith({
      requestId: expect.stringMatching(/^files-list-[a-z0-9]+$/),
      root: 'workspace',
      path: ['projects', 'λ'],
    });
    expect(pty.spec.startSession as unknown as jest.Mock).not.toHaveBeenCalled();
    expect(dispose).toHaveBeenCalled();
    expect(pty.listeners).toHaveLength(0);
  });

  test('reads through the native module and keeps the PTY UTF-8 classification', async () => {
    const runtime = nativeRuntime();
    const text = 'hello 🌙\n';
    await expect(readGuestTextFile('home', ['notes.md'], undefined, runtime)).resolves.toEqual({
      kind: 'success',
      content: text,
      sizeBytes: new TextEncoder().encode(text).byteLength,
    });
    expect(runtime.readGuestFile).toHaveBeenCalledWith({
      requestId: expect.stringMatching(/^files-read-[a-z0-9]+$/),
      root: 'home',
      path: ['notes.md'],
    });

    const id = 'files-read-x';
    expect(mapNativeGuestFileResponse({requestId: id, status: 'success', base64: '', sizeBytes: 0}, id)).toEqual({kind: 'success', content: '', sizeBytes: 0});
    expect(mapNativeGuestFileResponse({requestId: id, status: 'success', base64: '/w==', sizeBytes: 1}, id)).toEqual({kind: 'error', errorCode: 'binary_or_invalid_text'});
    expect(mapNativeGuestFileResponse({requestId: id, status: 'success', base64: 'AA==', sizeBytes: 1}, id)).toEqual({kind: 'error', errorCode: 'binary_or_invalid_text'});
    expect(mapNativeGuestFileResponse({requestId: id, status: 'success', base64: encodeTestBase64('abc'), sizeBytes: 4}, id)).toEqual({kind: 'error', errorCode: 'invalid_output'});
    expect(mapNativeGuestFileResponse({requestId: id, status: 'success', base64: 'bad!', sizeBytes: 3}, id)).toEqual({kind: 'error', errorCode: 'invalid_output'});
    expect(mapNativeGuestFileResponse({requestId: id, status: 'success', base64: encodeTestBase64(new Uint8Array(64 * 1024 + 1)), sizeBytes: 65537}, id)).toEqual({kind: 'error', errorCode: 'too_large'});
    expect(mapNativeGuestFileResponse({requestId: id, status: 'error', errorCode: 'too_large'}, id)).toEqual({kind: 'error', errorCode: 'too_large'});
    expect(mapNativeGuestFileResponse({requestId: id, status: 'error', errorCode: 'command_failed'}, id)).toEqual({kind: 'error', errorCode: 'command_failed'});
    expect(mapNativeGuestFileResponse({requestId: id, status: 'error', errorCode: 'bogus'}, id)).toEqual({kind: 'error', errorCode: 'invalid_output'});
    expect(mapNativeGuestFileResponse({requestId: 'other', status: 'success', base64: '', sizeBytes: 0}, id)).toEqual({kind: 'error', errorCode: 'invalid_output'});
    expect(mapNativeGuestFileResponse({requestId: id, status: 'success', sizeBytes: 0}, id)).toEqual({kind: 'error', errorCode: 'invalid_output'});
  });

  test('applies the PTY listing bounds to native responses', () => {
    const id = 'files-list-x';
    const ok = (extra: Record<string, unknown>) => ({requestId: id, status: 'success', entries: [], truncated: false, hiddenInvalidNameCount: 0, ...extra});
    const full = Array.from({length: GUEST_FILE_LIST_LIMIT}, (_, index) => ({name: `entry-${index}`, kind: 'file', sizeBytes: 1}));

    expect(mapNativeGuestDirectoryResponse(ok({}), id)).toEqual({kind: 'success', entries: [], truncated: false, hiddenInvalidNameCount: 0});
    expect(mapNativeGuestDirectoryResponse(ok({entries: full, truncated: true}), id)).toMatchObject({kind: 'success', truncated: true});
    expect(mapNativeGuestDirectoryResponse(ok({truncated: true}), id)).toEqual({kind: 'error', errorCode: 'invalid_output'});
    expect(mapNativeGuestDirectoryResponse(ok({entries: full.slice(1), truncated: true, hiddenInvalidNameCount: 1}), id)).toEqual({kind: 'error', errorCode: 'invalid_output'});
    expect(mapNativeGuestDirectoryResponse(ok({entries: [...full, {name: 'extra', kind: 'file', sizeBytes: 1}]}), id)).toEqual({kind: 'error', errorCode: 'invalid_output'});
    expect(mapNativeGuestDirectoryResponse(ok({entries: [{name: 'a', kind: 'file', sizeBytes: 1}, {name: 'a', kind: 'file', sizeBytes: 1}]}), id)).toEqual({kind: 'error', errorCode: 'invalid_output'});
    expect(mapNativeGuestDirectoryResponse(ok({entries: [{name: '..', kind: 'directory', sizeBytes: 0}]}), id)).toEqual({kind: 'error', errorCode: 'invalid_output'});
    expect(mapNativeGuestDirectoryResponse(ok({entries: [{name: 'a/b', kind: 'file', sizeBytes: 0}]}), id)).toEqual({kind: 'error', errorCode: 'invalid_output'});
    expect(mapNativeGuestDirectoryResponse(ok({entries: [{name: 'a', kind: 'socket', sizeBytes: 0}]}), id)).toEqual({kind: 'error', errorCode: 'invalid_output'});
    expect(mapNativeGuestDirectoryResponse(ok({entries: [{name: 'a', kind: 'file', sizeBytes: -1}]}), id)).toEqual({kind: 'error', errorCode: 'invalid_output'});
    expect(mapNativeGuestDirectoryResponse(ok({hiddenInvalidNameCount: 1.5}), id)).toEqual({kind: 'error', errorCode: 'invalid_output'});
    expect(mapNativeGuestDirectoryResponse(ok({requestId: 'other'}), id)).toEqual({kind: 'error', errorCode: 'invalid_output'});
    expect(mapNativeGuestDirectoryResponse({requestId: id, status: 'error', errorCode: 'not_found'}, id)).toEqual({kind: 'error', errorCode: 'not_found'});
    expect(mapNativeGuestDirectoryResponse({requestId: id, status: 'error', errorCode: 'invalid_path'}, id)).toEqual({kind: 'error', errorCode: 'invalid_path'});
    expect(mapNativeGuestDirectoryResponse({requestId: id, status: 'error', errorCode: 'session_limit_reached'}, id)).toEqual({kind: 'error', errorCode: 'invalid_output'});
  });

  test('rejects invalid paths before calling native and maps native rejections', async () => {
    const runtime = nativeRuntime({
      list: async () => { throw new Error('bridge gone'); },
      read: async () => { throw new Error('bridge gone'); },
    });
    await expect(listGuestDirectory('home', ['..'], undefined, runtime)).resolves.toEqual({kind: 'error', errorCode: 'invalid_path'});
    await expect(readGuestTextFile('workspace', [], undefined, runtime)).resolves.toEqual({kind: 'error', errorCode: 'invalid_path'});
    expect(runtime.listGuestDirectory).not.toHaveBeenCalled();
    expect(runtime.readGuestFile).not.toHaveBeenCalled();
    await expect(listGuestDirectory('home', [], undefined, runtime)).resolves.toEqual({kind: 'error', errorCode: 'internal_error'});
    await expect(readGuestTextFile('home', ['a'], undefined, runtime)).resolves.toEqual({kind: 'error', errorCode: 'internal_error'});
  });

  test('falls back to the PTY query when the native methods are missing', async () => {
    const pty = fakeRuntime();
    const resultPromise = listGuestDirectory('home', [], connectedClient(pty), pty.spec as unknown as Record<string, never>);
    await flushPromises();
    expect(pty.spec.startSession as unknown as jest.Mock).toHaveBeenCalledTimes(1);
    const queryMarker = currentQueryMarker(pty);
    pty.emit(outputEvent('s-1-1', 1, [
      `HORUS_FILE_QUERY_${queryMarker}_BEGIN`,
      `HORUS_FILE_QUERY_${queryMarker}_END|ok|0`,
    ].join('\n')));
    pty.emit({type: 'exit', sessionId: 's-1-1', reason: 'exited', exitCode: 0});
    await expect(resultPromise).resolves.toEqual({kind: 'success', entries: [], truncated: false, hiddenInvalidNameCount: 0});
  });
});

describe('guest folder export to Downloads', () => {
  test('exports through the native module and validates its response', async () => {
    const exportMock = jest.fn(async (request: {requestId: string; root: string; path: string[]}) => ({
      requestId: request.requestId,
      status: 'success',
      destination: 'Download/Horus/src-20260928-143205',
      fileCount: 3,
      byteCount: 42,
      skippedCount: 1,
    }));
    const runtime = {exportGuestDirectory: exportMock} as never;

    await expect(exportGuestDirectory('workspace', ['src'], runtime)).resolves.toEqual({
      kind: 'success',
      destination: 'Download/Horus/src-20260928-143205',
      fileCount: 3,
      byteCount: 42,
      skippedCount: 1,
    });
    expect(exportMock).toHaveBeenCalledWith(expect.objectContaining({root: 'workspace', path: ['src']}));

    await expect(exportGuestDirectory('workspace', ['..'], runtime)).resolves.toEqual({kind: 'error', errorCode: 'invalid_path'});
    await expect(exportGuestDirectory('home', [], null)).resolves.toEqual({kind: 'error', errorCode: 'unsupported'});
    expect(exportMock).toHaveBeenCalledTimes(1);
  });

  test('maps native export errors and rejects malformed success payloads', () => {
    const id = 'files-export-1';
    expect(mapNativeGuestExportResponse({requestId: id, status: 'error', errorCode: 'too_large'}, id)).toEqual({kind: 'error', errorCode: 'too_large'});
    expect(mapNativeGuestExportResponse({requestId: id, status: 'error', errorCode: 'permission_denied'}, id)).toEqual({kind: 'error', errorCode: 'permission_denied'});
    expect(mapNativeGuestExportResponse({requestId: id, status: 'error', errorCode: 'bogus'}, id)).toEqual({kind: 'error', errorCode: 'invalid_output'});
    expect(mapNativeGuestExportResponse({requestId: 'other', status: 'success'}, id)).toEqual({kind: 'error', errorCode: 'invalid_output'});
    expect(mapNativeGuestExportResponse({requestId: id, status: 'success', destination: '', fileCount: 1, byteCount: 1, skippedCount: 0}, id)).toEqual({kind: 'error', errorCode: 'invalid_output'});
    expect(mapNativeGuestExportResponse({requestId: id, status: 'success', destination: 'd', fileCount: -1, byteCount: 1, skippedCount: 0}, id)).toEqual({kind: 'error', errorCode: 'invalid_output'});
  });
});
