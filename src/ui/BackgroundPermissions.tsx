import React from 'react';
import {AppState, StyleSheet, Text, View} from 'react-native';
import {uiColors} from './brand';
import {InteractivePressable as Pressable} from './InteractivePressable';
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

type PermissionRowsProps = Readonly<{
  state: BackgroundPermissionsState;
  testIDPrefix?: string;
}>;

/** The notification and battery rows, each with ALLOW or ALLOWED. */
export function BackgroundPermissionRows({state, testIDPrefix = 'permission'}: PermissionRowsProps): React.JSX.Element {
  return (
    <>
      <PermissionRow
        detail="Shows running sessions and tells you when an agent needs you."
        granted={state.granted?.notifications === true}
        label="NOTIFICATIONS"
        onRequest={state.requestNotifications}
        testID={`${testIDPrefix}-notifications`}
      />
      <PermissionRow
        detail="Lets Android keep sessions running in the background instead of pausing them."
        granted={state.granted?.batteryUnrestricted === true}
        label="UNRESTRICTED BATTERY"
        onRequest={state.requestBattery}
        testID={`${testIDPrefix}-battery`}
      />
    </>
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

type BackgroundPermissionsPanelProps = Readonly<{
  actions?: BackgroundPermissionActions;
}>;

/** Settings panel for the permissions onboarding lets the user skip. */
export function BackgroundPermissionsPanel({actions = DEFAULT_BACKGROUND_PERMISSION_ACTIONS}: BackgroundPermissionsPanelProps): React.JSX.Element {
  const state = useBackgroundPermissions(actions);
  const status = state.granted === undefined
    ? 'Checking…'
    : state.granted === null
      ? 'Horus could not read these permissions on this device.'
      : state.allGranted
        ? 'Both allowed. To turn them off, use Android settings.'
        : 'Without these, Android may pause sessions in the background.';
  return (
    <View style={styles.panel} testID="settings-background-permissions">
      <Text style={styles.sectionLabel}>BACKGROUND</Text>
      <Text style={styles.title}>Keep your agents running</Text>
      <Text style={styles.detail}>Sessions keep working while the screen is off or you use other apps. Android needs two permissions for that.</Text>
      <BackgroundPermissionRows state={state} testIDPrefix="settings-permission" />
      <Text style={[styles.status, state.granted !== undefined && state.granted !== null && !state.allGranted && styles.statusWarning]} testID="settings-permissions-status">
        {status}
      </Text>
    </View>
  );
}

const styles = StyleSheet.create({
  panel: {backgroundColor: uiColors.panel, borderColor: uiColors.border, borderRadius: 14, borderWidth: 1, marginTop: 14, padding: 15},
  sectionLabel: {color: uiColors.muted, fontFamily: UI_FONT_FAMILY, fontSize: 10, letterSpacing: 0.8},
  title: {color: uiColors.ink, fontFamily: UI_FONT_FAMILY, fontSize: 17, fontWeight: '800', marginTop: 12},
  detail: {color: uiColors.muted, fontFamily: UI_FONT_FAMILY, fontSize: 10, lineHeight: 17, marginTop: 8},
  status: {color: uiColors.subdued, fontFamily: UI_FONT_FAMILY, fontSize: 10, lineHeight: 16, marginTop: 12},
  statusWarning: {color: uiColors.warning},
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
