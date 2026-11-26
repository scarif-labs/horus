import nativeTerminalRuntime, {type SessionSettingsResponse, type Spec} from '../../native/NativeTerminalRuntime';

export const TERMINAL_SESSION_LIMIT_MIN = 1 as const;
export const TERMINAL_SESSION_LIMIT_DEFAULT = 2 as const;
export const TERMINAL_SESSION_LIMIT_MAX = 4 as const;

/** Guest zsh prompts, matching ZshTheme.kt. The first is the default. */
export const ZSH_THEMES = ['duellj', 'robbyrussell', 'minimal', 'agnoster'] as const;
export type ZshThemeId = (typeof ZSH_THEMES)[number];

export type SessionSettings = Readonly<{
  maxConcurrentSessions: number;
  minConcurrentSessions: number;
  maxSupportedConcurrentSessions: number;
  zshTheme: ZshThemeId;
}>;

export type SessionSettingsErrorCode =
  | 'unavailable'
  | 'internal_error'
  | 'invalid_request'
  | 'invalid_response';

export type SessionSettingsResult = Readonly<
  | {kind: 'success'; settings: SessionSettings}
  | {kind: 'error'; errorCode: SessionSettingsErrorCode}
>;

type SessionSettingsRuntime = Pick<Spec, 'getSessionSettings' | 'setSessionLimit' | 'setZshTheme'>;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isZshTheme(value: unknown): value is ZshThemeId {
  return typeof value === 'string' && (ZSH_THEMES as readonly string[]).includes(value);
}

function isBoundedInteger(value: unknown, minimum: number, maximum: number): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= minimum && value <= maximum;
}

function parseSessionSettings(value: unknown): SessionSettingsResult {
  if (!isRecord(value) || typeof value.status !== 'string') return {kind: 'error', errorCode: 'invalid_response'};
  if (value.status === 'error') {
    return value.errorCode === 'internal_error' || value.errorCode === 'invalid_request'
      ? {kind: 'error', errorCode: value.errorCode}
      : {kind: 'error', errorCode: 'invalid_response'};
  }
  if (
    value.status !== 'success' ||
    !isBoundedInteger(value.maxConcurrentSessions, TERMINAL_SESSION_LIMIT_MIN, TERMINAL_SESSION_LIMIT_MAX) ||
    value.minConcurrentSessions !== TERMINAL_SESSION_LIMIT_MIN ||
    value.maxSupportedConcurrentSessions !== TERMINAL_SESSION_LIMIT_MAX
  ) return {kind: 'error', errorCode: 'invalid_response'};
  return {
    kind: 'success',
    settings: {
      maxConcurrentSessions: value.maxConcurrentSessions,
      minConcurrentSessions: value.minConcurrentSessions,
      maxSupportedConcurrentSessions: value.maxSupportedConcurrentSessions,
      // An unknown id is a theme from a newer build; show the default.
      zshTheme: isZshTheme(value.zshTheme) ? value.zshTheme : ZSH_THEMES[0],
    },
  };
}

export async function readSessionSettings(
  runtime: SessionSettingsRuntime | null = nativeTerminalRuntime,
): Promise<SessionSettingsResult> {
  if (runtime === null) return {kind: 'error', errorCode: 'unavailable'};
  let response: SessionSettingsResponse;
  try {
    response = await runtime.getSessionSettings();
  } catch {
    return {kind: 'error', errorCode: 'internal_error'};
  }
  return parseSessionSettings(response);
}

export async function writeSessionLimit(
  maxConcurrentSessions: number,
  runtime: SessionSettingsRuntime | null = nativeTerminalRuntime,
): Promise<SessionSettingsResult> {
  if (!isBoundedInteger(maxConcurrentSessions, TERMINAL_SESSION_LIMIT_MIN, TERMINAL_SESSION_LIMIT_MAX)) {
    return {kind: 'error', errorCode: 'invalid_request'};
  }
  if (runtime === null) return {kind: 'error', errorCode: 'unavailable'};
  let response: SessionSettingsResponse;
  try {
    response = await runtime.setSessionLimit({maxConcurrentSessions});
  } catch {
    return {kind: 'error', errorCode: 'internal_error'};
  }
  return parseSessionSettings(response);
}

export async function writeZshTheme(
  zshTheme: ZshThemeId,
  runtime: SessionSettingsRuntime | null = nativeTerminalRuntime,
): Promise<SessionSettingsResult> {
  if (!isZshTheme(zshTheme)) return {kind: 'error', errorCode: 'invalid_request'};
  if (runtime === null) return {kind: 'error', errorCode: 'unavailable'};
  let response: SessionSettingsResponse;
  try {
    response = await runtime.setZshTheme({zshTheme});
  } catch {
    return {kind: 'error', errorCode: 'internal_error'};
  }
  return parseSessionSettings(response);
}
