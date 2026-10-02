import React from 'react';
import ReactTestRenderer from 'react-test-renderer';
import {SessionSettingsScreen} from '../src/ui/SessionSettingsScreen';
import type {SessionSettingsResult} from '../src/terminal/session/sessionSettings';

function settings(limit: number): SessionSettingsResult {
  return {
    kind: 'success',
    settings: {
      maxConcurrentSessions: limit,
      minConcurrentSessions: 1,
      maxSupportedConcurrentSessions: 4,
    },
  };
}

describe('SessionSettingsScreen', () => {
  test('loads the limit, saves a selected value, and exposes bounded controls', async () => {
    const readSettings = jest.fn(async () => settings(1));
    const saveLimit = jest.fn(async (limit: number) => settings(limit));
    const onBack = jest.fn();
    let renderer: ReactTestRenderer.ReactTestRenderer | undefined;

    await ReactTestRenderer.act(async () => {
      renderer = ReactTestRenderer.create(<SessionSettingsScreen onBack={onBack} readSettings={readSettings} saveLimit={saveLimit} />);
      await Promise.resolve();
    });

    expect(readSettings).toHaveBeenCalledTimes(1);
    expect(renderer?.root.findByProps({testID: 'settings-session-limit-1'})).toBeDefined();
    expect(renderer?.root.findByProps({testID: 'settings-session-limit-4'})).toBeDefined();
    expect(renderer?.root.findByProps({testID: 'settings-session-limit-current'}).props.children).toContain('1 app');

    await ReactTestRenderer.act(async () => {
      renderer?.root.findByProps({testID: 'settings-session-limit-3'}).props.onPress();
      await Promise.resolve();
    });
    expect(saveLimit).toHaveBeenCalledWith(3);
    expect(renderer?.root.findByProps({testID: 'settings-session-limit-current'}).props.children).toContain('3 apps');

    await ReactTestRenderer.act(async () => {
      renderer?.root.findByProps({testID: 'settings-back'}).props.onPress();
    });
    expect(onBack).toHaveBeenCalledTimes(1);
    await ReactTestRenderer.act(async () => {
      renderer?.unmount();
    });
  });

  test('opens the documentation from the Help section', async () => {
    const openUrl = jest.fn(async () => undefined);
    let renderer: ReactTestRenderer.ReactTestRenderer | undefined;
    await ReactTestRenderer.act(async () => {
      renderer = ReactTestRenderer.create(<SessionSettingsScreen onBack={() => undefined} openUrl={openUrl} readSettings={async () => settings(1)} />);
      await Promise.resolve();
    });
    await ReactTestRenderer.act(async () => {
      renderer?.root.findByProps({testID: 'settings-docs'}).props.onPress();
    });
    expect(openUrl).toHaveBeenCalledWith('https://www.scariflabs.com/horus/docs');
    await ReactTestRenderer.act(async () => { renderer?.unmount(); });
  });
});
