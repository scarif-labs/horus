import {NativeModules} from 'react-native';

export type UserProfile = Readonly<{
  hasPassword: boolean;
}>;

type NativeProfileModule = Readonly<{
  getProfile: () => Promise<unknown>;
  saveProfile: (request: {password: string}) => Promise<unknown>;
  verifyPassword: (request: {password: string}) => Promise<unknown>;
}>;

function nativeProfileModule(): NativeProfileModule | null {
  const module = NativeModules.HorusDevice as NativeProfileModule | undefined;
  return module === undefined ? null : module;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isProfile(value: unknown): value is UserProfile {
  return (
    isRecord(value) &&
    value.configured === true &&
    typeof value.hasPassword === 'boolean'
  );
}

export async function readUserProfile(): Promise<UserProfile | null> {
  const module = nativeProfileModule();
  if (module === null) return null;
  try {
    const response = await module.getProfile();
    return isProfile(response) ? response : null;
  } catch {
    return null;
  }
}

export async function saveUserProfile(password: string): Promise<boolean> {
  if (password.length < 4 || password.length > 128) {
    return false;
  }
  const module = nativeProfileModule();
  if (module === null) return false;
  try {
    const response = await module.saveProfile({password});
    return isRecord(response) && response.status === 'success';
  } catch {
    return false;
  }
}

export type PasswordCheck =
  | Readonly<{kind: 'success'}>
  | Readonly<{kind: 'incorrect'}>
  /** Too many wrong attempts; native refuses to check until the wait is over. */
  | Readonly<{kind: 'locked'; retryAfterMs: number}>;

export async function verifyUserPassword(password: string): Promise<PasswordCheck> {
  const module = nativeProfileModule();
  if (module === null) return {kind: 'incorrect'};
  try {
    const response = await module.verifyPassword({password});
    if (!isRecord(response)) return {kind: 'incorrect'};
    if (response.status === 'success') return {kind: 'success'};
    if (response.status === 'locked' && typeof response.retryAfterMs === 'number' && response.retryAfterMs > 0) {
      return {kind: 'locked', retryAfterMs: response.retryAfterMs};
    }
    return {kind: 'incorrect'};
  } catch {
    return {kind: 'incorrect'};
  }
}

export function lockedOutMessage(retryAfterMs: number): string {
  const seconds = Math.ceil(retryAfterMs / 1000);
  const wait = seconds < 60 ? `${seconds} seconds` : `${Math.ceil(seconds / 60)} minutes`;
  return `Too many incorrect attempts. Try again in ${wait}.`;
}
