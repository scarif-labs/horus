import type {Spec} from '../src/native/NativeTerminalRuntime';
import {
  readSessionSettings,
  TERMINAL_SESSION_LIMIT_MAX,
  TERMINAL_SESSION_LIMIT_MIN,
  writeSessionLimit,
} from '../src/terminal/session/sessionSettings';

function runtime(overrides: Partial<Pick<Spec, 'getSessionSettings' | 'setSessionLimit'>> = {}): Pick<Spec, 'getSessionSettings' | 'setSessionLimit'> {
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
      },
    });
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
