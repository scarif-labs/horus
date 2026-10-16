import React from 'react';
import {HARNESS_LOGO_SOURCES, type HarnessLogo} from '../terminal/harnessLogos';
import {AppState, BackHandler, Image, ScrollView, StyleSheet, Text, View} from 'react-native';
import {UI_FONT_FAMILY} from './typography';
import {readDeviceSnapshot, type DeviceSnapshot} from '../device/deviceSnapshot';
import type {GithubAccount} from '../projects/githubRepositories';
import {buildZshCommand, buildZshScriptCommand} from '../terminal/commandFactory';
import type {TerminalToolchainTarget} from '../native/NativeTerminalRuntime';
import type {ActiveTerminalSession} from '../terminal/session/sessionContract';
import {formatSessionAge, sessionTitle} from '../terminal/toolchainLabels';
import {ScreenShell} from '../screen/ScreenShell';
import {BrandHeader, uiColors} from './brand';
import {InteractivePressable as Pressable} from './InteractivePressable';
import {ProfileAvatar} from './ProfileAvatar';
import {DISTRO_NAMES, type ProfileUser, type LinuxDistro} from '../profile/profileStore';

export type MetroLaunchTarget = Readonly<{
  title: string;
  eyebrow: string;
  command?: string;
  toolchain?: TerminalToolchainTarget;
  kind?: 'terminal' | 'files';
  returnTo?: 'home' | 'projects';
  sessionId?: string;
  /** Exact marker emitted after a visible workspace setup command succeeds. */
  completionMarker?: string;
}>;

export type MetroHomeScreenProps = Readonly<{
  githubAccount?: GithubAccount;
  /** Who is signed in, so it is clear whose Linux the sessions run in. */
  user?: ProfileUser;
  onOpenUser?: () => void;
  /** Shown once after upgrading from a Horus without Linux users. */
  accountIntro?: Readonly<{onOpen: () => void; onDismiss: () => void; addableDistro?: LinuxDistro}>;
  onOpen: (target: MetroLaunchTarget) => void;
  onOpenGithubLogin: () => void;
  onOpenGithubAccount?: () => void;
  onOpenSettings?: () => void;
  loadRecentSessions?: () => Promise<readonly ActiveTerminalSession[] | undefined>;
  onResumeSession?: (session: ActiveTerminalSession) => void;
  onTerminateSession?: (sessionId: string) => Promise<boolean>;
}>;

function formatBytes(bytes: number): string {
  if (bytes >= 1024 ** 3) return `${(bytes / 1024 ** 3).toFixed(1)} GB`;
  if (bytes >= 1024 ** 2) return `${Math.round(bytes / 1024 ** 2)} MB`;
  return `${Math.round(bytes / 1024)} KB`;
}

const harnessLogoSources = HARNESS_LOGO_SOURCES;

function StatTile({label, value, detail, divided}: {label: string; value: string; detail: string; divided: boolean}): React.JSX.Element {
  return (
    <View style={[styles.statTile, divided && styles.statDivider]}>
      <Text adjustsFontSizeToFit minimumFontScale={0.75} numberOfLines={1} style={[styles.tileLabel, styles.statText]}>{label}</Text>
      <Text adjustsFontSizeToFit minimumFontScale={0.72} numberOfLines={1} style={[styles.statValue, styles.statText]}>{value}</Text>
      <Text adjustsFontSizeToFit minimumFontScale={0.75} numberOfLines={1} style={[styles.tileDetail, styles.statText]}>{detail}</Text>
    </View>
  );
}

type AppTileProps = Readonly<{
  title: string;
  detail?: string;
  onPress: () => void;
}> & (
  | Readonly<{icon: string; logo?: never}>
  | Readonly<{logo: HarnessLogo; icon?: never}>
);

function AppTile({icon, logo, title, detail, onPress}: AppTileProps): React.JSX.Element {
  return (
    <Pressable accessibilityLabel={detail === undefined ? title : `${title}, ${detail}`} accessibilityRole="button" onPress={onPress} style={styles.appTile} testID={logo === undefined ? 'metro-harness-shell' : `metro-harness-${logo}`}>
      {logo === undefined
        ? <Text accessibilityElementsHidden style={styles.appIcon}>{icon}</Text>
        : <Image source={harnessLogoSources[logo]} resizeMode="contain" style={[styles.appLogo, logo === 'codex' && styles.codexLogo]} />}
      <Text numberOfLines={1} style={styles.appTitle}>{title}</Text>
      {detail === undefined ? null : <Text numberOfLines={1} style={styles.appDetail}>{detail}</Text>}
    </Pressable>
  );
}

export function MetroHomeScreen({githubAccount, user, onOpenUser, accountIntro, onOpen, onOpenGithubLogin, onOpenGithubAccount, onOpenSettings, loadRecentSessions, onResumeSession, onTerminateSession}: MetroHomeScreenProps): React.JSX.Element {
  const [snapshot, setSnapshot] = React.useState<DeviceSnapshot | null>(null);
  // The upgrade coach mark spotlights the user card; both are measured in
  // window coordinates so the hole lines up whatever the shell's insets.
  const userCardRef = React.useRef<React.ElementRef<typeof View>>(null);
  const introRootRef = React.useRef<React.ElementRef<typeof View>>(null);
  const [introHole, setIntroHole] = React.useState<IntroHole | undefined>();
  const showIntro = accountIntro !== undefined && user !== undefined;
  const measureIntro = React.useCallback(() => {
    if (!showIntro) return;
    introRootRef.current?.measureInWindow((rootX, rootY) => {
      userCardRef.current?.measureInWindow((x, y, width, height) => {
        if (width > 0 && height > 0) setIntroHole({x: x - rootX, y: y - rootY, width, height});
      });
    });
  }, [showIntro]);
  React.useEffect(() => {
    if (!showIntro || accountIntro === undefined) return undefined;
    const subscription = BackHandler.addEventListener('hardwareBackPress', () => {
      accountIntro.onDismiss();
      return true;
    });
    return () => subscription.remove();
  }, [accountIntro, showIntro]);
  const [recentSessions, setRecentSessions] = React.useState<readonly ActiveTerminalSession[]>([]);
  const [terminatingSessions, setTerminatingSessions] = React.useState<ReadonlySet<string>>(() => new Set());
  const [terminateError, setTerminateError] = React.useState(false);
  const mountedRef = React.useRef(true);
  const refreshingRef = React.useRef(false);

  React.useEffect(() => {
    mountedRef.current = true;
    const refresh = async (): Promise<void> => {
      if (refreshingRef.current) return;
      refreshingRef.current = true;
      const [deviceResult, sessionsResult] = await Promise.allSettled([
        readDeviceSnapshot(),
        loadRecentSessions?.() ?? Promise.resolve([]),
      ]);
      if (mountedRef.current) {
        if (deviceResult.status === 'fulfilled') setSnapshot(deviceResult.value);
        if (sessionsResult.status === 'fulfilled' && sessionsResult.value !== undefined) {
          setRecentSessions([...sessionsResult.value].sort((left, right) => right.startedAtMs - left.startedAtMs));
        }
      }
      refreshingRef.current = false;
    };
    // Poll only while Horus is in the foreground; JS timers keep firing in
    // the background until Android freezes the process.
    let timer: ReturnType<typeof setInterval> | undefined;
    const startPolling = () => {
      if (timer !== undefined) return;
      void refresh();
      timer = setInterval(() => { void refresh(); }, 8_000);
    };
    const stopPolling = () => {
      if (timer !== undefined) clearInterval(timer);
      timer = undefined;
    };
    if (AppState.currentState !== 'background') startPolling();
    const subscription = AppState.addEventListener('change', state => {
      if (state === 'active') startPolling();
      else stopPolling();
    });
    return () => {
      mountedRef.current = false;
      subscription.remove();
      stopPolling();
    };
  }, [loadRecentSessions]);

  const terminateSession = React.useCallback(async (sessionId: string) => {
    if (onTerminateSession === undefined || terminatingSessions.has(sessionId)) return;
    setTerminateError(false);
    setTerminatingSessions(current => new Set(current).add(sessionId));
    let stopped = false;
    try {
      stopped = await onTerminateSession(sessionId);
    } catch {
      stopped = false;
    }
    if (mountedRef.current) {
      if (stopped) {
        setRecentSessions(current => current.filter(session => session.sessionId !== sessionId));
      } else {
        setTerminateError(true);
      }
      setTerminatingSessions(current => {
        const next = new Set(current);
        next.delete(sessionId);
        return next;
      });
    }
  }, [onTerminateSession, terminatingSessions]);

  return (
    <ScreenShell testID="metro-home-screen">
      <ScrollView contentContainerStyle={styles.content}>
        <BrandHeader
          eyebrow=""
          title="HORUS"
          meta={null}
          action={onOpenSettings === undefined ? undefined : (
            <Pressable
              accessibilityLabel="Settings"
              accessibilityRole="button"
              onPress={onOpenSettings}
              style={styles.settingsButton}
              testID="metro-settings">
              <Text style={styles.settingsText}>SETTINGS</Text>
            </Pressable>
          )}
        />

        <View style={styles.menuPanel} testID="metro-menu">
          <View style={styles.identityRow}>
            {user === undefined ? null : (
              <View collapsable={false} onLayout={measureIntro} ref={userCardRef} style={styles.userSlot}>
              <Pressable
                accessibilityLabel={`Signed in as ${user.username}, ${DISTRO_NAMES[user.distro]}`}
                accessibilityRole="button"
                onPress={onOpenUser}
                style={[styles.githubTile, styles.halfTile]}
                testID="metro-user">
                <ProfileAvatar avatar={user.avatar} selected size={48} />
                <View style={[styles.githubCopy, styles.userCopy]}>
                  <Text adjustsFontSizeToFit minimumFontScale={0.65} numberOfLines={1} style={styles.halfTitle} testID="metro-user-name">{user.username}</Text>
                  <Text numberOfLines={1} style={[styles.tileLabel, styles.userDistro]} testID="metro-user-distro">{DISTRO_NAMES[user.distro].toUpperCase()}</Text>
                </View>
              </Pressable>
              </View>
            )}
            <Pressable
              accessibilityLabel={githubAccount === undefined ? 'Log in to GitHub' : `GitHub account, @${githubAccount.username}`}
              accessibilityRole="button"
              onPress={() => {
                if (githubAccount === undefined) {
                  onOpenGithubLogin();
                  return;
                }
                if (onOpenGithubAccount !== undefined) {
                  onOpenGithubAccount();
                  return;
                }
              }}
              style={[styles.githubTile, user !== undefined && styles.halfTile]}
              testID="metro-github">
              {user === undefined ? null : (
                <View style={[styles.githubAction, styles.halfAction]}>
                  {githubAccount?.avatarUrl === undefined
                    ? <Image accessibilityLabel="GitHub" source={require('../../assets/ui/github-mark.png')} style={styles.halfMark} testID="metro-github-mark" />
                    : <Image accessibilityLabel={`${githubAccount.username} GitHub profile image`} source={{uri: githubAccount.avatarUrl}} style={styles.halfAction} testID="metro-github-avatar" />}
                </View>
              )}
              <View style={[styles.githubCopy, user !== undefined && styles.userCopy]}>
                <Text style={styles.tileLabel} testID="metro-github-eyebrow">GITHUB</Text>
                <Text adjustsFontSizeToFit minimumFontScale={0.65} numberOfLines={1} style={user === undefined ? styles.githubTitle : styles.halfTitle} testID="metro-github-title">
                  {githubAccount !== undefined ? `@${githubAccount.username}` : user === undefined ? 'Login to GitHub' : 'Log in'}
                </Text>
              </View>
              {user !== undefined ? null : (
                <>
                  <View style={styles.githubSeparator} />
                  <View style={styles.githubAction}>
                    {githubAccount?.avatarUrl === undefined
                      ? <Image accessibilityLabel="GitHub" source={require('../../assets/ui/github-mark.png')} style={styles.githubMark} testID="metro-github-mark" />
                      : <Image accessibilityLabel={`${githubAccount.username} GitHub profile image`} source={{uri: githubAccount.avatarUrl}} style={styles.githubAvatar} testID="metro-github-avatar" />}
                  </View>
                </>
              )}
            </Pressable>
          </View>

          <View style={styles.statsPanel}>
            <StatTile label="STORAGE" value={snapshot === null ? '—' : formatBytes(snapshot.freeStorageBytes)} detail={snapshot === null ? 'checking' : 'available'} divided />
            <StatTile label="RAM" value={snapshot === null ? '—' : formatBytes(snapshot.freeMemoryBytes)} detail={snapshot === null ? 'checking' : 'available'} divided />
            <StatTile label="NETWORK" value={snapshot?.wifiConnected ? 'Wi-Fi' : 'Offline'} detail={snapshot === null ? 'checking' : snapshot.wifiConnected ? 'connected' : 'no route'} divided />
            <StatTile label="BATTERY" value={snapshot === null ? '—' : `${Math.round(snapshot.batteryPercent)}%`} detail="device power" divided={false} />
          </View>

          <View testID="metro-harnesses">
            <Text style={styles.sectionLabel}>OPEN A SESSION</Text>
            <View style={styles.toolRow}>
              <AppTile logo="claude" title="Claude Code" onPress={() => onOpen({title: 'Claude Code', eyebrow: 'AI WORKBENCH', command: buildZshCommand('claude'), toolchain: 'claude'})} />
              <AppTile logo="codex" title="Codex" onPress={() => onOpen({title: 'Codex', eyebrow: 'AI WORKBENCH', command: buildZshCommand('codex'), toolchain: 'codex'})} />
            </View>
            <View style={styles.toolRow}>
              <AppTile logo="opencode" title="OpenCode" onPress={() => onOpen({title: 'OpenCode', eyebrow: 'AI WORKBENCH', command: buildZshScriptCommand('opencode'), toolchain: 'opencode'})} />
              <AppTile icon=">_" title="Bare terminal" onPress={() => onOpen({title: 'Bare terminal', eyebrow: 'SHELL', command: buildZshCommand('mkdir -p /workspace/projects && cd /workspace/projects && exec zsh -l'), toolchain: 'shell'})} />
            </View>
          </View>

          <Pressable accessibilityRole="button" onPress={() => onOpen({title: 'Files', eyebrow: 'WORKSPACE', kind: 'files'})} style={styles.filesTile} testID="metro-files">
            <View style={styles.filesCopy}>
              <Text numberOfLines={1} style={styles.filesTitle}>Browse your files</Text>
              <Text numberOfLines={1} style={styles.filesDetail}>Read only</Text>
            </View>
            <Text style={styles.filesArrow}>→</Text>
          </Pressable>
        </View>

        {recentSessions.length > 0 ? (
          <View style={styles.activeSessionsPanel} testID="metro-recents">
            <Text style={[styles.sectionLabel, styles.panelSectionLabel]}>ACTIVE SESSIONS</Text>
            {recentSessions.map(session => {
              const title = sessionTitle(session);
              const stopping = terminatingSessions.has(session.sessionId);
              return (
                <View key={session.sessionId} style={styles.recentSessionRow} testID={`metro-recent-${session.sessionId}`}>
                  <Pressable
                    accessibilityLabel={`Resume ${title}, running for ${formatSessionAge(session.startedAtMs)}`}
                    accessibilityRole="button"
                    disabled={onResumeSession === undefined}
                    onPress={() => onResumeSession?.(session)}
                    style={styles.recentSessionOpen}
                    testID={`metro-resume-${session.sessionId}`}>
                    <View style={styles.recentSessionCopy}>
                      <Text numberOfLines={1} style={styles.recentSessionTitle}>{title}</Text>
                      <Text numberOfLines={1} style={styles.recentSessionMeta}>ACTIVE PTY · {formatSessionAge(session.startedAtMs)}</Text>
                    </View>
                  </Pressable>
                  <Pressable
                    accessibilityLabel={`Terminate ${title}`}
                    accessibilityRole="button"
                    accessibilityState={{disabled: stopping}}
                    disabled={stopping || onTerminateSession === undefined}
                    onPress={() => { void terminateSession(session.sessionId); }}
                    style={[styles.terminateButton, stopping && styles.terminateButtonDisabled]}
                    testID={`metro-terminate-${session.sessionId}`}>
                    <Text style={styles.terminateButtonText}>{stopping ? 'STOPPING' : 'TERMINATE'}</Text>
                  </Pressable>
                </View>
              );
            })}
            {terminateError ? <Text style={styles.terminateError}>Could not stop the session. Try again.</Text> : null}
          </View>
        ) : null}

      </ScrollView>
      {accountIntro === undefined || user === undefined ? null : (
        <View collapsable={false} onLayout={measureIntro} ref={introRootRef} style={StyleSheet.absoluteFill} testID="metro-account-intro">
          <AccountCoachMark
            addableDistro={accountIntro.addableDistro}
            hole={introHole}
            onDismiss={accountIntro.onDismiss}
            onOpen={accountIntro.onOpen}
          />
        </View>
      )}
    </ScreenShell>
  );
}

type IntroHole = Readonly<{x: number; y: number; width: number; height: number}>;

const HOLE_PAD = 6;

/**
 * Dims Home except the user card, rings it, and points at it with a short
 * leader line and a note, like an app's first-run coach marks.
 */
function AccountCoachMark({hole, addableDistro, onOpen, onDismiss}: Readonly<{
  hole?: IntroHole;
  addableDistro?: LinuxDistro;
  onOpen: () => void;
  onDismiss: () => void;
}>): React.JSX.Element {
  const note = (
    <View style={styles.coachNote}>
      <Text style={styles.coachTitle}>This is you</Text>
      <Text style={styles.coachBody}>Horus now has Linux users. Tap your card to change your name and picture.</Text>
      {addableDistro === undefined ? null : (
        <Text style={styles.coachBody} testID="metro-account-intro-add">
          {addableDistro === 'debian'
            ? 'You can also create a Debian account there, for tools Alpine can’t run.'
            : 'You can also create an Alpine account there: small and fast.'}
        </Text>
      )}
      <View style={styles.coachActions}>
        <Pressable accessibilityRole="button" onPress={onDismiss} style={styles.coachLater} testID="metro-account-intro-dismiss">
          <Text style={styles.coachLaterText}>NOT NOW</Text>
        </Pressable>
        <Pressable accessibilityRole="button" onPress={onOpen} style={styles.coachOpen} testID="metro-account-intro-open">
          <Text style={styles.coachOpenText}>OPEN ACCOUNT</Text>
        </Pressable>
      </View>
    </View>
  );
  if (hole === undefined) {
    return <View style={[StyleSheet.absoluteFill, styles.coachDim, styles.coachCentered]}>{note}</View>;
  }
  const top = Math.max(0, hole.y - HOLE_PAD);
  const left = Math.max(0, hole.x - HOLE_PAD);
  const width = hole.width + HOLE_PAD * 2;
  const height = hole.height + HOLE_PAD * 2;
  const lineX = left + width / 2;
  return (
    <>
      <View style={[styles.coachDim, {height: top, left: 0, position: 'absolute', right: 0, top: 0}]} />
      <View style={[styles.coachDim, {bottom: 0, left: 0, position: 'absolute', right: 0, top: top + height}]} />
      <View style={[styles.coachDim, {height, left: 0, position: 'absolute', top, width: left}]} />
      <View style={[styles.coachDim, {height, left: left + width, position: 'absolute', right: 0, top}]} />
      <Pressable
        accessibilityLabel="Open Account"
        accessibilityRole="button"
        onPress={onOpen}
        style={[styles.coachRing, {height, left, top, width}]}
        testID="metro-account-intro-spot"
      />
      <View style={[styles.coachLine, {left: lineX - 1, top: top + height}]} />
      <View style={[styles.coachDot, {left: lineX - 5, top: top + height + 34}]} />
      <View style={[styles.coachNoteAt, {top: top + height + 50}]}>{note}</View>
    </>
  );
}

const styles = StyleSheet.create({
  content: {paddingHorizontal: 18, paddingBottom: 20},
  userSlot: {flex: 1, minWidth: 0},
  coachDim: {backgroundColor: 'rgba(0, 0, 0, 0.85)'},
  coachCentered: {justifyContent: 'center', padding: 24},
  coachRing: {borderColor: uiColors.accent, borderRadius: 16, borderWidth: 2, position: 'absolute'},
  coachLine: {backgroundColor: uiColors.ink, height: 34, position: 'absolute', width: 2},
  coachDot: {backgroundColor: uiColors.ink, borderRadius: 5, height: 10, position: 'absolute', width: 10},
  coachNoteAt: {left: 24, position: 'absolute', right: 24},
  coachNote: {alignSelf: 'stretch'},
  coachTitle: {color: uiColors.ink, fontFamily: UI_FONT_FAMILY, fontSize: 20, fontWeight: '800'},
  coachBody: {color: uiColors.ink, fontFamily: UI_FONT_FAMILY, fontSize: 13, lineHeight: 20, marginTop: 8, opacity: 0.85},
  coachActions: {flexDirection: 'row', gap: 10, marginTop: 20},
  coachLater: {alignItems: 'center', backgroundColor: uiColors.panel, borderColor: uiColors.border, borderRadius: 10, borderWidth: 1, flex: 1, justifyContent: 'center', minHeight: 46},
  coachLaterText: {color: uiColors.ink, fontFamily: UI_FONT_FAMILY, fontSize: 11, fontWeight: '800', letterSpacing: 0.6},
  coachOpen: {alignItems: 'center', backgroundColor: uiColors.accent, borderRadius: 10, flex: 1, justifyContent: 'center', minHeight: 46},
  coachOpenText: {color: uiColors.inset, fontFamily: UI_FONT_FAMILY, fontSize: 11, fontWeight: '900', letterSpacing: 0.6},
  menuPanel: {backgroundColor: uiColors.background, borderColor: uiColors.border, borderRadius: 14, borderWidth: 1, marginTop: 0, padding: 10},
  activeSessionsPanel: {backgroundColor: uiColors.background, borderColor: uiColors.border, borderRadius: 14, borderWidth: 1, marginTop: 12, padding: 10},
  panelSectionLabel: {marginTop: 0},
  // The signed-in user and GitHub share the first row, half each.
  identityRow: {flexDirection: 'row', gap: 10},
  halfTile: {flex: 1, minHeight: 76, minWidth: 0, padding: 10},
  halfTitle: {color: uiColors.ink, fontFamily: UI_FONT_FAMILY, fontSize: 16, fontWeight: '800'},
  halfAction: {borderRadius: 8, height: 48, width: 48},
  halfMark: {height: 28, width: 28},
  userCopy: {marginLeft: 10},
  userDistro: {color: uiColors.accent, marginTop: 4},
  githubTile: {flex: 1, alignItems: 'center', backgroundColor: uiColors.panel, borderColor: uiColors.border, borderRadius: 12, borderWidth: 1, flexDirection: 'row', marginTop: 0, minHeight: 98, padding: 12},
  githubCopy: {flex: 1, minWidth: 0},
  tileLabel: {color: uiColors.muted, fontFamily: UI_FONT_FAMILY, fontSize: 10, letterSpacing: 0.6},
  githubTitle: {color: uiColors.ink, fontFamily: UI_FONT_FAMILY, fontSize: 18, fontWeight: '800', marginTop: 6},
  githubDetail: {color: uiColors.muted, fontFamily: UI_FONT_FAMILY, fontSize: 10, lineHeight: 15, marginTop: 4},
  githubSeparator: {backgroundColor: uiColors.border, height: 72, marginHorizontal: 11, width: 1},
  githubAction: {alignItems: 'center', backgroundColor: uiColors.accent, borderRadius: 9, height: 64, justifyContent: 'center', width: 64},
  githubMark: {height: 36, width: 36},
  githubAvatar: {height: 50, width: 50},
  statsPanel: {backgroundColor: uiColors.panel, borderColor: uiColors.border, borderRadius: 12, borderWidth: 1, flexDirection: 'row', marginTop: 10, minHeight: 78, opacity: 0.65, overflow: 'hidden'},
  statTile: {flex: 1, justifyContent: 'space-between', minWidth: 0, paddingHorizontal: 7, paddingVertical: 10},
  statDivider: {borderRightColor: uiColors.border, borderRightWidth: 1},
  statText: {textAlign: 'center'},
  statValue: {color: uiColors.ink, fontFamily: UI_FONT_FAMILY, fontSize: 17, fontWeight: '800', marginTop: 6},
  tileDetail: {color: uiColors.subdued, fontFamily: UI_FONT_FAMILY, fontSize: 8, marginTop: 4},
  sectionLabel: {color: uiColors.muted, fontFamily: UI_FONT_FAMILY, fontSize: 11, letterSpacing: 0.9, marginBottom: 8, marginTop: 17},
  recentSessionRow: {alignItems: 'center', backgroundColor: uiColors.panel, borderColor: uiColors.border, borderRadius: 10, borderWidth: 1, flexDirection: 'row', marginBottom: 7, minHeight: 70, paddingHorizontal: 9, paddingVertical: 8},
  recentSessionOpen: {alignItems: 'center', flex: 1, flexDirection: 'row', minWidth: 0, paddingVertical: 5},
  recentSessionCopy: {flex: 1, minWidth: 0},
  recentSessionTitle: {color: uiColors.ink, fontFamily: UI_FONT_FAMILY, fontSize: 14, fontWeight: '800'},
  recentSessionMeta: {color: uiColors.muted, fontFamily: UI_FONT_FAMILY, fontSize: 8, letterSpacing: 0.2, marginTop: 5},
  terminateButton: {alignItems: 'center', borderColor: uiColors.danger, borderRadius: 6, borderWidth: 1, justifyContent: 'center', minHeight: 34, minWidth: 78, paddingHorizontal: 7},
  terminateButtonDisabled: {borderColor: uiColors.subdued, opacity: 0.7},
  terminateButtonText: {color: uiColors.danger, fontFamily: UI_FONT_FAMILY, fontSize: 8, fontWeight: '800', letterSpacing: 0.1},
  terminateError: {color: uiColors.danger, fontFamily: UI_FONT_FAMILY, fontSize: 9, marginBottom: 8},
  toolRow: {flexDirection: 'row', gap: 8, marginTop: 0, marginBottom: 8},
  appTile: {alignItems: 'center', backgroundColor: uiColors.panel, borderColor: uiColors.border, borderRadius: 12, borderWidth: 1, flex: 1, justifyContent: 'center', minHeight: 104, padding: 10},
  appIcon: {color: uiColors.ink, fontFamily: UI_FONT_FAMILY, fontSize: 34, fontWeight: '700', height: 42, lineHeight: 42, textAlign: 'center'},
  appLogo: {height: 42, width: 42},
  codexLogo: {tintColor: uiColors.ink},
  appTitle: {color: uiColors.ink, fontFamily: UI_FONT_FAMILY, fontSize: 11, fontWeight: '400', marginTop: 7},
  appDetail: {color: uiColors.subdued, fontFamily: UI_FONT_FAMILY, fontSize: 9, marginTop: 4},
  filesTile: {alignItems: 'center', backgroundColor: uiColors.panel, borderColor: uiColors.border, borderRadius: 12, borderWidth: 1, flexDirection: 'row', marginTop: 1, minHeight: 76, paddingHorizontal: 13},
  filesCopy: {flex: 1, minWidth: 0},
  filesTitle: {color: uiColors.ink, fontFamily: UI_FONT_FAMILY, fontSize: 14, fontWeight: '400'},
  filesDetail: {color: uiColors.subdued, fontFamily: UI_FONT_FAMILY, fontSize: 9, marginTop: 4},
  filesArrow: {color: uiColors.accent, fontFamily: UI_FONT_FAMILY, fontSize: 22, marginLeft: 10},
  settingsButton: {alignItems: 'center', borderColor: uiColors.border, borderRadius: 7, borderWidth: 1, justifyContent: 'center', minHeight: 32, paddingHorizontal: 8},
  settingsText: {color: uiColors.accent, fontFamily: UI_FONT_FAMILY, fontSize: 8, fontWeight: '800', letterSpacing: 0.3},
});
