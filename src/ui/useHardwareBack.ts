import React from 'react';
import {BackHandler} from 'react-native';

/**
 * Handles Android's hardware Back while `active` is true. The press counts as
 * handled unless `handler` returns `false`, which lets the next listener (or
 * the system) take it. Pass a stable handler; a new identity re-subscribes.
 */
export function useHardwareBack(active: boolean, handler: () => boolean | void): void {
  React.useEffect(() => {
    if (!active) return undefined;
    const subscription = BackHandler.addEventListener('hardwareBackPress', () => handler() !== false);
    return () => subscription.remove();
  }, [active, handler]);
}
