import {NativeModules} from 'react-native';

/** The Linux distributions a user can belong to; at most one user each. */
export const LINUX_DISTROS = ['alpine', 'debian'] as const;
export type LinuxDistro = (typeof LINUX_DISTROS)[number];

export const DISTRO_NAMES: Readonly<Record<LinuxDistro, string>> = {alpine: 'Alpine', debian: 'Debian'};

/** Avatar ids, mirrored from ProfileUsersCore.AVATARS; each names an image in assets/avatars. */
export const AVATAR_IDS = ['falcon', 'ibis', 'jackal', 'cat', 'cobra', 'scarab', 'lotus', 'papyrus', 'sun-disk'] as const;
export type AvatarId = (typeof AVATAR_IDS)[number];

/** One Linux user. The username is the real account name inside that distribution. */
export type ProfileUser = Readonly<{
  distro: LinuxDistro;
  username: string;
  avatar: AvatarId;
}>;

export type UserProfile = Readonly<{
  /** Configured users, Alpine first. Never empty. */
  users: readonly ProfileUser[];
  /** An upgrade from before Linux users that has not seen Account yet. */
  accountIntro?: boolean;
}>;

export type NewProfileUser = ProfileUser & Readonly<{password: string}>;

export type SaveProfileResult =
  | Readonly<{kind: 'success'}>
  | Readonly<{kind: 'error'; code: string}>;

type NativeProfileModule = Readonly<{
  getProfile: () => Promise<unknown>;
  saveProfile: (request: {distro: string; username: string; avatar: string; password: string}) => Promise<unknown>;
  verifyPassword: (request: {distro: string; password: string}) => Promise<unknown>;
  updateProfile: (request: {distro: string; username: string; avatar: string}) => Promise<unknown>;
  dismissAccountIntro: () => Promise<unknown>;
}>;

function nativeProfileModule(): NativeProfileModule | null {
  const module = NativeModules.HorusDevice as NativeProfileModule | undefined;
  return module === undefined ? null : module;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

export function isLinuxDistro(value: unknown): value is LinuxDistro {
  return typeof value === 'string' && (LINUX_DISTROS as readonly string[]).includes(value);
}

export function isAvatarId(value: unknown): value is AvatarId {
  return typeof value === 'string' && (AVATAR_IDS as readonly string[]).includes(value);
}

const USERNAME_PATTERN = /^[a-z_][a-z0-9_-]{0,31}$/;
/** Mirrors ProfileUsersCore.RESERVED_USERNAMES: names a guest system account already uses. */
const RESERVED_USERNAMES = new Set([
  'root', 'daemon', 'bin', 'sys', 'sync', 'games', 'man', 'lp', 'mail', 'news', 'uucp', 'proxy',
  'www-data', 'backup', 'list', 'irc', 'gnats', 'nobody', 'nogroup', '_apt', 'messagebus',
  'adm', 'shutdown', 'halt', 'operator', 'postmaster', 'cron', 'ftp', 'sshd', 'at', 'squid',
  'xfs', 'ntp', 'smmsp', 'guest', 'utmp', 'staff', 'users', 'wheel', 'tty', 'disk', 'kmem',
  'audio', 'video', 'input', 'kvm', 'dialout', 'floppy', 'cdrom', 'tape', 'abuild', 'ping',
  'shadow', 'sudo', 'src', 'plugdev', 'netdev', 'systemd-network', 'systemd-timesync',
]);

/** Why a username cannot be a Linux login, or undefined when it can. */
export function usernameProblem(username: string): string | undefined {
  if (username.length === 0) return 'Choose a username.';
  if (username.length > 32) return 'Use at most 32 characters.';
  if (!USERNAME_PATTERN.test(username)) {
    return 'Use lowercase letters, digits, _ or -, starting with a letter.';
  }
  if (RESERVED_USERNAMES.has(username) || username.startsWith('harness_')) {
    return 'That name belongs to a system account. Pick another.';
  }
  return undefined;
}

function isProfileUser(value: unknown): value is ProfileUser {
  return (
    isRecord(value) &&
    isLinuxDistro(value.distro) &&
    typeof value.username === 'string' &&
    usernameProblem(value.username) === undefined &&
    isAvatarId(value.avatar)
  );
}

export async function readUserProfile(): Promise<UserProfile | null> {
  const module = nativeProfileModule();
  if (module === null) return null;
  try {
    const response = await module.getProfile();
    if (!isRecord(response) || response.configured !== true || !Array.isArray(response.users)) return null;
    const users = response.users.filter(isProfileUser);
    const distros = new Set(users.map(user => user.distro));
    if (users.length === 0 || users.length !== response.users.length || distros.size !== users.length) return null;
    return response.accountIntro === true ? {users, accountIntro: true} : {users};
  } catch {
    return null;
  }
}

/** The Home card that points an upgraded user at Account; shown once. */
export async function dismissAccountIntro(): Promise<void> {
  try {
    await nativeProfileModule()?.dismissAccountIntro();
  } catch {
    // Shown again next time; harmless.
  }
}

export async function saveUserProfile(user: NewProfileUser): Promise<SaveProfileResult> {
  if (user.password.length < 4 || user.password.length > 128) return {kind: 'error', code: 'invalid_password'};
  if (usernameProblem(user.username) !== undefined) return {kind: 'error', code: 'invalid_username'};
  const module = nativeProfileModule();
  if (module === null) return {kind: 'error', code: 'unavailable'};
  try {
    const response = await module.saveProfile({
      distro: user.distro,
      username: user.username,
      avatar: user.avatar,
      password: user.password,
    });
    if (isRecord(response) && response.status === 'success') return {kind: 'success'};
    const code = isRecord(response) && typeof response.errorCode === 'string' ? response.errorCode : 'internal_error';
    return {kind: 'error', code};
  } catch {
    return {kind: 'error', code: 'internal_error'};
  }
}

/** Renames an existing user or changes their avatar; the password is unchanged. */
export async function updateUserProfile(user: ProfileUser): Promise<SaveProfileResult> {
  if (usernameProblem(user.username) !== undefined) return {kind: 'error', code: 'invalid_username'};
  const module = nativeProfileModule();
  if (module === null) return {kind: 'error', code: 'unavailable'};
  try {
    const response = await module.updateProfile({distro: user.distro, username: user.username, avatar: user.avatar});
    if (isRecord(response) && response.status === 'success') return {kind: 'success'};
    const code = isRecord(response) && typeof response.errorCode === 'string' ? response.errorCode : 'internal_error';
    return {kind: 'error', code};
  } catch {
    return {kind: 'error', code: 'internal_error'};
  }
}

export type PasswordCheck =
  | Readonly<{kind: 'success'}>
  | Readonly<{kind: 'incorrect'}>
  /** Too many wrong attempts; native refuses to check until the wait is over. */
  | Readonly<{kind: 'locked'; retryAfterMs: number}>;

/** Checks [distro]'s user's password; wrong guesses count against one limit shared by every user. */
export async function verifyUserPassword(distro: LinuxDistro, password: string): Promise<PasswordCheck> {
  const module = nativeProfileModule();
  if (module === null) return {kind: 'incorrect'};
  try {
    const response = await module.verifyPassword({distro, password});
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
