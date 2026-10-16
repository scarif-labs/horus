import React from 'react';
import {Animated, BackHandler, StyleSheet, Text, TextInput, View} from 'react-native';
import {DISTRO_NAMES, type LinuxDistro, type ProfileUser} from '../profile/profileStore';
import {AuthScreenLayout, authStyles} from './AuthScreenLayout';
import {uiColors} from './brand';
import {InteractivePressable as Pressable} from './InteractivePressable';
import {ProfileAvatar} from './ProfileAvatar';
import {UI_FONT_FAMILY} from './typography';

export type LoginScreenProps = Readonly<{
  /** Every user, Alpine first; at most one per distribution. */
  users: readonly ProfileUser[];
  onLogin: (user: ProfileUser, password: string) => Promise<void>;
  error?: string;
  notice?: string;
  /** Opens straight on this user's password, e.g. after "Switch to" on Account. */
  initialUser?: LinuxDistro;
}>;

/**
 * A macOS-style login window. With several users it first shows only their
 * avatars; picking one shows that user alone with a password field, and
 * "Switch user" goes back. A single user goes straight to the password.
 * Signing in also switches new sessions to that user's distribution.
 */
export function LoginScreen({users, onLogin, error, notice, initialUser}: LoginScreenProps): React.JSX.Element {
  const single = users.length === 1;
  const [picked, setPicked] = React.useState<ProfileUser | undefined>(() => users.find(user => user.distro === initialUser));
  // One user skips the list and the brand entirely, even if users load late.
  const selected = single ? users[0] : picked;
  const [password, setPassword] = React.useState('');
  const [saving, setSaving] = React.useState(false);
  const [validationError, setValidationError] = React.useState<string | undefined>();
  const passwordRef = React.useRef<React.ElementRef<typeof TextInput>>(null);
  const shake = React.useRef(new Animated.Value(0)).current;

  const choose = React.useCallback((user: ProfileUser) => {
    setPicked(user);
    setPassword('');
    setValidationError(undefined);
  }, []);

  const switchUser = React.useCallback(() => {
    setPicked(undefined);
    setPassword('');
    setValidationError(undefined);
  }, []);

  // Android's back button leaves the password for the user list, as Cancel does on a Mac.
  React.useEffect(() => {
    if (single || selected === undefined) return undefined;
    const subscription = BackHandler.addEventListener('hardwareBackPress', () => {
      switchUser();
      return true;
    });
    return () => subscription.remove();
  }, [selected, single, switchUser]);

  // A rejected password shakes the field, like the Mac login window.
  React.useEffect(() => {
    if (error === undefined && validationError === undefined) return;
    shake.setValue(0);
    Animated.sequence([10, -10, 7, -7, 0].map(toValue =>
      Animated.timing(shake, {duration: 55, toValue, useNativeDriver: true}),
    )).start();
  }, [error, shake, validationError]);

  const submit = React.useCallback(async () => {
    if (selected === undefined || saving) return;
    if (password.length < 4) {
      setValidationError(`Enter ${selected.username}’s password to unlock Horus.`);
      return;
    }
    setValidationError(undefined);
    setSaving(true);
    try {
      await onLogin(selected, password);
    } finally {
      setSaving(false);
      setPassword('');
      // Like the Mac, keep the cursor in the field for another try.
      passwordRef.current?.focus();
    }
  }, [onLogin, password, saving, selected]);

  if (selected === undefined) {
    return (
      <AuthScreenLayout brandTestID="login" screenTestID="login-screen">
        <View accessibilityRole="radiogroup" style={styles.users} testID="login-users">
          {users.map(user => (
            <Pressable
              key={user.distro}
              accessibilityLabel={`${user.username}, ${DISTRO_NAMES[user.distro]}`}
              accessibilityRole="radio"
              accessibilityState={{checked: false}}
              // Keyboard focus would draw Android's square highlight over the round avatar.
              focusable={false}
              onPress={() => choose(user)}
              style={styles.user}
              testID={`login-user-${user.distro}`}>
              <ProfileAvatar avatar={user.avatar} size={76} />
              <Text numberOfLines={1} style={styles.listName}>{user.username}</Text>
              <Text style={styles.distro}>{DISTRO_NAMES[user.distro]}</Text>
            </Pressable>
          ))}
        </View>
        {notice !== undefined ? <Text style={[authStyles.notice, styles.centered]} testID="login-notice">{notice}</Text> : null}
      </AuthScreenLayout>
    );
  }

  const canSubmit = !saving && password.length > 0;
  // Like the Mac, the chosen user replaces the brand as the focus.
  return (
    <AuthScreenLayout brandTestID="login" screenTestID="login-screen" showBrand={false}>
      <View style={styles.chosen}>
        <ProfileAvatar avatar={selected.avatar} selected size={96} testID={`login-user-${selected.distro}-selected`} />
        <Text numberOfLines={1} style={styles.name}>{selected.username}</Text>
        <Text style={[styles.distro, styles.distroChosen]}>{DISTRO_NAMES[selected.distro]}</Text>
        <Animated.View style={[styles.field, {transform: [{translateX: shake}]}]}>
          <TextInput
            accessibilityLabel={`${selected.username}’s password`}
            autoCapitalize="none"
            autoCorrect={false}
            autoFocus
            onChangeText={setPassword}
            onSubmitEditing={() => { void submit(); }}
            placeholder="Enter Password"
            placeholderTextColor={uiColors.subdued}
            ref={passwordRef}
            returnKeyType="go"
            secureTextEntry
            style={styles.input}
            testID="login-password"
            value={password} />
          <Pressable
            accessibilityLabel={saving ? 'Signing in' : 'Sign in'}
            accessibilityRole="button"
            disabled={saving}
            focusable={false}
            onPress={() => { void submit(); }}
            style={[styles.go, !canSubmit && styles.goIdle]}
            testID="login-submit">
            <Text style={[styles.goText, !canSubmit && styles.goTextIdle]}>{saving ? '…' : '→'}</Text>
          </Pressable>
        </Animated.View>
        {notice !== undefined ? <Text style={[authStyles.notice, styles.centered]} testID="login-notice">{notice}</Text> : null}
        {validationError !== undefined ? <Text style={[authStyles.error, styles.centered]} testID="login-validation-error">{validationError}</Text> : null}
        {error !== undefined ? <Text style={[authStyles.error, styles.centered]} testID="login-error">{error}</Text> : null}
        {single ? null : (
          <Pressable accessibilityRole="button" disabled={saving} focusable={false} onPress={switchUser} style={styles.back} testID="login-back">
            <Text style={styles.backText}>‹  Switch user</Text>
          </Pressable>
        )}
      </View>
    </AuthScreenLayout>
  );
}

// Hierarchy: in the list the Horus mark (112) leads and avatars (76) follow.
// Once a user is chosen the brand hides and their avatar (96) leads, then
// name, then distribution as a caption.
// Spacing steps in 8s: 8 inside a group, 16 between groups, 24 for actions.
const styles = StyleSheet.create({
  users: {flexDirection: 'row', flexWrap: 'wrap', gap: 24, justifyContent: 'center'},
  user: {alignItems: 'center', borderRadius: 16, maxWidth: 140, padding: 8},
  listName: {color: uiColors.ink, fontFamily: UI_FONT_FAMILY, fontSize: 14, fontWeight: '700', marginTop: 8},
  distro: {color: uiColors.subdued, fontFamily: UI_FONT_FAMILY, fontSize: 10, letterSpacing: 0.8, marginTop: 2, textTransform: 'uppercase'},
  chosen: {alignItems: 'center', alignSelf: 'center', maxWidth: 340, width: '100%'},
  name: {color: uiColors.ink, fontFamily: UI_FONT_FAMILY, fontSize: 16, fontWeight: '800', marginTop: 8},
  distroChosen: {color: uiColors.accent},
  field: {alignItems: 'center', alignSelf: 'stretch', backgroundColor: uiColors.panelRaised, borderColor: uiColors.borderSoft, borderRadius: 24, borderWidth: 1, flexDirection: 'row', marginTop: 24, minHeight: 48, paddingLeft: 16, paddingRight: 4},
  input: {color: uiColors.ink, flex: 1, fontFamily: UI_FONT_FAMILY, fontSize: 14, minHeight: 46, padding: 0},
  go: {alignItems: 'center', backgroundColor: uiColors.accent, borderRadius: 20, height: 40, justifyContent: 'center', width: 40},
  goIdle: {backgroundColor: 'transparent', borderColor: uiColors.borderSoft, borderWidth: 1},
  goText: {color: uiColors.background, fontFamily: UI_FONT_FAMILY, fontSize: 18, fontWeight: '900', includeFontPadding: false},
  goTextIdle: {color: uiColors.muted},
  centered: {alignSelf: 'stretch', marginTop: 8, textAlign: 'center'},
  back: {marginTop: 16, paddingHorizontal: 16, paddingVertical: 8},
  backText: {color: uiColors.muted, fontFamily: UI_FONT_FAMILY, fontSize: 12, fontWeight: '700'},
});
