import React from 'react';
import {ActivityIndicator, Linking, StyleSheet, Text, TextInput, View} from 'react-native';
import {PINNED_ROOTFS_URL} from '../terminal/distroContract';
import {AuthScreenLayout, authStyles} from './AuthScreenLayout';
import {uiColors} from './brand';
import {EntryIcon} from './EntryIcon';
import {InteractivePressable as Pressable} from './InteractivePressable';
import {UI_FONT_FAMILY} from './typography';
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
  /** Downloads and installs the pinned Alpine rootfs. */
  installRootfs: () => Promise<RootfsSetupResult>;
  /** Installs a rootfs archive the user picks from storage. */
  importRootfs: () => Promise<RootfsSetupResult>;
  /** Installs the shell's tools (unless skipped) and saves the password. */
  saveProfile: (password: string, options: Readonly<{skipTools: boolean; onToolsReady: () => void}>) => Promise<ProfileSetupResult>;
  /** Leaves onboarding for the home screen. */
  done: () => void;
}>;

export type OnboardingScreenProps = Readonly<{
  /** True when Alpine is already installed, e.g. after a profile reset. */
  rootfsInstalled: boolean;
  actions: OnboardingActions;
  permissions?: OnboardingPermissions;
}>;

type OnboardingStep = 'welcome' | 'password' | 'permissions' | 'setup' | 'keys';
const STEPS: readonly OnboardingStep[] = ['welcome', 'password', 'permissions', 'setup', 'keys'];

type RootfsState =
  | Readonly<{kind: 'idle' | 'ready'}>
  | Readonly<{kind: 'working'; source: 'download' | 'import'}>
  | Readonly<{kind: 'failed'; errorCode: string}>;

/**
 * First run, in five steps: what Horus is, a password, background
 * permissions, installing Linux, and how to use the terminal keys. The Alpine
 * download starts as soon as the user leaves the welcome step so it runs
 * while they set the password and permissions.
 */
export function OnboardingScreen({rootfsInstalled, actions, permissions = DEFAULT_BACKGROUND_PERMISSION_ACTIONS}: OnboardingScreenProps): React.JSX.Element {
  const [step, setStep] = React.useState<OnboardingStep>('welcome');
  const [password, setPassword] = React.useState('');
  const [rootfs, setRootfs] = React.useState<RootfsState>({kind: rootfsInstalled ? 'ready' : 'idle'});
  const rootfsRef = React.useRef(rootfs);
  const mountedRef = React.useRef(true);
  React.useEffect(() => {
    mountedRef.current = true;
    return () => { mountedRef.current = false; };
  }, []);

  const runRootfs = React.useCallback((source: 'download' | 'import') => {
    const current = rootfsRef.current;
    if (current.kind === 'working' || current.kind === 'ready') return;
    const update = (next: RootfsState) => {
      rootfsRef.current = next;
      if (mountedRef.current) setRootfs(next);
    };
    update({kind: 'working', source});
    const run = source === 'download' ? actions.installRootfs : actions.importRootfs;
    run().catch((): RootfsSetupResult => ({kind: 'error', errorCode: 'internal_error'})).then(result => {
      if (result.kind === 'success') {
        update({kind: 'ready'});
      } else if (result.errorCode === 'import_cancelled' && current.kind === 'failed') {
        // Backing out of the file picker keeps the reason the download failed.
        update(current);
      } else {
        update({kind: 'failed', errorCode: result.errorCode});
      }
    });
  }, [actions.importRootfs, actions.installRootfs]);

  const startFromWelcome = React.useCallback(() => {
    runRootfs('download');
    setStep('password');
  }, [runRootfs]);

  const goBack = React.useCallback(() => {
    if (step === 'password') setStep('welcome');
    else if (step === 'permissions') setStep('password');
    else return false;
    return true;
  }, [step]);
  useHardwareBack(true, goBack);

  const stepNumber = STEPS.indexOf(step) + 1;
  switch (step) {
    case 'welcome':
      return <WelcomeStep onContinue={startFromWelcome} />;
    case 'password':
      return <PasswordStep onContinue={value => { setPassword(value); setStep('permissions'); }} step={stepNumber} />;
    case 'permissions':
      return <PermissionsStep onContinue={() => setStep('setup')} permissions={permissions} step={stepNumber} />;
    case 'setup':
      return <SetupStep actions={actions} onDone={() => setStep('keys')} onRootfs={runRootfs} password={password} rootfs={rootfs} step={stepNumber} />;
    case 'keys':
      return <KeysStep onDone={actions.done} step={stepNumber} />;
  }
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

const WELCOME_POINTS: readonly Readonly<{title: string; detail: string}>[] = [
  {title: 'Coding agents in your pocket', detail: 'Run Claude Code, Codex and OpenCode on this phone, no computer needed.'},
  {title: 'A real Linux terminal', detail: 'Horus runs Alpine Linux with git, zsh and the tools agents expect.'},
  {title: 'Your projects, here', detail: 'Clone your GitHub repositories and work on them where you are.'},
  {title: 'Keeps going in the background', detail: 'Agents keep working while the screen is off, and Horus tells you when they need you.'},
];

function WelcomeStep({onContinue}: Readonly<{onContinue: () => void}>): React.JSX.Element {
  return (
    <AuthScreenLayout brandTestID="setup" screenTestID="onboarding-welcome">
      <View style={styles.step}>
        <Text style={styles.welcomeTitle}>Welcome to Horus</Text>
        <View style={styles.points}>
          {WELCOME_POINTS.map(point => (
            <View key={point.title} style={styles.point}>
              <View style={styles.pointMark} />
              <View style={styles.pointBody}>
                <Text style={styles.pointTitle}>{point.title}</Text>
                <Text style={styles.pointDetail}>{point.detail}</Text>
              </View>
            </View>
          ))}
        </View>
        <PrimaryButton label="GET STARTED  →" onPress={onContinue} testID="welcome-continue" />
      </View>
    </AuthScreenLayout>
  );
}

const MIN_PASSWORD_LENGTH = 4;

function PasswordStep({step, onContinue}: Readonly<{step: number; onContinue: (password: string) => void}>): React.JSX.Element {
  const [password, setPassword] = React.useState('');
  const [confirmation, setConfirmation] = React.useState('');
  const [error, setError] = React.useState<string | undefined>();
  const confirmRef = React.useRef<React.ElementRef<typeof TextInput>>(null);

  const submit = () => {
    if (password.length < MIN_PASSWORD_LENGTH) {
      setError(`Choose a password with at least ${MIN_PASSWORD_LENGTH} characters.`);
    } else if (password !== confirmation) {
      setError('The two passwords don’t match.');
    } else {
      setError(undefined);
      onContinue(password);
    }
  };

  return (
    <AuthScreenLayout brandTestID="setup" screenTestID="onboarding-password">
      <View style={styles.step}>
        <StepHeader
          detail="Agents in Horus can read your code and act with your GitHub and AI accounts. The password keeps anyone who picks up your phone out of them. Horus asks for it when you open the app, and after 15 minutes in the background."
          step={step}
          title="Create a password" />
        <TextInput accessibilityLabel="Create password" autoCapitalize="none" autoCorrect={false} onChangeText={setPassword} onSubmitEditing={() => confirmRef.current?.focus()} placeholder="Create password" placeholderTextColor={uiColors.subdued} returnKeyType="next" secureTextEntry style={[authStyles.input, styles.field]} submitBehavior="submit" testID="profile-password" value={password} />
        <TextInput accessibilityLabel="Repeat password" autoCapitalize="none" autoCorrect={false} onChangeText={setConfirmation} onSubmitEditing={submit} placeholder="Repeat password" placeholderTextColor={uiColors.subdued} ref={confirmRef} returnKeyType="done" secureTextEntry style={[authStyles.input, styles.field]} testID="profile-password-confirm" value={confirmation} />
        <Text style={styles.note}>It stays on this phone. Horus can’t recover it, so pick one you’ll remember.</Text>
        {error === undefined ? null : <Text style={authStyles.error} testID="profile-error">{error}</Text>}
        <PrimaryButton label="CONTINUE  →" onPress={submit} testID="profile-continue" />
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
  return (
    <AuthScreenLayout brandTestID="setup" screenTestID="onboarding-permissions">
      <View style={styles.step}>
        <StepHeader detail="Two permissions let sessions continue while the screen is off." step={step} title="Keep your agents running" />
        <View style={styles.checklist}>
          <PermissionCheck allowed={notificationsAllowed} detail="Know when an agent finishes or needs an answer." icon="bell" label="Notifications" onPress={state.requestNotifications} testID="permission-notifications" />
          <PermissionCheck allowed={batteryAllowed} detail="Stop Android from pausing agents to save battery." icon="battery" label="Unrestricted battery" onPress={state.requestBattery} testID="permission-battery" />
        </View>
        <PrimaryButton label={state.allGranted ? 'CONTINUE  →' : 'ALLOW'} onPress={primary} testID="permissions-continue" />
        {state.allGranted ? null : (
          <Pressable accessibilityRole="button" onPress={onContinue} style={styles.skip} testID="permissions-skip">
            <Text style={styles.skipText}>Not now</Text>
          </Pressable>
        )}
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
  password: string;
  rootfs: RootfsState;
  actions: OnboardingActions;
  onRootfs: (source: 'download' | 'import') => void;
  onDone: () => void;
}>;

/** Waits for Alpine, then installs the shell's tools and saves the password. */
function SetupStep({step, password, rootfs, actions, onRootfs, onDone}: SetupStepProps): React.JSX.Element {
  const [profile, setProfile] = React.useState<ProfileState>({kind: 'idle'});
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
    actions.saveProfile(password, {skipTools, onToolsReady: () => { if (mountedRef.current) setProfile({kind: 'saving'}); }})
      .catch((): ProfileSetupResult => ({kind: 'error', stage: 'profile'}))
      .then(result => {
        runningRef.current = false;
        if (!mountedRef.current) return;
        if (result.kind === 'success') onDone();
        else setProfile({kind: 'failed', stage: result.stage});
      });
  }, [actions, onDone, password]);

  // Start the download if it never began, and carry on once Alpine is in.
  React.useEffect(() => {
    if (rootfs.kind === 'idle') onRootfs('download');
    if (rootfs.kind === 'ready' && profile.kind === 'idle') saveProfile(false);
  }, [onRootfs, profile.kind, rootfs.kind, saveProfile]);

  const linuxStatus: TaskStatus = rootfs.kind === 'ready' ? 'done' : rootfs.kind === 'failed' ? 'failed' : 'working';
  const toolsStatus: TaskStatus = rootfs.kind !== 'ready' ? 'waiting'
    : profile.kind === 'tools' || profile.kind === 'idle' ? 'working'
    : profile.kind === 'failed' && profile.stage === 'alpine-tools' ? 'failed' : 'done';
  const saveStatus: TaskStatus = profile.kind === 'saving' ? 'working'
    : profile.kind === 'failed' && profile.stage === 'profile' ? 'failed' : 'waiting';
  const linuxLabel = rootfs.kind === 'working' && rootfs.source === 'import' ? 'Installing Alpine Linux from your file' : 'Downloading Alpine Linux';

  return (
    <AuthScreenLayout brandTestID="setup" screenTestID="onboarding-setup">
      <View style={styles.step}>
        <StepHeader detail="Horus is setting up Linux on this phone. This can take a few minutes on a slow connection." step={step} title="Installing Linux" />
        <View accessibilityLiveRegion="polite" style={styles.tasks}>
          <SetupTask label={linuxLabel} status={linuxStatus} testID="setup-task-linux" />
          <SetupTask label="Installing command-line tools" status={toolsStatus} testID="setup-task-tools" />
          <SetupTask label="Saving your password" status={saveStatus} testID="setup-task-profile" />
        </View>
        {rootfs.kind === 'failed' ? <RootfsFailure errorCode={rootfs.errorCode} onImport={() => onRootfs('import')} onRetry={() => onRootfs('download')} /> : null}
        {profile.kind === 'failed' && profile.stage === 'alpine-tools' ? (
          <View style={styles.problem} testID="setup-tools-failed">
            <Text style={styles.problemTitle}>The tools need the internet</Text>
            <Text style={styles.problemDetail}>Alpine is installed, but git, zsh and the other tools couldn’t be downloaded. Try again once you’re online, or skip for now and they’ll install the first time you open a terminal.</Text>
            <PrimaryButton label="TRY AGAIN" onPress={() => saveProfile(false)} testID="setup-tools-retry" />
            <SecondaryButton label="SKIP FOR NOW" onPress={() => saveProfile(true)} testID="setup-tools-skip" />
          </View>
        ) : null}
        {profile.kind === 'failed' && profile.stage === 'profile' ? (
          <View style={styles.problem} testID="setup-profile-failed">
            <Text style={styles.problemTitle}>Your password couldn’t be saved</Text>
            <Text style={styles.problemDetail}>Something went wrong storing it on this phone.</Text>
            <PrimaryButton label="TRY AGAIN" onPress={() => saveProfile(true)} testID="setup-profile-retry" />
          </View>
        ) : null}
      </View>
    </AuthScreenLayout>
  );
}

type TaskStatus = 'waiting' | 'working' | 'done' | 'failed';

function SetupTask({label, status, testID}: Readonly<{label: string; status: TaskStatus; testID: string}>): React.JSX.Element {
  return (
    <View accessibilityLabel={`${label}, ${status}`} style={styles.task} testID={`${testID}-${status}`}>
      <View style={styles.taskIcon}>
        {status === 'working' ? <ActivityIndicator color={uiColors.accent} size="small" />
          : status === 'done' ? <View style={[styles.checkMark, styles.checkMarkOn]}><EntryIcon kind="check" size={14} style={styles.checkIcon} /></View>
          : <View style={[styles.taskDot, status === 'failed' && styles.taskDotFailed]} />}
      </View>
      <Text style={[styles.taskLabel, status === 'waiting' && styles.taskLabelWaiting, status === 'failed' && styles.taskLabelFailed]}>{label}</Text>
    </View>
  );
}

/** Offline or a bad file: retry, or pick an archive downloaded elsewhere. */
function RootfsFailure({errorCode, onRetry, onImport}: Readonly<{errorCode: string; onRetry: () => void; onImport: () => void}>): React.JSX.Element {
  const badFile = errorCode === 'import_failed' || errorCode === 'digest_mismatch';
  const offline = errorCode === 'download_failed' || errorCode === 'import_cancelled';
  return (
    <View style={styles.problem} testID="setup-linux-failed">
      <Text style={styles.problemTitle}>{badFile ? 'That file isn’t the right one' : offline ? 'Can’t reach the internet' : 'Linux couldn’t be installed'}</Text>
      <Text style={styles.problemDetail}>
        {badFile
          ? 'Horus checks the file’s SHA-256 and it didn’t match. Download it again from the link below, then choose it here.'
          : offline
            ? 'Connect to Wi-Fi or mobile data and try again. If you already have the Alpine file, or can download it on another connection, choose it from your phone instead.'
            : `Something went wrong (${errorCode}). Try again, or choose the Alpine file from your phone.`}
      </Text>
      <Text selectable style={styles.url} testID="setup-rootfs-url">{PINNED_ROOTFS_URL}</Text>
      <PrimaryButton label="TRY AGAIN" onPress={onRetry} testID="setup-linux-retry" />
      <SecondaryButton label="CHOOSE FILE" onPress={onImport} testID="setup-linux-import" />
      <SecondaryButton label="OPEN LINK IN BROWSER ↗" onPress={() => { void Linking.openURL(PINNED_ROOTFS_URL).catch(() => undefined); }} testID="setup-linux-open-url" />
    </View>
  );
}

type KeyTip = Readonly<{keys: readonly string[]; detail: string}>;

const KEY_TIPS: readonly KeyTip[] = [
  {keys: ['ESC'], detail: 'Interrupts an agent, or closes a menu.'},
  {keys: ['↑', '↓', '←', '→'], detail: 'Move through an agent’s menus. ↑ brings back earlier commands.'},
  {keys: ['CTRL', 'ALT'], detail: 'Tap one, then a key. CTRL then C stops a running command.'},
  {keys: ['TAB'], detail: 'Completes file and command names.'},
  {keys: ['PASTE'], detail: 'Pastes what you copied, like a sign-in code.'},
  {keys: ['SHOW'], detail: 'Shows or hides the keyboard.'},
  {keys: ['RETURN'], detail: 'Sends what you typed.'},
];

const GESTURE_TIPS: readonly string[] = [
  'Swipe up or down on the terminal to scroll back.',
  'Tap a link to open it in your browser.',
  'Back leaves an agent running. Find it again on the home screen.',
];

/** A cheat sheet for the terminal's key row, shown once before home. */
function KeysStep({step, onDone}: Readonly<{step: number; onDone: () => void}>): React.JSX.Element {
  return (
    <AuthScreenLayout brandTestID="setup" screenTestID="onboarding-keys">
      <View style={styles.step}>
        <StepHeader detail="Phones have no Esc, Ctrl or arrow keys, so Horus puts them in a row above the keyboard." step={step} title="Using the terminal" />
        <View style={authStyles.card} testID="onboarding-key-card">
          {KEY_TIPS.map(tip => (
            <View key={tip.keys.join()} style={styles.keyTip}>
              <View style={styles.keyCaps}>
                {tip.keys.map(key => (
                  <View key={key} style={styles.keyCap}>
                    <Text style={styles.keyCapText}>{key}</Text>
                  </View>
                ))}
              </View>
              <Text style={styles.keyDetail}>{tip.detail}</Text>
            </View>
          ))}
          <View style={styles.gestures}>
            {GESTURE_TIPS.map(tip => (
              <View key={tip} style={styles.point}>
                <View style={styles.pointMark} />
                <Text style={[styles.keyDetail, styles.pointBody]}>{tip}</Text>
              </View>
            ))}
          </View>
        </View>
        <PrimaryButton label="START USING HORUS  →" onPress={onDone} testID="onboarding-done" />
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
  welcomeTitle: {color: uiColors.ink, fontFamily: UI_FONT_FAMILY, fontSize: 18, fontWeight: '800', marginBottom: 6, textAlign: 'center'},
  points: {marginTop: 12},
  point: {flexDirection: 'row', marginTop: 12},
  pointMark: {backgroundColor: uiColors.accent, borderRadius: 2, height: 4, marginRight: 12, marginTop: 7, width: 4},
  pointBody: {flex: 1},
  pointTitle: {color: uiColors.ink, fontFamily: UI_FONT_FAMILY, fontSize: 13, fontWeight: '700'},
  pointDetail: {color: uiColors.muted, fontFamily: UI_FONT_FAMILY, fontSize: 11, lineHeight: 17, marginTop: 2},
  field: {marginTop: 10},
  note: {color: uiColors.subdued, fontFamily: UI_FONT_FAMILY, fontSize: 10, lineHeight: 15, marginTop: 10},
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
  taskLabel: {color: uiColors.ink, flex: 1, fontFamily: UI_FONT_FAMILY, fontSize: 12},
  taskLabelWaiting: {color: uiColors.subdued},
  taskLabelFailed: {color: uiColors.danger},
  problem: {marginTop: 20},
  problemTitle: {color: uiColors.warning, fontFamily: UI_FONT_FAMILY, fontSize: 13, fontWeight: '800'},
  problemDetail: {color: uiColors.muted, fontFamily: UI_FONT_FAMILY, fontSize: 11, lineHeight: 17, marginTop: 6},
  url: {backgroundColor: uiColors.background, borderColor: uiColors.borderSoft, borderRadius: 8, borderWidth: 1, color: uiColors.ink, fontFamily: UI_FONT_FAMILY, fontSize: 10, lineHeight: 16, marginTop: 10, padding: 10},
  keyTip: {alignItems: 'center', flexDirection: 'row', gap: 12, paddingVertical: 7},
  keyCaps: {flexDirection: 'row', flexWrap: 'wrap', gap: 4, width: 120},
  keyCap: {alignItems: 'center', backgroundColor: uiColors.background, borderColor: uiColors.border, borderRadius: 6, borderWidth: 1, justifyContent: 'center', minHeight: 26, minWidth: 26, paddingHorizontal: 6},
  keyCapText: {color: uiColors.accent, fontFamily: UI_FONT_FAMILY, fontSize: 10, fontWeight: '800'},
  keyDetail: {color: uiColors.ink, flex: 1, fontFamily: UI_FONT_FAMILY, fontSize: 11, lineHeight: 16},
  gestures: {borderTopColor: uiColors.borderSoft, borderTopWidth: 1, marginTop: 8, paddingTop: 2},
});
