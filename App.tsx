import React from 'react';
import {AppState} from 'react-native';
import {importRootfs, installRootfs, readTerminalRuntimeStatus} from './src/terminal/runtimeStatus';
import {TerminalScreen} from './src/terminal/TerminalScreen';
import {buildZshCommand, shellQuote} from './src/terminal/commandFactory';
import {TerminalSessionClient} from './src/terminal/session/sessionClient';
import type {ActiveTerminalSession} from './src/terminal/session/sessionContract';
import {listGuestDirectory} from './src/files/fileExplorer';
import {githubRepositoryError, listGithubRepositories, type GithubAccount} from './src/projects/githubRepositories';
import {clearStoredGithubAccount, readStoredGithubAccount, saveGithubAccount} from './src/projects/githubAccountStore';
import {GITHUB_AUTH_LOGIN_COMMAND, openGithubDeviceLoginUrl} from './src/projects/githubDeviceLogin';
import type {ProjectSummary} from './src/projects/projectTypes';
import {WORKSPACE_PROJECTS_DIRECTORY, buildProjectCloneCommand, harnessSessionTarget, printHiddenMarker, workspaceProjectDirectory} from './src/projects/projectCommands';
import {lockedOutMessage, readUserProfile, verifyUserPassword, type UserProfile} from './src/profile/profileStore';
import {setupOnboardingProfile} from './src/profile/onboardingSetup';
import {LoadingScreen} from './src/ui/LoadingScreen';
import {MetroHomeScreen, type MetroLaunchTarget} from './src/ui/MetroHomeScreen';
import {OnboardingScreen} from './src/ui/OnboardingScreen';
import {ProjectHubScreen} from './src/ui/ProjectHubScreen';
import {LoginScreen} from './src/ui/LoginScreen';
import {FileExplorerScreen} from './src/ui/FileExplorerScreen';
import {GithubAccountScreen} from './src/ui/GithubAccountScreen';
import {SessionSettingsScreen} from './src/ui/SessionSettingsScreen';
import {useHardwareBack} from './src/ui/useHardwareBack';
import {useBackgroundSessionLock} from './src/profile/sessionTimeout';
import {updateUnlockGrant, useUnlockGrantAppState} from './src/profile/unlockGrant';
import {consumeLaunchSessionId} from './src/terminal/session/launchSession';
import {sessionTitle} from './src/terminal/toolchainLabels';
import {createRequestIdFactory} from './src/terminal/requestIds';

type AppRoute = 'boot' | 'onboarding' | 'login' | 'home' | 'settings' | 'github-account' | 'projects' | 'files' | 'terminal';
type BootState = 'checking' | 'installing' | 'error';
type PendingGithubLogin = Readonly<{marker: string; returnTo: 'home' | 'projects'}>;

const BOOT_REQUEST_ID = 'horus-bootstrap-1';
const IMPORT_REQUEST_ID = 'horus-import-1';
const nextSessionRequestId = createRequestIdFactory('launcher');

type PendingProjectClone = Readonly<{
  marker: string;
  name: string;
  directory: string;
  harness: MetroLaunchTarget;
}>;

function isHarnessTarget(target: MetroLaunchTarget): boolean {
  return target.sessionId === undefined && (
    target.toolchain === 'claude' || target.toolchain === 'codex' || target.toolchain === 'opencode'
  );
}

function App(): React.JSX.Element {
  const [route, setRoute] = React.useState<AppRoute>('boot');
  const routeRef = React.useRef(route);
  routeRef.current = route;
  const [bootState, setBootState] = React.useState<BootState>('checking');
  const [runtimeReady, setRuntimeReady] = React.useState(false);
  const [bootError, setBootError] = React.useState<string | undefined>();
  const [profile, setProfile] = React.useState<UserProfile | null>(null);
  const [profileError, setProfileError] = React.useState<string | undefined>();
  const [lockNotice, setLockNotice] = React.useState<string | undefined>();
  const [terminalTarget, setTerminalTarget] = React.useState<MetroLaunchTarget>({
    title: 'Bare terminal',
    eyebrow: 'SHELL',
    command: buildZshCommand(`mkdir -p ${WORKSPACE_PROJECTS_DIRECTORY} && cd ${WORKSPACE_PROJECTS_DIRECTORY} && exec zsh -l`),
    toolchain: 'shell',
  });
  const [terminalInstanceKey, setTerminalInstanceKey] = React.useState(0);
  const [terminalReturnRoute, setTerminalReturnRoute] = React.useState<'home' | 'projects'>('home');
  const [selectedHarness, setSelectedHarness] = React.useState<MetroLaunchTarget | undefined>();
  const [projectConnected, setProjectConnected] = React.useState(false);
  const [projects, setProjects] = React.useState<readonly ProjectSummary[]>([]);
  const [githubAccount, setGithubAccount] = React.useState<GithubAccount | undefined>();
  const [githubAccountError, setGithubAccountError] = React.useState<string | undefined>();
  const [githubRepositoriesLoaded, setGithubRepositoriesLoaded] = React.useState(false);
  const [projectLoading, setProjectLoading] = React.useState(false);
  const [projectError, setProjectError] = React.useState<string | undefined>();
  const [githubAutoRefreshRoute, setGithubAutoRefreshRoute] = React.useState<'home' | 'projects' | undefined>();
  const [pendingProjectClone, setPendingProjectClone] = React.useState<PendingProjectClone | undefined>();
  const pendingProjectCloneRef = React.useRef(pendingProjectClone);
  pendingProjectCloneRef.current = pendingProjectClone;
  const pendingGithubLogoutMarkerRef = React.useRef<string | undefined>(undefined);
  const pendingGithubLoginRef = React.useRef<PendingGithubLogin | undefined>(undefined);
  const projectLoadingRef = React.useRef(false);
  const queuedGithubRefreshRef = React.useRef(false);
  const refreshGithubRepositoriesRef = React.useRef<() => Promise<void>>(async () => undefined);
  const manualWorkspaceRefreshRef = React.useRef(0);
  const [retryCount, setRetryCount] = React.useState(0);
  const sessionClient = React.useMemo(() => new TerminalSessionClient(), []);
  const appMountedRef = React.useRef(false);

  React.useEffect(() => {
    appMountedRef.current = true;
    return () => {
      appMountedRef.current = false;
      queuedGithubRefreshRef.current = false;
      sessionClient.dispose();
    };
  }, [sessionClient]);

  const openTerminal = React.useCallback((target: MetroLaunchTarget, returnRoute: 'home' | 'projects' = 'home') => {
    setTerminalTarget(target);
    setTerminalInstanceKey(value => value + 1);
    setTerminalReturnRoute(returnRoute);
    setRoute('terminal');
  }, []);

  const openDebugTerminal = React.useCallback(() => {
    if (!__DEV__) return;
    // Bootstrap owns installation and PRoot readiness. Keep the loading route
    // mounted while it is in flight so the terminal cannot launch a second
    // status/install request against the same native runtime.
    if (bootState === 'checking' || bootState === 'installing') return;
    openTerminal({title: 'Bare terminal', eyebrow: 'DEBUG / SHELL', command: buildZshCommand(`mkdir -p ${WORKSPACE_PROJECTS_DIRECTORY} && cd ${WORKSPACE_PROJECTS_DIRECTORY} && exec zsh -l`), toolchain: 'shell'});
  }, [bootState, openTerminal]);

  React.useEffect(() => {
    let mounted = true;
    const bootstrap = async () => {
      setBootState('checking');
      setBootError(undefined);
      setRuntimeReady(false);
      const existingProfile = await readUserProfile();
      if (!mounted) return;
      setProfile(existingProfile);
      const storedGithubAccount = await readStoredGithubAccount();
      if (!mounted) return;
      setGithubAccount(storedGithubAccount);
      setProjectConnected(storedGithubAccount !== undefined);

      let runtime = await readTerminalRuntimeStatus();
      if (!mounted) return;
      if (runtime.kind === 'error') {
        setBootState('error');
        setBootError(runtime.errorCode);
        return;
      }
      if (runtime.runtimeState === 'not_installed') {
        setBootState('installing');
        const installed = await installRootfs(BOOT_REQUEST_ID);
        if (!mounted) return;
        if (installed.kind !== 'success') {
          setBootState('error');
          setBootError(installed.errorCode);
          return;
        }
        runtime = await readTerminalRuntimeStatus();
        if (!mounted) return;
        if (runtime.kind === 'error') {
          setBootState('error');
          setBootError(runtime.errorCode);
          return;
        }
      }
      if (!runtime.prootAvailable) {
        setBootState('error');
        setBootError('proot_unavailable');
        return;
      }
      // Android may have reclaimed only this UI process while the terminal
      // service kept the user's sessions (and their unlock) alive.
      const stillUnlocked = !__DEV__ && existingProfile !== null && await updateUnlockGrant('resume');
      if (!mounted) return;
      setRuntimeReady(true);
      setRoute(__DEV__ ? 'terminal' : existingProfile === null ? 'onboarding' : stillUnlocked ? 'home' : 'login');
    };
    void bootstrap();
    return () => {
      mounted = false;
    };
  }, [retryCount]);

  const completeOnboarding = React.useCallback(async (password: string, onToolsReady: () => void) => {
    const setup = await setupOnboardingProfile(
      password,
      nextSessionRequestId('onboarding-shell'),
      onToolsReady,
    );
    if (setup.kind !== 'success') {
      setProfileError(
        setup.stage === 'alpine-tools'
          ? 'Could not prepare Alpine command-line tools. Check the connection and tap Continue to retry.'
          : 'Could not save the local profile. Please try again.',
      );
      return;
    }
    const nextProfile: UserProfile = {hasPassword: true};
    void updateUnlockGrant('grant');
    setProfileError(undefined);
    setLockNotice(undefined);
    setProfile(nextProfile);
    setRoute('home');
  }, []);

  const login = React.useCallback(async (password: string) => {
    if (profile === null) {
      setProfileError('Incorrect password.');
      return;
    }
    const check = await verifyUserPassword(password);
    if (check.kind !== 'success') {
      setProfileError(check.kind === 'locked' ? lockedOutMessage(check.retryAfterMs) : 'Incorrect password.');
      return;
    }
    void updateUnlockGrant('grant');
    setProfileError(undefined);
    setLockNotice(undefined);
    setRoute('home');
  }, [profile]);

  // Locking only hides the workspace. Running harnesses and shells keep
  // working behind the password screen and are in Recents after unlock.
  const lockSession = React.useCallback(() => {
    setProfileError(undefined);
    setLockNotice('Horus locked after 15 minutes in the background. Your sessions are still running.');
    void updateUnlockGrant('revoke');
    setRoute('login');
  }, []);

  const authenticated = route === 'home' || route === 'settings' || route === 'github-account' || route === 'projects' || route === 'files' || route === 'terminal';
  useBackgroundSessionLock(!__DEV__ && profile !== null && authenticated, lockSession);
  useUnlockGrantAppState(!__DEV__ && profile !== null && authenticated);

  const leaveTerminal = React.useCallback(() => {
    if (
      terminalTarget.completionMarker !== undefined &&
      (pendingProjectClone?.marker === terminalTarget.completionMarker ||
        pendingGithubLogoutMarkerRef.current === terminalTarget.completionMarker)
    ) return;
    if (pendingGithubLoginRef.current?.marker === terminalTarget.completionMarker) {
      pendingGithubLoginRef.current = undefined;
    }
    setRoute(terminalReturnRoute);
  }, [pendingProjectClone, terminalReturnRoute, terminalTarget.completionMarker]);

  const leaveProjects = React.useCallback(() => setRoute('home'), []);

  const leaveGithubAccount = React.useCallback(() => setRoute('home'), []);

  useHardwareBack(route === 'terminal', leaveTerminal);
  useHardwareBack(route === 'projects', leaveProjects);
  useHardwareBack(route === 'github-account', leaveGithubAccount);

  const openTarget = React.useCallback((target: MetroLaunchTarget) => {
    if (target.kind === 'files') {
      setRoute('files');
      return;
    }
    if (isHarnessTarget(target)) {
      setSelectedHarness(target);
      setProjectError(undefined);
      setGithubAutoRefreshRoute('projects');
      setRoute('projects');
      return;
    }
    openTerminal(target, target.returnTo ?? 'home');
  }, [openTerminal]);

  const loadRecentSessions = React.useCallback(async (): Promise<readonly ActiveTerminalSession[] | undefined> => {
    const result = await sessionClient.listTerminalSessions(nextSessionRequestId('recents'));
    return result.kind === 'success' ? result.sessions : undefined;
  }, [sessionClient]);

  const terminateRecentSession = React.useCallback(async (sessionId: string): Promise<boolean> => {
    const result = await sessionClient.stopSession(
      nextSessionRequestId('terminate'),
      sessionId,
      'user_stop',
    );
    return result.kind === 'success';
  }, [sessionClient]);

  const resumeRecentSession = React.useCallback((session: ActiveTerminalSession) => {
    openTarget({
      title: sessionTitle(session),
      eyebrow: 'RECENT SESSION',
      sessionId: session.sessionId,
      toolchain: session.toolchain,
    });
  }, [openTarget]);

  const terminalSessionIdRef = React.useRef(terminalTarget.sessionId);
  terminalSessionIdRef.current = terminalTarget.sessionId;

  // A tapped session notification opens that session once Horus is unlocked.
  React.useEffect(() => {
    if (!authenticated) return undefined;
    let cancelled = false;
    const openLaunchSession = async () => {
      const sessionId = await consumeLaunchSessionId();
      if (cancelled || sessionId === undefined) return;
      if (routeRef.current === 'terminal' && terminalSessionIdRef.current === sessionId) return;
      const sessions = await loadRecentSessions();
      if (cancelled) return;
      const session = sessions?.find(candidate => candidate.sessionId === sessionId);
      if (session !== undefined) resumeRecentSession(session);
    };
    void openLaunchSession();
    const subscription = AppState.addEventListener('change', state => {
      if (state === 'active') void openLaunchSession();
    });
    return () => {
      cancelled = true;
      subscription.remove();
    };
  }, [authenticated, loadRecentSessions, resumeRecentSession]);

  const openGithubAccount = React.useCallback(() => {
    setGithubAccountError(undefined);
    setRoute('github-account');
  }, []);

  const openSettings = React.useCallback(() => setRoute('settings'), []);

  const logoutGithubAccount = React.useCallback(() => {
    if (githubAccount === undefined) {
      setRoute('home');
      return;
    }
    const marker = `HORUS_GITHUB_LOGOUT_COMPLETE_${nextSessionRequestId('github-logout')}`;
    pendingGithubLogoutMarkerRef.current = marker;
    setGithubAccountError(undefined);
    const command = `gh auth logout --hostname github.com --user ${shellQuote(githubAccount.username)} && ${printHiddenMarker(marker)}`;
    openTerminal({
      title: 'GitHub Logout',
      eyebrow: 'GITHUB / LOGOUT',
      command: buildZshCommand(command),
      toolchain: 'github',
      completionMarker: marker,
    });
  }, [githubAccount, openTerminal]);

  const openGithubLoginBrowser = React.useCallback(async (url: string) => {
    await openGithubDeviceLoginUrl(url);
  }, []);

  const addProject = React.useCallback((name: string) => {
    const directory = workspaceProjectDirectory(name);
    if (directory === undefined || selectedHarness === undefined) return;
    manualWorkspaceRefreshRef.current += 1;
    const project = {name, path: directory};
    setProjects(current => [...current.filter(item => item.path !== directory), project]);
    openTerminal(harnessSessionTarget(selectedHarness, name, directory));
  }, [openTerminal, selectedHarness]);

  const beginProjectClone = React.useCallback((source: string, name: string, kind: 'github' | 'url') => {
    const directory = workspaceProjectDirectory(name);
    if (directory === undefined || selectedHarness === undefined) return;
    manualWorkspaceRefreshRef.current += 1;
    const marker = `HORUS_PROJECT_CLONE_COMPLETE_${nextSessionRequestId('clone')}`;
    const command = buildProjectCloneCommand(source, directory, marker, kind);
    setPendingProjectClone({marker, name, directory, harness: selectedHarness});
    openTerminal({
      title: `Clone or reuse ${name}`,
      eyebrow: 'BARE TERMINAL / CLONE',
      command: buildZshCommand(command),
      toolchain: kind === 'github' ? 'github' : 'shell',
      returnTo: 'projects',
      completionMarker: marker,
    }, 'projects');
  }, [openTerminal, selectedHarness]);

  const cloneProject = React.useCallback((url: string, name: string) => {
    beginProjectClone(url, name, 'url');
  }, [beginProjectClone]);

  const refreshGithubRepositories = React.useCallback(async () => {
    if (projectLoadingRef.current) {
      queuedGithubRefreshRef.current = true;
      return;
    }
    projectLoadingRef.current = true;
    setProjectLoading(true);
    setGithubRepositoriesLoaded(false);
    setProjectError(undefined);
    let accountPublished = false;
    const publishAccount = (account: GithubAccount) => {
      if (accountPublished) return;
      accountPublished = true;
      setGithubAccount(account);
      setProjectConnected(true);
      void saveGithubAccount(account);
    };
    try {
      const result = await listGithubRepositories(undefined, publishAccount);
      if (result.kind === 'success') {
        setGithubRepositoriesLoaded(true);
        publishAccount(result.account);
        setProjects(current => {
          const localProjects = current.filter(item => item.remote === undefined);
          return [...localProjects, ...result.repositories];
        });
      } else {
        if (result.account !== undefined) publishAccount(result.account);
        setProjectError(githubRepositoryError(result.errorCode, result.outputIssue));
      }
    } catch {
      setProjectError('Could not load GitHub repositories. Refresh and try again.');
    } finally {
      projectLoadingRef.current = false;
      setProjectLoading(false);
      if (queuedGithubRefreshRef.current) {
        queuedGithubRefreshRef.current = false;
        if (appMountedRef.current) void refreshGithubRepositoriesRef.current().catch(() => undefined);
      }
    }
  }, []);
  refreshGithubRepositoriesRef.current = refreshGithubRepositories;

  const refreshManualWorkspaces = React.useCallback(async () => {
    const refreshId = ++manualWorkspaceRefreshRef.current;
    const result = await listGuestDirectory('workspace', ['projects']);
    if (refreshId !== manualWorkspaceRefreshRef.current) return;
    if (result.kind === 'error') {
      if (result.errorCode === 'not_found') {
        setProjects(current => current.filter(project => project.remote !== undefined));
      }
      return;
    }
    const manualProjects = result.entries
      .filter(entry => entry.kind === 'directory' && workspaceProjectDirectory(entry.name) !== undefined)
      .map(entry => ({name: entry.name, path: `${WORKSPACE_PROJECTS_DIRECTORY}/${entry.name}`}));
    setProjects(current => [
      ...manualProjects,
      ...current.filter(project => project.remote !== undefined),
    ]);
  }, []);

  React.useEffect(() => {
    if (githubAutoRefreshRoute === undefined || route !== githubAutoRefreshRoute) return;
    setGithubAutoRefreshRoute(undefined);
    if (route === 'projects') {
      void refreshManualWorkspaces().catch(() => undefined).finally(() => {
        if (routeRef.current === 'projects') refreshGithubRepositories().catch(() => undefined);
      });
      return;
    }
    void refreshGithubRepositories().catch(() => undefined);
  }, [githubAutoRefreshRoute, refreshGithubRepositories, refreshManualWorkspaces, route]);

  const openGithubLogin = React.useCallback((returnTo: 'home' | 'projects') => {
    const marker = `HORUS_GITHUB_LOGIN_COMPLETE_${nextSessionRequestId('github-login')}`;
    pendingGithubLoginRef.current = {marker, returnTo};
    const command = `mkdir -p ${shellQuote(WORKSPACE_PROJECTS_DIRECTORY)} && cd ${shellQuote(WORKSPACE_PROJECTS_DIRECTORY)} && ${GITHUB_AUTH_LOGIN_COMMAND} && ${printHiddenMarker(marker)}`;
    openTarget({title: 'GitHub Login', eyebrow: 'GITHUB / LOGIN', command: buildZshCommand(command), toolchain: 'github', returnTo, completionMarker: marker});
  }, [openTarget]);
  const openProjectLogin = React.useCallback(() => openGithubLogin('projects'), [openGithubLogin]);
  const openHomeGithubLogin = React.useCallback(() => openGithubLogin('home'), [openGithubLogin]);

  const openExistingProject = React.useCallback((project: ProjectSummary) => {
    if (project.remote !== undefined) {
      beginProjectClone(project.path, project.name, 'github');
      return;
    }
    const directory = workspaceProjectDirectory(project.name);
    if (directory === undefined || selectedHarness === undefined) return;
    openTerminal(harnessSessionTarget(selectedHarness, project.name, directory));
  }, [beginProjectClone, openTerminal, selectedHarness]);

  const completeProjectClone = React.useCallback((marker: string) => {
    const pending = pendingProjectCloneRef.current;
    if (pending === undefined || pending.marker !== marker) return;
    manualWorkspaceRefreshRef.current += 1;
    setProjects(current => [...current.filter(item => item.path !== pending.directory), {name: pending.name, path: pending.directory}]);
    setPendingProjectClone(undefined);
    openTerminal(harnessSessionTarget(pending.harness, pending.name, pending.directory));
  }, [openTerminal]);

  const failProjectClone = React.useCallback((marker: string) => {
    if (pendingGithubLogoutMarkerRef.current === marker) {
      pendingGithubLogoutMarkerRef.current = undefined;
      setGithubAccountError('GitHub logout failed. The account is still connected.');
      setRoute('github-account');
      return;
    }
    if (pendingGithubLoginRef.current?.marker === marker) {
      pendingGithubLoginRef.current = undefined;
      return;
    }
    const pending = pendingProjectCloneRef.current;
    if (pending === undefined || pending.marker !== marker) return;
    setPendingProjectClone(undefined);
    setProjectError('Clone did not complete. Review the terminal output before retrying.');
    // Part of the clone may have landed; show what is on disk now.
    void refreshManualWorkspaces().catch(() => undefined);
  }, [refreshManualWorkspaces]);

  const onTerminalCompletion = React.useCallback(() => {
    const marker = terminalTarget.completionMarker;
    if (marker === undefined) return;
    const pendingGithubLogin = pendingGithubLoginRef.current;
    if (pendingGithubLogin?.marker === marker) {
      pendingGithubLoginRef.current = undefined;
      const returnRoute = pendingGithubLogin.returnTo;
      if (returnRoute === 'projects') {
        setGithubAutoRefreshRoute('projects');
        setRoute('projects');
        return;
      }
      setRoute('home');
      void refreshGithubRepositories().catch(() => undefined);
      return;
    }
    if (pendingGithubLogoutMarkerRef.current === marker) {
      pendingGithubLogoutMarkerRef.current = undefined;
      clearStoredGithubAccount().then(cleared => {
        if (!cleared) {
          setGithubAccountError('GitHub logged out, but Horus could not clear its saved account details.');
          setRoute('github-account');
          return;
        }
        setGithubAccount(undefined);
        setProjectConnected(false);
        setGithubRepositoriesLoaded(false);
        setProjects(current => current.filter(project => project.remote === undefined));
        setGithubAccountError(undefined);
        setRoute('home');
      });
      return;
    }
    completeProjectClone(marker);
  }, [completeProjectClone, refreshGithubRepositories, terminalTarget.completionMarker]);

  const importDownloadedRootfs = React.useCallback(() => {
    void (async () => {
      const previousError = bootError;
      setBootState('installing');
      const imported = await importRootfs(IMPORT_REQUEST_ID);
      if (!appMountedRef.current) return;
      if (imported.kind === 'success') {
        // Bootstrap re-checks the runtime and finds it ready.
        setRetryCount(value => value + 1);
        return;
      }
      setBootState('error');
      setBootError(imported.errorCode === 'import_cancelled' ? previousError : imported.errorCode);
    })();
  }, [bootError]);

  const onTerminalCommandFailure = React.useCallback(() => {
    if (terminalTarget.completionMarker !== undefined) failProjectClone(terminalTarget.completionMarker);
  }, [failProjectClone, terminalTarget.completionMarker]);

  if (route === 'boot') {
    return <LoadingScreen onDebugTerminal={__DEV__ ? openDebugTerminal : undefined} onImport={importDownloadedRootfs} onRetry={() => setRetryCount(value => value + 1)} status={bootState} detail={bootError} />;
  }
  if (!__DEV__ && route === 'login' && profile !== null) {
    return <LoginScreen error={profileError} notice={lockNotice} onLogin={login} />;
  }
  if ((profile === null && !__DEV__) || (!__DEV__ && route === 'onboarding')) {
    return <OnboardingScreen error={profileError} onComplete={completeOnboarding} runtimeReady={bootState !== 'error'} />;
  }
  if (route === 'home') {
    return (
      <MetroHomeScreen
        githubAccount={githubAccount}
        loadRecentSessions={loadRecentSessions}
        onOpenGithubLogin={openHomeGithubLogin}
        onOpenGithubAccount={openGithubAccount}
        onOpenSettings={openSettings}
        onOpen={openTarget}
        onResumeSession={resumeRecentSession}
        onTerminateSession={terminateRecentSession}
      />
    );
  }
  if (route === 'settings') {
    return <SessionSettingsScreen onBack={() => setRoute('home')} />;
  }
  if (route === 'github-account' && githubAccount !== undefined) {
    return <GithubAccountScreen account={githubAccount} error={githubAccountError} onBack={leaveGithubAccount} onLogout={logoutGithubAccount} />;
  }
  if (route === 'projects') {
    return (
      <ProjectHubScreen
        connected={projectConnected}
        githubRepositoriesLoaded={githubRepositoriesLoaded}
        loadingRepos={projectLoading}
        projectError={projectError}
        onBack={leaveProjects}
        onCloneRepo={cloneProject}
        onConfirmConnected={() => { void refreshGithubRepositories(); }}
        onCreateProject={addProject}
        onLogin={openProjectLogin}
        onOpenProject={openExistingProject}
        onRefreshRepos={() => { void refreshGithubRepositories(); }}
        projects={projects}
        toolName={selectedHarness?.title ?? 'Harness'}
      />
    );
  }
  if (route === 'files') {
    return <FileExplorerScreen onBack={() => setRoute('home')} />;
  }
  const clonePending = terminalTarget.completionMarker !== undefined && pendingProjectClone?.marker === terminalTarget.completionMarker;
  const logoutPending = terminalTarget.completionMarker !== undefined && pendingGithubLogoutMarkerRef.current === terminalTarget.completionMarker;
  const completionPending = clonePending || logoutPending;
  return (
    <TerminalScreen
      key={terminalInstanceKey}
      onBack={completionPending ? undefined : leaveTerminal}
      onGithubDeviceLogin={openGithubLoginBrowser}
      onHome={completionPending ? undefined : leaveTerminal}
      onCompletion={onTerminalCompletion}
      onCommandFailure={onTerminalCommandFailure}
      screenEyebrow={clonePending ? 'CLONING / WAIT' : logoutPending ? 'GITHUB / LOGOUT / WAIT' : terminalTarget.eyebrow}
      screenTitle={terminalTarget.title}
      sessionCommand={terminalTarget.command}
      existingSessionId={terminalTarget.sessionId}
      runtimeReady={runtimeReady}
      stopSessionOnUnmount={terminalTarget.completionMarker !== undefined}
      toolchain={terminalTarget.toolchain ?? 'shell'}
      completionMarker={terminalTarget.completionMarker}
    />
  );
}

export default App;
