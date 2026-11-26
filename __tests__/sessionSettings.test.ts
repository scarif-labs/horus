import type {Spec} from '../src/native/NativeTerminalRuntime';
import {
  readSessionSettings,
  TERMINAL_SESSION_LIMIT_MAX,
  TERMINAL_SESSION_LIMIT_MIN,
  writeSessionLimit,
  writeZshTheme,
  type ZshThemeId,
} from '../src/terminal/session/sessionSettings';

type SettingsRuntime = Pick<Spec, 'getSessionSettings' | 'setSessionLimit' | 'setZshTheme'>;

function runtime(overrides: Partial<SettingsRuntime> = {}): SettingsRuntime {
  return {
    getSessionSettings: async () => ({
      status: 'success' as const,
      maxConcurrentSessions: TERMINAL_SESSION_LIMIT_MIN,
      minConcurrentSessions: TERMINAL_SESSION_LIMIT_MIN,
      maxSupportedConcurrentSessions: TERMINAL_SESSION_LIMIT_MAX,
    }),
    setSessionLimit: async request => ({
      status: 'success' as const,
      maxConcurrentSessions: request.maxConcurrentSessions,
      minConcurrentSessions: TERMINAL_SESSION_LIMIT_MIN,
      maxSupportedConcurrentSessions: TERMINAL_SESSION_LIMIT_MAX,
    }),
    setZshTheme: async request => ({
      status: 'success' as const,
      maxConcurrentSessions: TERMINAL_SESSION_LIMIT_MIN,
      minConcurrentSessions: TERMINAL_SESSION_LIMIT_MIN,
      maxSupportedConcurrentSessions: TERMINAL_SESSION_LIMIT_MAX,
      zshTheme: request.zshTheme,
    }),
    ...overrides,
  };
}

describe('session settings contract', () => {
  test('reads a bounded native setting', async () => {
    await expect(readSessionSettings(runtime())).resolves.toEqual({
      kind: 'success',
      settings: {
        maxConcurrentSessions: 1,
        minConcurrentSessions: 1,
        maxSupportedConcurrentSessions: 4,
        zshTheme: 'duellj',
      },
    });
  });

  test('reads the zsh theme, and shows the default for one this build does not know', async () => {
    const base = {status: 'success' as const, maxConcurrentSessions: 2, minConcurrentSessions: 1, maxSupportedConcurrentSessions: 4};
    await expect(readSessionSettings(runtime({getSessionSettings: async () => ({...base, zshTheme: 'agnoster'})}))).resolves.toMatchObject({settings: {zshTheme: 'agnoster'}});
    await expect(readSessionSettings(runtime({getSessionSettings: async () => ({...base, zshTheme: 'spaceship'})}))).resolves.toMatchObject({settings: {zshTheme: 'duellj'}});
  });

  test('saves a zsh theme and rejects an unknown one before the bridge', async () => {
    const native = runtime();
    const setTheme = jest.spyOn(native, 'setZshTheme');
    await expect(writeZshTheme('minimal', native)).resolves.toMatchObject({kind: 'success', settings: {zshTheme: 'minimal'}});
    expect(setTheme).toHaveBeenCalledWith({zshTheme: 'minimal'});
    setTheme.mockClear();
    await expect(writeZshTheme('spaceship' as ZshThemeId, native)).resolves.toEqual({kind: 'error', errorCode: 'invalid_request'});
    expect(setTheme).not.toHaveBeenCalled();
  });

  test('rejects malformed native settings and invalid writes before the bridge', async () => {
    const malformed = runtime({getSessionSettings: async () => ({status: 'success' as const, maxConcurrentSessions: 0, minConcurrentSessions: 1, maxSupportedConcurrentSessions: 4})});
    await expect(readSessionSettings(malformed)).resolves.toEqual({kind: 'error', errorCode: 'invalid_response'});

    const native = runtime();
    const setLimit = jest.spyOn(native, 'setSessionLimit');
    await expect(writeSessionLimit(0, native)).resolves.toEqual({kind: 'error', errorCode: 'invalid_request'});
    expect(setLimit).not.toHaveBeenCalled();
  });

  test('maps a native write response', async () => {
    await expect(writeSessionLimit(3, runtime())).resolves.toMatchObject({
      kind: 'success',
      settings: {maxConcurrentSessions: 3},
    });
  });
});
