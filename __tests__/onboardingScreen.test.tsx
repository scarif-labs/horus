import React from 'react';
import {Text} from 'react-native';
import ReactTestRenderer from 'react-test-renderer';
import {OnboardingScreen} from '../src/ui/OnboardingScreen';

function fakePermissions(initial: {notifications: boolean; batteryUnrestricted: boolean}) {
  const state = {...initial};
  return {
    state,
    permissions: {
      read: jest.fn(async () => ({...state})),
      requestNotifications: jest.fn(async () => { state.notifications = true; }),
      requestBattery: jest.fn(async () => { state.batteryUnrestricted = true; }),
    },
  };
}

describe('OnboardingScreen', () => {
  test('asks for notifications and unrestricted battery before the password', async () => {
    const {permissions} = fakePermissions({notifications: false, batteryUnrestricted: false});
    let renderer: ReactTestRenderer.ReactTestRenderer | undefined;
    await ReactTestRenderer.act(async () => {
      renderer = ReactTestRenderer.create(
        <OnboardingScreen onComplete={jest.fn(async () => undefined)} permissions={permissions} runtimeReady />,
      );
    });
    expect(renderer?.root.findAllByProps({testID: 'profile-password'}).length).toBe(0);
    const continueText = () => renderer?.root.findByProps({testID: 'permissions-continue'}).findByType(Text).props.children;
    expect(continueText()).toBe('ALLOW');
    expect(renderer?.root.findAllByProps({testID: 'permissions-skip'}).length).toBeGreaterThan(0);

    // The primary button asks for the first missing permission.
    await ReactTestRenderer.act(async () => {
      renderer?.root.findByProps({testID: 'permissions-continue'}).props.onPress();
      await Promise.resolve();
    });
    expect(permissions.requestNotifications).toHaveBeenCalledTimes(1);
    expect(renderer?.root.findAllByProps({testID: 'permission-notifications-granted'}).length).toBeGreaterThan(0);
    expect(continueText()).toBe('ALLOW');

    await ReactTestRenderer.act(async () => {
      renderer?.root.findByProps({testID: 'permission-battery-allow'}).props.onPress();
      await Promise.resolve();
    });
    expect(permissions.requestBattery).toHaveBeenCalledTimes(1);
    expect(continueText()).toBe('CONTINUE  →');
    expect(renderer?.root.findAllByProps({testID: 'permissions-skip'})).toHaveLength(0);

    await ReactTestRenderer.act(async () => {
      renderer?.root.findByProps({testID: 'permissions-continue'}).props.onPress();
    });
    expect(renderer?.root.findAllByProps({testID: 'profile-password'}).length).toBeGreaterThan(0);
    await ReactTestRenderer.act(async () => { renderer?.unmount(); });
  });

  test('lets the user skip both permissions with Not now', async () => {
    const {permissions} = fakePermissions({notifications: false, batteryUnrestricted: false});
    let renderer: ReactTestRenderer.ReactTestRenderer | undefined;
    await ReactTestRenderer.act(async () => {
      renderer = ReactTestRenderer.create(
        <OnboardingScreen onComplete={jest.fn(async () => undefined)} permissions={permissions} runtimeReady />,
      );
    });
    await ReactTestRenderer.act(async () => {
      renderer?.root.findByProps({testID: 'permissions-skip'}).props.onPress();
    });
    expect(permissions.requestNotifications).not.toHaveBeenCalled();
    expect(renderer?.root.findAllByProps({testID: 'profile-password'}).length).toBeGreaterThan(0);
    await ReactTestRenderer.act(async () => { renderer?.unmount(); });
  });

  test('shows the Horus sign-in-style setup and still creates the profile', async () => {
    const onComplete = jest.fn(async (_password: string) => undefined);
    const {permissions} = fakePermissions({notifications: true, batteryUnrestricted: true});
    let renderer: ReactTestRenderer.ReactTestRenderer | undefined;
    await ReactTestRenderer.act(async () => {
      renderer = ReactTestRenderer.create(
        <OnboardingScreen onComplete={onComplete} permissions={permissions} runtimeReady />,
      );
    });
    await ReactTestRenderer.act(async () => {
      renderer?.root.findByProps({testID: 'permissions-continue'}).props.onPress();
    });

    expect(renderer?.root.findByProps({testID: 'setup-logo'}).props.style).toMatchObject({height: 112, width: 112});
    expect(renderer?.root.findByProps({testID: 'setup-wordmark'}).props.children).toBe('HORUS');
    expect(renderer?.root.findByProps({testID: 'profile-password'}).props.placeholder).toBe('Create password');
    expect(renderer?.root.findByProps({testID: 'profile-continue'})).toBeDefined();
    for (const removedCopy of ['HORUS / ALPINE', 'FIRST RUN', '01 / LOCAL PROFILE', 'Set a local password.', 'PASSWORD']) {
      expect(renderer?.root.findAll(node => node.props.children === removedCopy)).toHaveLength(0);
    }

    await ReactTestRenderer.act(async () => {
      renderer?.root.findByProps({testID: 'profile-password'}).props.onChangeText('correct horse');
    });
    await ReactTestRenderer.act(async () => {
      renderer?.root.findByProps({testID: 'profile-continue'}).props.onPress();
      await Promise.resolve();
    });
    expect(onComplete).toHaveBeenCalledWith('correct horse', expect.any(Function));

    await ReactTestRenderer.act(async () => { renderer?.unmount(); });
  });
});
