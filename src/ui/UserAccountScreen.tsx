import React from 'react';
import {ScrollView, StyleSheet, Text, TextInput, View} from 'react-native';
import {DISTRO_NAMES, LINUX_DISTROS, usernameProblem, type AvatarId, type ProfileUser, type SaveProfileResult} from '../profile/profileStore';
import {AvatarPicker} from './AccountForm';
import {InteractivePressable as Pressable} from './InteractivePressable';
import {ScreenShell} from '../screen/ScreenShell';
import {BrandBackButton, BrandHeader, uiColors} from './brand';
import {ProfileAvatar} from './ProfileAvatar';
import {SettingsAction, SettingsRow, SettingsSection} from './SettingsList';
import {UI_FONT_FAMILY} from './typography';
import {useHardwareBack} from './useHardwareBack';

export type UserAccountScreenProps = Readonly<{
  /** The signed-in user. */
  user: ProfileUser;
  /** Every user, at most one per distribution. */
  users: readonly ProfileUser[];
  onBack: () => void;
  /** Opens the flow that adds the user for the distribution without one. */
  onAddUser?: () => void;
  /** Stops every running session and returns to sign in; false if the stop failed. */
  onLogout?: () => Promise<boolean>;
  /** Stops every running session, then opens sign in on [user]'s password; false if the stop failed. */
  onSwitchTo?: (user: ProfileUser) => Promise<boolean>;
  /** Saves a new username or avatar for the signed-in user. */
  onUpdateUser?: (user: ProfileUser) => Promise<SaveProfileResult>;
}>;

/** Everything about Linux users, opened from the user card on the home screen. */
export function UserAccountScreen({user, users, onBack, onAddUser, onLogout, onSwitchTo, onUpdateUser}: UserAccountScreenProps): React.JSX.Element {
  const [editing, setEditing] = React.useState(false);
  // Back leaves the editor first, then the screen.
  // Back leaves the editor first, then the screen.
  useHardwareBack(true, editing ? () => setEditing(false) : onBack);
  const missing = LINUX_DISTROS.find(distro => !users.some(other => other.distro === distro));
  const others = users.filter(other => other.distro !== user.distro);
  const [armed, setArmed] = React.useState<string | undefined>();
  const [switching, setSwitching] = React.useState(false);
  const [switchFailed, setSwitchFailed] = React.useState(false);
  const mountedRef = React.useRef(true);
  React.useEffect(() => () => { mountedRef.current = false; }, []);

  // Like Log out, switching ends every session, so it takes a second tap.
  const switchTo = React.useCallback(async (other: ProfileUser) => {
    if (onSwitchTo === undefined || switching) return;
    if (armed !== other.distro) {
      setArmed(other.distro);
      setSwitchFailed(false);
      return;
    }
    setSwitching(true);
    const stopped = await onSwitchTo(other);
    if (!mountedRef.current) return;
    setSwitching(false);
    setArmed(undefined);
    setSwitchFailed(!stopped);
  }, [armed, onSwitchTo, switching]);

  return (
    <ScreenShell testID="user-account-screen">
      <ScrollView contentContainerStyle={styles.content}>
        <BrandHeader
          eyebrow="LINUX USER"
          title="Account"
          meta={null}
          action={<BrandBackButton onPress={onBack} testID="user-account-back" />}
        />

        {editing && onUpdateUser !== undefined ? (
          <View style={styles.editor}>
            <EditUserCard onCancel={() => setEditing(false)} onSave={onUpdateUser} onSaved={() => setEditing(false)} user={user} />
          </View>
        ) : (
          <View style={styles.hero} testID="user-account-card">
            <View>
              <ProfileAvatar avatar={user.avatar} selected size={104} />
              {onUpdateUser === undefined ? null : (
                <Pressable
                  accessibilityLabel="Edit name and picture"
                  accessibilityRole="button"
                  focusable={false}
                  hitSlop={10}
                  onPress={() => setEditing(true)}
                  style={styles.pen}
                  testID="user-account-edit">
                  <Text style={styles.penText}>✎</Text>
                </Pressable>
              )}
            </View>
            <Text numberOfLines={1} style={styles.username} testID="user-account-name">{user.username}</Text>
            <Text numberOfLines={1} style={styles.meta}>
              <Text style={styles.distro}>{DISTRO_NAMES[user.distro].toUpperCase()}</Text>
              <Text testID="user-account-home">{`  ·  /home/${user.username}`}</Text>
            </Text>
          </View>
        )}

        <SettingsSection
          footer={switchFailed ? 'Couldn’t stop every session. Try again.' : others.length > 0 ? 'Switching stops every running app and shell, then asks for that user’s password.' : undefined}
          footerTone={switchFailed ? 'danger' : 'muted'}
          testID="user-account-users"
          title={others.length > 0 ? 'OTHER USERS' : 'USERS'}>
          {others.map(other => {
            const confirming = armed === other.distro;
            return (
              <Pressable
                key={other.distro}
                accessibilityHint="Stops every running app and shell first"
                accessibilityLabel={confirming ? `Tap again to switch to ${other.username}` : `Switch to ${other.username}`}
                accessibilityRole="button"
                disabled={onSwitchTo === undefined || switching}
                focusable={false}
                onPress={() => { void switchTo(other); }}
                style={styles.other}
                testID={`user-account-user-${other.distro}`}>
                <ProfileAvatar avatar={other.avatar} size={40} />
                <View style={styles.otherCopy}>
                  <Text numberOfLines={1} style={styles.otherName}>{other.username}</Text>
                  <Text numberOfLines={2} style={[styles.otherDetail, confirming && styles.otherWarning]}>
                    {confirming ? 'Tap again. Every app and shell stops.' : `${DISTRO_NAMES[other.distro]} · /home/${other.username}`}
                  </Text>
                </View>
                {onSwitchTo === undefined ? null : (
                  <Text style={[styles.switchLabel, confirming && styles.switchConfirm]}>
                    {switching && confirming ? 'SWITCHING…' : confirming ? 'CONFIRM' : 'SWITCH ›'}
                  </Text>
                )}
              </Pressable>
            );
          })}
          {missing === undefined || onAddUser === undefined ? null : (
            <SettingsRow
              detail={missing === 'debian' ? 'glibc and apt, for tools Alpine can’t run. Experimental.' : 'Small and fast, with musl and apk.'}
              label={`Add ${missing === 'alpine' ? 'an' : 'a'} ${DISTRO_NAMES[missing]} user`}
              right={<SettingsAction label="ADD" onPress={onAddUser} testID="user-account-add-user" />}
              testID={`user-account-add-${missing}`}
            />
          )}
        </SettingsSection>

        {onLogout === undefined ? null : <LogoutSection onLogout={onLogout} />}
      </ScrollView>
    </ScreenShell>
  );
}

/** The signed-in user's hieroglyph and Linux username. The password stays as it is. */
function EditUserCard({user, onSave, onSaved, onCancel}: Readonly<{
  user: ProfileUser;
  onSave: (user: ProfileUser) => Promise<SaveProfileResult>;
  onSaved: () => void;
  onCancel: () => void;
}>): React.JSX.Element {
  const [avatar, setAvatar] = React.useState<AvatarId>(user.avatar);
  const [username, setUsername] = React.useState(user.username);
  const [saving, setSaving] = React.useState(false);
  const [error, setError] = React.useState<string | undefined>();
  const changed = avatar !== user.avatar || username !== user.username;

  const save = React.useCallback(async () => {
    const problem = usernameProblem(username);
    if (problem !== undefined) {
      setError(problem);
      return;
    }
    setSaving(true);
    setError(undefined);
    const result = await onSave({distro: user.distro, username, avatar});
    setSaving(false);
    if (result.kind === 'success') onSaved();
    else setError(`Couldn’t save (${result.code}). Try again.`);
  }, [avatar, onSave, onSaved, user.distro, username]);

  return (
    <View style={styles.card} testID="user-account-editor">
      <AvatarPicker onChange={setAvatar} value={avatar} />
      <TextInput
        accessibilityLabel="Username"
        autoCapitalize="none"
        autoComplete="off"
        autoCorrect={false}
        maxLength={32}
        onChangeText={setUsername}
        placeholder="Username"
        placeholderTextColor={uiColors.subdued}
        style={styles.input}
        testID="user-account-username"
        value={username} />
      <Text style={styles.note}>
        {username === user.username ? `Your home is /home/${username}.` : username.length === 0 ? 'Your username is also your home folder’s name.' : `Your home becomes /home/${username}; /home/${user.username} keeps working as a link to it. Apps and shells already running keep the old name until they restart.`}
      </Text>
      {error === undefined ? null : <Text style={styles.error} testID="user-account-edit-error">{error}</Text>}
      <View style={styles.editButtons}>
        <Pressable accessibilityRole="button" disabled={saving} onPress={onCancel} style={styles.cancel} testID="user-account-edit-cancel">
          <Text style={styles.cancelText}>CANCEL</Text>
        </Pressable>
        <Pressable
          accessibilityRole="button"
          disabled={saving || !changed}
          onPress={() => { void save(); }}
          style={[styles.save, (saving || !changed) && styles.saveIdle]}
          testID="user-account-edit-save">
          <Text style={styles.saveText}>{saving ? 'SAVING…' : 'SAVE'}</Text>
        </Pressable>
      </View>
    </View>
  );
}

/** Log out takes a second tap, since it ends every running app and shell. */
function LogoutSection({onLogout}: Readonly<{onLogout: () => Promise<boolean>}>): React.JSX.Element {
  const [armed, setArmed] = React.useState(false);
  const [stopping, setStopping] = React.useState(false);
  const [failed, setFailed] = React.useState(false);
  const mountedRef = React.useRef(true);
  React.useEffect(() => () => { mountedRef.current = false; }, []);

  const press = React.useCallback(async () => {
    if (!armed) {
      setArmed(true);
      setFailed(false);
      return;
    }
    setStopping(true);
    const stopped = await onLogout();
    if (!mountedRef.current) return;
    setStopping(false);
    setArmed(false);
    setFailed(!stopped);
  }, [armed, onLogout]);

  const label = stopping ? 'Stopping…' : armed ? 'Tap again to log out' : 'Log out';
  return (
    <View style={styles.logout}>
      <View style={styles.logoutGroup}>
        <Pressable
          accessibilityLabel={label}
          accessibilityRole="button"
          disabled={stopping}
          focusable={false}
          onPress={() => { void press(); }}
          style={styles.logoutRow}
          testID="user-account-logout">
          <Text style={styles.logoutText}>{label}</Text>
        </Pressable>
      </View>
      <Text style={[styles.logoutNote, failed && styles.logoutFailed]}>
        {failed ? 'Couldn’t stop every session. Try again.' : armed ? 'Every running app and shell will be stopped.' : 'Stops all apps and shells, then opens sign in.'}
      </Text>
    </View>
  );
}

const styles = StyleSheet.create({
  content: {paddingHorizontal: 18, paddingBottom: 28},
  card: {alignItems: 'center', backgroundColor: uiColors.panel, borderColor: uiColors.border, borderRadius: 14, borderWidth: 1, paddingHorizontal: 18, paddingVertical: 24},
  hero: {alignItems: 'center', paddingBottom: 6, paddingTop: 22},
  username: {color: uiColors.ink, fontFamily: UI_FONT_FAMILY, fontSize: 22, fontWeight: '800', marginTop: 16},
  meta: {color: uiColors.subdued, fontFamily: UI_FONT_FAMILY, fontSize: 11, marginTop: 6},
  pen: {alignItems: 'center', backgroundColor: uiColors.accent, borderColor: uiColors.background, borderRadius: 16, borderWidth: 3, bottom: -8, height: 32, justifyContent: 'center', position: 'absolute', right: -8, width: 32},
  penText: {color: uiColors.inset, fontSize: 14, fontWeight: '900', includeFontPadding: false},
  editor: {marginTop: 18},
  distro: {color: uiColors.accent, letterSpacing: 0.9},
  input: {alignSelf: 'stretch', backgroundColor: uiColors.background, borderColor: uiColors.borderSoft, borderRadius: 8, borderWidth: 1, color: uiColors.ink, fontFamily: UI_FONT_FAMILY, fontSize: 14, marginTop: 16, minHeight: 48, paddingHorizontal: 12},
  note: {alignSelf: 'stretch', color: uiColors.subdued, fontFamily: UI_FONT_FAMILY, fontSize: 10, lineHeight: 15, marginTop: 8},
  error: {alignSelf: 'stretch', color: uiColors.danger, fontFamily: UI_FONT_FAMILY, fontSize: 11, lineHeight: 16, marginTop: 8},
  editButtons: {alignSelf: 'stretch', flexDirection: 'row', gap: 8, marginTop: 16},
  cancel: {alignItems: 'center', borderColor: uiColors.borderSoft, borderRadius: 8, borderWidth: 1, flex: 1, justifyContent: 'center', minHeight: 44},
  cancelText: {color: uiColors.muted, fontFamily: UI_FONT_FAMILY, fontSize: 11, fontWeight: '800', letterSpacing: 0.6},
  save: {alignItems: 'center', backgroundColor: uiColors.accent, borderRadius: 8, flex: 1, justifyContent: 'center', minHeight: 44},
  saveIdle: {opacity: 0.4},
  saveText: {color: uiColors.background, fontFamily: UI_FONT_FAMILY, fontSize: 11, fontWeight: '900', letterSpacing: 0.6},
  other: {alignItems: 'center', flexDirection: 'row', gap: 12, minHeight: 64, paddingHorizontal: 14, paddingVertical: 12},
  otherCopy: {flex: 1, minWidth: 0},
  otherName: {color: uiColors.ink, fontFamily: UI_FONT_FAMILY, fontSize: 13, fontWeight: '700'},
  otherDetail: {color: uiColors.subdued, fontFamily: UI_FONT_FAMILY, fontSize: 10, marginTop: 3},
  otherWarning: {color: uiColors.warning},
  switchLabel: {color: uiColors.accent, fontFamily: UI_FONT_FAMILY, fontSize: 10, fontWeight: '800', letterSpacing: 0.6},
  switchConfirm: {color: uiColors.warning},
  logout: {marginTop: 22},
  logoutGroup: {backgroundColor: uiColors.panel, borderColor: uiColors.border, borderRadius: 14, borderWidth: 1, overflow: 'hidden'},
  logoutRow: {alignItems: 'center', justifyContent: 'center', minHeight: 52},
  logoutText: {color: uiColors.danger, fontFamily: UI_FONT_FAMILY, fontSize: 13, fontWeight: '800'},
  logoutNote: {color: uiColors.subdued, fontFamily: UI_FONT_FAMILY, fontSize: 9, lineHeight: 14, marginTop: 8, textAlign: 'center'},
  logoutFailed: {color: uiColors.danger},
});
