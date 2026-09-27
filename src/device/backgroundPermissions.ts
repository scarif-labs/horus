import {NativeModules, PermissionsAndroid, Platform} from 'react-native';

export type BackgroundPermissions = Readonly<{
  notifications: boolean;
  batteryUnrestricted: boolean;
}>;

type NativeBackgroundModule = Readonly<{
  getBackgroundPermissions: () => Promise<unknown>;
  requestBatteryUnrestricted: () => Promise<unknown>;
  openNotificationSettings: () => Promise<unknown>;
}>;

function nativeModule(): NativeBackgroundModule | null {
  const module = NativeModules.HorusDevice as Partial<NativeBackgroundModule> | undefined;
  if (
    module === undefined ||
    typeof module.getBackgroundPermissions !== 'function' ||
    typeof module.requestBatteryUnrestricted !== 'function' ||
    typeof module.openNotificationSettings !== 'function'
  ) return null;
  return module as NativeBackgroundModule;
}

export async function readBackgroundPermissions(): Promise<BackgroundPermissions | null> {
  const module = nativeModule();
  if (module === null) return null;
  try {
    const value = await module.getBackgroundPermissions();
    if (typeof value !== 'object' || value === null) return null;
    const record = value as Record<string, unknown>;
    if (typeof record.notifications !== 'boolean' || typeof record.batteryUnrestricted !== 'boolean') return null;
    return {notifications: record.notifications, batteryUnrestricted: record.batteryUnrestricted};
  } catch {
    return null;
  }
}

/**
 * Shows Android's notification prompt (Android 13+). If the user refused it
 * for good, or notifications were turned off in settings, open those instead.
 */
export async function requestNotifications(): Promise<void> {
  if (Platform.OS === 'android' && Number(Platform.Version) >= 33) {
    try {
      const result = await PermissionsAndroid.request(PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS);
      if (result === PermissionsAndroid.RESULTS.GRANTED) return;
      if (result === PermissionsAndroid.RESULTS.DENIED) return;
    } catch {
      // Fall through to the settings screen.
    }
  }
  await nativeModule()?.openNotificationSettings().catch(() => undefined);
}

export async function requestBatteryUnrestricted(): Promise<void> {
  await nativeModule()?.requestBatteryUnrestricted().catch(() => undefined);
}
