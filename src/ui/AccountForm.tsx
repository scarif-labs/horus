import React from 'react';
import {StyleSheet, Text, TextInput, View} from 'react-native';
import {AVATAR_IDS, usernameProblem, type AvatarId} from '../profile/profileStore';
import {authStyles} from './AuthScreenLayout';
import {uiColors} from './brand';
import {InteractivePressable as Pressable} from './InteractivePressable';
import {AVATAR_LABELS, ProfileAvatar} from './ProfileAvatar';
import {UI_FONT_FAMILY} from './typography';

const MIN_PASSWORD_LENGTH = 4;

export type AccountDraft = Readonly<{username: string; avatar: AvatarId; password: string}>;

/** Form state for a new user, and the check that runs before it is used. */
export function useAccountDraft(): Readonly<{
  avatar: AvatarId;
  setAvatar: (avatar: AvatarId) => void;
  username: string;
  setUsername: (username: string) => void;
  password: string;
  setPassword: (password: string) => void;
  confirmation: string;
  setConfirmation: (confirmation: string) => void;
  error: string | undefined;
  /** The finished user, or undefined after showing what is wrong. */
  validate: () => AccountDraft | undefined;
}> {
  const [avatar, setAvatar] = React.useState<AvatarId>('falcon');
  const [username, setUsernameRaw] = React.useState('');
  const [password, setPassword] = React.useState('');
  const [confirmation, setConfirmation] = React.useState('');
  const [error, setError] = React.useState<string | undefined>();
  // Linux logins are lowercase; fold as the user types instead of rejecting.
  const setUsername = React.useCallback((value: string) => setUsernameRaw(value.toLowerCase().replace(/\s/g, '')), []);
  const validate = React.useCallback((): AccountDraft | undefined => {
    const problem = usernameProblem(username);
    if (problem !== undefined) {
      setError(problem);
      return undefined;
    }
    if (password.length < MIN_PASSWORD_LENGTH) {
      setError(`Use at least ${MIN_PASSWORD_LENGTH} characters for the password.`);
      return undefined;
    }
    if (password !== confirmation) {
      setError('Passwords don’t match.');
      return undefined;
    }
    setError(undefined);
    return {username, avatar, password};
  }, [avatar, confirmation, password, username]);
  return {avatar, setAvatar, username, setUsername, password, setPassword, confirmation, setConfirmation, error, validate};
}

type AccountFormProps = Readonly<{
  draft: ReturnType<typeof useAccountDraft>;
  onSubmit: () => void;
  /** The distribution the user lives in, for the note under the fields. */
  distroName: string;
}>;

/** The nine hieroglyphs, three across; also used to change the signed-in user's. */
export function AvatarPicker({value, onChange}: Readonly<{value: AvatarId; onChange: (avatar: AvatarId) => void}>): React.JSX.Element {
  return (
    <View accessibilityRole="radiogroup" style={styles.grid} testID="account-avatars">
      {AVATAR_IDS.map(id => {
        const selected = value === id;
        return (
          <Pressable
            key={id}
            accessibilityLabel={AVATAR_LABELS[id]}
            accessibilityRole="radio"
            accessibilityState={{checked: selected}}
            onPress={() => onChange(id)}
            style={styles.cell}
            testID={`account-avatar-${id}${selected ? '-selected' : ''}`}>
            <ProfileAvatar avatar={id} selected={selected} size={60} />
          </Pressable>
        );
      })}
    </View>
  );
}

/** Pick a hieroglyph, a Linux username, and a password. */
export function AccountForm({draft, onSubmit, distroName}: AccountFormProps): React.JSX.Element {
  const passwordRef = React.useRef<React.ElementRef<typeof TextInput>>(null);
  const confirmRef = React.useRef<React.ElementRef<typeof TextInput>>(null);
  return (
    <View>
      <AvatarPicker onChange={draft.setAvatar} value={draft.avatar} />
      <TextInput
        accessibilityLabel="Username"
        autoCapitalize="none"
        autoComplete="off"
        autoCorrect={false}
        maxLength={32}
        onChangeText={draft.setUsername}
        onSubmitEditing={() => passwordRef.current?.focus()}
        placeholder="Username"
        placeholderTextColor={uiColors.subdued}
        returnKeyType="next"
        style={[authStyles.input, styles.field]}
        submitBehavior="submit"
        testID="account-username"
        value={draft.username} />
      <TextInput
        accessibilityLabel="Create password"
        autoCapitalize="none"
        autoCorrect={false}
        onChangeText={draft.setPassword}
        onSubmitEditing={() => confirmRef.current?.focus()}
        placeholder="Create password"
        placeholderTextColor={uiColors.subdued}
        ref={passwordRef}
        returnKeyType="next"
        secureTextEntry
        style={[authStyles.input, styles.field]}
        submitBehavior="submit"
        testID="account-password"
        value={draft.password} />
      <TextInput
        accessibilityLabel="Repeat password"
        autoCapitalize="none"
        autoCorrect={false}
        onChangeText={draft.setConfirmation}
        onSubmitEditing={onSubmit}
        placeholder="Repeat password"
        placeholderTextColor={uiColors.subdued}
        ref={confirmRef}
        returnKeyType="done"
        secureTextEntry
        style={[authStyles.input, styles.field]}
        testID="account-password-confirm"
        value={draft.confirmation} />
      <Text style={styles.note}>
        {`Your login in ${distroName}, with a home of its own. The password stays on this phone and can’t be recovered.`}
      </Text>
      {draft.error === undefined ? null : <Text style={authStyles.error} testID="account-error">{draft.error}</Text>}
    </View>
  );
}

const styles = StyleSheet.create({
  // Three across: 60 dp avatars with 6 dp around each.
  grid: {alignSelf: 'center', flexDirection: 'row', flexWrap: 'wrap', marginBottom: 6, width: 3 * 72},
  cell: {alignItems: 'center', justifyContent: 'center', padding: 6},
  field: {marginTop: 10},
  note: {color: uiColors.subdued, fontFamily: UI_FONT_FAMILY, fontSize: 10, lineHeight: 15, marginTop: 10},
});
