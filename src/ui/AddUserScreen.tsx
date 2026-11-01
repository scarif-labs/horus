import React from 'react';
import {ActivityIndicator, ScrollView, StyleSheet, Text, View} from 'react-native';
import {ScreenShell} from '../screen/ScreenShell';
import {DISTRO_NAMES, LINUX_DISTROS, saveUserProfile, type LinuxDistro, type ProfileUser} from '../profile/profileStore';
import {rootfsForDistro} from '../terminal/distroContract';
import {installRootfs} from '../terminal/runtimeStatus';
import nativeTerminalRuntime, {type RootfsInstallProgressResponse} from '../native/NativeTerminalRuntime';
import {AccountForm, useAccountDraft, type AccountDraft} from './AccountForm';
import {authStyles} from './AuthScreenLayout';
import {BrandBackButton, BrandHeader, uiColors} from './brand';
import {EntryIcon} from './EntryIcon';
import {InteractivePressable as Pressable} from './InteractivePressable';
import {UI_FONT_FAMILY} from './typography';
import {useHardwareBack} from './useHardwareBack';

const ADD_USER_REQUEST_ID = 'horus-add-user-1';

export function addUserInstallDetail(progress: RootfsInstallProgressResponse | undefined): string | undefined {
  if (progress?.status !== 'working' || progress.requestId !== ADD_USER_REQUEST_ID) return undefined;
  switch (progress.stage) {
    case 'checking': return 'Checking Linux files…';
    case 'download': {
      const downloaded = progress.downloadedBytes;
      const total = progress.totalBytes;
      return typeof downloaded === 'number' && typeof total === 'number' && Number.isFinite(downloaded) && Number.isFinite(total) && total > 0
        ? `Downloading Linux… ${Math.min(100, Math.max(0, Math.floor(downloaded * 100 / total)))}%`
        : 'Downloading Linux…';
    }
    case 'verify': return 'Checking the download…';
    case 'extract': return 'Unpacking Linux…';
    case 'probe': return 'Checking Linux can start…';
    case 'promote': return 'Finishing installation…';
    default: return undefined;
  }
}

export type AddUserActions = Readonly<{
  /** Installs [distro]'s rootfs beside the active one, without switching new sessions to it. */
  installBeside: (distro: LinuxDistro) => Promise<Readonly<{kind: 'success'}> | Readonly<{kind: 'error'; errorCode: string}>>;
  readInstallProgress?: () => Promise<string | undefined>;
  createUser: (user: ProfileUser & Readonly<{password: string}>) => Promise<Readonly<{kind: 'success'}> | Readonly<{kind: 'error'; code: string}>>;
}>;

const DEFAULT_ACTIONS: AddUserActions = {
  installBeside: async distro => {
    const entry = rootfsForDistro(distro);
    if (entry === undefined) return {kind: 'error', errorCode: 'invalid_request'};
    const installed = await installRootfs(ADD_USER_REQUEST_ID, undefined, {rootfsId: entry.rootfsId, makeActive: false});
    return installed.kind === 'success' ? {kind: 'success'} : {kind: 'error', errorCode: installed.errorCode};
  },
  readInstallProgress: async () => addUserInstallDetail(await nativeTerminalRuntime?.readRootfsInstallProgress()),
  createUser: saveUserProfile,
};

type AddUserScreenProps = Readonly<{
  existingUsers: readonly ProfileUser[];
  onBack: () => void;
  onCreated: (user: ProfileUser) => void;
  actions?: AddUserActions;
}>;

type Progress =
  | Readonly<{kind: 'form'}>
  | Readonly<{kind: 'installing' | 'creating'; draft: AccountDraft}>
  | Readonly<{kind: 'failed'; draft: AccountDraft; stage: 'install' | 'create'; errorCode: string; message: string}>;

type TaskStatus = 'waiting' | 'working' | 'done' | 'failed';

function installFailureMessage(name: string, errorCode: string): string {
  switch (errorCode) {
    case 'download_failed':
      return `Couldn’t download ${name}. Check your connection, then try again.`;
    case 'digest_mismatch':
      return `The downloaded ${name} image failed its integrity check. Try the download again.`;
    case 'extraction_failed':
      return `Horus couldn’t unpack ${name}. Free up storage if needed, then try again.`;
    case 'probe_failed':
      return `${name} failed its startup checks. Try the installation again.`;
    case 'runtime_busy':
      return `${name} is in use. Close its terminals and turn off SSH access, then try again.`;
    case 'install_in_progress':
      return `Another Linux installation is still running. Wait for it to finish, then try again.`;
    case 'promote_failed':
      return `Horus couldn’t finish setting up ${name}. Check available storage, then try again.`;
    case 'runtime_unavailable':
    case 'unavailable':
      return `The Linux installer isn’t available. Reopen Horus and try again.`;
    default:
      return `${name} couldn’t be installed.`;
  }
}

function createFailureMessage(name: string, errorCode: string): string {
  switch (errorCode) {
    case 'duplicate_username':
      return 'That username is already in use. Go back to the form and choose another.';
    case 'invalid_username':
      return 'That username can’t be used for a Linux account. Go back to the form and choose another.';
    case 'invalid_password':
      return 'That password can’t be used. Go back to the form and choose a password between 4 and 128 characters.';
    case 'invalid_avatar':
      return 'That avatar couldn’t be saved. Go back to the form and choose another.';
    case 'exists':
      return `A ${name} user already exists. Return to Account and refresh the user list.`;
    case 'unavailable':
      return 'The Linux user store isn’t available. Reopen Horus and try again.';
    default:
      return `Horus couldn’t create the ${name} user.`;
  }
}

/**
 * Adds the user for the distribution that has none: the same hieroglyph,
 * username, and password as onboarding. The distribution installs beside the
 * current one; the new user switches to it by signing in.
 */
export function AddUserScreen({existingUsers, onBack, onCreated, actions = DEFAULT_ACTIONS}: AddUserScreenProps): React.JSX.Element {
  const distro = LINUX_DISTROS.find(candidate => !existingUsers.some(user => user.distro === candidate));
  const account = useAccountDraft(existingUsers.map(user => user.username));
  const installBeside = actions.installBeside;
  const [progress, setProgress] = React.useState<Progress>({kind: 'form'});
  const [installDetail, setInstallDetail] = React.useState<string | undefined>();
  const mountedRef = React.useRef(true);
  const installedDistroRef = React.useRef<LinuxDistro | undefined>(undefined);
  React.useEffect(() => {
    mountedRef.current = true;
    return () => { mountedRef.current = false; };
  }, []);
  const busy = progress.kind === 'installing' || progress.kind === 'creating';
  React.useEffect(() => {
    const read = actions.readInstallProgress;
    if (progress.kind !== 'installing' || read === undefined) {
      setInstallDetail(undefined);
      return undefined;
    }
    let active = true;
    const poll = () => { read().then(value => { if (active) setInstallDetail(value); }).catch(() => undefined); };
    poll();
    const timer = setInterval(poll, 500);
    return () => { active = false; clearInterval(timer); };
  }, [actions.readInstallProgress, progress.kind]);
  useHardwareBack(true, () => {
    if (!busy) onBack();
    return true;
  });

  const createUser = React.useCallback(async (draft: AccountDraft) => {
    if (distro === undefined) return;
    const name = DISTRO_NAMES[distro];
    setProgress({kind: 'creating', draft});
    const user: ProfileUser = {distro, username: draft.username, avatar: draft.avatar};
    const created = await actions.createUser({...user, password: draft.password}).catch(() => ({kind: 'error', code: 'internal_error'} as const));
    if (!mountedRef.current) return;
    if (created.kind !== 'success') {
      setProgress({kind: 'failed', draft, stage: 'create', errorCode: created.code, message: createFailureMessage(name, created.code)});
      return;
    }
    onCreated(user);
  }, [actions, distro, onCreated]);

  const run = React.useCallback(async (draft: AccountDraft) => {
    if (distro === undefined) return;
    if (installedDistroRef.current !== distro) {
      const name = DISTRO_NAMES[distro];
      setProgress({kind: 'installing', draft});
      const installed = await installBeside(distro).catch(() => ({kind: 'error', errorCode: 'internal_error'} as const));
      if (!mountedRef.current) return;
      if (installed.kind !== 'success') {
        setProgress({kind: 'failed', draft, stage: 'install', errorCode: installed.errorCode, message: installFailureMessage(name, installed.errorCode)});
        return;
      }
      installedDistroRef.current = distro;
    }
    await createUser(draft);
  }, [createUser, distro, installBeside]);

  const submit = React.useCallback(() => {
    const draft = account.validate();
    if (draft !== undefined) void run(draft);
  }, [account, run]);

  return (
    <ScreenShell testID="add-user-screen">
      <ScrollView contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
        <BrandHeader
          action={busy ? null : <BrandBackButton onPress={onBack} testID="add-user-back" />}
          eyebrow="ACCOUNT / USERS"
          meta={null}
          title={distro === undefined ? 'Users' : `New ${DISTRO_NAMES[distro]} user`}
        />
        {distro === undefined ? (
          <Text style={styles.detail} testID="add-user-none">Every distribution already has its user.</Text>
        ) : progress.kind === 'form' ? (
          <View style={styles.body}>
            <Text style={styles.detail}>
              {`Installs ${DISTRO_NAMES[distro]} next to ${existingUsers.map(user => DISTRO_NAMES[user.distro]).join(' and ')}. Nothing you have now changes. Sign in as this user to use it.`}
            </Text>
            <AccountForm distroName={DISTRO_NAMES[distro]} draft={account} onSubmit={submit} />
            <Pressable accessibilityRole="button" onPress={submit} style={[authStyles.button, styles.button]} testID="add-user-create">
              <Text style={authStyles.buttonText}>{`ADD ${DISTRO_NAMES[distro].toUpperCase()} USER  →`}</Text>
            </Pressable>
          </View>
        ) : (
          <View accessibilityLiveRegion="polite" style={styles.body} testID={`add-user-${progress.kind}`}>
            <View style={styles.tasks}>
              <SetupTask
                detail={progress.kind === 'installing' ? installDetail ?? 'Preparing the Linux files. This can take a few minutes.' : undefined}
                label={`Install ${DISTRO_NAMES[distro]}`}
                status={progress.kind === 'installing' ? 'working'
                  : progress.kind === 'creating' || (progress.kind === 'failed' && progress.stage === 'create') ? 'done'
                  : progress.kind === 'failed' ? 'failed' : 'waiting'}
                testID="add-user-task-install" />
              <SetupTask
                detail={progress.kind === 'creating' ? `Saving ${progress.draft.username}…` : undefined}
                label={`Create ${progress.draft.username}`}
                status={progress.kind === 'creating' ? 'working'
                  : progress.kind === 'failed' && progress.stage === 'create' ? 'failed'
                  : 'waiting'}
                testID="add-user-task-create" />
            </View>
            {progress.kind === 'failed' ? (
              <>
                <Text style={styles.failureTitle} testID="add-user-failure-title">
                  {progress.stage === 'install' ? `${DISTRO_NAMES[distro]} couldn’t be installed` : `The ${DISTRO_NAMES[distro]} user couldn’t be created`}
                </Text>
                <Text style={styles.failureMessage} testID="add-user-failure-message">{progress.message}</Text>
                <Text selectable style={styles.errorCode} testID="add-user-error-code">Error code: {progress.errorCode}</Text>
                <Pressable accessibilityRole="button" onPress={() => { void (progress.stage === 'install' ? run(progress.draft) : createUser(progress.draft)); }} style={[authStyles.button, styles.button]} testID="add-user-retry">
                  <Text style={authStyles.buttonText}>TRY AGAIN</Text>
                </Pressable>
                <Pressable accessibilityRole="button" onPress={() => setProgress({kind: 'form'})} style={styles.secondary} testID="add-user-edit">
                  <Text style={styles.secondaryText}>BACK TO THE FORM</Text>
                </Pressable>
              </>
            ) : null}
          </View>
        )}
      </ScrollView>
    </ScreenShell>
  );
}

function SetupTask({label, detail, status, testID}: Readonly<{label: string; detail?: string; status: TaskStatus; testID: string}>): React.JSX.Element {
  return (
    <View accessibilityLabel={`${label}, ${detail ?? status}`} style={styles.task} testID={`${testID}-${status}`}>
      <View style={styles.taskIcon}>
        {status === 'working' ? <ActivityIndicator color={uiColors.accent} size="small" />
          : status === 'done' ? <View style={styles.checkMark}><EntryIcon kind="check" size={14} style={styles.checkIcon} /></View>
          : <View style={[styles.taskDot, status === 'failed' && styles.taskDotFailed]} />}
      </View>
      <View style={styles.taskBody}>
        <Text style={[styles.taskLabel, status === 'waiting' && styles.taskLabelWaiting, status === 'failed' && styles.taskLabelFailed]}>{label}</Text>
        {detail === undefined ? null : <Text style={styles.taskDetail} testID={`${testID}-detail`}>{detail}</Text>}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  content: {paddingBottom: 28, paddingHorizontal: 18},
  body: {alignSelf: 'center', marginTop: 16, maxWidth: 420, width: '100%'},
  detail: {color: uiColors.muted, fontFamily: UI_FONT_FAMILY, fontSize: 11, lineHeight: 17, marginBottom: 16, marginTop: 16},
  button: {marginTop: 18},
  tasks: {borderBottomColor: uiColors.borderSoft, borderBottomWidth: 1},
  task: {alignItems: 'center', borderTopColor: uiColors.borderSoft, borderTopWidth: 1, flexDirection: 'row', gap: 14, minHeight: 52},
  taskIcon: {alignItems: 'center', justifyContent: 'center', width: 22},
  taskDot: {borderColor: uiColors.border, borderRadius: 11, borderWidth: 1.5, height: 22, width: 22},
  taskDotFailed: {backgroundColor: uiColors.warning, borderColor: uiColors.warning},
  taskBody: {flex: 1},
  taskLabel: {color: uiColors.ink, fontFamily: UI_FONT_FAMILY, fontSize: 12},
  taskDetail: {color: uiColors.muted, fontFamily: UI_FONT_FAMILY, fontSize: 10, lineHeight: 15, marginTop: 3},
  taskLabelWaiting: {color: uiColors.subdued},
  taskLabelFailed: {color: uiColors.warning},
  failureTitle: {color: uiColors.warning, fontFamily: UI_FONT_FAMILY, fontSize: 13, fontWeight: '800', marginTop: 18},
  failureMessage: {color: uiColors.muted, fontFamily: UI_FONT_FAMILY, fontSize: 11, lineHeight: 17, marginTop: 6},
  errorCode: {color: uiColors.subdued, fontFamily: UI_FONT_FAMILY, fontSize: 10, marginTop: 8},
  checkMark: {alignItems: 'center', backgroundColor: uiColors.accent, borderColor: uiColors.accent, borderRadius: 11, borderWidth: 1.5, height: 22, justifyContent: 'center', width: 22},
  checkIcon: {marginRight: 0},
  secondary: {alignItems: 'center', borderColor: uiColors.border, borderRadius: 8, borderWidth: 1, justifyContent: 'center', marginTop: 10, minHeight: 46},
  secondaryText: {color: uiColors.ink, fontFamily: UI_FONT_FAMILY, fontSize: 10, fontWeight: '800', letterSpacing: 0.5},
});
