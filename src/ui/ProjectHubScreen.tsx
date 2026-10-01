import React from 'react';
import {ScrollView, StyleSheet, Text, TextInput, View} from 'react-native';
import {UI_FONT_FAMILY} from './typography';
import {ScreenShell} from '../screen/ScreenShell';
import {BrandHeader, uiColors} from './brand';
import type {ProjectSummary} from '../projects/projectTypes';
import {InteractivePressable as Pressable} from './InteractivePressable';

export type {ProjectSummary} from '../projects/projectTypes';

export type ProjectHubScreenProps = Readonly<{
  toolName: string;
  connected: boolean;
  projects: readonly ProjectSummary[];
  loadingRepos: boolean;
  githubRepositoriesLoaded: boolean;
  projectError?: string;
  onBack: () => void;
  onLogin: () => void;
  onConfirmConnected: () => void;
  onRefreshRepos: () => void;
  onCreateProject: (name: string) => void;
  onCloneRepo: (url: string, name: string) => void;
  onOpenProject: (project: ProjectSummary) => void;
}>;

function repoNameFromUrl(url: string): string | undefined {
  const last = url.trim().replace(/\/$/, '').split('/').pop()?.replace(/\.git$/i, '');
  return last !== undefined && last !== '.' && last !== '..' && /^[A-Za-z0-9._-]{1,48}$/.test(last) ? last : undefined;
}

function isAllowedCloneUrl(url: string): boolean {
  const host = String.raw`[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?`;
  const path = String.raw`[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*(?:\.git)?`;
  return new RegExp(`^https://${host}/${path}$`).test(url) ||
    new RegExp(`^git@${host}:${path}$`).test(url);
}

export function ProjectHubScreen({toolName, connected, projects, loadingRepos, githubRepositoriesLoaded, projectError, onBack, onLogin, onConfirmConnected, onRefreshRepos, onCreateProject, onCloneRepo, onOpenProject}: ProjectHubScreenProps): React.JSX.Element {
  const [newProject, setNewProject] = React.useState('');
  const [repoUrl, setRepoUrl] = React.useState('');
  const [error, setError] = React.useState<string | undefined>();
  const manualProjects = projects.filter(project => project.remote === undefined);
  const githubProjects = projects.filter(project => project.remote !== undefined);
  const githubReady = connected || githubRepositoriesLoaded;

  const create = React.useCallback(() => {
    const name = newProject.trim();
    if (name === '.' || name === '..' || !/^[A-Za-z0-9._-]{1,48}$/.test(name)) {
      setError('Use a short project name: letters, numbers, dots, dashes, or underscores.');
      return;
    }
    setError(undefined);
    onCreateProject(name);
    setNewProject('');
  }, [newProject, onCreateProject]);

  const clone = React.useCallback(() => {
    const url = repoUrl.trim();
    const name = repoNameFromUrl(url);
    if (!isAllowedCloneUrl(url)) {
      setError('Use an HTTPS or SSH Git URL without embedded credentials.');
      return;
    }
    if (name === undefined) {
      setError('Could not determine a safe project folder name.');
      return;
    }
    setError(undefined);
    onCloneRepo(url, name);
    setRepoUrl('');
  }, [onCloneRepo, repoUrl]);

  return (
    <ScreenShell keyboardAware testID="project-hub-screen">
      <ScrollView contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
        <BrandHeader
          eyebrow={`OPEN IN ${toolName.toUpperCase()}`}
          title="Choose project"
          meta={null}
          action={(
            <Pressable
              accessibilityLabel="Back to home"
              accessibilityRole="button"
              onPress={onBack}
              style={styles.menuButton}
              testID="project-hub-back">
              <Text style={styles.menuButtonText}>menu ^</Text>
            </Pressable>
          )}
        />
        <Text style={styles.intro} testID="project-intro">Choose an existing folder or create a new one.</Text>
        <View style={styles.sectionCard} testID="project-start-card">
          <Text style={styles.sectionTitle}>Start a project</Text>
          <Text style={styles.sectionDescription}>Create a folder or clone a Git repository.</Text>
          <View style={styles.formCard}>
            <View>
              <Text style={styles.formTitle}>Create a local folder</Text>
              <View style={styles.formRow}>
                <TextInput accessibilityLabel="New project folder name" autoCapitalize="none" autoCorrect={false} maxLength={48} onChangeText={setNewProject} placeholder="my-project" placeholderTextColor={uiColors.subdued} style={styles.input} testID="project-new-name" value={newProject} />
                <Pressable accessibilityRole="button" onPress={create} style={styles.smallButton} testID="project-create"><Text style={styles.smallButtonText}>Create</Text></Pressable>
              </View>
            </View>
            <View style={styles.formDivider} />
            <View>
              <Text style={styles.formTitle}>Clone from a Git URL</Text>
              <View style={styles.formRow}>
                <TextInput accessibilityLabel="Git repository URL" autoCapitalize="none" autoCorrect={false} maxLength={256} onChangeText={setRepoUrl} placeholder="https://github.com/org/repo" placeholderTextColor={uiColors.subdued} style={styles.input} testID="project-repo-url" value={repoUrl} />
                <Pressable accessibilityRole="button" onPress={clone} style={styles.smallButton} testID="project-clone"><Text style={styles.smallButtonText}>Clone</Text></Pressable>
              </View>
            </View>
          </View>
          {error !== undefined ? <Text style={styles.error} testID="project-error">{error}</Text> : null}
        </View>
        <View style={styles.sectionCard} testID="project-local-card">
          <View style={styles.sectionTitleRow}>
            <Text style={styles.sectionTitle} testID="project-manual-section">On this device</Text>
            <Text style={styles.countBadge}>{manualProjects.length}</Text>
          </View>
          <Text style={styles.sectionDescription}>Folders in /workspace/projects</Text>
          {manualProjects.map(project => (
            <Pressable accessibilityLabel={`Open ${project.name}`} accessibilityRole="button" key={project.path} onPress={() => onOpenProject(project)} style={styles.projectRow} testID={`project-${project.name}`}>
              <View style={styles.projectDetails}><Text numberOfLines={1} style={styles.projectName}>{project.name}</Text><Text numberOfLines={1} style={styles.projectPath}>{project.path}</Text></View>
              <Text style={styles.projectArrow}>Open  ›</Text>
            </Pressable>
          ))}
          {manualProjects.length === 0 ? (
            <View style={styles.emptyCard} testID="project-manual-empty">
              <Text style={styles.emptyTitle} testID="project-manual-empty-title">No local projects yet</Text>
              <Text style={styles.emptyBody} testID="project-manual-empty-body">Create a folder above or clone a repository to get started.</Text>
            </View>
          ) : null}
        </View>
        <View style={styles.sectionCard} testID="project-github-card">
          <View style={styles.sectionTitleRow}>
            <Text style={styles.sectionTitle} testID="project-github-section">GitHub</Text>
            {githubProjects.length > 0 ? <Text style={styles.countBadge}>{githubProjects.length}</Text> : null}
          </View>
          <Text style={styles.sectionDescription}>Repositories you can access</Text>
          <View style={styles.loginCard}>
            <View style={styles.accountRow}>
              <View style={styles.accountStatus}>
                <View style={[styles.dot, githubReady ? styles.dotLive : styles.dotPending]} />
                <Text style={styles.accountLabel}>{githubReady ? 'Connected' : 'Not connected'}</Text>
              </View>
            </View>
            <Text style={styles.cardBody}>{githubReady ? 'Choose a repository below to clone it into your workspace.' : 'Sign in to browse your repositories. You can still clone public repositories by URL.'}</Text>
            {!githubReady ? (
              <Pressable accessibilityRole="button" disabled={loadingRepos} onPress={onLogin} style={[styles.primaryButton, loadingRepos && styles.buttonDisabled]} testID="project-hub-login">
                <Text style={styles.primaryText}>Connect GitHub</Text>
              </Pressable>
            ) : null}
            <Pressable accessibilityRole="button" disabled={loadingRepos} onPress={githubReady ? onRefreshRepos : onConfirmConnected} style={[styles.secondaryButton, loadingRepos && styles.buttonDisabled]} testID="project-hub-confirm-login">
              <Text style={styles.secondaryText}>{loadingRepos ? (githubReady ? 'Refreshing repositories…' : 'Checking GitHub…') : githubReady ? 'Refresh repositories  ↻' : 'I’ve already signed in'}</Text>
            </Pressable>
            {projectError !== undefined ? <Text style={styles.error} testID="project-load-error">{projectError}</Text> : null}
          </View>
          {githubProjects.map(project => (
            <Pressable accessibilityLabel={`Clone ${project.name}`} accessibilityRole="button" key={project.remote ?? project.path} onPress={() => onOpenProject(project)} style={styles.projectRow} testID={`project-${project.name}`}>
              <View style={styles.projectDetails}><Text numberOfLines={1} style={styles.projectName}>{project.name}</Text><Text numberOfLines={1} style={styles.projectPath}>{project.path}</Text></View>
              <Text style={styles.projectArrow}>Clone  ↓</Text>
            </Pressable>
          ))}
          {githubProjects.length === 0 && (loadingRepos || !connected || githubRepositoriesLoaded) ? (
            <View style={styles.emptyCard} testID="project-github-empty">
              <Text style={styles.emptyTitle} testID="project-github-empty-title">{loadingRepos ? 'Loading repositories…' : githubRepositoriesLoaded ? 'No repositories found' : 'Connect GitHub to continue'}</Text>
              <Text style={styles.emptyBody} testID="project-github-empty-body">{loadingRepos ? 'Your repository list will appear here.' : githubRepositoriesLoaded ? 'This GitHub login has no accessible repositories yet. You can create a local folder or clone a repository by URL.' : 'Sign in to see your repositories here, or use a Git URL above.'}</Text>
            </View>
          ) : null}
        </View>
      </ScrollView>
    </ScreenShell>
  );
}

const styles = StyleSheet.create({
  content: {paddingHorizontal: 18, paddingBottom: 30, paddingTop: 2},
  menuButton: {alignItems: 'center', borderColor: uiColors.border, borderRadius: 9, borderWidth: 1, height: 38, justifyContent: 'center', marginLeft: 8, paddingHorizontal: 10},
  menuButtonText: {color: uiColors.accent, fontFamily: UI_FONT_FAMILY, fontSize: 12, fontWeight: '800'},
  intro: {color: uiColors.muted, fontFamily: UI_FONT_FAMILY, fontSize: 10, lineHeight: 16, marginBottom: 2},
  sectionCard: {backgroundColor: uiColors.background, borderColor: uiColors.border, borderRadius: 14, borderWidth: 1, marginTop: 12, padding: 10},
  sectionTitleRow: {alignItems: 'center', flexDirection: 'row', gap: 8},
  sectionTitle: {color: uiColors.ink, fontFamily: UI_FONT_FAMILY, fontSize: 14, fontWeight: '800'},
  sectionDescription: {color: uiColors.muted, fontFamily: UI_FONT_FAMILY, fontSize: 10, lineHeight: 15, marginTop: 4},
  countBadge: {backgroundColor: uiColors.panelRaised, borderColor: uiColors.border, borderRadius: 12, borderWidth: 1, color: uiColors.muted, fontFamily: UI_FONT_FAMILY, fontSize: 9, minWidth: 24, overflow: 'hidden', paddingHorizontal: 7, paddingVertical: 2, textAlign: 'center'},
  accountRow: {alignItems: 'center', flexDirection: 'row'},
  accountStatus: {alignItems: 'center', flexDirection: 'row', gap: 8},
  accountLabel: {color: uiColors.ink, fontFamily: UI_FONT_FAMILY, fontSize: 11, fontWeight: '700'},
  dot: {borderRadius: 5, height: 9, width: 9},
  dotPending: {backgroundColor: '#D0A757'},
  dotLive: {backgroundColor: uiColors.accent},
  loginCard: {backgroundColor: uiColors.panel, borderColor: uiColors.border, borderRadius: 12, borderWidth: 1, marginTop: 10, padding: 10},
  cardBody: {color: uiColors.muted, fontFamily: UI_FONT_FAMILY, fontSize: 10, lineHeight: 16, marginTop: 7},
  primaryButton: {alignItems: 'center', backgroundColor: uiColors.accent, borderRadius: 8, justifyContent: 'center', marginTop: 12, minHeight: 44, paddingHorizontal: 12},
  primaryText: {color: uiColors.background, fontFamily: UI_FONT_FAMILY, fontSize: 11, fontWeight: '900'},
  secondaryButton: {alignItems: 'center', borderColor: uiColors.border, borderRadius: 8, borderWidth: 1, justifyContent: 'center', marginTop: 8, minHeight: 42, paddingHorizontal: 12},
  secondaryText: {color: uiColors.ink, fontFamily: UI_FONT_FAMILY, fontSize: 9, fontWeight: '700'},
  buttonDisabled: {opacity: 0.45},
  formCard: {backgroundColor: uiColors.panel, borderColor: uiColors.border, borderRadius: 12, borderWidth: 1, marginTop: 10, padding: 10},
  formTitle: {color: uiColors.ink, fontFamily: UI_FONT_FAMILY, fontSize: 11, fontWeight: '700'},
  formDivider: {backgroundColor: uiColors.borderSoft, height: 1, marginVertical: 12},
  formRow: {alignItems: 'center', flexDirection: 'row', gap: 8},
  input: {backgroundColor: uiColors.background, borderColor: uiColors.borderSoft, borderRadius: 7, borderWidth: 1, color: uiColors.ink, flex: 1, fontFamily: UI_FONT_FAMILY, fontSize: 11, minHeight: 44, minWidth: 0, paddingHorizontal: 10},
  smallButton: {alignItems: 'center', backgroundColor: uiColors.accent, borderRadius: 7, justifyContent: 'center', minHeight: 44, minWidth: 74, paddingHorizontal: 12},
  smallButtonText: {color: uiColors.background, fontFamily: UI_FONT_FAMILY, fontSize: 11, fontWeight: '900'},
  error: {color: uiColors.danger, fontFamily: UI_FONT_FAMILY, fontSize: 10, lineHeight: 16, marginTop: 10},
  emptyCard: {backgroundColor: uiColors.panel, borderColor: uiColors.borderSoft, borderRadius: 10, borderWidth: 1, marginTop: 8, paddingHorizontal: 10, paddingVertical: 10},
  emptyTitle: {color: uiColors.ink, fontFamily: UI_FONT_FAMILY, fontSize: 11, fontWeight: '700'},
  emptyBody: {color: uiColors.muted, fontFamily: UI_FONT_FAMILY, fontSize: 9, lineHeight: 14, marginTop: 5},
  projectRow: {alignItems: 'center', backgroundColor: uiColors.panel, borderColor: uiColors.border, borderRadius: 12, borderWidth: 1, flexDirection: 'row', marginTop: 6, minHeight: 58, paddingHorizontal: 10, paddingVertical: 8},
  projectDetails: {flex: 1, minWidth: 0},
  projectName: {color: uiColors.ink, fontFamily: UI_FONT_FAMILY, fontSize: 11, fontWeight: '800'},
  projectPath: {color: uiColors.muted, fontFamily: UI_FONT_FAMILY, fontSize: 8, marginTop: 4},
  projectArrow: {color: uiColors.accent, fontFamily: UI_FONT_FAMILY, fontSize: 8, fontWeight: '800', marginLeft: 8},
});
