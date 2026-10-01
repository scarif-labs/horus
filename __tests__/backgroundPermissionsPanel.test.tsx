import React from 'react';
import ReactTestRenderer from 'react-test-renderer';
import {BackgroundPermissionsPanel} from '../src/ui/BackgroundPermissions';

function fakeActions(initial: {notifications: boolean; batteryUnrestricted: boolean} | null) {
  const state = initial === null ? null : {...initial};
  return {
    read: jest.fn(async () => (state === null ? null : {...state})),
    requestNotifications: jest.fn(async () => { if (state !== null) state.notifications = true; }),
    requestBattery: jest.fn(async () => { if (state !== null) state.batteryUnrestricted = true; }),
  };
}

async function render(actions: ReturnType<typeof fakeActions>) {
  let renderer: ReactTestRenderer.ReactTestRenderer | undefined;
  await ReactTestRenderer.act(async () => {
    renderer = ReactTestRenderer.create(<BackgroundPermissionsPanel actions={actions} />);
    await Promise.resolve();
  });
  return renderer!;
}

const status = (renderer: ReactTestRenderer.ReactTestRenderer) =>
  renderer.root.findByProps({testID: 'settings-permissions-status'}).props.children;

describe('BackgroundPermissionsPanel', () => {
  test('lets a user who skipped onboarding allow both permissions later', async () => {
    const actions = fakeActions({notifications: false, batteryUnrestricted: false});
    const renderer = await render(actions);
    expect(status(renderer)).toContain('may pause sessions');

    await ReactTestRenderer.act(async () => {
      renderer.root.findByProps({testID: 'settings-permission-notifications-allow'}).props.onPress();
      await Promise.resolve();
    });
    expect(actions.requestNotifications).toHaveBeenCalledTimes(1);
    expect(renderer.root.findAllByProps({testID: 'settings-permission-notifications-granted'}).length).toBeGreaterThan(0);

    await ReactTestRenderer.act(async () => {
      renderer.root.findByProps({testID: 'settings-permission-battery-allow'}).props.onPress();
      await Promise.resolve();
    });
    expect(actions.requestBattery).toHaveBeenCalledTimes(1);
    expect(renderer.root.findAllByProps({testID: 'settings-permission-battery-allow'})).toHaveLength(0);
    expect(status(renderer)).toContain('Both allowed');
    renderer.unmount();
  });

  test('shows both as allowed without offering a request', async () => {
    const renderer = await render(fakeActions({notifications: true, batteryUnrestricted: true}));
    expect(renderer.root.findAllByProps({testID: 'settings-permission-notifications-allow'})).toHaveLength(0);
    expect(renderer.root.findAllByProps({testID: 'settings-permission-battery-allow'})).toHaveLength(0);
    expect(status(renderer)).toContain('Both allowed');
    renderer.unmount();
  });

  test('says so when the permissions cannot be read', async () => {
    const renderer = await render(fakeActions(null));
    expect(status(renderer)).toContain('could not read');
    renderer.unmount();
  });
});
