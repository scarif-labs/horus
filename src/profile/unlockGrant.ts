import React from 'react';
import {AppState} from 'react-native';
import NativeTerminalRuntime, {type Spec} from '../native/NativeTerminalRuntime';
import {createRequestIdFactory} from '../terminal/requestIds';

export type UnlockGrantOp = 'grant' | 'revoke' | 'background' | 'resume';

type UnlockGrantRuntime = Pick<Spec, 'updateUnlockGrant'>;

/** Bounds the cold-start check so a slow service bind never stalls boot. */
export const UNLOCK_GRANT_TIMEOUT_MS = 2500;

const nextRequestId = createRequestIdFactory('unlock');

/**
 * Updates the terminal service's in-memory unlock grant and resolves whether
 * Horus is unlocked afterwards. Android may reclaim the UI process on
 * low-memory devices while the service keeps sessions alive; `resume` lets a
 * recreated UI skip the password in that case. Any failure resolves `false`,
 * which keeps the password prompt.
 */
export async function updateUnlockGrant(
  op: UnlockGrantOp,
  runtime: UnlockGrantRuntime | null = NativeTerminalRuntime,
  timeoutMs = UNLOCK_GRANT_TIMEOUT_MS,
): Promise<boolean> {
  if (runtime === null) return false;
  const requestId = nextRequestId(op);
  let timer: ReturnType<typeof setTimeout> | undefined;
  const timeout = new Promise<false>(resolve => {
    timer = setTimeout(() => resolve(false), timeoutMs);
  });
  try {
    const request = runtime.updateUnlockGrant({requestId, op}).then(
      response => response.requestId === requestId && response.status === 'success' && response.unlocked === true,
      () => false,
    );
    return await Promise.race([request, timeout]);
  } catch {
    return false;
  } finally {
    if (timer !== undefined) clearTimeout(timer);
  }
}

/** Mirrors foreground/background transitions into the service grant while unlocked. */
export function useUnlockGrantAppState(enabled: boolean): void {
  React.useEffect(() => {
    if (!enabled) return undefined;
    const subscription = AppState.addEventListener('change', state => {
      if (state === 'active') {
        void updateUnlockGrant('resume');
      } else if (state === 'background') {
        void updateUnlockGrant('background');
      }
    });
    return () => subscription.remove();
  }, [enabled]);
}
