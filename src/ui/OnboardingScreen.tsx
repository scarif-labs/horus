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
  const progressTitle = progressStage === 'saving-profile'
    ? 'SAVING YOUR PROFILE'
    : 'PROVISIONING ALPINE WORKSPACE';
  const progressDetail = progressStage === 'saving-profile'
    ? 'Finishing your local sign-in setup…'
    : 'Installing CLI tools…';

  return (
    <AuthScreenLayout brandTestID="setup" screenTestID="onboarding-screen">
      <View style={authStyles.card}>
        <TextInput accessibilityLabel="Create password" autoCapitalize="none" autoCorrect={false} editable={!isWorking} onChangeText={setPassword} placeholder="Create password" placeholderTextColor={uiColors.subdued} secureTextEntry style={authStyles.input} testID="profile-password" value={password} />

        {validationError !== undefined ? <Text style={authStyles.error} testID="profile-error">{validationError}</Text> : null}
        {error !== undefined ? <Text style={authStyles.error} testID="profile-save-error">{error}</Text> : null}
        {isWorking ? (
          <View accessibilityLiveRegion="polite" style={styles.progress} testID="onboarding-progress">
            <ActivityIndicator color={uiColors.accent} size="small" />
            <View style={styles.progressCopy}>
              <Text style={styles.progressTitle} testID="onboarding-progress-title">{progressTitle}</Text>
              <Text style={styles.progressDetail} testID="onboarding-progress-detail">{progressDetail}</Text>
            </View>
          </View>
        ) : null}
        <Pressable accessibilityRole="button" disabled={!runtimeReady || isWorking} onPress={() => { void submit(); }} style={[authStyles.button, (!runtimeReady || isWorking) && authStyles.disabled]} testID="profile-continue">
          <Text style={authStyles.buttonText}>{isWorking ? 'PLEASE WAIT…' : runtimeReady ? 'CONTINUE  →' : 'PREPARING…'}</Text>
        </Pressable>
      </View>
    </AuthScreenLayout>
  );
}

const styles = StyleSheet.create({
  progress: {
    alignItems: 'center',
    backgroundColor: uiColors.background,
    borderColor: uiColors.borderSoft,
    borderRadius: 8,
    borderWidth: 1,
    flexDirection: 'row',
    gap: 12,
    marginTop: 14,
    paddingHorizontal: 12,
    paddingVertical: 11,
  },
  progressCopy: {flex: 1},
  progressTitle: {color: uiColors.accent, fontFamily: UI_FONT_FAMILY, fontSize: 10, fontWeight: '700', letterSpacing: 0.4},
  progressDetail: {color: uiColors.subdued, fontFamily: UI_FONT_FAMILY, fontSize: 10, lineHeight: 15, marginTop: 4},
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
