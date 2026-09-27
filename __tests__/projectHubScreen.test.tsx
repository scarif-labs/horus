import React from 'react';
import {Text} from 'react-native';
import ReactTestRenderer from 'react-test-renderer';
import {ProjectHubScreen} from '../src/ui/ProjectHubScreen';
import {BrandHeader} from '../src/ui/brand';
import {UI_FONT_FAMILY} from '../src/ui/typography';

describe('ProjectHubScreen', () => {
  test('offers folder creation and URL cloning without requiring GitHub login', async () => {
    const onCreateProject = jest.fn();
    const onCloneRepo = jest.fn();
    const onLogin = jest.fn();
    const onBack = jest.fn();
    let renderer!: ReactTestRenderer.ReactTestRenderer;
    await ReactTestRenderer.act(async () => {
      renderer = ReactTestRenderer.create(
        <ProjectHubScreen
          connected={false}
          githubRepositoriesLoaded={false}
          loadingRepos={false}
          onBack={onBack}
          onCloneRepo={onCloneRepo}
          onConfirmConnected={() => undefined}
          onCreateProject={onCreateProject}
          onLogin={onLogin}
          onOpenProject={() => undefined}
          onRefreshRepos={() => undefined}
          projects={[]}
          toolName="Claude Code"
        />,
      );
    });

    const menuButton = renderer.root.findByProps({testID: 'project-hub-back'});
    const headerAction = renderer.root.findByType(BrandHeader).props.action as React.ReactElement<{testID?: string}>;
    expect(headerAction.props.testID).toBe('project-hub-back');
    expect(menuButton.props.accessibilityLabel).toBe('Return to Metro menu');
    expect(menuButton.props.children.props.children).toBe('menu ^');
    expect(renderer.root.findByType(BrandHeader).props.title).toBe('Choose project');
    expect(renderer.root.findByType(BrandHeader).props.eyebrow).toBe('OPEN IN CLAUDE CODE');
    await ReactTestRenderer.act(async () => { menuButton.props.onPress(); });
    expect(onBack).toHaveBeenCalledTimes(1);

    expect(renderer.root.findByProps({testID: 'project-create'})).toBeDefined();
    expect(renderer.root.findByProps({testID: 'project-clone'})).toBeDefined();
    expect(renderer.root.findByProps({testID: 'project-manual-section'}).props.children).toBe('On this device');
    expect(renderer.root.findByProps({testID: 'project-github-section'}).props.children).toBe('GitHub');
    expect(renderer.root.findByProps({testID: 'project-manual-empty-title'}).props.children).toBe('No local projects yet');
    expect(renderer.root.findByProps({testID: 'project-manual-empty-body'}).props.children).toContain('Create a folder above');
    expect(renderer.root.findByProps({testID: 'project-hub-login'}).findByType(Text).props.children).toBe('Connect GitHub');
    expect(renderer.root.findByProps({testID: 'project-intro'}).props.style.fontFamily).toBe(UI_FONT_FAMILY);
    expect(renderer.root.findByProps({testID: 'project-manual-section'}).props.style.fontFamily).toBe(UI_FONT_FAMILY);
    expect(renderer.root.findByProps({testID: 'project-manual-empty-body'}).props.style.fontFamily).toBe(UI_FONT_FAMILY);
    const cards = ['project-start-card', 'project-local-card', 'project-github-card'].map(testID => renderer.root.findByProps({testID}));
    expect(cards.map(card => card.props.testID)).toEqual(['project-start-card', 'project-local-card', 'project-github-card']);
    expect(cards[0]?.findByProps({testID: 'project-create'})).toBeDefined();
    expect(cards[0]?.findByProps({testID: 'project-clone'})).toBeDefined();
    expect(cards[1]?.findByProps({testID: 'project-manual-empty-title'})).toBeDefined();
    expect(cards[2]?.findByProps({testID: 'project-hub-login'})).toBeDefined();
    await ReactTestRenderer.act(async () => {
      renderer.root.findByProps({testID: 'project-new-name'}).props.onChangeText('notes');
    });
    await ReactTestRenderer.act(async () => {
      renderer.root.findByProps({testID: 'project-create'}).props.onPress();
    });
    await ReactTestRenderer.act(async () => {
      renderer.root.findByProps({testID: 'project-repo-url'}).props.onChangeText('https://github.com/octocat/hello-world.git');
    });
    await ReactTestRenderer.act(async () => {
      renderer.root.findByProps({testID: 'project-clone'}).props.onPress();
      renderer.root.findByProps({testID: 'project-hub-login'}).props.onPress();
    });

    expect(onCreateProject).toHaveBeenCalledWith('notes');
    expect(onCloneRepo).toHaveBeenCalledWith('https://github.com/octocat/hello-world.git', 'hello-world');
    expect(onLogin).toHaveBeenCalledTimes(1);

    await ReactTestRenderer.act(async () => {
      renderer.root.findByProps({testID: 'project-repo-url'}).props.onChangeText('https://gitlab.com/group/project.git');
    });
    await ReactTestRenderer.act(async () => {
      renderer.root.findByProps({testID: 'project-clone'}).props.onPress();
    });
    expect(onCloneRepo).toHaveBeenLastCalledWith('https://gitlab.com/group/project.git', 'project');

    await ReactTestRenderer.act(async () => {
      renderer.root.findByProps({testID: 'project-repo-url'}).props.onChangeText('https://token@github.com/octocat/private.git');
    });
    await ReactTestRenderer.act(async () => {
      renderer.root.findByProps({testID: 'project-clone'}).props.onPress();
    });
    expect(onCloneRepo).toHaveBeenCalledTimes(2);
    expect(renderer.root.findByProps({testID: 'project-error'}).props.children).toContain('without embedded credentials');
    await ReactTestRenderer.act(async () => { renderer.unmount(); });
  });

  test('shows GitHub repositories as clone actions on the harness chooser', async () => {
    const onOpenProject = jest.fn();
    let renderer!: ReactTestRenderer.ReactTestRenderer;
    await ReactTestRenderer.act(async () => {
      renderer = ReactTestRenderer.create(
        <ProjectHubScreen
          connected
          githubRepositoriesLoaded={true}
          loadingRepos={false}
          onBack={() => undefined}
          onCloneRepo={() => undefined}
          onConfirmConnected={() => undefined}
          onCreateProject={() => undefined}
          onLogin={() => undefined}
          onOpenProject={onOpenProject}
          onRefreshRepos={() => undefined}
          projects={[{name: 'hello-world', path: 'octocat/hello-world', remote: 'https://github.com/octocat/hello-world.git'}]}
          toolName="Codex"
        />,
      );
    });

    await ReactTestRenderer.act(async () => {
      renderer.root.findByProps({testID: 'project-hello-world'}).props.onPress();
    });
    expect(onOpenProject).toHaveBeenCalledWith(expect.objectContaining({path: 'octocat/hello-world', remote: expect.any(String)}));
    await ReactTestRenderer.act(async () => { renderer.unmount(); });
  });

  test('lists manual workspaces before GitHub repositories and opens either entry', async () => {
    const onOpenProject = jest.fn();
    const manualProject = {name: 'notes', path: '/workspace/projects/notes'};
    const githubProject = {name: 'hello-world', path: 'octocat/hello-world', remote: 'https://github.com/octocat/hello-world.git'};
    let renderer!: ReactTestRenderer.ReactTestRenderer;
    await ReactTestRenderer.act(async () => {
      renderer = ReactTestRenderer.create(
        <ProjectHubScreen
          connected
          githubRepositoriesLoaded={true}
          loadingRepos={false}
          onBack={() => undefined}
          onCloneRepo={() => undefined}
          onConfirmConnected={() => undefined}
          onCreateProject={() => undefined}
          onLogin={() => undefined}
          onOpenProject={onOpenProject}
          onRefreshRepos={() => undefined}
          projects={[githubProject, manualProject]}
          toolName="OpenCode"
        />,
      );
    });

    const orderedIds = renderer.root.findAll(node => typeof node.props.testID === 'string').map(node => node.props.testID);
    expect(orderedIds.indexOf('project-manual-section')).toBeLessThan(orderedIds.indexOf('project-notes'));
    expect(orderedIds.indexOf('project-notes')).toBeLessThan(orderedIds.indexOf('project-github-section'));
    expect(orderedIds.indexOf('project-github-section')).toBeLessThan(orderedIds.indexOf('project-hello-world'));
    expect(renderer.root.findAllByProps({testID: 'project-manual-empty'})).toHaveLength(0);

    await ReactTestRenderer.act(async () => {
      renderer.root.findByProps({testID: 'project-notes'}).props.onPress();
      renderer.root.findByProps({testID: 'project-hello-world'}).props.onPress();
    });
    expect(onOpenProject).toHaveBeenNthCalledWith(1, manualProject);
    expect(onOpenProject).toHaveBeenNthCalledWith(2, githubProject);
    await ReactTestRenderer.act(async () => { renderer.unmount(); });
  });

  test('shows the empty GitHub state when local folders exist and the account has no repositories', async () => {
    let renderer!: ReactTestRenderer.ReactTestRenderer;
    await ReactTestRenderer.act(async () => {
      renderer = ReactTestRenderer.create(
        <ProjectHubScreen
          connected
          githubRepositoriesLoaded
          loadingRepos={false}
          projectError="Clone did not complete. Review the terminal output before retrying."
          onBack={() => undefined}
          onCloneRepo={() => undefined}
          onConfirmConnected={() => undefined}
          onCreateProject={() => undefined}
          onLogin={() => undefined}
          onOpenProject={() => undefined}
          onRefreshRepos={() => undefined}
          projects={[{name: 'notes', path: '/workspace/projects/notes'}]}
          toolName="OpenCode"
        />,
      );
    });

    expect(renderer.root.findByProps({testID: 'project-github-empty-title'}).props.children).toBe('No repositories found');
    expect(renderer.root.findByProps({testID: 'project-github-empty-body'}).props.children).toContain('This GitHub login has no accessible repositories yet');
    expect(renderer.root.findByProps({testID: 'project-load-error'}).props.children).toContain('Clone did not complete');
    await ReactTestRenderer.act(async () => { renderer.unmount(); });
  });

  test('does not claim an empty GitHub account before its repository query succeeds', async () => {
    let renderer!: ReactTestRenderer.ReactTestRenderer;
    await ReactTestRenderer.act(async () => {
      renderer = ReactTestRenderer.create(
        <ProjectHubScreen
          connected
          githubRepositoriesLoaded={false}
          loadingRepos={false}
          onBack={() => undefined}
          onCloneRepo={() => undefined}
          onConfirmConnected={() => undefined}
          onCreateProject={() => undefined}
          onLogin={() => undefined}
          onOpenProject={() => undefined}
          onRefreshRepos={() => undefined}
          projects={[]}
          toolName="OpenCode"
        />,
      );
    });

    expect(renderer.root.findAllByProps({testID: 'project-github-empty'})).toHaveLength(0);
    await ReactTestRenderer.act(async () => { renderer.unmount(); });
  });

  test('shows an informational empty state without a repository error after a successful empty query', async () => {
    let renderer!: ReactTestRenderer.ReactTestRenderer;
    await ReactTestRenderer.act(async () => {
      renderer = ReactTestRenderer.create(
        <ProjectHubScreen
          connected
          githubRepositoriesLoaded
          loadingRepos={false}
          onBack={() => undefined}
          onCloneRepo={() => undefined}
          onConfirmConnected={() => undefined}
          onCreateProject={() => undefined}
          onLogin={() => undefined}
          onOpenProject={() => undefined}
          onRefreshRepos={() => undefined}
          projects={[]}
          toolName="OpenCode"
        />,
      );
    });

    expect(renderer.root.findByProps({testID: 'project-github-empty-title'}).props.children).toBe('No repositories found');
    expect(renderer.root.findByProps({testID: 'project-github-empty-body'}).props.children).toContain('This GitHub login has no accessible repositories yet');
    expect(renderer.root.findAllByProps({testID: 'project-load-error'})).toHaveLength(0);
    await ReactTestRenderer.act(async () => { renderer.unmount(); });
  });
});
