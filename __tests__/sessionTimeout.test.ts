import {BACKGROUND_SESSION_TIMEOUT_MS, createBackgroundSessionLock} from '../src/profile/sessionTimeout';

describe('background session lock', () => {
  beforeEach(() => {
    jest.useFakeTimers();
  });

  afterEach(() => {
    jest.useRealTimers();
  });

  test('keeps the session through a short app switch and locks at fifteen minutes', () => {
    let now = 0;
    const locks: string[] = [];
    const lock = createBackgroundSessionLock({onLock: () => locks.push('locked'), now: () => now});

    lock.onAppStateChange('background');
    now = 14 * 60 * 1000;
    jest.advanceTimersByTime(14 * 60 * 1000);
    expect(locks).toEqual([]);

    lock.onAppStateChange('active');
    expect(locks).toEqual([]);

    lock.onAppStateChange('background');
    now += BACKGROUND_SESSION_TIMEOUT_MS;
    jest.advanceTimersByTime(BACKGROUND_SESSION_TIMEOUT_MS);
    expect(locks).toEqual(['locked']);
    lock.dispose();
  });

  test('locks immediately on return when Android suspends the timer', () => {
    let now = 0;
    const locks: string[] = [];
    const lock = createBackgroundSessionLock({onLock: () => locks.push('locked'), now: () => now});

    lock.onAppStateChange('inactive');
    now = BACKGROUND_SESSION_TIMEOUT_MS;
    lock.onAppStateChange('active');

    expect(locks).toEqual(['locked']);
    lock.dispose();
  });

  test('dispose clears the pending timer', () => {
    const locks: string[] = [];
    const lock = createBackgroundSessionLock({onLock: () => locks.push('locked')});

    lock.onAppStateChange('background');
    lock.dispose();
    jest.advanceTimersByTime(BACKGROUND_SESSION_TIMEOUT_MS);

    expect(locks).toEqual([]);
  });
});
