import React from 'react';
import {ActivityIndicator, StyleSheet, Text, TextInput, View} from 'react-native';
import {AuthScreenLayout, authStyles} from './AuthScreenLayout';
import {uiColors} from './brand';
import {EntryIcon} from './EntryIcon';
import {InteractivePressable as Pressable} from './InteractivePressable';
import {UI_FONT_FAMILY} from './typography';
import {
  DEFAULT_BACKGROUND_PERMISSION_ACTIONS,
  useBackgroundPermissions,
  type BackgroundPermissionActions,
} from './BackgroundPermissions';

export type OnboardingPermissions = BackgroundPermissionActions;

export type OnboardingScreenProps = Readonly<{
  runtimeReady: boolean;
  onComplete: (password: string, onToolsReady: () => void) => Promise<void>;
  error?: string;
  permissions?: OnboardingPermissions;
}>;

export function OnboardingScreen({runtimeReady, onComplete, error, permissions = DEFAULT_BACKGROUND_PERMISSION_ACTIONS}: OnboardingScreenProps): React.JSX.Element {
  const [step, setStep] = React.useState<'permissions' | 'password'>('permissions');
  if (step === 'permissions') {
    return <PermissionsStep permissions={permissions} onContinue={() => setStep('password')} />;
  }
  return <PasswordStep error={error} onComplete={onComplete} runtimeReady={runtimeReady} />;
}

type PermissionsStepProps = Readonly<{
  permissions: OnboardingPermissions;
  onContinue: () => void;
}>;

/**
 * Asked first: without these, Android pauses agents in the background and
 * the user never hears that a session needs them.
 */
function PermissionsStep({permissions, onContinue}: PermissionsStepProps): React.JSX.Element {
  const state = useBackgroundPermissions(permissions);
  const notificationsAllowed = state.granted?.notifications === true;
  const batteryAllowed = state.granted?.batteryUnrestricted === true;
  // One button walks through whatever is still missing, then continues.
  const primary = state.allGranted
    ? onContinue
    : !notificationsAllowed ? state.requestNotifications : state.requestBattery;
  return (
    <AuthScreenLayout brandTestID="setup" screenTestID="onboarding-permissions">
      <View style={styles.permissionsStep}>
        <Text style={styles.permissionsTitle}>Keep your agents running</Text>
        <Text style={styles.permissionsDetail}>Two permissions let sessions continue while the screen is off.</Text>
        <View style={styles.checklist}>
          <PermissionCheck allowed={notificationsAllowed} icon="bell" label="Notifications" onPress={state.requestNotifications} testID="permission-notifications" />
          <PermissionCheck allowed={batteryAllowed} icon="battery" label="Unrestricted battery" onPress={state.requestBattery} testID="permission-battery" />
        </View>
        <Pressable accessibilityRole="button" onPress={primary} style={authStyles.button} testID="permissions-continue">
          <Text style={authStyles.buttonText}>{state.allGranted ? 'CONTINUE  →' : 'ALLOW'}</Text>
        </Pressable>
        {state.allGranted ? null : (
          <Pressable accessibilityRole="button" onPress={onContinue} style={styles.skip} testID="permissions-skip">
            <Text style={styles.skipText}>Not now</Text>
          </Pressable>
        )}
      </View>
    </AuthScreenLayout>
  );
}

type PermissionCheckProps = Readonly<{allowed: boolean; icon: 'bell' | 'battery'; label: string; onPress: () => void; testID: string}>;

/** One permission: icon, name, and a check once Android reports it allowed. */
function PermissionCheck({allowed, icon, label, onPress, testID}: PermissionCheckProps): React.JSX.Element {
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
      <Text style={styles.checkLabel}>{label}</Text>
      <View style={[styles.checkMark, allowed && styles.checkMarkOn]}>
        {allowed ? <EntryIcon kind="check" size={14} style={styles.checkIcon} /> : null}
      </View>
    </Pressable>
  );
}

type PasswordStepProps = Readonly<{
  runtimeReady: boolean;
  onComplete: (password: string, onToolsReady: () => void) => Promise<void>;
  error?: string;
}>;

function PasswordStep({runtimeReady, onComplete, error}: PasswordStepProps): React.JSX.Element {
  const [password, setPassword] = React.useState('');
  const [progressStage, setProgressStage] = React.useState<'provisioning' | 'saving-profile' | null>(null);
  const [validationError, setValidationError] = React.useState<string | undefined>();

  const submit = React.useCallback(async () => {
    if (password.length < 4) {
      setValidationError('Choose a password with at least 4 characters.');
      return;
    }
    setValidationError(undefined);
    setProgressStage('provisioning');
    try {
      await onComplete(password, () => setProgressStage('saving-profile'));
    } finally {
      setProgressStage(null);
    }
  }, [onComplete, password]);

  const isWorking = progressStage !== null;
  // While setup runs, the field locks with a check and the button itself
  // carries the spinner and the current step.
  const progressLabel = progressStage === 'saving-profile' ? 'SAVING YOUR PROFILE…' : 'INSTALLING LINUX TOOLS…';

  return (
    <AuthScreenLayout brandTestID="setup" screenTestID="onboarding-screen">
      <View style={authStyles.card}>
        <View>
          <TextInput accessibilityLabel="Create password" autoCapitalize="none" autoCorrect={false} editable={!isWorking} onChangeText={setPassword} placeholder="Create password" placeholderTextColor={uiColors.subdued} secureTextEntry style={[authStyles.input, isWorking && styles.inputLocked]} testID="profile-password" value={password} />
          {isWorking ? (
            <View style={styles.inputCheck} testID="profile-password-locked">
              <EntryIcon kind="check" size={12} style={styles.checkIcon} />
            </View>
          ) : null}
        </View>

        {validationError !== undefined ? <Text style={authStyles.error} testID="profile-error">{validationError}</Text> : null}
        {error !== undefined ? <Text style={authStyles.error} testID="profile-save-error">{error}</Text> : null}
        <Pressable
          accessibilityLabel={isWorking ? progressLabel : undefined}
          accessibilityRole="button"
          accessibilityState={{busy: isWorking, disabled: !runtimeReady || isWorking}}
          disabled={!runtimeReady || isWorking}
          onPress={() => { void submit(); }}
          style={[authStyles.button, !runtimeReady && !isWorking && authStyles.disabled]}
          testID="profile-continue">
          {isWorking ? (
            <View accessibilityLiveRegion="polite" style={styles.buttonProgress} testID="onboarding-progress">
              <ActivityIndicator color={uiColors.background} size="small" />
              <Text style={authStyles.buttonText} testID="onboarding-progress-title">{progressLabel}</Text>
            </View>
          ) : (
            <Text style={authStyles.buttonText}>{runtimeReady ? 'CONTINUE  →' : 'PREPARING…'}</Text>
          )}
        </Pressable>
      </View>
    </AuthScreenLayout>
  );
}

const styles = StyleSheet.create({
  inputLocked: {color: uiColors.muted, paddingRight: 44},
  inputCheck: {alignItems: 'center', backgroundColor: uiColors.accent, borderRadius: 10, height: 20, justifyContent: 'center', position: 'absolute', right: 14, top: 16, width: 20},
  buttonProgress: {alignItems: 'center', flexDirection: 'row', gap: 10},
  permissionsStep: {alignSelf: 'center', maxWidth: 360, width: '100%'},
  permissionsTitle: {color: uiColors.ink, fontFamily: UI_FONT_FAMILY, fontSize: 18, fontWeight: '800', textAlign: 'center'},
  permissionsDetail: {color: uiColors.muted, fontFamily: UI_FONT_FAMILY, fontSize: 11, lineHeight: 17, marginTop: 8, textAlign: 'center'},
  checklist: {borderBottomColor: uiColors.borderSoft, borderBottomWidth: 1, marginTop: 24},
  check: {alignItems: 'center', borderTopColor: uiColors.borderSoft, borderTopWidth: 1, flexDirection: 'row', minHeight: 56},
  checkLabel: {color: uiColors.ink, flex: 1, fontFamily: UI_FONT_FAMILY, fontSize: 13},
  checkMark: {alignItems: 'center', borderColor: uiColors.border, borderRadius: 11, borderWidth: 1.5, height: 22, justifyContent: 'center', width: 22},
  checkMarkOn: {backgroundColor: uiColors.accent, borderColor: uiColors.accent},
  checkIcon: {marginRight: 0},
  skip: {alignItems: 'center', justifyContent: 'center', marginTop: 6, minHeight: 44},
  skipText: {color: uiColors.muted, fontFamily: UI_FONT_FAMILY, fontSize: 11},
});
