import React from 'react';
import ReactTestRenderer from 'react-test-renderer';
import {BackHandler} from 'react-native';
import {readTerminalRuntimeStatus} from '../src/terminal/runtimeStatus';
import {listGuestDirectory} from '../src/files/fileExplorer';
import {listGithubRepositories, type GithubRepositoryListResult} from '../src/projects/githubRepositories';
import {clearStoredGithubAccount, readStoredGithubAccount} from '../src/projects/githubAccountStore';
import {TerminalSessionClient} from '../src/terminal/session/sessionClient';
import {useBackgroundSessionLock} from '../src/profile/sessionTimeout';
import App from '../App';

jest.mock('../src/terminal/runtimeStatus', () => ({
  installRootfs: jest.fn(),
  readTerminalRuntimeStatus: jest.fn(async () => ({
    kind: 'success',
    runtimeState: 'ready',
    prootAvailable: true,
  })),
}));

jest.mock('../src/files/fileExplorer', () => ({
  listGuestDirectory: jest.fn(async () => ({kind: 'success', entries: [], truncated: false, hiddenInvalidNameCount: 0})),
}));

jest.mock('../src/terminal/session/sessionClient', () => ({
  TerminalSessionClient: jest.fn().mockImplementation(() => ({
    dispose: jest.fn(),
    listTerminalSessions: jest.fn(async () => ({kind: 'success', sessions: []})),
    stopAllTerminalSessions: jest.fn(async () => ({kind: 'success'})),
    stopSession: jest.fn(async () => ({kind: 'success'})),
  })),
}));

jest.mock('../src/projects/githubRepositories', () => ({
  ...jest.requireActual('../src/projects/githubRepositories'),
  listGithubRepositories: jest.fn(async (_client: unknown, onAccount?: (account: {username: string; avatarUrl?: string}) => void) => {
    const account = {username: 'debug', avatarUrl: 'https://avatars.githubusercontent.com/u/1'};
    onAccount?.(account);
    return {kind: 'success', account, repositories: []};
  }),
}));

jest.mock('../src/projects/githubAccountStore', () => ({
  readStoredGithubAccount: jest.fn(async () => undefined),
  saveGithubAccount: jest.fn(async () => undefined),
  clearStoredGithubAccount: jest.fn(async () => true),
}));

jest.mock('../src/projects/githubDeviceLogin', () => ({
  GITHUB_AUTH_LOGIN_COMMAND: 'gh auth login',
  openGithubDeviceLoginUrl: jest.fn(async () => undefined),
}));

jest.mock('../src/profile/profileStore', () => ({
  readUserProfile: jest.fn(async () => null),
  saveUserProfile: jest.fn(async () => true),
  verifyUserPassword: jest.fn(async () => ({kind: 'incorrect'})),
  lockedOutMessage: jest.fn(() => 'locked'),
}));

jest.mock('../src/profile/sessionTimeout', () => ({
  useBackgroundSessionLock: jest.fn(),
}));

jest.mock('../src/terminal/TerminalScreen', () => ({
  TerminalScreen: ({runtimeReady, screenTitle, screenEyebrow, sessionCommand, toolchain, completionMarker, onCompletion, onCommandFailure, onBack, onHome, stopSessionOnUnmount}: {runtimeReady?: boolean; screenTitle?: string; screenEyebrow?: string; sessionCommand?: string; toolchain?: string; completionMarker?: string; onCompletion?: () => void; onCommandFailure?: () => void; onBack?: () => void; onHome?: () => void; stopSessionOnUnmount?: boolean}) => require('react').createElement(
    require('react-native').View,
    {testID: 'debug-terminal', runtimeReady, screenTitle, screenEyebrow, sessionCommand, toolchain, completionMarker, onBack, onHome, stopSessionOnUnmount},
    onCompletion === undefined ? null : require('react').createElement(
      require('react-native').Pressable,
      {testID: 'debug-terminal-completion', onPress: onCompletion},
    ),
    onCommandFailure === undefined ? null : require('react').createElement(
      require('react-native').Pressable,
      {testID: 'debug-terminal-command-failure', onPress: onCommandFailure},
    ),
  ),
}));

jest.mock('../src/ui/MetroHomeScreen', () => ({
  MetroHomeScreen: ({onOpen, onOpenGithubLogin, onOpenGithubAccount, githubAccount}: {onOpen: (target: {title: string; eyebrow: string; toolchain?: 'claude' | 'github'; command?: string; kind?: 'files'}) => void; onOpenGithubLogin: () => void; onOpenGithubAccount?: () => void; githubAccount?: {username: string; avatarUrl?: string}}) => require('react').createElement(
    require('react-native').View,
    {testID: 'metro-home'},
    require('react').createElement(
      require('react-native').Text,
      {testID: 'metro-github-account-state'},
      githubAccount?.username ?? 'signed-out',
    ),
    githubAccount?.avatarUrl === undefined ? null : require('react').createElement(
      require('react-native').Image,
      {testID: 'metro-github-avatar', source: {uri: githubAccount.avatarUrl}},
    ),
    require('react').createElement(
      require('react-native').Text,
      {testID: 'metro-profile'},
      'HORUS',
    ),
    require('react').createElement(
      require('react-native').Pressable,
      {testID: 'metro-open-files', onPress: () => onOpen({title: 'Files', eyebrow: 'WORKSPACE', kind: 'files'})},
    ),
    require('react').createElement(
      require('react-native').Pressable,
      {testID: 'metro-open-github', onPress: onOpenGithubLogin},
    ),
    require('react').createElement(
      require('react-native').Pressable,
      {testID: 'metro-open-github-account', onPress: onOpenGithubAccount},
    ),
    require('react').createElement(
      require('react-native').Pressable,
      {testID: 'metro-open-claude', onPress: () => onOpen({title: 'Claude Code', eyebrow: 'AI WORKBENCH', command: 'claude', toolchain: 'claude'})},
    ),
  ),
}));

jest.mock('../src/ui/GithubAccountScreen', () => ({
  GithubAccountScreen: ({account, error, onBack, onLogout}: {account: {username: string}; error?: string; onBack: () => void; onLogout: () => void}) => require('react').createElement(
    require('react-native').View,
    {testID: 'github-account-screen', error},
    require('react').createElement(require('react-native').Text, {testID: 'github-account-name'}, `@${account.username}`),
    require('react').createElement(require('react-native').Pressable, {testID: 'github-account-back', onPress: onBack}),
    require('react').createElement(require('react-native').Pressable, {testID: 'github-account-logout', onPress: onLogout}),
  ),
}));

jest.mock('../src/ui/LoadingScreen', () => ({
  LoadingScreen: ({onDebugTerminal}: {onDebugTerminal?: () => void}) => require('react').createElement(
    require('react-native').View,
    {testID: 'loading-screen'},
    onDebugTerminal === undefined ? null : require('react').createElement(
      require('react-native').Pressable,
      {testID: 'loading-debug-terminal', onPress: onDebugTerminal},
    ),
  ),
}));

jest.mock('../src/ui/OnboardingScreen', () => ({
  OnboardingScreen: () => require('react').createElement(
    require('react-native').View,
    {testID: 'onboarding-screen'},
  ),
}));

jest.mock('../src/ui/LoginScreen', () => ({
  LoginScreen: () => require('react').createElement(
    require('react-native').View,
    {testID: 'login-screen'},
  ),
}));

jest.mock('../src/ui/ProjectHubScreen', () => ({
  ProjectHubScreen: ({toolName, projectError, projects, onConfirmConnected, onCreateProject, onCloneRepo, onOpenProject}: {toolName: string; projectError?: string; onConfirmConnected: () => void; projects: readonly {name: string; path: string; remote?: string}[]; onCreateProject: (name: string) => void; onCloneRepo: (url: string, name: string) => void; onOpenProject: (project: {name: string; path: string; remote?: string}) => void}) => require('react').createElement(
    require('react-native').View,
    {testID: 'project-hub-screen', projectError},
    require('react').createElement(require('react-native').Text, {testID: 'project-hub-harness'}, toolName),
    require('react').createElement(
      require('react-native').Pressable,
      {testID: 'project-hub-confirm-connected', onPress: onConfirmConnected},
    ),
    require('react').createElement(
      require('react-native').Pressable,
      {testID: 'project-hub-create-sample', onPress: () => onCreateProject('sample')},
    ),
    require('react').createElement(
      require('react-native').Pressable,
      {testID: 'project-hub-clone-sample', onPress: () => onCloneRepo('https://github.com/octocat/hello-world.git', 'hello-world')},
    ),
    require('react').createElement(
      require('react-native').Pressable,
      {testID: 'project-hub-clone-account', onPress: () => onOpenProject({name: 'hello-world', path: 'octocat/hello-world', remote: 'https://github.com/octocat/hello-world.git'})},
    ),
    require('react').createElement(
      require('react-native').Pressable,
      {testID: 'project-hub-open-recovered', onPress: () => { const project = projects.find(item => item.name === 'recovered'); if (project !== undefined) onOpenProject(project); }},
    ),
  ),
}));

jest.mock('../src/ui/FileExplorerScreen', () => ({
  FileExplorerScreen: ({onBack}: {onBack: () => void}) => require('react').createElement(
    require('react-native').Pressable,
    {testID: 'file-explorer-screen', onPress: onBack},
  ),
}));

describe('debug navigation', () => {
  let backHandler: Parameters<typeof BackHandler.addEventListener>[1] | undefined;
  let removeBackSubscription: jest.Mock;

  beforeEach(() => {
    jest.mocked(listGuestDirectory).mockReset().mockResolvedValue({kind: 'success', entries: [], truncated: false, hiddenInvalidNameCount: 0});
    jest.mocked(readStoredGithubAccount).mockReset().mockResolvedValue(undefined);
    jest.mocked(clearStoredGithubAccount).mockReset().mockResolvedValue(true);
    jest.mocked(listGithubRepositories).mockClear();
    backHandler = undefined;
    removeBackSubscription = jest.fn();
    jest.spyOn(BackHandler, 'addEventListener').mockImplementation((eventName, handler) => {
      if (eventName === 'hardwareBackPress') backHandler = handler;
      return {remove: removeBackSubscription};
    });
  });

  afterEach(() => jest.restoreAllMocks());

  test('skips local profile prompts and returns from the terminal to Metro on Back', async () => {
    let renderer!: ReactTestRenderer.ReactTestRenderer;
    await ReactTestRenderer.act(async () => {
      renderer = ReactTestRenderer.create(<App />);
      for (let index = 0; index < 12; index += 1) await Promise.resolve();
    });

    expect(renderer.root.findAllByProps({testID: 'debug-terminal'}).length).toBeGreaterThan(0);
    expect(renderer.root.findByProps({testID: 'debug-terminal'}).props.runtimeReady).toBe(true);
    expect(renderer.root.findAllByProps({testID: 'onboarding-screen'})).toHaveLength(0);
    expect(renderer.root.findAllByProps({testID: 'login-screen'})).toHaveLength(0);
    expect(backHandler).toBeDefined();
    await ReactTestRenderer.act(async () => {
      expect(backHandler?.({} as never)).toBe(true);
      for (let index = 0; index < 8; index += 1) await Promise.resolve();
    });
    expect(renderer.root.findAllByProps({testID: 'metro-home'}).length).toBeGreaterThan(0);
    expect(renderer.root.findByProps({testID: 'metro-profile'}).props.children).toBe('HORUS');

    await ReactTestRenderer.act(async () => {
      renderer.unmount();
      await Promise.resolve();
    });
    expect(removeBackSubscription).toHaveBeenCalled();
  });

  // Debug builds never render the login screen; this pins the lock action itself.
  test('locking does not stop running sessions', async () => {
    let renderer!: ReactTestRenderer.ReactTestRenderer;
    await ReactTestRenderer.act(async () => {
      renderer = ReactTestRenderer.create(<App />);
      for (let index = 0; index < 12; index += 1) await Promise.resolve();
    });
    // Open a harness through Metro so a session is in play.
    await ReactTestRenderer.act(async () => {
      backHandler?.({} as never);
      for (let index = 0; index < 8; index += 1) await Promise.resolve();
    });
    await ReactTestRenderer.act(async () => {
      renderer.root.findByProps({testID: 'metro-open-claude'}).props.onPress();
      for (let index = 0; index < 8; index += 1) await Promise.resolve();
    });
    await ReactTestRenderer.act(async () => {
      renderer.root.findByProps({testID: 'project-hub-create-sample'}).props.onPress();
      for (let index = 0; index < 8; index += 1) await Promise.resolve();
    });
    expect(renderer.root.findByProps({testID: 'debug-terminal'}).props.toolchain).toBe('claude');
    const lockSession = jest.mocked(useBackgroundSessionLock).mock.calls.at(-1)?.[1];
    expect(lockSession).toBeDefined();
    await ReactTestRenderer.act(async () => {
      lockSession?.();
      for (let index = 0; index < 4; index += 1) await Promise.resolve();
    });

    const client = jest.mocked(TerminalSessionClient).mock.results.at(-1)?.value as {stopAllTerminalSessions: jest.Mock; stopSession: jest.Mock};
    expect(client.stopAllTerminalSessions).not.toHaveBeenCalled();
    expect(client.stopSession).not.toHaveBeenCalled();

    await ReactTestRenderer.act(async () => {
      renderer.unmount();
      await Promise.resolve();
    });
  });

  test('opens Files from Metro and returns there through its back control', async () => {
    let renderer!: ReactTestRenderer.ReactTestRenderer;
    await ReactTestRenderer.act(async () => {
      renderer = ReactTestRenderer.create(<App />);
      for (let index = 0; index < 12; index += 1) await Promise.resolve();
    });
    expect(renderer.root.findAllByProps({testID: 'debug-terminal'}).length).toBeGreaterThan(0);
    expect(backHandler).toBeDefined();
    await ReactTestRenderer.act(async () => {
      backHandler?.({} as never);
      for (let index = 0; index < 8; index += 1) await Promise.resolve();
    });
    expect(renderer.root.findAllByProps({testID: 'metro-home'}).length).toBeGreaterThan(0);
    await ReactTestRenderer.act(async () => {
      renderer.root.findByProps({testID: 'metro-open-files'}).props.onPress();
      for (let index = 0; index < 4; index += 1) await Promise.resolve();
    });

    expect(renderer.root.findAllByProps({testID: 'file-explorer-screen'}).length).toBeGreaterThan(0);
    await ReactTestRenderer.act(async () => {
      renderer.root.findByProps({testID: 'file-explorer-screen'}).props.onPress();
      await Promise.resolve();
    });
    expect(renderer.root.findAllByProps({testID: 'metro-home'}).length).toBeGreaterThan(0);
    await ReactTestRenderer.act(async () => {
      renderer.unmount();
      await Promise.resolve();
    });
  });

  test('debug terminal shortcut waits for bootstrap instead of repeating runtime status', async () => {
    const statusReader = readTerminalRuntimeStatus as jest.Mock;
    statusReader.mockClear();
    let finishStatus: ((value: {kind: 'success'; runtimeState: 'ready'; prootAvailable: boolean}) => void) | undefined;
    statusReader.mockImplementationOnce(() => new Promise(resolve => { finishStatus = resolve; }));
    let renderer!: ReactTestRenderer.ReactTestRenderer;
    await ReactTestRenderer.act(async () => {
      renderer = ReactTestRenderer.create(<App />);
      for (let index = 0; index < 8; index += 1) await Promise.resolve();
    });
    expect(renderer.root.findAllByProps({testID: 'loading-screen'}).length).toBeGreaterThan(0);
    await ReactTestRenderer.act(async () => {
      renderer.root.findByProps({testID: 'loading-debug-terminal'}).props.onPress();
      await Promise.resolve();
    });
    expect(statusReader).toHaveBeenCalledTimes(1);
    expect(renderer.root.findAllByProps({testID: 'debug-terminal'})).toHaveLength(0);
    await ReactTestRenderer.act(async () => {
      finishStatus?.({kind: 'success', runtimeState: 'ready', prootAvailable: true});
      for (let index = 0; index < 8; index += 1) await Promise.resolve();
    });
    expect(renderer.root.findAllByProps({testID: 'debug-terminal'}).length).toBeGreaterThan(0);
    expect(renderer.root.findByProps({testID: 'debug-terminal'}).props.runtimeReady).toBe(true);
    expect(statusReader).toHaveBeenCalledTimes(1);
    await ReactTestRenderer.act(async () => { renderer.unmount(); await Promise.resolve(); });
  });

  test('returns to Metro as soon as GitHub login succeeds and fills the account card when account details arrive', async () => {
    let publishGithubAccount: ((account: {username: string; avatarUrl?: string}) => void) | undefined;
    let finishGithubRefresh: ((result: GithubRepositoryListResult) => void) | undefined;
    let finishPreviousRefresh: ((result: GithubRepositoryListResult) => void) | undefined;
    jest.mocked(listGithubRepositories)
      .mockImplementationOnce(() => new Promise(resolve => { finishPreviousRefresh = resolve; }))
      .mockImplementationOnce((_client, onAccount) => new Promise(resolve => {
        publishGithubAccount = onAccount;
        finishGithubRefresh = resolve;
      }));
    let renderer!: ReactTestRenderer.ReactTestRenderer;
    await ReactTestRenderer.act(async () => {
      renderer = ReactTestRenderer.create(<App />);
      for (let index = 0; index < 12; index += 1) await Promise.resolve();
    });
    await ReactTestRenderer.act(async () => {
      backHandler?.({} as never);
      for (let index = 0; index < 8; index += 1) await Promise.resolve();
    });
    await ReactTestRenderer.act(async () => {
      renderer.root.findByProps({testID: 'metro-open-claude'}).props.onPress();
      for (let index = 0; index < 12; index += 1) await Promise.resolve();
    });
    // Signed out, opening the chooser does not query GitHub on its own.
    expect(listGithubRepositories).not.toHaveBeenCalled();
    await ReactTestRenderer.act(async () => {
      renderer.root.findByProps({testID: 'project-hub-confirm-connected'}).props.onPress();
      for (let index = 0; index < 4; index += 1) await Promise.resolve();
    });
    expect(listGithubRepositories).toHaveBeenCalledTimes(1);
    await ReactTestRenderer.act(async () => {
      backHandler?.({} as never);
      for (let index = 0; index < 8; index += 1) await Promise.resolve();
    });

    await ReactTestRenderer.act(async () => {
      renderer.root.findByProps({testID: 'metro-open-github'}).props.onPress();
      await Promise.resolve();
    });
    const githubLoginTerminal = renderer.root.findByProps({testID: 'debug-terminal'});
    expect(githubLoginTerminal.props.toolchain).toBe('github');
    expect(githubLoginTerminal.props.screenTitle).toBe('GitHub Login');
    expect(githubLoginTerminal.props.sessionCommand).toContain('gh auth login');
    expect(githubLoginTerminal.props.sessionCommand).toContain('/workspace/projects');
    expect(githubLoginTerminal.props.completionMarker).toMatch(/^HORUS_GITHUB_LOGIN_COMPLETE_/);
    expect(githubLoginTerminal.props.sessionCommand).toContain(githubLoginTerminal.props.completionMarker);
    expect(renderer.root.findAllByProps({testID: 'project-hub-screen'})).toHaveLength(0);

    await ReactTestRenderer.act(async () => {
      renderer.root.findByProps({testID: 'debug-terminal-completion'}).props.onPress();
      for (let index = 0; index < 4; index += 1) await Promise.resolve();
    });
    expect(listGithubRepositories).toHaveBeenCalledTimes(1);
    expect(renderer.root.findAllByProps({testID: 'metro-home'}).length).toBeGreaterThan(0);
    expect(renderer.root.findByProps({testID: 'metro-github-account-state'}).props.children).toBe('signed-out');

    await ReactTestRenderer.act(async () => {
      finishPreviousRefresh?.({kind: 'error', errorCode: 'command_failed', exitCode: 1});
      for (let index = 0; index < 12; index += 1) await Promise.resolve();
    });
    expect(listGithubRepositories).toHaveBeenCalledTimes(2);

    await ReactTestRenderer.act(async () => {
      publishGithubAccount?.({username: 'debug', avatarUrl: 'https://avatars.githubusercontent.com/u/1'});
      finishGithubRefresh?.({
        kind: 'success',
        account: {username: 'debug', avatarUrl: 'https://avatars.githubusercontent.com/u/1'},
        repositories: [],
      });
      for (let index = 0; index < 8; index += 1) await Promise.resolve();
    });
    expect(renderer.root.findByProps({testID: 'metro-github-account-state'}).props.children).toBe('debug');
    expect(renderer.root.findByProps({testID: 'metro-github-avatar'}).props.source.uri).toBe('https://avatars.githubusercontent.com/u/1');

    await ReactTestRenderer.act(async () => {
      renderer.root.findByProps({testID: 'metro-open-claude'}).props.onPress();
      await Promise.resolve();
    });
    expect(renderer.root.findByProps({testID: 'project-hub-harness'}).props.children).toBe('Claude Code');

    await ReactTestRenderer.act(async () => {
      renderer.root.findByProps({testID: 'project-hub-create-sample'}).props.onPress();
      await Promise.resolve();
    });
    const terminal = renderer.root.findByProps({testID: 'debug-terminal'});
    expect(terminal.props.toolchain).toBe('claude');
    expect(terminal.props.sessionCommand).toContain('/workspace/projects/sample');
    expect(terminal.props.sessionCommand).toContain('mkdir -p');

    await ReactTestRenderer.act(async () => {
      renderer.unmount();
      await Promise.resolve();
    });
    expect(removeBackSubscription).toHaveBeenCalled();
  });

  test('does not start a queued GitHub refresh after App unmounts', async () => {
    let finishPreviousRefresh: ((result: GithubRepositoryListResult) => void) | undefined;
    jest.mocked(listGithubRepositories).mockImplementationOnce(() => new Promise(resolve => { finishPreviousRefresh = resolve; }));
    let renderer!: ReactTestRenderer.ReactTestRenderer;
    await ReactTestRenderer.act(async () => {
      renderer = ReactTestRenderer.create(<App />);
      for (let index = 0; index < 12; index += 1) await Promise.resolve();
    });
    await ReactTestRenderer.act(async () => {
      backHandler?.({} as never);
      for (let index = 0; index < 8; index += 1) await Promise.resolve();
    });
    await ReactTestRenderer.act(async () => {
      renderer.root.findByProps({testID: 'metro-open-claude'}).props.onPress();
      for (let index = 0; index < 12; index += 1) await Promise.resolve();
    });
    // Signed out, opening the chooser does not query GitHub on its own.
    expect(listGithubRepositories).not.toHaveBeenCalled();
    await ReactTestRenderer.act(async () => {
      renderer.root.findByProps({testID: 'project-hub-confirm-connected'}).props.onPress();
      for (let index = 0; index < 4; index += 1) await Promise.resolve();
    });
    expect(listGithubRepositories).toHaveBeenCalledTimes(1);
    await ReactTestRenderer.act(async () => {
      backHandler?.({} as never);
      for (let index = 0; index < 8; index += 1) await Promise.resolve();
    });
    await ReactTestRenderer.act(async () => {
      renderer.root.findByProps({testID: 'metro-open-github'}).props.onPress();
      await Promise.resolve();
    });
    await ReactTestRenderer.act(async () => {
      renderer.root.findByProps({testID: 'debug-terminal-completion'}).props.onPress();
      for (let index = 0; index < 4; index += 1) await Promise.resolve();
    });
    await ReactTestRenderer.act(async () => {
      renderer.unmount();
      await Promise.resolve();
    });
    await ReactTestRenderer.act(async () => {
      finishPreviousRefresh?.({kind: 'error', errorCode: 'command_failed', exitCode: 1});
      for (let index = 0; index < 12; index += 1) await Promise.resolve();
    });
    expect(listGithubRepositories).toHaveBeenCalledTimes(1);
  });

  test('shows signed-in details and clears them only after GitHub CLI confirms logout', async () => {
    jest.mocked(readStoredGithubAccount).mockResolvedValue({username: 'octocat', avatarUrl: 'https://avatars.githubusercontent.com/u/1'});
    let renderer!: ReactTestRenderer.ReactTestRenderer;
    await ReactTestRenderer.act(async () => {
      renderer = ReactTestRenderer.create(<App />);
      for (let index = 0; index < 12; index += 1) await Promise.resolve();
    });
    await ReactTestRenderer.act(async () => {
      backHandler?.({} as never);
      for (let index = 0; index < 8; index += 1) await Promise.resolve();
    });

    await ReactTestRenderer.act(async () => {
      renderer.root.findByProps({testID: 'metro-open-github-account'}).props.onPress();
      await Promise.resolve();
    });
    expect(renderer.root.findByProps({testID: 'github-account-name'}).props.children).toBe('@octocat');
    await ReactTestRenderer.act(async () => {
      renderer.root.findByProps({testID: 'github-account-logout'}).props.onPress();
      await Promise.resolve();
    });

    let terminal = renderer.root.findByProps({testID: 'debug-terminal'});
    expect(terminal.props.toolchain).toBe('github');
    expect(terminal.props.screenTitle).toBe('GitHub Logout');
    expect(terminal.props.sessionCommand).toContain('gh auth logout --hostname github.com --user');
    expect(terminal.props.sessionCommand).toContain('octocat');
    expect(terminal.props.completionMarker).toMatch(/^HORUS_GITHUB_LOGOUT_COMPLETE_/);
    expect(terminal.props.stopSessionOnUnmount).toBe(true);
    expect(terminal.props.onBack).toBeUndefined();
    expect(clearStoredGithubAccount).not.toHaveBeenCalled();

    await ReactTestRenderer.act(async () => {
      renderer.root.findByProps({testID: 'debug-terminal-completion'}).props.onPress();
      for (let index = 0; index < 8; index += 1) await Promise.resolve();
    });
    expect(clearStoredGithubAccount).toHaveBeenCalledTimes(1);
    expect(renderer.root.findAllByProps({testID: 'github-account-screen'})).toHaveLength(0);
    expect(renderer.root.findAllByProps({testID: 'metro-home'}).length).toBeGreaterThan(0);

    await ReactTestRenderer.act(async () => { renderer.unmount(); await Promise.resolve(); });
  });

  test('keeps the account details available when GitHub logout fails', async () => {
    jest.mocked(readStoredGithubAccount).mockResolvedValue({username: 'octocat'});
    let renderer!: ReactTestRenderer.ReactTestRenderer;
    await ReactTestRenderer.act(async () => {
      renderer = ReactTestRenderer.create(<App />);
      for (let index = 0; index < 12; index += 1) await Promise.resolve();
    });
    await ReactTestRenderer.act(async () => {
      backHandler?.({} as never);
      for (let index = 0; index < 8; index += 1) await Promise.resolve();
    });
    await ReactTestRenderer.act(async () => {
      renderer.root.findByProps({testID: 'metro-open-github-account'}).props.onPress();
      await Promise.resolve();
    });
    await ReactTestRenderer.act(async () => {
      renderer.root.findByProps({testID: 'github-account-logout'}).props.onPress();
      await Promise.resolve();
    });
    await ReactTestRenderer.act(async () => {
      renderer.root.findByProps({testID: 'debug-terminal-command-failure'}).props.onPress();
      for (let index = 0; index < 8; index += 1) await Promise.resolve();
    });
    expect(clearStoredGithubAccount).not.toHaveBeenCalled();
    expect(renderer.root.findByProps({testID: 'github-account-screen'}).props.error).toBe('GitHub logout failed. The account is still connected.');
    expect(renderer.root.findByProps({testID: 'github-account-name'}).props.children).toBe('@octocat');
    await ReactTestRenderer.act(async () => { renderer.unmount(); await Promise.resolve(); });
  });

  test('waits for the visible clone terminal to finish before launching the selected harness', async () => {
    let renderer!: ReactTestRenderer.ReactTestRenderer;
    await ReactTestRenderer.act(async () => {
      renderer = ReactTestRenderer.create(<App />);
      for (let index = 0; index < 12; index += 1) await Promise.resolve();
    });
    await ReactTestRenderer.act(async () => {
      backHandler?.({} as never);
      for (let index = 0; index < 8; index += 1) await Promise.resolve();
    });
    await ReactTestRenderer.act(async () => {
      renderer.root.findByProps({testID: 'metro-open-claude'}).props.onPress();
      await Promise.resolve();
    });
    await ReactTestRenderer.act(async () => {
      renderer.root.findByProps({testID: 'project-hub-clone-sample'}).props.onPress();
      await Promise.resolve();
    });

    let terminal = renderer.root.findByProps({testID: 'debug-terminal'});
    expect(terminal.props.toolchain).toBe('shell');
    expect(terminal.props.screenTitle).toBe('Clone or reuse hello-world');
    expect(terminal.props.sessionCommand).toContain('git clone');
    expect(terminal.props.sessionCommand).toContain('/workspace/projects/hello-world');
    expect(terminal.props.sessionCommand).toContain('git -C');
    expect(terminal.props.sessionCommand).toContain('remote get-url origin');
    expect(terminal.props.sessionCommand).toContain('normalize_git_remote');
    expect(terminal.props.sessionCommand).toContain('That workspace folder already exists but is not a checkout of this repository. It was left unchanged.');
    expect(terminal.props.stopSessionOnUnmount).toBe(true);
    expect(terminal.props.completionMarker).toMatch(/^HORUS_PROJECT_CLONE_COMPLETE_/);

    await ReactTestRenderer.act(async () => {
      renderer.root.findByProps({testID: 'debug-terminal-completion'}).props.onPress();
      await Promise.resolve();
    });
    terminal = renderer.root.findByProps({testID: 'debug-terminal'});
    expect(terminal.props.toolchain).toBe('claude');
    expect(terminal.props.screenTitle).toBe('Claude Code');
    expect(terminal.props.sessionCommand).toContain('/workspace/projects/hello-world');

    await ReactTestRenderer.act(async () => { renderer.unmount(); await Promise.resolve(); });
  });

  test('holds the clone terminal on Back and returns to the chooser after a start failure', async () => {
    let renderer!: ReactTestRenderer.ReactTestRenderer;
    await ReactTestRenderer.act(async () => {
      renderer = ReactTestRenderer.create(<App />);
      for (let index = 0; index < 12; index += 1) await Promise.resolve();
    });
    await ReactTestRenderer.act(async () => {
      backHandler?.({} as never);
      for (let index = 0; index < 8; index += 1) await Promise.resolve();
    });
    await ReactTestRenderer.act(async () => {
      renderer.root.findByProps({testID: 'metro-open-claude'}).props.onPress();
      for (let index = 0; index < 8; index += 1) await Promise.resolve();
    });
    await ReactTestRenderer.act(async () => {
      renderer.root.findByProps({testID: 'project-hub-clone-sample'}).props.onPress();
      await Promise.resolve();
    });

    let terminal = renderer.root.findByProps({testID: 'debug-terminal'});
    expect(terminal.props.onBack).toBeUndefined();
    expect(terminal.props.onHome).toBeUndefined();
    expect(terminal.props.screenEyebrow).toBe('CLONING / WAIT');
    await ReactTestRenderer.act(async () => { backHandler?.({} as never); });
    expect(renderer.root.findByProps({testID: 'debug-terminal'}).props.screenEyebrow).toBe('CLONING / WAIT');
    expect(renderer.root.findAllByProps({testID: 'project-hub-screen'})).toHaveLength(0);

    const failureCallback = terminal.props.onCommandFailure;
    await ReactTestRenderer.act(async () => {
      renderer.root.findByProps({testID: 'debug-terminal-command-failure'}).props.onPress();
      await Promise.resolve();
    });
    terminal = renderer.root.findByProps({testID: 'debug-terminal'});
    expect(terminal.props.onCommandFailure).toBe(failureCallback);
    expect(terminal.props.onBack).toEqual(expect.any(Function));
    await ReactTestRenderer.act(async () => {
      backHandler?.({} as never);
      await Promise.resolve();
    });
    expect(renderer.root.findByProps({testID: 'project-hub-screen'}).props.projectError).toBe(
      'Clone did not complete. Review the terminal output before retrying.',
    );
    await ReactTestRenderer.act(async () => { renderer.unmount(); await Promise.resolve(); });
  });

  test('reloads existing manual folders before opening them in the selected harness', async () => {
    jest.mocked(listGuestDirectory).mockResolvedValueOnce({
      kind: 'success',
      entries: [
        {name: 'recovered', kind: 'directory', sizeBytes: 0},
        {name: 'ignored.txt', kind: 'file', sizeBytes: 0},
      ],
      truncated: false,
      hiddenInvalidNameCount: 0,
    });
    let renderer!: ReactTestRenderer.ReactTestRenderer;
    await ReactTestRenderer.act(async () => {
      renderer = ReactTestRenderer.create(<App />);
      for (let index = 0; index < 12; index += 1) await Promise.resolve();
    });
    await ReactTestRenderer.act(async () => {
      backHandler?.({} as never);
      for (let index = 0; index < 8; index += 1) await Promise.resolve();
    });
    await ReactTestRenderer.act(async () => {
      renderer.root.findByProps({testID: 'metro-open-claude'}).props.onPress();
      for (let index = 0; index < 12; index += 1) await Promise.resolve();
    });

    expect(listGuestDirectory).toHaveBeenCalledWith('workspace', ['projects']);
    await ReactTestRenderer.act(async () => {
      renderer.root.findByProps({testID: 'project-hub-open-recovered'}).props.onPress();
      await Promise.resolve();
    });
    const terminal = renderer.root.findByProps({testID: 'debug-terminal'});
    expect(terminal.props.sessionCommand).toContain('mkdir -p');
    expect(terminal.props.sessionCommand).toContain('cd');
    expect(terminal.props.sessionCommand).toContain('/workspace/projects/recovered');
    await ReactTestRenderer.act(async () => { renderer.unmount(); await Promise.resolve(); });
  });

  test('keeps an incomplete GitHub response as an error instead of claiming the account has no repositories', async () => {
    jest.mocked(readStoredGithubAccount).mockResolvedValue({username: 'cached', avatarUrl: 'https://avatars.githubusercontent.com/u/1'});
    jest.mocked(listGithubRepositories).mockResolvedValueOnce({
      kind: 'error',
      errorCode: 'invalid_output',
      outputIssue: 'account_marker_missing_or_incomplete',
    });
    let renderer!: ReactTestRenderer.ReactTestRenderer;
    await ReactTestRenderer.act(async () => {
      renderer = ReactTestRenderer.create(<App />);
      for (let index = 0; index < 12; index += 1) await Promise.resolve();
    });
    await ReactTestRenderer.act(async () => {
      backHandler?.({} as never);
      for (let index = 0; index < 8; index += 1) await Promise.resolve();
    });
    await ReactTestRenderer.act(async () => {
      renderer.root.findByProps({testID: 'metro-open-claude'}).props.onPress();
      for (let index = 0; index < 20; index += 1) await Promise.resolve();
    });

    expect(listGithubRepositories).toHaveBeenCalled();
    expect(renderer.root.findByProps({testID: 'project-hub-screen'}).props.projectError).toBe(
      'GitHub did not return account data. Reconnect GitHub, then refresh repositories.',
    );
    await ReactTestRenderer.act(async () => { renderer.unmount(); await Promise.resolve(); });
  });

  test('recognizes GitHub account repositories as clone-or-reuse workspace sources', async () => {
    let renderer!: ReactTestRenderer.ReactTestRenderer;
    await ReactTestRenderer.act(async () => {
      renderer = ReactTestRenderer.create(<App />);
      for (let index = 0; index < 12; index += 1) await Promise.resolve();
    });
    await ReactTestRenderer.act(async () => {
      backHandler?.({} as never);
      for (let index = 0; index < 8; index += 1) await Promise.resolve();
    });
    await ReactTestRenderer.act(async () => {
      renderer.root.findByProps({testID: 'metro-open-claude'}).props.onPress();
      for (let index = 0; index < 8; index += 1) await Promise.resolve();
    });
    await ReactTestRenderer.act(async () => {
      renderer.root.findByProps({testID: 'project-hub-clone-account'}).props.onPress();
      await Promise.resolve();
    });

    const terminal = renderer.root.findByProps({testID: 'debug-terminal'});
    expect(terminal.props.screenTitle).toBe('Clone or reuse hello-world');
    expect(terminal.props.sessionCommand).toContain('gh repo clone');
    expect(terminal.props.sessionCommand).toContain('github.com');
    expect(terminal.props.sessionCommand).toContain('octocat/hello-world');
    expect(terminal.props.sessionCommand).toContain('existing_origin');
    expect(terminal.props.sessionCommand).toContain('existing_root');
    expect(terminal.props.sessionCommand).toContain('Using the existing checkout of this repository.');
    expect(terminal.props.sessionCommand).toContain('That workspace folder already exists but is not a checkout of this repository. It was left unchanged.');
    await ReactTestRenderer.act(async () => { renderer.unmount(); await Promise.resolve(); });
  });
});
