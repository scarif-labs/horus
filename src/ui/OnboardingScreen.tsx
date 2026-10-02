import React from 'react';
import {ActivityIndicator, Linking, StyleSheet, Text, TextInput, View} from 'react-native';
import {ALPINE_MIRRORS, npmRegistryFor, rootfsUrl, type DownloadSources} from '../terminal/downloadSources';
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
  /** Downloads and installs the pinned Alpine rootfs. */
  installRootfs: () => Promise<RootfsSetupResult>;
  /** Installs a rootfs archive the user picks from storage. */
  importRootfs: () => Promise<RootfsSetupResult>;
  /** Installs the shell's tools (unless skipped) and saves the password. */
  saveProfile: (password: string, options: Readonly<{skipTools: boolean; onToolsReady: () => void}>) => Promise<ProfileSetupResult>;
  readDownloadSources: () => Promise<DownloadSources>;
  /** Resolves false when the sources could not be saved. */
  saveDownloadSources: (sources: DownloadSources) => Promise<boolean>;
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

const MIN_PASSWORD_LENGTH = 4;

function PasswordStep({step, onContinue}: Readonly<{step: number; onContinue: (password: string) => void}>): React.JSX.Element {
  const [password, setPassword] = React.useState('');
  const [confirmation, setConfirmation] = React.useState('');
  const [error, setError] = React.useState<string | undefined>();
  const confirmRef = React.useRef<React.ElementRef<typeof TextInput>>(null);

  const submit = () => {
    if (password.length < MIN_PASSWORD_LENGTH) {
      setError(`Use at least ${MIN_PASSWORD_LENGTH} characters.`);
    } else if (password !== confirmation) {
      setError('Passwords don’t match.');
    } else {
      setError(undefined);
      onContinue(password);
    }
  };

  return (
    <AuthScreenLayout brandTestID="setup" footer={<PrimaryButton label="CONTINUE  →" onPress={submit} testID="profile-continue" />} pinned screenTestID="onboarding-password">
      <View style={styles.step}>
        <StepHeader
          detail="Agents can use your code and accounts. This keeps them safe if someone picks up your phone."
          step={step}
          title="Create a password" />
        <TextInput accessibilityLabel="Create password" autoCapitalize="none" autoCorrect={false} onChangeText={setPassword} onSubmitEditing={() => confirmRef.current?.focus()} placeholder="Create password" placeholderTextColor={uiColors.subdued} returnKeyType="next" secureTextEntry style={[authStyles.input, styles.field]} submitBehavior="submit" testID="profile-password" value={password} />
        <TextInput accessibilityLabel="Repeat password" autoCapitalize="none" autoCorrect={false} onChangeText={setConfirmation} onSubmitEditing={submit} placeholder="Repeat password" placeholderTextColor={uiColors.subdued} ref={confirmRef} returnKeyType="done" secureTextEntry style={[authStyles.input, styles.field]} testID="profile-password-confirm" value={confirmation} />
        <Text style={styles.note}>Stored only on this phone. It can’t be recovered.</Text>
        {error === undefined ? null : <Text style={authStyles.error} testID="profile-error">{error}</Text>}
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
  password: string;
  rootfs: RootfsState;
  actions: OnboardingActions;
  onRootfs: (source: 'download' | 'import') => void;
  onDone: () => void;
}>;

/** Waits for Alpine, then installs the shell's tools and saves the password. */
function SetupStep({step, password, rootfs, actions, onRootfs, onDone}: SetupStepProps): React.JSX.Element {
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
    actions.saveProfile(password, {skipTools, onToolsReady: () => { if (mountedRef.current) setProfile({kind: 'saving'}); }})
      .catch((): ProfileSetupResult => ({kind: 'error', stage: 'profile'}))
      .then(result => {
        runningRef.current = false;
        if (!mountedRef.current) return;
        if (result.kind === 'success') onDone();
        else setProfile({kind: 'failed', stage: result.stage});
      });
  }, [actions, onDone, password]);

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

  const linuxStatus: TaskStatus = rootfs.kind === 'ready' ? 'done' : rootfs.kind === 'failed' ? 'failed' : 'working';
  const toolsStatus: TaskStatus = rootfs.kind !== 'ready' ? 'waiting'
    : profile.kind === 'tools' || profile.kind === 'idle' ? 'working'
    : profile.kind === 'failed' && profile.stage === 'alpine-tools' ? 'failed' : 'done';
  const saveStatus: TaskStatus = profile.kind === 'saving' ? 'working'
    : profile.kind === 'failed' && profile.stage === 'profile' ? 'failed' : 'waiting';
  const linuxLabel = rootfs.kind === 'working' && rootfs.source === 'import' ? 'Installing Linux from your file' : 'Downloading Linux';

  return (
    <AuthScreenLayout brandTestID="setup" pinned screenTestID="onboarding-setup">
      <View style={styles.step}>
        <StepHeader detail="This can take a few minutes." step={step} title="Installing Linux" />
        <View accessibilityLiveRegion="polite" style={styles.tasks}>
          <SetupTask label={linuxLabel} status={linuxStatus} testID="setup-task-linux" />
          <SetupTask label="Installing tools" status={toolsStatus} testID="setup-task-tools" />
          <SetupTask label="Saving your password" status={saveStatus} testID="setup-task-profile" />
        </View>
        {rootfs.kind === 'failed' ? <RootfsFailure errorCode={rootfs.errorCode} mirror={mirror} onImport={() => onRootfs('import')} onMirror={chooseMirror} onRetry={() => onRootfs('download')} /> : null}
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
            <Text style={styles.problemTitle}>Your password couldn’t be saved</Text>
            <Text style={styles.problemDetail}>Please try again.</Text>
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
type RootfsFailureProps = Readonly<{
  errorCode: string;
  mirror: string | undefined;
  onMirror: (url: string | undefined) => void;
  onRetry: () => void;
  onImport: () => void;
}>;

/** Offline, blocked or a bad file: try a mirror, retry, or pick an archive downloaded elsewhere. */
function RootfsFailure({errorCode, mirror, onMirror, onRetry, onImport}: RootfsFailureProps): React.JSX.Element {
  const badFile = errorCode === 'import_failed' || errorCode === 'digest_mismatch';
  const offline = errorCode === 'download_failed' || errorCode === 'import_cancelled';
  const url = rootfsUrl(mirror);
  return (
    <View style={styles.problem} testID="setup-linux-failed">
      <Text style={styles.problemTitle}>{badFile ? 'Wrong file' : offline ? 'Couldn’t download Linux' : 'Linux couldn’t be installed'}</Text>
      <Text style={styles.problemDetail}>
        {badFile
          ? 'Its checksum didn’t match. Download it again from the link below.'
          : offline
            ? 'Check your connection, or try a mirror closer to you.'
            : `Something went wrong (${errorCode}).`}
      </Text>
      <Text style={styles.problemLabel}>DOWNLOAD FROM</Text>
      <MirrorPicker onSelect={onMirror} options={ALPINE_MIRRORS} placeholder="https://mirror.example/alpine" testID="setup-mirror" value={mirror} />
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
          <Text style={styles.keyInfoDetail} testID="onboarding-key-detail">{info?.detail ?? 'See what each one does.'}</Text>
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
  problemLabel: {color: uiColors.muted, fontFamily: UI_FONT_FAMILY, fontSize: 9, letterSpacing: 0.9, marginBottom: 4, marginTop: 16},
  problemLabelGap: {marginTop: 24},
  problemDetail: {color: uiColors.muted, fontFamily: UI_FONT_FAMILY, fontSize: 11, lineHeight: 17, marginTop: 6},
  url: {backgroundColor: uiColors.background, borderColor: uiColors.borderSoft, borderRadius: 8, borderWidth: 1, color: uiColors.ink, fontFamily: UI_FONT_FAMILY, fontSize: 10, lineHeight: 16, marginTop: 10, padding: 10},
  keyInfo: {alignItems: 'center', backgroundColor: uiColors.panel, borderColor: uiColors.border, borderRadius: 12, borderWidth: 1, justifyContent: 'center', minHeight: 104, paddingHorizontal: 16, paddingVertical: 14},
  keyInfoLabel: {color: uiColors.accent, fontFamily: UI_FONT_FAMILY, fontSize: 22, fontWeight: '800'},
  keyInfoPrompt: {color: uiColors.subdued, fontSize: 13, letterSpacing: 0.8},
  keyInfoDetail: {color: uiColors.ink, fontFamily: UI_FONT_FAMILY, fontSize: 11, lineHeight: 17, marginTop: 8, textAlign: 'center'},
  keyRow: {borderColor: uiColors.border, borderRadius: 10, borderWidth: 1, marginTop: 14, overflow: 'hidden'},
  gestures: {marginTop: 16},
  gesture: {alignItems: 'center', flexDirection: 'row', gap: 12, minHeight: 28},
  gestureGlyph: {color: uiColors.accent, fontFamily: UI_FONT_FAMILY, fontSize: 13, textAlign: 'center', width: 18},
  gestureLabel: {color: uiColors.muted, fontFamily: UI_FONT_FAMILY, fontSize: 11},
});
