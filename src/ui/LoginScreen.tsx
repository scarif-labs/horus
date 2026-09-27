import React from 'react';
import {Text, TextInput, View} from 'react-native';
import {AuthScreenLayout, authStyles} from './AuthScreenLayout';
import {uiColors} from './brand';
import {InteractivePressable as Pressable} from './InteractivePressable';

export type LoginScreenProps = Readonly<{
  onLogin: (password: string) => Promise<void>;
  error?: string;
  notice?: string;
}>;

export function LoginScreen({onLogin, error, notice}: LoginScreenProps): React.JSX.Element {
  const [password, setPassword] = React.useState('');
  const [saving, setSaving] = React.useState(false);
  const [validationError, setValidationError] = React.useState<string | undefined>();

  const submit = React.useCallback(async () => {
    if (password.length < 4) {
      setValidationError('Enter your password to unlock Horus.');
      return;
    }
    setValidationError(undefined);
    setSaving(true);
    try {
      await onLogin(password);
    } finally {
      setSaving(false);
      setPassword('');
    }
  }, [onLogin, password]);

  return (
    <AuthScreenLayout brandTestID="login" screenTestID="login-screen">
      <View style={authStyles.card}>
        <TextInput accessibilityLabel="Password" autoCapitalize="none" autoCorrect={false} onChangeText={setPassword} placeholder="Password" placeholderTextColor={uiColors.subdued} secureTextEntry style={authStyles.input} testID="login-password" value={password} />
        {notice !== undefined ? <Text style={authStyles.notice} testID="login-notice">{notice}</Text> : null}
        {validationError !== undefined ? <Text style={authStyles.error} testID="login-validation-error">{validationError}</Text> : null}
        {error !== undefined ? <Text style={authStyles.error} testID="login-error">{error}</Text> : null}
        <Pressable accessibilityRole="button" disabled={saving} onPress={() => void submit()} style={[authStyles.button, saving && authStyles.disabled]} testID="login-submit">
          <Text style={authStyles.buttonText}>{saving ? 'SIGNING IN…' : 'SIGN IN  →'}</Text>
        </Pressable>
      </View>
    </AuthScreenLayout>
  );
}
