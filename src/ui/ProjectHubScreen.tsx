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

type ProjectTab = 'local' | 'github' | 'new';
type NewProjectMode = 'folder' | 'clone';

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
  const [tab, setTab] = React.useState<ProjectTab>('local');
  const [mode, setMode] = React.useState<NewProjectMode>('folder');
  const [newProject, setNewProject] = React.useState('');
  const [repoUrl, setRepoUrl] = React.useState('');
  const [error, setError] = React.useState<string | undefined>();
  const manualProjects = projects.filter(project => project.remote === undefined);
  const githubProjects = projects.filter(project => project.remote !== undefined);
  const githubReady = connected || githubRepositoriesLoaded;

  const chooseMode = React.useCallback((next: NewProjectMode) => {
    setMode(next);
    setError(undefined);
  }, []);

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

        <View accessibilityRole="tablist" style={styles.tabs}>
          <TabButton active={tab === 'local'} count={manualProjects.length > 0 ? manualProjects.length : undefined} label="On device" onPress={() => setTab('local')} testID="project-tab-local" />
          <TabButton active={tab === 'github'} count={githubProjects.length > 0 ? githubProjects.length : undefined} label="GitHub" onPress={() => setTab('github')} testID="project-tab-github" />
          <TabButton active={tab === 'new'} label="+ New" onPress={() => setTab('new')} testID="project-tab-new" />
        </View>

        {projectError !== undefined ? <Text style={styles.banner} testID="project-load-error">{projectError}</Text> : null}

        {tab === 'local' ? (
          manualProjects.length > 0 ? (
            <View style={styles.list} testID="project-local-list">
              {manualProjects.map((project, index) => (
                <ProjectRow
                  accessibilityLabel={`Open ${project.name}`}
                  first={index === 0}
                  key={project.path}
                  name={project.name}
                  onPress={() => onOpenProject(project)}
                  trailing="›"
                  testID={`project-${project.name}`}
                />
              ))}
            </View>
          ) : (
            <View style={styles.empty} testID="project-manual-empty">
              <Text style={styles.emptyTitle} testID="project-manual-empty-title">No projects on this device yet</Text>
              <Text style={styles.emptyBody} testID="project-manual-empty-body">Start an empty folder or clone a repository.</Text>
              <Pressable accessibilityRole="button" onPress={() => setTab('new')} style={styles.primaryButton} testID="project-manual-empty-new">
                <Text style={styles.primaryText}>+ New project</Text>
              </Pressable>
            </View>
          )
        ) : null}

        {tab === 'github' ? (
          <>
            <View style={styles.statusRow}>
              <View style={[styles.dot, githubReady ? styles.dotLive : styles.dotPending]} />
              <Text style={styles.statusLabel} testID="project-github-status">{githubReady ? 'Connected' : 'Not connected'}</Text>
              {githubReady ? (
                <Pressable accessibilityRole="button" disabled={loadingRepos} onPress={onRefreshRepos} style={[styles.linkButton, loadingRepos && styles.buttonDisabled]} testID="project-hub-confirm-login">
                  <Text style={styles.linkText}>{loadingRepos ? 'Refreshing…' : 'Refresh ↻'}</Text>
                </Pressable>
              ) : null}
            </View>
            {githubReady ? (
              githubProjects.length > 0 ? (
                <View style={styles.list} testID="project-github-list">
                  {githubProjects.map((project, index) => (
                    <ProjectRow
                      accessibilityLabel={`Clone ${project.name}`}
                      detail={project.path}
                      first={index === 0}
                      key={project.remote ?? project.path}
                      name={project.name}
                      onPress={() => onOpenProject(project)}
                      trailing="Clone ↓"
                      testID={`project-${project.name}`}
                    />
                  ))}
                </View>
              ) : githubRepositoriesLoaded || loadingRepos ? (
                <View style={styles.empty} testID="project-github-empty">
                  <Text style={styles.emptyTitle} testID="project-github-empty-title">{loadingRepos ? 'Loading repositories…' : 'No repositories found'}</Text>
                  <Text style={styles.emptyBody} testID="project-github-empty-body">{loadingRepos ? 'Your repository list will appear here.' : 'This GitHub login has no accessible repositories yet.'}</Text>
                </View>
              ) : (
                <View style={styles.empty} testID="project-github-unloaded">
                  <Text style={styles.emptyBody}>Tap Refresh to load your repositories.</Text>
                </View>
              )
            ) : (
              <View style={styles.empty} testID="project-github-signin">
                <Text style={styles.emptyBody}>Sign in to see your repositories here. Public repositories can also be cloned by URL under + New.</Text>
                <Pressable accessibilityRole="button" disabled={loadingRepos} onPress={onLogin} style={[styles.primaryButton, loadingRepos && styles.buttonDisabled]} testID="project-hub-login">
                  <Text style={styles.primaryText}>Connect GitHub</Text>
                </Pressable>
                <Pressable accessibilityRole="button" disabled={loadingRepos} onPress={onConfirmConnected} style={[styles.linkButtonCentered, loadingRepos && styles.buttonDisabled]} testID="project-hub-confirm-login">
                  <Text style={styles.linkText}>{loadingRepos ? 'Checking GitHub…' : 'Already signed in? Check again'}</Text>
                </Pressable>
              </View>
            )}
          </>
        ) : null}

        {tab === 'new' ? (
          <View style={styles.empty} testID="project-new">
            <View style={styles.segment}>
              <SegmentButton active={mode === 'folder'} label="Empty folder" onPress={() => chooseMode('folder')} testID="project-new-mode-folder" />
              <SegmentButton active={mode === 'clone'} label="Clone URL" onPress={() => chooseMode('clone')} testID="project-new-mode-clone" />
            </View>
            {mode === 'folder' ? (
              <View style={styles.formRow}>
                <TextInput accessibilityLabel="New project folder name" autoCapitalize="none" autoCorrect={false} maxLength={48} onChangeText={setNewProject} onSubmitEditing={create} placeholder="my-project" placeholderTextColor={uiColors.subdued} returnKeyType="go" style={styles.input} testID="project-new-name" value={newProject} />
                <Pressable accessibilityRole="button" onPress={create} style={styles.smallButton} testID="project-create"><Text style={styles.smallButtonText}>Create</Text></Pressable>
              </View>
            ) : (
              <View style={styles.formRow}>
                <TextInput accessibilityLabel="Git repository URL" autoCapitalize="none" autoCorrect={false} keyboardType="url" maxLength={256} onChangeText={setRepoUrl} onSubmitEditing={clone} placeholder="https://github.com/org/repo" placeholderTextColor={uiColors.subdued} returnKeyType="go" style={styles.input} testID="project-repo-url" value={repoUrl} />
                <Pressable accessibilityRole="button" onPress={clone} style={styles.smallButton} testID="project-clone"><Text style={styles.smallButtonText}>Clone</Text></Pressable>
              </View>
            )}
            <Text style={styles.hint}>{mode === 'folder' ? 'Created in /workspace/projects.' : 'HTTPS or SSH. The folder takes the repository name.'}</Text>
            {error !== undefined ? <Text style={styles.error} testID="project-error">{error}</Text> : null}
          </View>
        ) : null}
      </ScrollView>
    </ScreenShell>
  );
}

type TabButtonProps = Readonly<{active: boolean; count?: number; label: string; onPress: () => void; testID: string}>;

function TabButton({active, count, label, onPress, testID}: TabButtonProps): React.JSX.Element {
  return (
    <Pressable accessibilityRole="tab" accessibilityState={{selected: active}} onPress={onPress} style={[styles.tab, active && styles.tabActive]} testID={testID}>
      <Text numberOfLines={1} style={[styles.tabText, active && styles.tabTextActive]}>
        {label}{count === undefined ? '' : ` ${count}`}
      </Text>
    </Pressable>
  );
}

function SegmentButton({active, label, onPress, testID}: Omit<TabButtonProps, 'count'>): React.JSX.Element {
  return (
    <Pressable accessibilityRole="button" accessibilityState={{selected: active}} onPress={onPress} style={[styles.segmentButton, active && styles.segmentActive]} testID={testID}>
      <Text style={[styles.segmentText, active && styles.segmentTextActive]}>{label}</Text>
    </Pressable>
  );
}

type ProjectRowProps = Readonly<{accessibilityLabel: string; detail?: string; first: boolean; name: string; onPress: () => void; trailing: string; testID: string}>;

function ProjectRow({accessibilityLabel, detail, first, name, onPress, trailing, testID}: ProjectRowProps): React.JSX.Element {
  return (
    <Pressable accessibilityLabel={accessibilityLabel} accessibilityRole="button" onPress={onPress} style={[styles.row, !first && styles.rowDivider]} testID={testID}>
      <View style={styles.rowCopy}>
        <Text numberOfLines={1} style={styles.rowName}>{name}</Text>
        {detail === undefined ? null : <Text numberOfLines={1} style={styles.rowDetail}>{detail}</Text>}
      </View>
      <Text style={styles.rowTrailing}>{trailing}</Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  content: {paddingHorizontal: 18, paddingBottom: 30, paddingTop: 2},
  menuButton: {alignItems: 'center', borderColor: uiColors.border, borderRadius: 9, borderWidth: 1, height: 38, justifyContent: 'center', marginLeft: 8, paddingHorizontal: 10},
  menuButtonText: {color: uiColors.accent, fontFamily: UI_FONT_FAMILY, fontSize: 12, fontWeight: '800'},
  tabs: {backgroundColor: uiColors.panel, borderColor: uiColors.border, borderRadius: 10, borderWidth: 1, flexDirection: 'row', gap: 4, marginTop: 4, padding: 4},
  tab: {alignItems: 'center', borderRadius: 7, flex: 1, justifyContent: 'center', minHeight: 40, paddingHorizontal: 6},
  tabActive: {backgroundColor: uiColors.accent},
  tabText: {color: uiColors.muted, fontFamily: UI_FONT_FAMILY, fontSize: 11, fontWeight: '800'},
  tabTextActive: {color: uiColors.background},
  banner: {color: uiColors.danger, fontFamily: UI_FONT_FAMILY, fontSize: 10, lineHeight: 16, marginTop: 14},
  list: {backgroundColor: uiColors.panel, borderColor: uiColors.border, borderRadius: 12, borderWidth: 1, marginTop: 14, overflow: 'hidden'},
  row: {alignItems: 'center', flexDirection: 'row', minHeight: 56, paddingHorizontal: 14, paddingVertical: 10},
  rowDivider: {borderTopColor: uiColors.borderSoft, borderTopWidth: 1},
  rowCopy: {flex: 1, minWidth: 0},
  rowName: {color: uiColors.ink, fontFamily: UI_FONT_FAMILY, fontSize: 13, fontWeight: '800'},
  rowDetail: {color: uiColors.subdued, fontFamily: UI_FONT_FAMILY, fontSize: 9, marginTop: 4},
  rowTrailing: {color: uiColors.accent, fontFamily: UI_FONT_FAMILY, fontSize: 12, fontWeight: '800', marginLeft: 10},
  empty: {backgroundColor: uiColors.panel, borderColor: uiColors.border, borderRadius: 12, borderWidth: 1, marginTop: 14, padding: 14},
  emptyTitle: {color: uiColors.ink, fontFamily: UI_FONT_FAMILY, fontSize: 12, fontWeight: '800'},
  emptyBody: {color: uiColors.muted, fontFamily: UI_FONT_FAMILY, fontSize: 10, lineHeight: 16, marginTop: 6},
  statusRow: {alignItems: 'center', flexDirection: 'row', gap: 8, marginTop: 16, minHeight: 32},
  statusLabel: {color: uiColors.ink, flex: 1, fontFamily: UI_FONT_FAMILY, fontSize: 11, fontWeight: '700'},
  dot: {borderRadius: 5, height: 9, width: 9},
  dotPending: {backgroundColor: uiColors.warning},
  dotLive: {backgroundColor: uiColors.accent},
  primaryButton: {alignItems: 'center', backgroundColor: uiColors.accent, borderRadius: 8, justifyContent: 'center', marginTop: 14, minHeight: 44, paddingHorizontal: 12},
  primaryText: {color: uiColors.background, fontFamily: UI_FONT_FAMILY, fontSize: 11, fontWeight: '900'},
  linkButton: {justifyContent: 'center', minHeight: 32, paddingHorizontal: 4},
  linkButtonCentered: {alignItems: 'center', justifyContent: 'center', marginTop: 8, minHeight: 36},
  linkText: {color: uiColors.accent, fontFamily: UI_FONT_FAMILY, fontSize: 10, fontWeight: '800'},
  buttonDisabled: {opacity: 0.45},
  segment: {backgroundColor: uiColors.background, borderColor: uiColors.borderSoft, borderRadius: 8, borderWidth: 1, flexDirection: 'row', padding: 3},
  segmentButton: {alignItems: 'center', borderRadius: 6, flex: 1, justifyContent: 'center', minHeight: 34},
  segmentActive: {backgroundColor: uiColors.panelRaised, borderColor: uiColors.border, borderWidth: 1},
  segmentText: {color: uiColors.subdued, fontFamily: UI_FONT_FAMILY, fontSize: 10, fontWeight: '800'},
  segmentTextActive: {color: uiColors.ink},
  formRow: {alignItems: 'center', flexDirection: 'row', gap: 8, marginTop: 12},
  input: {backgroundColor: uiColors.background, borderColor: uiColors.borderSoft, borderRadius: 7, borderWidth: 1, color: uiColors.ink, flex: 1, fontFamily: UI_FONT_FAMILY, fontSize: 11, minHeight: 44, minWidth: 0, paddingHorizontal: 10},
  smallButton: {alignItems: 'center', backgroundColor: uiColors.accent, borderRadius: 7, justifyContent: 'center', minHeight: 44, minWidth: 74, paddingHorizontal: 12},
  smallButtonText: {color: uiColors.background, fontFamily: UI_FONT_FAMILY, fontSize: 11, fontWeight: '900'},
  hint: {color: uiColors.subdued, fontFamily: UI_FONT_FAMILY, fontSize: 9, lineHeight: 14, marginTop: 8},
  error: {color: uiColors.danger, fontFamily: UI_FONT_FAMILY, fontSize: 10, lineHeight: 16, marginTop: 10},
});
