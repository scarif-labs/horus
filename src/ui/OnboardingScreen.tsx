import React from 'react';
import {ActivityIndicator, Linking, StyleSheet, Text, View} from 'react-native';
import {DISTRO_NAMES, type LinuxDistro, type NewProfileUser} from '../profile/profileStore';
import {ALPINE_ROOTFS, DEBIAN_ROOTFS} from '../terminal/distroContract';
import {ALPINE_MIRRORS, npmRegistryFor, rootfsUrl, type DownloadSources} from '../terminal/downloadSources';
import {AccountForm, useAccountDraft} from './AccountForm';
import {AuthScreenLayout, authStyles} from './AuthScreenLayout';
import {uiColors} from './brand';
import {EntryIcon} from './EntryIcon';
import {InteractivePressable as Pressable} from './InteractivePressable';
import {UI_FONT_FAMILY} from './typography';
import {MirrorPicker} from './MirrorPicker';
import {WelcomeDemo} from './WelcomeDemo';
import {TerminalControls, type TerminalArrow} from '../terminal/TerminalControls';
import {useHardwareBack} from './useHardwareBack';
import {
  DEFAULT_BACKGROUND_PERMISSION_ACTIONS,
  useBackgroundPermissions,
  type BackgroundPermissionActions,
} from './BackgroundPermissions';

export type OnboardingPermissions = BackgroundPermissionActions;

export type RootfsSetupResult = Readonly<{kind: 'success'}> | Readonly<{kind: 'error'; errorCode: string}>;
export type ProfileSetupResult = Readonly<{kind: 'success'}> | Readonly<{kind: 'error'; stage: 'alpine-tools' | 'profile'}>;

export type OnboardingActions = Readonly<{
  /** Downloads and installs the pinned rootfs of [distro], and makes it active. */
  installRootfs: (distro: LinuxDistro) => Promise<RootfsSetupResult>;
  /** Installs a rootfs archive for [distro] the user picks from storage. */
  importRootfs: (distro: LinuxDistro) => Promise<RootfsSetupResult>;
  /** The latest package progress while tools install, e.g. "Installing packages… 64%". */
  readToolsProgress?: () => Promise<string | undefined>;
  /** Installs the shell's tools (unless skipped) and creates the user. */
  saveProfile: (user: NewProfileUser, options: Readonly<{skipTools: boolean; onToolsReady: () => void}>) => Promise<ProfileSetupResult>;
  readDownloadSources: () => Promise<DownloadSources>;
  /** Resolves false when the sources could not be saved. */
  saveDownloadSources: (sources: DownloadSources) => Promise<boolean>;
  /** Leaves onboarding for the home screen. */
  done: () => void;
}>;

export type OnboardingScreenProps = Readonly<{
  /** True when Alpine is already installed and active, e.g. after a profile reset. */
  rootfsInstalled: boolean;
  actions: OnboardingActions;
  permissions?: OnboardingPermissions;
}>;

type OnboardingStep = 'welcome' | 'distro' | 'account' | 'permissions' | 'setup' | 'keys';
const STEPS: readonly OnboardingStep[] = ['welcome', 'distro', 'account', 'permissions', 'setup', 'keys'];

type RootfsState =
  | Readonly<{kind: 'idle' | 'ready'}>
  | Readonly<{kind: 'working'; source: 'download' | 'import'}>
  | Readonly<{kind: 'failed'; errorCode: string}>;

/**
 * First run, in six steps: what Horus is, Alpine or Debian, the user
 * (hieroglyph, username, password), background permissions, installing
 * Linux, and how to use the terminal keys. The download starts as soon as
 * the user picks a distribution, so it runs while they fill in the rest.
 */
export function OnboardingScreen({rootfsInstalled, actions, permissions = DEFAULT_BACKGROUND_PERMISSION_ACTIONS}: OnboardingScreenProps): React.JSX.Element {
  const [step, setStep] = React.useState<OnboardingStep>('welcome');
  const [distro, setDistro] = React.useState<LinuxDistro>('alpine');
  const [user, setUser] = React.useState<NewProfileUser | undefined>();
  const account = useAccountDraft();
  // An Alpine left over from a profile reset counts as installed; any other choice installs.
  const [rootfs, setRootfs] = React.useState<RootfsState>({kind: 'idle'});
  const rootfsRef = React.useRef(rootfs);
  const mountedRef = React.useRef(true);
  React.useEffect(() => {
    mountedRef.current = true;
    return () => { mountedRef.current = false; };
  }, []);

  const runRootfs = React.useCallback((source: 'download' | 'import', target: LinuxDistro) => {
    const current = rootfsRef.current;
    if (current.kind === 'working' || current.kind === 'ready') return;
    const update = (next: RootfsState) => {
      rootfsRef.current = next;
      if (mountedRef.current) setRootfs(next);
    };
    if (source === 'download' && target === 'alpine' && rootfsInstalled) {
      update({kind: 'ready'});
      return;
    }
    update({kind: 'working', source});
    const run = source === 'download' ? actions.installRootfs : actions.importRootfs;
    run(target).catch((): RootfsSetupResult => ({kind: 'error', errorCode: 'internal_error'})).then(result => {
      if (result.kind === 'success') {
        update({kind: 'ready'});
      } else if (result.errorCode === 'import_cancelled' && current.kind === 'failed') {
        // Backing out of the file picker keeps the reason the download failed.
        update(current);
      } else {
        update({kind: 'failed', errorCode: result.errorCode});
      }
    });
  }, [actions.importRootfs, actions.installRootfs, rootfsInstalled]);

  const chooseDistro = React.useCallback(() => {
    runRootfs('download', distro);
    setStep('account');
  }, [distro, runRootfs]);

  const submitAccount = React.useCallback(() => {
    const draft = account.validate();
    if (draft === undefined) return;
    setUser({...draft, distro});
    setStep('permissions');
  }, [account, distro]);

  const goBack = React.useCallback(() => {
    if (step === 'distro') setStep('welcome');
    // The download for the chosen distribution has started; it cannot switch now.
    else if (step === 'account') return true;
    else if (step === 'permissions') setStep('account');
    else return false;
    return true;
  }, [step]);
  useHardwareBack(true, goBack);

  const stepNumber = STEPS.indexOf(step) + 1;
  switch (step) {
    case 'welcome':
      return <WelcomeStep onContinue={() => setStep('distro')} />;
    case 'distro':
      return <DistroStep distro={distro} onChoose={setDistro} onContinue={chooseDistro} step={stepNumber} />;
    case 'account':
      return (
        <AuthScreenLayout brandTestID="setup" footer={<PrimaryButton label="CONTINUE  →" onPress={submitAccount} testID="profile-continue" />} pinned screenTestID="onboarding-account">
          <View style={styles.step}>
            <StepHeader detail="Agents can use your code and accounts. Your password keeps them safe if someone picks up your phone." step={stepNumber} title="Create your user" />
            <AccountForm distroName={DISTRO_NAMES[distro]} draft={account} onSubmit={submitAccount} />
          </View>
        </AuthScreenLayout>
      );
    case 'permissions':
      return <PermissionsStep onContinue={() => setStep('setup')} permissions={permissions} step={stepNumber} />;
    case 'setup':
      return user === undefined ? <WelcomeStep onContinue={() => setStep('account')} /> : (
        <SetupStep actions={actions} distro={distro} onDone={() => setStep('keys')} onRootfs={source => runRootfs(source, distro)} rootfs={rootfs} step={stepNumber} user={user} />
      );
    case 'keys':
      return <KeysStep onDone={actions.done} step={stepNumber} />;
  }
}

type DistroOption = Readonly<{distro: LinuxDistro; tagline: string; facts: string; badge?: string}>;

const DISTRO_OPTIONS: readonly DistroOption[] = [
  {distro: 'alpine', tagline: 'Small and fast.', facts: `${Math.round(ALPINE_ROOTFS.sizeBytes / 1e6)} MB · musl · apk`},
  {distro: 'debian', tagline: 'Runs more prebuilt tools.', facts: `${Math.round(DEBIAN_ROOTFS.sizeBytes / 1e6)} MB · glibc · apt`, badge: 'EXPERIMENTAL'},
];

/** Alpine or Debian. Alpine is chosen unless the user picks Debian. */
function DistroStep({step, distro, onChoose, onContinue}: Readonly<{step: number; distro: LinuxDistro; onChoose: (distro: LinuxDistro) => void; onContinue: () => void}>): React.JSX.Element {
  return (
    <AuthScreenLayout brandTestID="setup" footer={<PrimaryButton label="CONTINUE  →" onPress={onContinue} testID="distro-continue" />} pinned screenTestID="onboarding-distro">
      <View style={styles.step}>
        <StepHeader detail="You can add a user on the other one later, in Settings." step={step} title="Choose your Linux" />
        <View accessibilityRole="radiogroup" style={styles.distros}>
          {DISTRO_OPTIONS.map(option => {
            const selected = option.distro === distro;
            return (
              <Pressable
                key={option.distro}
                accessibilityLabel={`${DISTRO_NAMES[option.distro]}. ${option.tagline} ${option.facts}`}
                accessibilityRole="radio"
                accessibilityState={{checked: selected}}
                onPress={() => onChoose(option.distro)}
                style={[styles.distro, selected && styles.distroSelected]}
                testID={`distro-${option.distro}${selected ? '-selected' : ''}`}>
                <View style={styles.distroHead}>
                  <Text style={styles.distroName}>{DISTRO_NAMES[option.distro]}</Text>
                  {option.badge === undefined ? null : <Text style={styles.distroBadge}>{option.badge}</Text>}
                  <View style={[styles.checkMark, selected && styles.checkMarkOn]}>
                    {selected ? <EntryIcon kind="check" size={14} style={styles.checkIcon} /> : null}
                  </View>
                </View>
                <Text style={styles.distroTagline}>{option.tagline}</Text>
                <Text style={styles.distroFacts}>{option.facts}</Text>
              </Pressable>
            );
          })}
        </View>
      </View>
    </AuthScreenLayout>
  );
}

/** "STEP 2 OF 5" with one dot per step. */
function StepHeader({step, title, detail}: Readonly<{step: number; title: string; detail?: string}>): React.JSX.Element {
  return (
    <View style={styles.header}>
      <View accessibilityLabel={`Step ${step} of ${STEPS.length}`} style={styles.dots} testID="onboarding-step">
        {STEPS.map((name, index) => (
          <View key={name} style={[styles.dot, index < step && styles.dotDone, index === step - 1 && styles.dotCurrent]} />
        ))}
      </View>
      <Text style={styles.title}>{title}</Text>
      {detail === undefined ? null : <Text style={styles.detail}>{detail}</Text>}
    </View>
  );
}

function PrimaryButton({label, onPress, testID, disabled = false}: Readonly<{label: string; onPress: () => void; testID: string; disabled?: boolean}>): React.JSX.Element {
  return (
    <Pressable accessibilityRole="button" accessibilityState={{disabled}} disabled={disabled} onPress={onPress} style={[authStyles.button, disabled && authStyles.disabled]} testID={testID}>
      <Text style={authStyles.buttonText}>{label}</Text>
    </Pressable>
  );
}

function SecondaryButton({label, onPress, testID}: Readonly<{label: string; onPress: () => void; testID: string}>): React.JSX.Element {
  return (
    <Pressable accessibilityRole="button" onPress={onPress} style={styles.secondary} testID={testID}>
      <Text style={styles.secondaryText}>{label}</Text>
    </Pressable>
  );
}

function WelcomeStep({onContinue}: Readonly<{onContinue: () => void}>): React.JSX.Element {
  return (
    <AuthScreenLayout brandTestID="setup" footer={<PrimaryButton label="GET STARTED  →" onPress={onContinue} testID="welcome-continue" />} pinned screenTestID="onboarding-welcome">
      <View style={styles.step}>
        <Text style={styles.welcomeTitle}>Coding agents in your pocket</Text>
        <Text style={styles.welcomeDetail}>A real Linux terminal. No computer needed.</Text>
        <View style={styles.demo}>
          <WelcomeDemo />
        </View>
      </View>
    </AuthScreenLayout>
  );
}

type PermissionsStepProps = Readonly<{
  step: number;
  permissions: OnboardingPermissions;
  onContinue: () => void;
}>;

/** Without these, Android pauses agents in the background and the user never hears that a session needs them. */
function PermissionsStep({step, permissions, onContinue}: PermissionsStepProps): React.JSX.Element {
  const state = useBackgroundPermissions(permissions);
  const notificationsAllowed = state.granted?.notifications === true;
  const batteryAllowed = state.granted?.batteryUnrestricted === true;
  // One button walks through whatever is still missing, then continues.
  const primary = state.allGranted
    ? onContinue
    : !notificationsAllowed ? state.requestNotifications : state.requestBattery;
  const footer = (
    <>
      <PrimaryButton label={state.allGranted ? 'CONTINUE  →' : 'ALLOW'} onPress={primary} testID="permissions-continue" />
      {state.allGranted ? null : (
        <Pressable accessibilityRole="button" onPress={onContinue} style={styles.skip} testID="permissions-skip">
          <Text style={styles.skipText}>Not now</Text>
        </Pressable>
      )}
    </>
  );
  return (
    <AuthScreenLayout brandTestID="setup" footer={footer} pinned screenTestID="onboarding-permissions">
      <View style={styles.step}>
        <StepHeader detail="So agents keep running with the screen off." step={step} title="Keep your agents running" />
        <View style={styles.checklist}>
          <PermissionCheck allowed={notificationsAllowed} detail="When an agent finishes or needs you." icon="bell" label="Notifications" onPress={state.requestNotifications} testID="permission-notifications" />
          <PermissionCheck allowed={batteryAllowed} detail="So Android doesn’t pause agents." icon="battery" label="Unrestricted battery" onPress={state.requestBattery} testID="permission-battery" />
        </View>
      </View>
    </AuthScreenLayout>
  );
}

type PermissionCheckProps = Readonly<{allowed: boolean; detail: string; icon: 'bell' | 'battery'; label: string; onPress: () => void; testID: string}>;

/** One permission: icon, name, and a check once Android reports it allowed. */
function PermissionCheck({allowed, detail, icon, label, onPress, testID}: PermissionCheckProps): React.JSX.Element {
  return (
    <Pressable
      accessibilityLabel={allowed ? `${label}, allowed` : `Allow ${label.toLowerCase()}`}
      accessibilityRole="button"
      accessibilityState={{checked: allowed, disabled: allowed}}
      disabled={allowed}
      onPress={onPress}
      style={styles.check}
      testID={allowed ? `${testID}-granted` : `${testID}-allow`}>
      <EntryIcon kind={icon} size={20} tint={allowed ? uiColors.accent : uiColors.ink} />
      <View style={styles.checkBody}>
        <Text style={styles.checkLabel}>{label}</Text>
        <Text style={styles.checkDetail}>{detail}</Text>
      </View>
      <View style={[styles.checkMark, allowed && styles.checkMarkOn]}>
        {allowed ? <EntryIcon kind="check" size={14} style={styles.checkIcon} /> : null}
      </View>
    </Pressable>
  );
}

type ProfileState =
  | Readonly<{kind: 'idle' | 'tools' | 'saving'}>
  | Readonly<{kind: 'failed'; stage: 'alpine-tools' | 'profile'}>;

type SetupStepProps = Readonly<{
  step: number;
  distro: LinuxDistro;
  user: NewProfileUser;
  rootfs: RootfsState;
  actions: OnboardingActions;
  onRootfs: (source: 'download' | 'import') => void;
  onDone: () => void;
}>;

/** Waits for Linux, then installs the shell's tools and creates the user. */
function SetupStep({step, distro, user, rootfs, actions, onRootfs, onDone}: SetupStepProps): React.JSX.Element {
  const [profile, setProfile] = React.useState<ProfileState>({kind: 'idle'});
  const [mirror, setMirror] = React.useState<string | undefined>();
  const runningRef = React.useRef(false);
  const mountedRef = React.useRef(true);
  React.useEffect(() => {
    mountedRef.current = true;
    return () => { mountedRef.current = false; };
  }, []);

  const saveProfile = React.useCallback((skipTools: boolean) => {
    if (runningRef.current) return;
    runningRef.current = true;
    setProfile({kind: skipTools ? 'saving' : 'tools'});
    actions.saveProfile(user, {skipTools, onToolsReady: () => { if (mountedRef.current) setProfile({kind: 'saving'}); }})
      .catch((): ProfileSetupResult => ({kind: 'error', stage: 'profile'}))
      .then(result => {
        runningRef.current = false;
        if (!mountedRef.current) return;
        if (result.kind === 'success') onDone();
        else setProfile({kind: 'failed', stage: result.stage});
      });
  }, [actions, onDone, user]);

  React.useEffect(() => {
    actions.readDownloadSources().then(sources => { if (mountedRef.current) setMirror(sources.alpineMirror); }).catch(() => undefined);
  }, [actions]);

  // A mirror chosen here also sets the matching npm registry, then retries.
  const chooseMirror = React.useCallback((url: string | undefined) => {
    actions.saveDownloadSources({alpineMirror: url, npmRegistry: npmRegistryFor(url)}).then(saved => {
      if (!saved || !mountedRef.current) return;
      setMirror(url);
      onRootfs('download');
    }).catch(() => undefined);
  }, [actions, onRootfs]);

  // Start the download if it never began, and carry on once Alpine is in.
  React.useEffect(() => {
    if (rootfs.kind === 'idle') onRootfs('download');
    if (rootfs.kind === 'ready' && profile.kind === 'idle') saveProfile(false);
  }, [onRootfs, profile.kind, rootfs.kind, saveProfile]);

  // Poll the package manager's progress while the tools install.
  const [toolsProgress, setToolsProgress] = React.useState<string | undefined>();
  const toolsRunning = rootfs.kind === 'ready' && (profile.kind === 'tools' || profile.kind === 'idle');
  React.useEffect(() => {
    const read = actions.readToolsProgress;
    if (!toolsRunning || read === undefined) {
      setToolsProgress(undefined);
      return undefined;
    }
    const timer = setInterval(() => {
      read().then(line => { if (mountedRef.current) setToolsProgress(line); }).catch(() => undefined);
    }, 1000);
    return () => clearInterval(timer);
  }, [actions.readToolsProgress, toolsRunning]);

  const linuxStatus: TaskStatus = rootfs.kind === 'ready' ? 'done' : rootfs.kind === 'failed' ? 'failed' : 'working';
  const toolsStatus: TaskStatus = rootfs.kind !== 'ready' ? 'waiting'
    : profile.kind === 'tools' || profile.kind === 'idle' ? 'working'
    : profile.kind === 'failed' && profile.stage === 'alpine-tools' ? 'failed' : 'done';
  const saveStatus: TaskStatus = profile.kind === 'saving' ? 'working'
    : profile.kind === 'failed' && profile.stage === 'profile' ? 'failed' : 'waiting';
  const linuxLabel = rootfs.kind === 'working' && rootfs.source === 'import' ? `Installing ${DISTRO_NAMES[distro]} from your file` : `Downloading ${DISTRO_NAMES[distro]}`;

  return (
    <AuthScreenLayout brandTestID="setup" pinned screenTestID="onboarding-setup">
      <View style={styles.step}>
        <StepHeader detail="This can take a few minutes." step={step} title="Installing Linux" />
        <View accessibilityLiveRegion="polite" style={styles.tasks}>
          <SetupTask label={linuxLabel} status={linuxStatus} testID="setup-task-linux" />
          <SetupTask detail={toolsStatus === 'working' ? toolsProgress : undefined} label="Installing tools" status={toolsStatus} testID="setup-task-tools" />
          <SetupTask label={`Creating ${user.username}`} status={saveStatus} testID="setup-task-profile" />
        </View>
        {rootfs.kind === 'failed' ? <RootfsFailure distro={distro} errorCode={rootfs.errorCode} mirror={mirror} onImport={() => onRootfs('import')} onMirror={chooseMirror} onRetry={() => onRootfs('download')} /> : null}
        {profile.kind === 'failed' && profile.stage === 'alpine-tools' ? (
          <View style={styles.problem} testID="setup-tools-failed">
            <Text style={styles.problemTitle}>Tools need the internet</Text>
            <Text style={styles.problemDetail}>Skip for now and they’ll install when you first open a terminal.</Text>
            <PrimaryButton label="TRY AGAIN" onPress={() => saveProfile(false)} testID="setup-tools-retry" />
            <SecondaryButton label="SKIP FOR NOW" onPress={() => saveProfile(true)} testID="setup-tools-skip" />
          </View>
        ) : null}
        {profile.kind === 'failed' && profile.stage === 'profile' ? (
          <View style={styles.problem} testID="setup-profile-failed">
            <Text style={styles.problemTitle}>Your user couldn’t be created</Text>
            <Text style={styles.problemDetail}>Please try again.</Text>
            <PrimaryButton label="TRY AGAIN" onPress={() => saveProfile(true)} testID="setup-profile-retry" />
          </View>
        ) : null}
      </View>
    </AuthScreenLayout>
  );
}

type TaskStatus = 'waiting' | 'working' | 'done' | 'failed';

function SetupTask({label, detail, status, testID}: Readonly<{label: string; detail?: string; status: TaskStatus; testID: string}>): React.JSX.Element {
  return (
    <View accessibilityLabel={`${label}, ${detail ?? status}`} style={styles.task} testID={`${testID}-${status}`}>
      <View style={styles.taskIcon}>
        {status === 'working' ? <ActivityIndicator color={uiColors.accent} size="small" />
          : status === 'done' ? <View style={[styles.checkMark, styles.checkMarkOn]}><EntryIcon kind="check" size={14} style={styles.checkIcon} /></View>
          : <View style={[styles.taskDot, status === 'failed' && styles.taskDotFailed]} />}
      </View>
      <View style={styles.taskBody}>
        <Text style={[styles.taskLabel, status === 'waiting' && styles.taskLabelWaiting, status === 'failed' && styles.taskLabelFailed]}>{label}</Text>
        {detail === undefined ? null : <Text style={styles.taskDetail} testID={`${testID}-detail`}>{detail}</Text>}
      </View>
    </View>
  );
}

/** Offline or a bad file: retry, or pick an archive downloaded elsewhere. */
type RootfsFailureProps = Readonly<{
  distro: LinuxDistro;
  errorCode: string;
  mirror: string | undefined;
  onMirror: (url: string | undefined) => void;
  onRetry: () => void;
  onImport: () => void;
}>;

/** Offline, blocked or a bad file: try a mirror, retry, or pick an archive downloaded elsewhere. */
function RootfsFailure({distro, errorCode, mirror, onMirror, onRetry, onImport}: RootfsFailureProps): React.JSX.Element {
  const badFile = errorCode === 'import_failed' || errorCode === 'digest_mismatch';
  const offline = errorCode === 'download_failed' || errorCode === 'import_cancelled';
  // Mirrors serve Alpine only; Debian's pinned archive has one home.
  const alpine = distro === 'alpine';
  const url = alpine ? rootfsUrl(mirror) : DEBIAN_ROOTFS.url;
  return (
    <View style={styles.problem} testID="setup-linux-failed">
      <Text style={styles.problemTitle}>{badFile ? 'Wrong file' : offline ? 'Couldn’t download Linux' : 'Linux couldn’t be installed'}</Text>
      <Text style={styles.problemDetail}>
        {badFile
          ? 'Its checksum didn’t match. Download it again from the link below.'
          : offline
            ? alpine ? 'Check your connection, or try a mirror closer to you.' : 'Check your connection and try again.'
            : errorCode === 'runtime_busy'
              ? 'Linux is in use. Close its terminals and turn off SSH access, then try again.'
              : `Something went wrong (${errorCode}).`}
      </Text>
      {alpine ? (
        <>
          <Text style={styles.problemLabel}>DOWNLOAD FROM</Text>
          <MirrorPicker onSelect={onMirror} options={ALPINE_MIRRORS} placeholder="https://mirror.example/alpine" testID="setup-mirror" value={mirror} />
        </>
      ) : null}
      <PrimaryButton label="TRY AGAIN" onPress={onRetry} testID="setup-linux-retry" />
      <Text style={[styles.problemLabel, styles.problemLabelGap]}>OR USE THE FILE</Text>
      <Text selectable style={styles.url} testID="setup-rootfs-url">{url}</Text>
      <SecondaryButton label="OPEN LINK IN BROWSER ↗" onPress={() => { void Linking.openURL(url).catch(() => undefined); }} testID="setup-linux-open-url" />
      <SecondaryButton label="CHOOSE DOWNLOADED FILE" onPress={onImport} testID="setup-linux-import" />
    </View>
  );
}

type KeyInfo = Readonly<{label: string; detail: string}>;

const KEY_INFO = {
  esc: {label: 'ESC', detail: 'Interrupts an agent or closes a menu.'},
  slash: {label: '/', detail: 'Starts a slash command, like /help.'},
  dash: {label: '―', detail: 'A dash, for command options.'},
  keyboard: {label: 'SHOW', detail: 'Shows or hides the keyboard.'},
  up: {label: '↑', detail: 'Moves up a menu, or back through earlier commands.'},
  down: {label: '↓', detail: 'Moves down a menu.'},
  left: {label: '←', detail: 'Moves the cursor left.'},
  right: {label: '→', detail: 'Moves the cursor right.'},
  paste: {label: 'PASTE', detail: 'Pastes from the clipboard, like a sign-in code.'},
  tab: {label: 'TAB', detail: 'Completes file and command names.'},
  ctrl: {label: 'CTRL', detail: 'Applies to the next key. CTRL then C stops a command.'},
  alt: {label: 'ALT', detail: 'Applies to the next key, for shortcuts.'},
  return: {label: 'RETURN', detail: 'Sends what you typed.'},
} satisfies Record<string, KeyInfo>;

const ARROW_INFO: Readonly<Record<TerminalArrow, KeyInfo>> = {A: KEY_INFO.up, B: KEY_INFO.down, C: KEY_INFO.right, D: KEY_INFO.left};
const TERMINAL_KEY_INFO: Readonly<Record<string, KeyInfo>> = {'/': KEY_INFO.slash, '-': KEY_INFO.dash, '\t': KEY_INFO.tab};

const GESTURES: readonly Readonly<{glyph: string; label: string}>[] = [
  {glyph: '↕', label: 'Swipe the terminal to scroll'},
  {glyph: '↗', label: 'Tap a link to open it'},
  {glyph: '←', label: 'Back keeps the agent running'},
];

/** The terminal's real key row, to try out: each tap explains that key. */
function KeysStep({step, onDone}: Readonly<{step: number; onDone: () => void}>): React.JSX.Element {
  const [info, setInfo] = React.useState<KeyInfo | undefined>();
  const [ctrl, setCtrl] = React.useState(false);
  const [alt, setAlt] = React.useState(false);
  const [keyboard, setKeyboard] = React.useState(false);
  const show = React.useCallback((next: KeyInfo) => {
    setCtrl(false);
    setAlt(false);
    setInfo(next);
  }, []);
  const noop = React.useCallback(() => undefined, []);

  return (
    <AuthScreenLayout brandTestID="setup" footer={<PrimaryButton label="START USING HORUS  →" onPress={onDone} testID="onboarding-done" />} pinned screenTestID="onboarding-keys">
      <View style={styles.step}>
        <StepHeader detail="Your phone’s missing keys sit above the keyboard. Try them." step={step} title="Terminal keys" />
        <View accessibilityLiveRegion="polite" style={styles.keyInfo} testID="onboarding-key-card">
          <Text style={[styles.keyInfoLabel, info === undefined && styles.keyInfoPrompt]} testID="onboarding-key-label">{info?.label ?? 'TAP A KEY'}</Text>
          <Text numberOfLines={2} style={styles.keyInfoDetail} testID="onboarding-key-detail">{info?.detail ?? 'See what each one does.'}</Text>
        </View>
        <View style={styles.keyRow}>
          <TerminalControls
            altActive={alt}
            ctrlActive={ctrl}
            keyboardVisible={keyboard}
            onArrow={direction => show(ARROW_INFO[direction])}
            onEscape={() => show(KEY_INFO.esc)}
            onKeyboardToggle={() => { setKeyboard(value => !value); show(KEY_INFO.keyboard); }}
            onPaste={() => show(KEY_INFO.paste)}
            onReturn={() => show(KEY_INFO.return)}
            onTerminalKey={value => { const next = TERMINAL_KEY_INFO[value]; if (next !== undefined) show(next); }}
            onToggleAlt={() => { setAlt(value => !value); setCtrl(false); setInfo(KEY_INFO.alt); }}
            onToggleCtrl={() => { setCtrl(value => !value); setAlt(false); setInfo(KEY_INFO.ctrl); }}
            onToggleTranscriptPager={noop}
            running
            toolchain="shell"
            transcriptPagerOpen={false} />
        </View>
        <View style={styles.gestures}>
          {GESTURES.map(gesture => (
            <View key={gesture.label} style={styles.gesture}>
              <Text style={styles.gestureGlyph}>{gesture.glyph}</Text>
              <Text style={styles.gestureLabel}>{gesture.label}</Text>
            </View>
          ))}
        </View>
      </View>
    </AuthScreenLayout>
  );
}

const styles = StyleSheet.create({
  step: {alignSelf: 'center', maxWidth: 420, width: '100%'},
  header: {alignItems: 'center', marginBottom: 20},
  dots: {flexDirection: 'row', gap: 6, marginBottom: 14},
  dot: {backgroundColor: uiColors.borderSoft, borderRadius: 3, height: 6, width: 6},
  dotDone: {backgroundColor: uiColors.accent},
  dotCurrent: {width: 18},
  title: {color: uiColors.ink, fontFamily: UI_FONT_FAMILY, fontSize: 18, fontWeight: '800', textAlign: 'center'},
  detail: {color: uiColors.muted, fontFamily: UI_FONT_FAMILY, fontSize: 11, lineHeight: 17, marginTop: 8, textAlign: 'center'},
  welcomeTitle: {color: uiColors.ink, fontFamily: UI_FONT_FAMILY, fontSize: 18, fontWeight: '800', textAlign: 'center'},
  welcomeDetail: {color: uiColors.muted, fontFamily: UI_FONT_FAMILY, fontSize: 11, marginTop: 8, textAlign: 'center'},
  demo: {marginBottom: 8, marginTop: 24},
  distros: {gap: 12},
  distro: {backgroundColor: uiColors.panel, borderColor: uiColors.border, borderRadius: 12, borderWidth: 1, padding: 16},
  distroSelected: {backgroundColor: uiColors.accentSurface, borderColor: uiColors.accent},
  distroHead: {alignItems: 'center', flexDirection: 'row', gap: 10},
  distroName: {color: uiColors.ink, flex: 1, fontFamily: UI_FONT_FAMILY, fontSize: 15, fontWeight: '800'},
  distroBadge: {borderColor: uiColors.warning, borderRadius: 5, borderWidth: 1, color: uiColors.warning, fontFamily: UI_FONT_FAMILY, fontSize: 8, fontWeight: '800', letterSpacing: 0.6, paddingHorizontal: 6, paddingVertical: 2},
  distroTagline: {color: uiColors.ink, fontFamily: UI_FONT_FAMILY, fontSize: 12, marginTop: 8},
  distroFacts: {color: uiColors.muted, fontFamily: UI_FONT_FAMILY, fontSize: 10, marginTop: 4},
  secondary: {alignItems: 'center', borderColor: uiColors.border, borderRadius: 8, borderWidth: 1, justifyContent: 'center', marginTop: 10, minHeight: 46},
  secondaryText: {color: uiColors.ink, fontFamily: UI_FONT_FAMILY, fontSize: 10, fontWeight: '800', letterSpacing: 0.5},
  checklist: {borderBottomColor: uiColors.borderSoft, borderBottomWidth: 1, marginTop: 4},
  check: {alignItems: 'center', borderTopColor: uiColors.borderSoft, borderTopWidth: 1, flexDirection: 'row', gap: 14, minHeight: 64, paddingVertical: 10},
  checkBody: {flex: 1},
  checkLabel: {color: uiColors.ink, fontFamily: UI_FONT_FAMILY, fontSize: 13},
  checkDetail: {color: uiColors.muted, fontFamily: UI_FONT_FAMILY, fontSize: 10, lineHeight: 15, marginTop: 2},
  checkMark: {alignItems: 'center', borderColor: uiColors.border, borderRadius: 11, borderWidth: 1.5, height: 22, justifyContent: 'center', width: 22},
  checkMarkOn: {backgroundColor: uiColors.accent, borderColor: uiColors.accent},
  checkIcon: {marginRight: 0},
  skip: {alignItems: 'center', justifyContent: 'center', marginTop: 6, minHeight: 44},
  skipText: {color: uiColors.muted, fontFamily: UI_FONT_FAMILY, fontSize: 11},
  tasks: {borderBottomColor: uiColors.borderSoft, borderBottomWidth: 1},
  task: {alignItems: 'center', borderTopColor: uiColors.borderSoft, borderTopWidth: 1, flexDirection: 'row', gap: 14, minHeight: 52},
  taskIcon: {alignItems: 'center', justifyContent: 'center', width: 22},
  taskDot: {borderColor: uiColors.border, borderRadius: 11, borderWidth: 1.5, height: 22, width: 22},
  taskDotFailed: {backgroundColor: uiColors.danger, borderColor: uiColors.danger},
  taskBody: {flex: 1},
  taskLabel: {color: uiColors.ink, fontFamily: UI_FONT_FAMILY, fontSize: 12},
  taskDetail: {color: uiColors.muted, fontFamily: UI_FONT_FAMILY, fontSize: 10, marginTop: 3},
  taskLabelWaiting: {color: uiColors.subdued},
  taskLabelFailed: {color: uiColors.danger},
  problem: {marginTop: 20},
  problemTitle: {color: uiColors.warning, fontFamily: UI_FONT_FAMILY, fontSize: 13, fontWeight: '800'},
  problemLabel: {color: uiColors.muted, fontFamily: UI_FONT_FAMILY, fontSize: 9, letterSpacing: 0.9, marginBottom: 4, marginTop: 16},
  problemLabelGap: {marginTop: 24},
  problemDetail: {color: uiColors.muted, fontFamily: UI_FONT_FAMILY, fontSize: 11, lineHeight: 17, marginTop: 6},
  url: {backgroundColor: uiColors.background, borderColor: uiColors.borderSoft, borderRadius: 8, borderWidth: 1, color: uiColors.ink, fontFamily: UI_FONT_FAMILY, fontSize: 10, lineHeight: 16, marginTop: 10, padding: 10},
  keyInfo: {alignItems: 'center', backgroundColor: uiColors.panel, borderColor: uiColors.border, borderRadius: 12, borderWidth: 1, justifyContent: 'center', height: 116, paddingHorizontal: 16},
  keyInfoLabel: {color: uiColors.accent, fontFamily: UI_FONT_FAMILY, fontSize: 22, fontWeight: '800', lineHeight: 30},
  keyInfoPrompt: {color: uiColors.subdued, fontSize: 13, letterSpacing: 0.8},
  // Room for two lines whether a key's note takes one or two, so the box
  // and the key row below never move.
  keyInfoDetail: {color: uiColors.ink, fontFamily: UI_FONT_FAMILY, fontSize: 11, height: 34, lineHeight: 17, marginTop: 8, textAlign: 'center', textAlignVertical: 'center'},
  keyRow: {borderColor: uiColors.border, borderRadius: 10, borderWidth: 1, marginTop: 14, overflow: 'hidden'},
  gestures: {marginTop: 16},
  gesture: {alignItems: 'center', flexDirection: 'row', gap: 12, minHeight: 28},
  gestureGlyph: {color: uiColors.accent, fontFamily: UI_FONT_FAMILY, fontSize: 13, textAlign: 'center', width: 18},
  gestureLabel: {color: uiColors.muted, fontFamily: UI_FONT_FAMILY, fontSize: 11},
});
