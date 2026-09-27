import {NativeModules} from 'react-native';

export type DeviceSnapshot = Readonly<{
  freeStorageBytes: number;
  totalStorageBytes: number;
  freeMemoryBytes: number;
  totalMemoryBytes: number;
  wifiConnected: boolean;
  batteryPercent: number;
  capturedAtMs: number;
}>;

type NativeDeviceModule = Readonly<{
  getDeviceSnapshot: () => Promise<unknown>;
}>;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
function safeNonNegative(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0;
}

function isDeviceSnapshot(value: unknown): value is DeviceSnapshot {
  return (
    isRecord(value) &&
    safeNonNegative(value.freeStorageBytes) &&
    safeNonNegative(value.totalStorageBytes) &&
    safeNonNegative(value.freeMemoryBytes) &&
    safeNonNegative(value.totalMemoryBytes) &&
    typeof value.wifiConnected === 'boolean' &&
    typeof value.batteryPercent === 'number' &&
    Number.isFinite(value.batteryPercent) &&
    value.batteryPercent >= 0 &&
    value.batteryPercent <= 100 &&
    safeNonNegative(value.capturedAtMs)
  );
}

export async function readDeviceSnapshot(): Promise<DeviceSnapshot | null> {
  const module = NativeModules.HorusDevice as NativeDeviceModule | undefined;
  if (module === undefined) return null;
  try {
    const response = await module.getDeviceSnapshot();
    return isDeviceSnapshot(response) ? response : null;
  } catch {
    return null;
  }
}
