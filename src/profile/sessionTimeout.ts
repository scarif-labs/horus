import React from 'react';
import {AppState, type AppStateStatus} from 'react-native';

export const BACKGROUND_SESSION_TIMEOUT_MS = 15 * 60 * 1000;

type SessionTimeoutOptions = Readonly<{
  onLock: () => void;
  timeoutMs?: number;
  now?: () => number;
  setTimer?: (callback: () => void, delayMs: number) => ReturnType<typeof setTimeout>;
  clearTimer?: (timer: ReturnType<typeof setTimeout>) => void;
}>;

export type BackgroundSessionLock = Readonly<{
  onAppStateChange: (state: AppStateStatus) => void;
  dispose: () => void;
}>;

/** Keeps a session alive through short app switches and locks after the timeout. */
export function createBackgroundSessionLock({
  onLock,
  timeoutMs = BACKGROUND_SESSION_TIMEOUT_MS,
  now = Date.now,
  setTimer = (callback, delayMs) => setTimeout(callback, delayMs),
  clearTimer = timer => clearTimeout(timer),
}: SessionTimeoutOptions): BackgroundSessionLock {
  let backgroundedAt: number | null = null;
  let timer: ReturnType<typeof setTimeout> | undefined;
  let disposed = false;

  const clearScheduledLock = (): void => {
    if (timer === undefined) return;
    clearTimer(timer);
    timer = undefined;
  };

  const lockIfExpired = (): void => {
    if (disposed || backgroundedAt === null) return;
    const remainingMs = timeoutMs - (now() - backgroundedAt);
    if (remainingMs <= 0) {
      backgroundedAt = null;
      timer = undefined;
      onLock();
      return;
    }
    timer = setTimer(lockIfExpired, remainingMs);
  };

  const startBackgroundTimer = (): void => {
    if (disposed || backgroundedAt !== null) return;
    backgroundedAt = now();
    clearScheduledLock();
    timer = setTimer(lockIfExpired, timeoutMs);
  };

  const handleActive = (): void => {
    if (backgroundedAt === null) return;
    const expired = now() - backgroundedAt >= timeoutMs;
    backgroundedAt = null;
    clearScheduledLock();
    if (expired && !disposed) onLock();
  };

  return {
    onAppStateChange: state => {
      if (state === 'active') {
        handleActive();
      } else {
        startBackgroundTimer();
      }
    },
    dispose: () => {
      disposed = true;
      backgroundedAt = null;
      clearScheduledLock();
    },
  };
}

export function useBackgroundSessionLock(enabled: boolean, onLock: () => void): void {
  const onLockRef = React.useRef(onLock);
  onLockRef.current = onLock;

  React.useEffect(() => {
    if (!enabled) return undefined;

    const lock = createBackgroundSessionLock({onLock: () => onLockRef.current()});
    const currentState = AppState.currentState;
    if (currentState === 'background' || currentState === 'inactive' || currentState === 'unknown') {
      lock.onAppStateChange(currentState);
    }
    const subscription = AppState.addEventListener('change', lock.onAppStateChange);
    return () => {
      subscription.remove();
      lock.dispose();
    };
  }, [enabled]);
}
