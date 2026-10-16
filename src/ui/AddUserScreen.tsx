import React from 'react';
import {ActivityIndicator, ScrollView, StyleSheet, Text, View} from 'react-native';
import {ScreenShell} from '../screen/ScreenShell';
import {DISTRO_NAMES, LINUX_DISTROS, saveUserProfile, type LinuxDistro, type ProfileUser} from '../profile/profileStore';
import {rootfsForDistro} from '../terminal/distroContract';
import {installRootfs} from '../terminal/runtimeStatus';
import {AccountForm, useAccountDraft, type AccountDraft} from './AccountForm';
import {authStyles} from './AuthScreenLayout';
import {BrandBackButton, BrandHeader, uiColors} from './brand';
import {InteractivePressable as Pressable} from './InteractivePressable';
import {UI_FONT_FAMILY} from './typography';
import {useHardwareBack} from './useHardwareBack';

const ADD_USER_REQUEST_ID = 'horus-add-user-1';

export type AddUserActions = Readonly<{
  /** Installs [distro]'s rootfs beside the active one, without switching new sessions to it. */
  installBeside: (distro: LinuxDistro) => Promise<Readonly<{kind: 'success'}> | Readonly<{kind: 'error'; errorCode: string}>>;
  createUser: (user: ProfileUser & Readonly<{password: string}>) => Promise<Readonly<{kind: 'success'}> | Readonly<{kind: 'error'; code: string}>>;
}>;

const DEFAULT_ACTIONS: AddUserActions = {
  installBeside: async distro => {
    const entry = rootfsForDistro(distro);
    if (entry === undefined) return {kind: 'error', errorCode: 'invalid_request'};
    const installed = await installRootfs(ADD_USER_REQUEST_ID, undefined, {rootfsId: entry.rootfsId, makeActive: false});
    return installed.kind === 'success' ? {kind: 'success'} : {kind: 'error', errorCode: installed.errorCode};
  },
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
  | Readonly<{kind: 'failed'; draft: AccountDraft; message: string}>;

/**
 * Adds the user for the distribution that has none: the same hieroglyph,
 * username, and password as onboarding. The distribution installs beside the
 * current one; the new user switches to it by signing in.
 */
export function AddUserScreen({existingUsers, onBack, onCreated, actions = DEFAULT_ACTIONS}: AddUserScreenProps): React.JSX.Element {
  const distro = LINUX_DISTROS.find(candidate => !existingUsers.some(user => user.distro === candidate));
  const account = useAccountDraft();
  const [progress, setProgress] = React.useState<Progress>({kind: 'form'});
  const mountedRef = React.useRef(true);
  React.useEffect(() => {
    mountedRef.current = true;
    return () => { mountedRef.current = false; };
  }, []);
  const busy = progress.kind === 'installing' || progress.kind === 'creating';
  useHardwareBack(true, () => {
    if (!busy) onBack();
    return true;
  });

  const run = React.useCallback(async (draft: AccountDraft) => {
    if (distro === undefined) return;
    const name = DISTRO_NAMES[distro];
    setProgress({kind: 'installing', draft});
    const installed = await actions.installBeside(distro).catch(() => ({kind: 'error', errorCode: 'internal_error'} as const));
    if (!mountedRef.current) return;
    if (installed.kind !== 'success') {
      setProgress({kind: 'failed', draft, message: installed.errorCode === 'download_failed'
        ? `Couldn’t download ${name}. Check your connection and try again.`
        : installed.errorCode === 'runtime_busy'
          ? `${name} is in use. Close its terminals and turn off SSH access, then try again.`
          : `${name} couldn’t be installed (${installed.errorCode}).`});
      return;
    }
    setProgress({kind: 'creating', draft});
    const user: ProfileUser = {distro, username: draft.username, avatar: draft.avatar};
    const created = await actions.createUser({...user, password: draft.password}).catch(() => ({kind: 'error', code: 'internal_error'} as const));
    if (!mountedRef.current) return;
    if (created.kind !== 'success') {
      setProgress({kind: 'failed', draft, message: `The user couldn’t be created (${created.code}).`});
      return;
    }
    onCreated(user);
  }, [actions, distro, onCreated]);

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
            <View style={styles.status}>
              {busy ? <ActivityIndicator color={uiColors.accent} size="small" /> : null}
              <Text style={[styles.statusText, progress.kind === 'failed' && styles.failed]}>
                {progress.kind === 'failed' ? progress.message
                  : progress.kind === 'installing' ? `Installing ${DISTRO_NAMES[distro]}…`
                  : `Creating ${progress.draft.username}…`}
              </Text>
            </View>
            {progress.kind === 'failed' ? (
              <>
                <Pressable accessibilityRole="button" onPress={() => { void run(progress.draft); }} style={[authStyles.button, styles.button]} testID="add-user-retry">
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

const styles = StyleSheet.create({
  content: {paddingBottom: 28, paddingHorizontal: 18},
  body: {alignSelf: 'center', marginTop: 16, maxWidth: 420, width: '100%'},
  detail: {color: uiColors.muted, fontFamily: UI_FONT_FAMILY, fontSize: 11, lineHeight: 17, marginBottom: 16, marginTop: 16},
  button: {marginTop: 18},
  status: {alignItems: 'center', flexDirection: 'row', gap: 12, minHeight: 52},
  statusText: {color: uiColors.ink, flex: 1, fontFamily: UI_FONT_FAMILY, fontSize: 12, lineHeight: 18},
  failed: {color: uiColors.warning},
  secondary: {alignItems: 'center', borderColor: uiColors.border, borderRadius: 8, borderWidth: 1, justifyContent: 'center', marginTop: 10, minHeight: 46},
  secondaryText: {color: uiColors.ink, fontFamily: UI_FONT_FAMILY, fontSize: 10, fontWeight: '800', letterSpacing: 0.5},
});
