import React from 'react';
import ReactTestRenderer from 'react-test-renderer';
import {SessionSettingsScreen} from '../src/ui/SessionSettingsScreen';
import type {SessionSettingsResult, ZshThemeId} from '../src/terminal/session/sessionSettings';

function settings(limit: number, zshTheme: ZshThemeId = 'duellj'): SessionSettingsResult {
  return {
    kind: 'success',
    settings: {
      maxConcurrentSessions: limit,
      minConcurrentSessions: 1,
      maxSupportedConcurrentSessions: 4,
      zshTheme,
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

  test('shows the saved zsh theme and saves a new one', async () => {
    const saveZshTheme = jest.fn(async (theme: ZshThemeId) => settings(2, theme));
    let renderer: ReactTestRenderer.ReactTestRenderer | undefined;
    await ReactTestRenderer.act(async () => {
      renderer = ReactTestRenderer.create(<SessionSettingsScreen onBack={() => undefined} readSettings={async () => settings(2, 'minimal')} saveZshTheme={saveZshTheme} />);
      await Promise.resolve();
    });
    const option = (id: string) => renderer!.root.findByProps({testID: `settings-zsh-theme-${id}`});
    const options = () => renderer!.root.findAll(node => typeof node.props.testID === 'string' && /^settings-zsh-theme-(duellj|robbyrussell|minimal|agnoster)$/.test(node.props.testID) && typeof node.type === 'function');
    const expand = async () => {
      await ReactTestRenderer.act(async () => { option('current').props.onPress(); });
    };
    // Folded: only the current theme shows.
    expect(option('current').props.accessibilityLabel).toBe('Shell theme, minimal');
    expect(options()).toHaveLength(0);

    await expand();
    expect(option('minimal').props.accessibilityState).toMatchObject({checked: true});
    expect(option('duellj').props.accessibilityState).toMatchObject({checked: false});
    await ReactTestRenderer.act(async () => {
      option('minimal').props.onPress();
      await Promise.resolve();
    });
    expect(saveZshTheme).not.toHaveBeenCalled();
    expect(option('current').props.accessibilityLabel).toBe('Shell theme, minimal');

    await expand();
    await ReactTestRenderer.act(async () => {
      option('agnoster').props.onPress();
      await Promise.resolve();
    });
    expect(saveZshTheme).toHaveBeenCalledWith('agnoster');
    expect(option('current').props.accessibilityLabel).toBe('Shell theme, agnoster');
    expect(options()).toHaveLength(0);
    await ReactTestRenderer.act(async () => { renderer?.unmount(); });
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
