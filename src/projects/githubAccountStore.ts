import {NativeModules} from 'react-native';
import type {GithubAccount} from './githubRepositories';

type NativeGithubAccountModule = Readonly<{
  getGithubAccount: () => Promise<unknown>;
  saveGithubAccount: (request: {username: string; avatarUrl?: string}) => Promise<unknown>;
  clearGithubAccount: () => Promise<unknown>;
}>;

const GITHUB_USERNAME_PATTERN = /^[A-Za-z0-9][A-Za-z0-9-]{0,38}$/;
const GITHUB_AVATAR_PATH_PATTERN = /^https:\/\/avatars\.githubusercontent\.com\/[A-Za-z0-9._~!$'()*+,;=:@%/-]{1,256}$/;

function nativeGithubAccountModule(): NativeGithubAccountModule | null {
  const module = NativeModules.HorusDevice as NativeGithubAccountModule | undefined;
  return module === undefined ? null : module;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isGithubAccount(value: unknown): value is GithubAccount {
  if (!isRecord(value) || typeof value.username !== 'string' || !GITHUB_USERNAME_PATTERN.test(value.username)) return false;
  return value.avatarUrl === undefined || (typeof value.avatarUrl === 'string' && GITHUB_AVATAR_PATH_PATTERN.test(value.avatarUrl));
}

/** Reads only the cached GitHub identity; tokens remain owned by gh. */
export async function readStoredGithubAccount(): Promise<GithubAccount | undefined> {
  const module = nativeGithubAccountModule();
  if (module === null) return undefined;
  try {
    const response = await module.getGithubAccount();
    return isGithubAccount(response) ? response : undefined;
  } catch {
    return undefined;
  }
}

/** Persists the non-secret identity shown by the launcher. */
export async function saveGithubAccount(account: GithubAccount): Promise<boolean> {
  if (!isGithubAccount(account)) return false;
  const module = nativeGithubAccountModule();
  if (module === null) return false;
  try {
    const response = await module.saveGithubAccount(account);
    return isRecord(response) && response.status === 'success';
  } catch {
    return false;
  }
}

/** Clears the cached, non-secret identity after gh confirms local logout. */
export async function clearStoredGithubAccount(): Promise<boolean> {
  const module = nativeGithubAccountModule();
  if (module === null) return false;
  try {
    const response = await module.clearGithubAccount();
    return isRecord(response) && response.status === 'success';
  } catch {
    return false;
  }
}
