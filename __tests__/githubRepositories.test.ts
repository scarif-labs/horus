import {decodeTestBase64, encodeTestBase64} from '../src/testSupport/base64';
import type {Spec} from '../src/native/NativeTerminalRuntime';
import {TerminalSessionClient} from '../src/terminal/session/sessionClient';
import {
  buildGithubRepositoryListCommand,
  GITHUB_REPO_LIST_TIMEOUT_MS,
  listGithubRepositories,
  parseGithubRepositoryListOutput,
} from '../src/projects/githubRepositories';

function fakeRuntime(): {spec: Spec; listeners: Array<(event: unknown) => void>; emit: (event: unknown) => void; stopCalls: string[]} {
  const listeners: Array<(event: unknown) => void> = [];
  const stopCalls: string[] = [];
  const spec = {
    getRuntimeStatus: async () => ({status: 'success', runtimeState: 'ready', prootAvailable: true}),
    installRootfs: async () => ({requestId: '', status: 'error', errorCode: 'internal_error'}),
    provisionToolchain: async (request: {requestId: string}) => ({requestId: request.requestId, status: 'success'}),
    resetRuntime: async () => ({requestId: '', status: 'error', errorCode: 'internal_error'}),
    startSession: jest.fn().mockImplementation(async (request: {requestId: string; rows?: number; columns?: number}) => ({requestId: request.requestId, status: 'success', sessionId: 's-1-1', pid: 1, rows: request.rows ?? 24, columns: request.columns ?? 80})),
    writeSessionInput: async (request: {requestId: string; base64: string}) => ({requestId: request.requestId, status: 'success', bytesWritten: decodeTestBase64(request.base64).byteLength}),
    resizeSession: async (request: {requestId: string; rows: number; columns: number}) => ({requestId: request.requestId, status: 'success', rows: request.rows, columns: request.columns}),
    signalSession: async (request: {requestId: string; signal: string}) => ({requestId: request.requestId, status: 'success', signal: request.signal}),
    stopSession: async (request: {requestId: string; sessionId: string}) => { stopCalls.push(request.sessionId); return {requestId: request.requestId, status: 'success', sessionId: request.sessionId, exitCode: 0, exitReason: 'screen_detach', remainingProcessCount: 0, stoppedWithinDeadline: true}; },
    subscribeSessionEvents: async (request: {requestId: string; sessionId: string}) => ({requestId: request.requestId, status: 'success', sessionId: request.sessionId, eventName: 'terminalSessionEvents', sessionState: 'running', firstAvailableSeq: 1, lastEmittedSeq: 0, replayAvailable: true}),
    acknowledgeSessionOutput: async (request: {requestId: string; sessionId: string; seq: number}) => ({requestId: request.requestId, status: 'success', sessionId: request.sessionId, acknowledgedSeq: request.seq, outstandingChunks: 0}),
    addListener: () => undefined,
    removeListeners: () => undefined,
  } as unknown as Spec;
  return {spec, listeners, stopCalls, emit: event => [...listeners].forEach(listener => listener(event))};
}

function outputEvent(sessionId: string, seq: number, text: string): unknown {
  return {type: 'output', sessionId, seq, base64: encodeTestBase64(text)};
}

describe('GitHub repository loading', () => {
  afterEach(() => {
    jest.useRealTimers();
  });

  test('builds a bounded machine-readable gh query', () => {
    const command = buildGithubRepositoryListCommand();
    expect(command).toContain('cd /workspace/projects');
    expect(command).toContain('exec zsh -fc');
    expect(command).not.toContain('zsh -lic');
    expect(command).toContain('export GH_PAGER=cat');
    expect(command).toContain('export NO_COLOR=1');
    expect(command).toContain('affiliation=owner,collaborator,organization_member');
    expect(command).toContain('per_page=100');
    expect(command).toContain('.name, .full_name');
    expect(command).toContain('HORUS_GITHUB_REPOS_BEGIN');
    expect(command).toContain('HORUS_GITHUB_REPOS_END');
  });

  test('parses repository rows and ignores duplicate rows', () => {
    expect(parseGithubRepositoryListOutput([
      'noise before marker',
      'HORUS_GITHUB_ACCOUNT_BEGIN',
      '0x1379\thttps://avatars.githubusercontent.com/u/123?v=4',
      'HORUS_GITHUB_ACCOUNT_END|0',
      'HORUS_GITHUB_REPOS_BEGIN',
      'horus\t0x1379/horus',
      'horus\t0x1379/horus',
      'HORUS_GITHUB_REPOS_END|0',
    ].join('\n'))).toEqual({
      kind: 'success',
      account: {username: '0x1379', avatarUrl: 'https://avatars.githubusercontent.com/u/123'},
      repositories: [{name: 'horus', path: '0x1379/horus', remote: 'https://github.com/0x1379/horus.git'}],
    });
  });

  test.each(['\u0007', '\u001b\\'])('preserves account and repository boundaries between OSC sequences ending in %j', terminator => {
    const terminalQuery = `\u001b]11;?${terminator}\u001b]10;?${terminator}`;
    expect(parseGithubRepositoryListOutput([
      'HORUS_TOOLCHAIN_READY',
      'HORUS_INSTALL_HANDOFF=horus',
      'HORUS_GITHUB_ACCOUNT_BEGIN',
      `${terminalQuery}user\thttps://avatars.githubusercontent.com/u/1`,
      'HORUS_GITHUB_ACCOUNT_END|0',
      'HORUS_GITHUB_REPOS_BEGIN',
      `${terminalQuery}repo\towner/repo`,
      'HORUS_GITHUB_REPOS_END|0',
      '',
    ].join('\r\n'))).toEqual({
      kind: 'success',
      account: {username: 'user', avatarUrl: 'https://avatars.githubusercontent.com/u/1'},
      repositories: [{name: 'repo', path: 'owner/repo', remote: 'https://github.com/owner/repo.git'}],
    });
  });

  test('treats a successful empty repository marker block as an empty account repository list', () => {
    expect(parseGithubRepositoryListOutput([
      'HORUS_GITHUB_ACCOUNT_BEGIN',
      'user\thttps://avatars.githubusercontent.com/u/1',
      'HORUS_GITHUB_ACCOUNT_END|0',
      'HORUS_GITHUB_REPOS_BEGIN',
      'HORUS_GITHUB_REPOS_END|0',
    ].join('\n'))).toEqual({
      kind: 'success',
      account: {username: 'user', avatarUrl: 'https://avatars.githubusercontent.com/u/1'},
      repositories: [],
    });
  });

  test('accepts GitHub repository names that begin with allowed punctuation', () => {
    expect(parseGithubRepositoryListOutput([
      'HORUS_GITHUB_ACCOUNT_BEGIN',
      'user\thttps://avatars.githubusercontent.com/u/1',
      'HORUS_GITHUB_ACCOUNT_END|0',
      'HORUS_GITHUB_REPOS_BEGIN',
      '.github\towner/.github',
      '_config\towner/_config',
      '-notes\towner/-notes',
      'HORUS_GITHUB_REPOS_END|0',
    ].join('\n'))).toEqual({
      kind: 'success',
      account: {username: 'user', avatarUrl: 'https://avatars.githubusercontent.com/u/1'},
      repositories: [
        {name: '.github', path: 'owner/.github', remote: 'https://github.com/owner/.github.git'},
        {name: '_config', path: 'owner/_config', remote: 'https://github.com/owner/_config.git'},
        {name: '-notes', path: 'owner/-notes', remote: 'https://github.com/owner/-notes.git'},
      ],
    });
  });

  test('rejects repository names that would escape the projects directory', () => {
    expect(parseGithubRepositoryListOutput([
      'HORUS_GITHUB_ACCOUNT_BEGIN',
      'user\thttps://avatars.githubusercontent.com/u/1',
      'HORUS_GITHUB_ACCOUNT_END|0',
      'HORUS_GITHUB_REPOS_BEGIN',
      '..\towner/..',
      'HORUS_GITHUB_REPOS_END|0',
    ].join('\n'))).toEqual({
      kind: 'error',
      account: {username: 'user', avatarUrl: 'https://avatars.githubusercontent.com/u/1'},
      errorCode: 'invalid_output',
      outputIssue: 'repository_row_invalid',
    });
  });

  test('rejects malformed rows and non-zero gh exits', () => {
    expect(parseGithubRepositoryListOutput('HORUS_GITHUB_ACCOUNT_BEGIN\nuser\thttps://avatars.githubusercontent.com/u/1\nHORUS_GITHUB_ACCOUNT_END|0\nHORUS_GITHUB_REPOS_BEGIN\nnot-a-row\nHORUS_GITHUB_REPOS_END|0\n')).toEqual({kind: 'error', account: {username: 'user', avatarUrl: 'https://avatars.githubusercontent.com/u/1'}, errorCode: 'invalid_output', outputIssue: 'repository_row_invalid'});
    expect(parseGithubRepositoryListOutput('HORUS_GITHUB_ACCOUNT_BEGIN\nuser\thttps://avatars.githubusercontent.com/u/1\nHORUS_GITHUB_ACCOUNT_END|0\nHORUS_GITHUB_REPOS_BEGIN\nHORUS_GITHUB_REPOS_END|4\n')).toEqual({kind: 'error', account: {username: 'user', avatarUrl: 'https://avatars.githubusercontent.com/u/1'}, errorCode: 'command_failed', exitCode: 4});
  });

  test('runs the query through the session contract and cleans up after exit', async () => {
    jest.useFakeTimers();
    const runtime = fakeRuntime();
    const client = new TerminalSessionClient(runtime.spec, handler => {
      runtime.listeners.push(handler);
      return () => {
        const index = runtime.listeners.indexOf(handler);
        if (index >= 0) runtime.listeners.splice(index, 1);
      };
    });
    const onAccount = jest.fn();
    const resultPromise = listGithubRepositories(client, onAccount);
    for (let index = 0; index < 8; index += 1) await Promise.resolve();
    expect((runtime.spec.startSession as unknown as jest.Mock)).toHaveBeenCalled();
    expect((runtime.spec.startSession as unknown as jest.Mock)).toHaveBeenCalledWith(expect.objectContaining({toolchain: 'github'}));
    runtime.emit(outputEvent('s-1-1', 1, 'HORUS_TOOLCHAIN_READY\r\nHORUS_INSTALL_HANDOFF=horus\r\nHORUS_GITHUB_ACCOUNT_BEGIN\r\n\u001b]11;?\u001b'));
    expect(onAccount).not.toHaveBeenCalled();
    runtime.emit(outputEvent('s-1-1', 2, '\\user\thttps://avatars.githubusercontent.com/u/1\r\nHORUS_GITHUB_ACCOUNT_END|0\r\n'));
    expect(onAccount).toHaveBeenCalledTimes(1);
    expect(onAccount).toHaveBeenCalledWith({username: 'user', avatarUrl: 'https://avatars.githubusercontent.com/u/1'});
    runtime.emit(outputEvent('s-1-1', 3, 'HORUS_GITHUB_REPOS_BEGIN\r\n\u001b]11;?\u001b'));
    runtime.emit(outputEvent('s-1-1', 4, '\\repo\towner/repo\r\nHORUS_GITHUB_REPOS_END|0\r\n'));
    runtime.emit({type: 'exit', sessionId: 's-1-1', reason: 'exited', exitCode: 0});
    await expect(resultPromise).resolves.toEqual({
      kind: 'success',
      account: {username: 'user', avatarUrl: 'https://avatars.githubusercontent.com/u/1'},
      repositories: [{name: 'repo', path: 'owner/repo', remote: 'https://github.com/owner/repo.git'}],
    });
    expect(onAccount).toHaveBeenCalledTimes(1);
    expect(runtime.listeners).toHaveLength(0);
    expect(jest.getTimerCount()).toBe(0);
  });

  test('stops a hung query at the bounded timeout', async () => {
    jest.useFakeTimers();
    const runtime = fakeRuntime();
    const client = new TerminalSessionClient(runtime.spec, handler => {
      runtime.listeners.push(handler);
      return () => {
        const index = runtime.listeners.indexOf(handler);
        if (index >= 0) runtime.listeners.splice(index, 1);
      };
    });
    const resultPromise = listGithubRepositories(client);
    for (let index = 0; index < 8; index += 1) await Promise.resolve();
    runtime.emit(outputEvent('s-1-1', 1, 'HORUS_GITHUB_ACCOUNT_BEGIN\nuser\thttps://avatars.githubusercontent.com/u/1\nHORUS_GITHUB_ACCOUNT_END|0\n'));
    await jest.advanceTimersByTimeAsync(GITHUB_REPO_LIST_TIMEOUT_MS);
    await expect(resultPromise).resolves.toEqual({kind: 'error', account: {username: 'user', avatarUrl: 'https://avatars.githubusercontent.com/u/1'}, errorCode: 'timeout'});
    expect(runtime.stopCalls).toEqual(['s-1-1']);
    expect(runtime.listeners).toHaveLength(0);
    expect(jest.getTimerCount()).toBe(0);
  });
});
