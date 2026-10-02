import React from 'react';
import {Text} from 'react-native';
import ReactTestRenderer from 'react-test-renderer';
import {ProjectHubScreen, type ProjectHubScreenProps} from '../src/ui/ProjectHubScreen';
import {BrandHeader} from '../src/ui/brand';

const noop = () => undefined;

async function renderHub(props: Partial<ProjectHubScreenProps>): Promise<ReactTestRenderer.ReactTestRenderer> {
  let renderer!: ReactTestRenderer.ReactTestRenderer;
  await ReactTestRenderer.act(async () => {
    renderer = ReactTestRenderer.create(
      <ProjectHubScreen
        connected={false}
        githubRepositoriesLoaded={false}
        loadingRepos={false}
        onBack={noop}
        onCloneRepo={noop}
        onConfirmConnected={noop}
        onCreateProject={noop}
        onLogin={noop}
        onOpenProject={noop}
        onRefreshRepos={noop}
        projects={[]}
        toolName="OpenCode"
        {...props}
      />,
    );
  });
  return renderer;
}

async function press(renderer: ReactTestRenderer.ReactTestRenderer, testID: string): Promise<void> {
  await ReactTestRenderer.act(async () => {
    renderer.root.findByProps({testID}).props.onPress();
  });
}

const isSelected = (renderer: ReactTestRenderer.ReactTestRenderer, testID: string) =>
  renderer.root.findAll(node => node.props.testID === testID && node.props.accessibilityState !== undefined)[0]?.props.accessibilityState.selected;

const exists = (renderer: ReactTestRenderer.ReactTestRenderer, testID: string) =>
  renderer.root.findAllByProps({testID}).length > 0;

describe('ProjectHubScreen', () => {
  test('keeps the header and shows one tab at a time, starting on this device', async () => {
    const onBack = jest.fn();
    const renderer = await renderHub({onBack, toolName: 'Claude Code'});

    const menuButton = renderer.root.findByProps({testID: 'project-hub-back'});
    const header = renderer.root.findByType(BrandHeader);
    expect((header.props.action as React.ReactElement<{testID?: string}>).props.testID).toBe('project-hub-back');
    expect(menuButton.props.accessibilityLabel).toBe('Back to home');
    expect(menuButton.props.children.props.children).toBe('menu ^');
    expect(header.props.title).toBe('Choose project');
    expect(header.props.eyebrow).toBe('OPEN IN CLAUDE CODE');
    await press(renderer, 'project-hub-back');
    expect(onBack).toHaveBeenCalledTimes(1);

    expect(isSelected(renderer, 'project-tab-local')).toBe(true);
    expect(renderer.root.findByProps({testID: 'project-manual-empty-title'}).props.children).toBe('No projects on this device yet');
    expect(exists(renderer, 'project-hub-login')).toBe(false);
    expect(exists(renderer, 'project-create')).toBe(false);

    // The empty state leads straight to the New tab.
    await press(renderer, 'project-manual-empty-new');
    expect(isSelected(renderer, 'project-tab-new')).toBe(true);
    expect(isSelected(renderer, 'project-tab-local')).toBe(false);
    expect(exists(renderer, 'project-create')).toBe(true);
    expect(exists(renderer, 'project-manual-empty')).toBe(false);
    await ReactTestRenderer.act(async () => { renderer.unmount(); });
  });

  test('creates a folder or clones a URL from the New tab without GitHub', async () => {
    const onCreateProject = jest.fn();
    const onCloneRepo = jest.fn();
    const renderer = await renderHub({onCloneRepo, onCreateProject});
    await press(renderer, 'project-tab-new');

    await ReactTestRenderer.act(async () => {
      renderer.root.findByProps({testID: 'project-new-name'}).props.onChangeText('notes');
    });
    await press(renderer, 'project-create');
    expect(onCreateProject).toHaveBeenCalledWith('notes');

    await ReactTestRenderer.act(async () => {
      renderer.root.findByProps({testID: 'project-new-name'}).props.onChangeText('../escape');
    });
    await press(renderer, 'project-create');
    expect(onCreateProject).toHaveBeenCalledTimes(1);
    expect(renderer.root.findByProps({testID: 'project-error'}).props.children).toContain('short project name');

    // Switching mode clears the old error and swaps the input.
    await press(renderer, 'project-new-mode-clone');
    expect(exists(renderer, 'project-error')).toBe(false);
    expect(exists(renderer, 'project-new-name')).toBe(false);

    const clone = async (url: string) => {
      await ReactTestRenderer.act(async () => {
        renderer.root.findByProps({testID: 'project-repo-url'}).props.onChangeText(url);
      });
      await press(renderer, 'project-clone');
    };
    await clone('https://github.com/octocat/hello-world.git');
    expect(onCloneRepo).toHaveBeenCalledWith('https://github.com/octocat/hello-world.git', 'hello-world');
    await clone('https://gitlab.com/group/project.git');
    expect(onCloneRepo).toHaveBeenLastCalledWith('https://gitlab.com/group/project.git', 'project');
    await clone('https://token@github.com/octocat/private.git');
    expect(onCloneRepo).toHaveBeenCalledTimes(2);
    expect(renderer.root.findByProps({testID: 'project-error'}).props.children).toContain('without embedded credentials');
    await ReactTestRenderer.act(async () => { renderer.unmount(); });
  });

  test('offers one sign-in path on the GitHub tab when not connected, without an error', async () => {
    const onLogin = jest.fn();
    const onConfirmConnected = jest.fn();
    const renderer = await renderHub({onConfirmConnected, onLogin});
    await press(renderer, 'project-tab-github');

    expect(renderer.root.findByProps({testID: 'project-github-status'}).props.children).toBe('Not connected');
    expect(renderer.root.findByProps({testID: 'project-hub-login'}).findByType(Text).props.children).toBe('Connect GitHub');
    expect(exists(renderer, 'project-github-empty')).toBe(false);
    expect(exists(renderer, 'project-load-error')).toBe(false);

    await press(renderer, 'project-hub-login');
    await press(renderer, 'project-hub-confirm-login');
    expect(onLogin).toHaveBeenCalledTimes(1);
    expect(onConfirmConnected).toHaveBeenCalledTimes(1);
    await ReactTestRenderer.act(async () => { renderer.unmount(); });
  });

  test('keeps local folders and GitHub repositories on their own tabs and opens either', async () => {
    const onOpenProject = jest.fn();
    const onRefreshRepos = jest.fn();
    const manualProject = {name: 'notes', path: '/workspace/projects/notes'};
    const githubProject = {name: 'hello-world', path: 'octocat/hello-world', remote: 'https://github.com/octocat/hello-world.git'};
    const renderer = await renderHub({
      connected: true,
      githubRepositoriesLoaded: true,
      onOpenProject,
      onRefreshRepos,
      projects: [githubProject, manualProject],
    });

    expect(renderer.root.findByProps({testID: 'project-tab-local'}).findByType(Text).props.children).toEqual(['On device', ' 1']);
    expect(exists(renderer, 'project-notes')).toBe(true);
    expect(exists(renderer, 'project-hello-world')).toBe(false);
    await press(renderer, 'project-notes');
    expect(onOpenProject).toHaveBeenNthCalledWith(1, manualProject);

    await press(renderer, 'project-tab-github');
    expect(exists(renderer, 'project-notes')).toBe(false);
    expect(renderer.root.findByProps({testID: 'project-hello-world'}).props.accessibilityLabel).toBe('Clone hello-world');
    await press(renderer, 'project-hello-world');
    expect(onOpenProject).toHaveBeenNthCalledWith(2, githubProject);
    await press(renderer, 'project-hub-confirm-login');
    expect(onRefreshRepos).toHaveBeenCalledTimes(1);
    await ReactTestRenderer.act(async () => { renderer.unmount(); });
  });

  test('shows a clone failure on any tab', async () => {
    const renderer = await renderHub({
      connected: true,
      githubRepositoriesLoaded: true,
      projectError: 'Clone did not complete. Review the terminal output before retrying.',
      projects: [{name: 'notes', path: '/workspace/projects/notes'}],
    });
    expect(renderer.root.findByProps({testID: 'project-load-error'}).props.children).toContain('Clone did not complete');
    await press(renderer, 'project-tab-new');
    expect(exists(renderer, 'project-load-error')).toBe(true);
    await ReactTestRenderer.act(async () => { renderer.unmount(); });
  });

  test('does not claim an empty GitHub account before its repository query succeeds', async () => {
    const renderer = await renderHub({connected: true, githubRepositoriesLoaded: false});
    await press(renderer, 'project-tab-github');
    expect(exists(renderer, 'project-github-empty')).toBe(false);
    expect(exists(renderer, 'project-github-unloaded')).toBe(true);
    await ReactTestRenderer.act(async () => { renderer.unmount(); });
  });

  test('shows an informational empty state without a repository error after a successful empty query', async () => {
    const renderer = await renderHub({connected: true, githubRepositoriesLoaded: true});
    await press(renderer, 'project-tab-github');
    expect(renderer.root.findByProps({testID: 'project-github-empty-title'}).props.children).toBe('No repositories found');
    expect(renderer.root.findByProps({testID: 'project-github-empty-body'}).props.children).toContain('This GitHub login has no accessible repositories yet');
    expect(exists(renderer, 'project-load-error')).toBe(false);
    await ReactTestRenderer.act(async () => { renderer.unmount(); });
  });
});
