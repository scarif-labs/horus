import React from 'react';
import {AppState, StyleSheet, Text} from 'react-native';
import {uiColors} from './brand';
import {SettingsAction, SettingsCheck, SettingsRow, SettingsSection} from './SettingsList';
import {UI_FONT_FAMILY} from './typography';
import {
  readBackgroundPermissions,
  requestBatteryUnrestricted,
  requestNotifications,
  type BackgroundPermissions,
} from '../device/backgroundPermissions';

export type BackgroundPermissionActions = Readonly<{
  read: () => Promise<BackgroundPermissions | null>;
  requestNotifications: () => Promise<void>;
  requestBattery: () => Promise<void>;
}>;

export const DEFAULT_BACKGROUND_PERMISSION_ACTIONS: BackgroundPermissionActions = {
  read: readBackgroundPermissions,
  requestNotifications,
  requestBattery: requestBatteryUnrestricted,
};

export type BackgroundPermissionsState = Readonly<{
  /** Undefined while the first read is in flight; null when it is unavailable. */
  granted: BackgroundPermissions | null | undefined;
  allGranted: boolean;
  requestNotifications: () => void;
  requestBattery: () => void;
}>;

/**
 * Reads both background permissions and re-reads them whenever Horus comes
 * back to the foreground, since both requests hand over to a system screen.
 */
export function useBackgroundPermissions(actions: BackgroundPermissionActions): BackgroundPermissionsState {
  const [granted, setGranted] = React.useState<BackgroundPermissions | null | undefined>(undefined);
  const mountedRef = React.useRef(true);
  const {read} = actions;

  const refresh = React.useCallback(() => {
    read().then(value => {
      if (mountedRef.current) setGranted(value);
    }).catch(() => undefined);
  }, [read]);

  React.useEffect(() => {
    mountedRef.current = true;
    refresh();
    const subscription = AppState.addEventListener('change', state => {
      if (state === 'active') refresh();
    });
    return () => {
      mountedRef.current = false;
      subscription.remove();
    };
  }, [refresh]);

  const request = React.useCallback((action: () => Promise<void>) => {
    action().then(refresh, refresh);
  }, [refresh]);

  return {
    granted,
    allGranted: granted?.notifications === true && granted.batteryUnrestricted,
    requestNotifications: () => request(actions.requestNotifications),
    requestBattery: () => request(actions.requestBattery),
  };
}

type BackgroundPermissionsPanelProps = Readonly<{
  actions?: BackgroundPermissionActions;
}>;

/** Settings section for the permissions onboarding lets the user skip. */
export function BackgroundPermissionsPanel({actions = DEFAULT_BACKGROUND_PERMISSION_ACTIONS}: BackgroundPermissionsPanelProps): React.JSX.Element {
  const state = useBackgroundPermissions(actions);
  const known = state.granted !== undefined && state.granted !== null;
  const status = state.granted === undefined
    ? 'Checking…'
    : state.granted === null
      ? 'Horus could not read these permissions on this device.'
      : state.allGranted
        ? 'Both allowed. To turn them off, use Android settings.'
        : 'Without these, Android may pause sessions in the background.';
  const control = (allowed: boolean, label: string, onPress: () => void, testID: string) => (
    allowed
      ? <SettingsCheck testID={`${testID}-granted`} />
      : <SettingsAction accessibilityLabel={`Allow ${label.toLowerCase()}`} disabled={!known} label="ALLOW" onPress={onPress} testID={`${testID}-allow`} />
  );
  return (
    <SettingsSection
      footer={<Text style={[styles.status, known && !state.allGranted && styles.statusWarning]} testID="settings-permissions-status">{status}</Text>}
      testID="settings-background-permissions"
      title="BACKGROUND">
      <SettingsRow
        detail="Running sessions, and when an agent needs you"
        icon="bell"
        label="Notifications"
        right={control(state.granted?.notifications === true, 'Notifications', state.requestNotifications, 'settings-permission-notifications')}
        testID="settings-permission-notifications"
      />
      <SettingsRow
        detail="Keeps sessions running instead of pausing them"
        icon="battery"
        label="Unrestricted battery"
        right={control(state.granted?.batteryUnrestricted === true, 'Unrestricted battery', state.requestBattery, 'settings-permission-battery')}
        testID="settings-permission-battery"
      />
    </SettingsSection>
  );
}

const styles = StyleSheet.create({
  status: {color: uiColors.subdued, fontFamily: UI_FONT_FAMILY, fontSize: 9, lineHeight: 14, marginHorizontal: 4, marginTop: 8},
  statusWarning: {color: uiColors.warning},
});
