import React from 'react';
import {ActivityIndicator, AppState, StyleSheet, Text, TextInput, View} from 'react-native';
import {AuthScreenLayout, authStyles} from './AuthScreenLayout';
import {uiColors} from './brand';
import {InteractivePressable as Pressable} from './InteractivePressable';
import {UI_FONT_FAMILY} from './typography';
import {
  readBackgroundPermissions,
  requestBatteryUnrestricted,
  requestNotifications,
  type BackgroundPermissions,
} from '../device/backgroundPermissions';

export type OnboardingPermissions = Readonly<{
  read: () => Promise<BackgroundPermissions | null>;
  requestNotifications: () => Promise<void>;
  requestBattery: () => Promise<void>;
}>;

const DEFAULT_PERMISSIONS: OnboardingPermissions = {
  read: readBackgroundPermissions,
  requestNotifications,
  requestBattery: requestBatteryUnrestricted,
};

export type OnboardingScreenProps = Readonly<{
  runtimeReady: boolean;
  onComplete: (password: string, onToolsReady: () => void) => Promise<void>;
  error?: string;
  permissions?: OnboardingPermissions;
}>;

export function OnboardingScreen({runtimeReady, onComplete, error, permissions = DEFAULT_PERMISSIONS}: OnboardingScreenProps): React.JSX.Element {
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
  const [granted, setGranted] = React.useState<BackgroundPermissions | null>(null);
  const {read} = permissions;

  const refresh = React.useCallback(() => {
    read().then(setGranted).catch(() => undefined);
  }, [read]);

  React.useEffect(() => {
    refresh();
    // Both requests hand over to a system screen; re-check on the way back.
    const subscription = AppState.addEventListener('change', state => {
      if (state === 'active') refresh();
    });
    return () => subscription.remove();
  }, [refresh]);

  const request = React.useCallback(async (action: () => Promise<void>) => {
    await action();
    refresh();
  }, [refresh]);

  const allGranted = granted?.notifications === true && granted.batteryUnrestricted;
  return (
    <AuthScreenLayout brandTestID="setup" screenTestID="onboarding-permissions">
      <View style={authStyles.card}>
        <Text style={styles.stepTitle}>Keep your agents running</Text>
        <Text style={styles.stepDetail}>Sessions keep working while the screen is off or you use other apps. Android needs two permissions for that.</Text>
        <PermissionRow
          detail="Shows running sessions and tells you when an agent needs you."
          granted={granted?.notifications === true}
          label="NOTIFICATIONS"
          onRequest={() => { request(permissions.requestNotifications).catch(() => undefined); }}
          testID="permission-notifications"
        />
        <PermissionRow
          detail="Lets Android keep sessions running in the background instead of pausing them."
          granted={granted?.batteryUnrestricted === true}
          label="UNRESTRICTED BATTERY"
          onRequest={() => { request(permissions.requestBattery).catch(() => undefined); }}
          testID="permission-battery"
        />
        <Pressable accessibilityRole="button" onPress={onContinue} style={authStyles.button} testID="permissions-continue">
          <Text style={authStyles.buttonText}>{allGranted ? 'CONTINUE  →' : 'SKIP FOR NOW  →'}</Text>
        </Pressable>
        {allGranted ? null : <Text style={styles.stepHint}>You can change these later in Android settings.</Text>}
      </View>
    </AuthScreenLayout>
  );
}

type PermissionRowProps = Readonly<{
  label: string;
  detail: string;
  granted: boolean;
  onRequest: () => void;
  testID: string;
}>;

function PermissionRow({label, detail, granted, onRequest, testID}: PermissionRowProps): React.JSX.Element {
  return (
    <View style={styles.permission} testID={testID}>
      <View style={styles.permissionCopy}>
        <Text style={styles.permissionLabel}>{label}</Text>
        <Text style={styles.permissionDetail}>{detail}</Text>
      </View>
      {granted ? (
        <Text style={styles.permissionGranted} testID={`${testID}-granted`}>ALLOWED</Text>
      ) : (
        <Pressable accessibilityLabel={`Allow ${label.toLowerCase()}`} accessibilityRole="button" onPress={onRequest} style={styles.permissionButton} testID={`${testID}-allow`}>
          <Text style={styles.permissionButtonText}>ALLOW</Text>
        </Pressable>
      )}
    </View>
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
  stepTitle: {color: uiColors.ink, fontFamily: UI_FONT_FAMILY, fontSize: 16, fontWeight: '800'},
  stepDetail: {color: uiColors.muted, fontFamily: UI_FONT_FAMILY, fontSize: 11, lineHeight: 17, marginTop: 8},
  stepHint: {color: uiColors.subdued, fontFamily: UI_FONT_FAMILY, fontSize: 10, lineHeight: 15, marginTop: 10, textAlign: 'center'},
  permission: {
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
  permissionCopy: {flex: 1},
  permissionLabel: {color: uiColors.ink, fontFamily: UI_FONT_FAMILY, fontSize: 10, fontWeight: '800', letterSpacing: 0.5},
  permissionDetail: {color: uiColors.subdued, fontFamily: UI_FONT_FAMILY, fontSize: 10, lineHeight: 15, marginTop: 4},
  permissionGranted: {color: uiColors.accent, fontFamily: UI_FONT_FAMILY, fontSize: 9, fontWeight: '800', letterSpacing: 0.5},
  permissionButton: {alignItems: 'center', borderColor: uiColors.accent, borderRadius: 7, borderWidth: 1, justifyContent: 'center', minHeight: 34, minWidth: 64, paddingHorizontal: 10},
  permissionButtonText: {color: uiColors.accent, fontFamily: UI_FONT_FAMILY, fontSize: 9, fontWeight: '800', letterSpacing: 0.5},
});
