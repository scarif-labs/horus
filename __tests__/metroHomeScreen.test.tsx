import React from 'react';
import ReactTestRenderer from 'react-test-renderer';
import {MetroHomeScreen, type MetroLaunchTarget} from '../src/ui/MetroHomeScreen';
import type {ActiveTerminalSession} from '../src/terminal/session/sessionContract';
import {buildZshScriptCommand} from '../src/terminal/commandFactory';

describe('MetroHomeScreen', () => {
  test('renders the launcher, opens the GitHub login flow, and tears down metrics refresh', async () => {
    const targets: MetroLaunchTarget[] = [];
    const onOpenGithubLogin = jest.fn();
    const onOpenSettings = jest.fn();
    let renderer: ReactTestRenderer.ReactTestRenderer | undefined;
    await ReactTestRenderer.act(async () => {
      renderer = ReactTestRenderer.create(
        <MetroHomeScreen
          onOpen={target => targets.push(target)}
          onOpenGithubLogin={onOpenGithubLogin}
          onOpenSettings={onOpenSettings}
        />,
      );
      await Promise.resolve();
    });

    expect(renderer?.root.findByProps({testID: 'metro-home-screen'})).toBeDefined();
    expect(renderer?.root.findByProps({testID: 'metro-github'})).toBeDefined();
    expect(renderer?.root.findAll(node => node.props.testID === 'metro-continue')).toHaveLength(0);
    expect(renderer?.root.findByProps({testID: 'metro-github'}).props.accessibilityLabel).toBe('Log in to GitHub');
    expect(renderer?.root.findByProps({testID: 'metro-github-mark'})).toBeDefined();
    expect(renderer?.root.findByProps({testID: 'metro-github-eyebrow'}).props.children).toBe('GITHUB');
    expect(renderer?.root.findByProps({testID: 'metro-github-title'}).props.children).toBe('Login to GitHub');
    await ReactTestRenderer.act(async () => {
      renderer?.root.findByProps({testID: 'metro-settings'}).props.onPress();
    });
    expect(onOpenSettings).toHaveBeenCalledTimes(1);
    await ReactTestRenderer.act(async () => {
      renderer?.root.findByProps({testID: 'metro-github'}).props.onPress();
    });
    expect(onOpenGithubLogin).toHaveBeenCalledTimes(1);
    expect(targets).toHaveLength(0);
    expect(renderer?.root.findByProps({testID: 'metro-harness-opencode'})).toBeDefined();
    await ReactTestRenderer.act(async () => {
      renderer?.root.findByProps({testID: 'metro-harness-opencode'}).props.onPress();
    });
    expect(targets[0]?.title).toBe('OpenCode');
    expect(targets[0]?.toolchain).toBe('opencode');
    expect(targets[0]?.command).toBe(buildZshScriptCommand('opencode'));
    await ReactTestRenderer.act(async () => {
      renderer?.root.findByProps({testID: 'metro-files'}).props.onPress();
    });
    expect(targets[1]?.title).toBe('Files');
    expect(targets[1]?.kind).toBe('files');

    await ReactTestRenderer.act(async () => {
      renderer?.unmount();
      await Promise.resolve();
    });
  });

  test('shows the authenticated GitHub handle in a square avatar and opens account details', async () => {
    const onOpenGithubAccount = jest.fn();
    let renderer: ReactTestRenderer.ReactTestRenderer | undefined;
    await ReactTestRenderer.act(async () => {
      renderer = ReactTestRenderer.create(
        <MetroHomeScreen
          githubAccount={{username: 'octocat', avatarUrl: 'https://avatars.githubusercontent.com/u/1'}}
          onOpen={() => undefined}
          onOpenGithubLogin={() => undefined}
          onOpenGithubAccount={onOpenGithubAccount}
        />,
      );
      await Promise.resolve();
    });

    const avatar = renderer?.root.findByProps({testID: 'metro-github-avatar'});
    expect(avatar?.props.source).toEqual({uri: 'https://avatars.githubusercontent.com/u/1'});
    expect(avatar?.props.style).toMatchObject({height: 50, width: 50});
    expect(avatar?.props.style.borderRadius).toBeUndefined();
    expect(renderer?.root.findByProps({testID: 'metro-github'}).props.accessibilityLabel).toBe('GitHub account, @octocat');
    expect(renderer?.root.findAll(node => node.props.children === '@octocat').length).toBeGreaterThan(0);
    await ReactTestRenderer.act(async () => {
      renderer?.root.findByProps({testID: 'metro-github'}).props.onPress();
    });
    expect(onOpenGithubAccount).toHaveBeenCalledTimes(1);
    await ReactTestRenderer.act(async () => {
      renderer?.unmount();
      await Promise.resolve();
    });
  });

  test('shows all four active sessions above the launcher tiles and resumes a listed session', async () => {
    const sessions: ActiveTerminalSession[] = Array.from({length: 4}, (_, index): ActiveTerminalSession => ({
      sessionId: `session-${index + 1}`,
      toolchain: index % 2 === 0 ? 'codex' : 'opencode',
      startedAtMs: Date.now() - (index + 1) * 60_000,
    }));
    const loadRecentSessions = jest.fn(async () => sessions);
    const onResumeSession = jest.fn();
    let renderer: ReactTestRenderer.ReactTestRenderer | undefined;
    await ReactTestRenderer.act(async () => {
      renderer = ReactTestRenderer.create(
        <MetroHomeScreen
          loadRecentSessions={loadRecentSessions}
          onOpen={() => undefined}
          onOpenGithubLogin={() => undefined}
          onResumeSession={onResumeSession}
        />,
      );
      for (let index = 0; index < 8; index += 1) await Promise.resolve();
    });

    const menuOrder = Array.from(new Set(renderer?.root.findAll(node => [
      'metro-harnesses',
      'metro-files',
      'metro-recents',
    ].includes(node.props.testID)).map(node => node.props.testID)));
    expect(menuOrder).toEqual(['metro-harnesses', 'metro-files', 'metro-recents']);
    const recentRowIds = new Set(renderer?.root.findAll(node => typeof node.props.testID === 'string' && node.props.testID.startsWith('metro-recent-session-')).map(node => node.props.testID));
    const terminateIds = new Set(renderer?.root.findAll(node => typeof node.props.testID === 'string' && node.props.testID.startsWith('metro-terminate-session-')).map(node => node.props.testID));
    expect(recentRowIds).toEqual(new Set(sessions.map(session => `metro-recent-${session.sessionId}`)));
    expect(terminateIds).toEqual(new Set(sessions.map(session => `metro-terminate-${session.sessionId}`)));
    await ReactTestRenderer.act(async () => {
      renderer?.root.findByProps({testID: 'metro-resume-session-1'}).props.onPress();
    });
    expect(onResumeSession).toHaveBeenCalledWith(sessions[0]);
    await ReactTestRenderer.act(async () => {
      renderer?.unmount();
      await Promise.resolve();
    });
  });
});
