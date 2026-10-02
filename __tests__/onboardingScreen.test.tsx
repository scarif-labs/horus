import React from 'react';
import {Text} from 'react-native';
import ReactTestRenderer from 'react-test-renderer';
import {OnboardingScreen, type OnboardingActions, type ProfileSetupResult, type RootfsSetupResult} from '../src/ui/OnboardingScreen';

function fakePermissions(initial: {notifications: boolean; batteryUnrestricted: boolean}) {
  const state = {...initial};
  return {
    read: jest.fn(async () => ({...state})),
    requestNotifications: jest.fn(async () => { state.notifications = true; }),
    requestBattery: jest.fn(async () => { state.batteryUnrestricted = true; }),
  };
}

type Deferred<T> = {promise: Promise<T>; resolve: (value: T) => void};
function deferred<T>(): Deferred<T> {
  let resolve: (value: T) => void = () => undefined;
  const promise = new Promise<T>(done => { resolve = done; });
  return {promise, resolve};
}

function fakeActions(overrides: Partial<OnboardingActions> = {}) {
  return {
    installRootfs: jest.fn(async (): Promise<RootfsSetupResult> => ({kind: 'success'})),
    importRootfs: jest.fn(async (): Promise<RootfsSetupResult> => ({kind: 'success'})),
    saveProfile: jest.fn(async (_password: string, _options: {skipTools: boolean; onToolsReady: () => void}): Promise<ProfileSetupResult> => ({kind: 'success'})),
    done: jest.fn(),
    ...overrides,
  };
}

async function render(actions: OnboardingActions, {rootfsInstalled = false, granted = true} = {}) {
  let renderer!: ReactTestRenderer.ReactTestRenderer;
  const permissions = fakePermissions({notifications: granted, batteryUnrestricted: granted});
  await ReactTestRenderer.act(async () => {
    renderer = ReactTestRenderer.create(<OnboardingScreen actions={actions} permissions={permissions} rootfsInstalled={rootfsInstalled} />);
  });
  const has = (testID: string) => renderer.root.findAllByProps({testID}).length > 0;
  const press = async (testID: string) => {
    await ReactTestRenderer.act(async () => {
      renderer.root.findByProps({testID}).props.onPress();
      for (let index = 0; index < 4; index += 1) await Promise.resolve();
    });
  };
  const type = async (testID: string, text: string) => {
    await ReactTestRenderer.act(async () => { renderer.root.findByProps({testID}).props.onChangeText(text); });
  };
  const enterPassword = async (password = 'correct horse', confirmation = password) => {
    await type('profile-password', password);
    await type('profile-password-confirm', confirmation);
    await press('profile-continue');
  };
  return {renderer, permissions, has, press, type, enterPassword};
}

describe('OnboardingScreen', () => {
  test('walks through welcome, password, permissions, Linux setup and the key guide', async () => {
    const actions = fakeActions();
    const {renderer, has, press, enterPassword} = await render(actions);

    expect(has('onboarding-welcome')).toBe(true);
    expect(actions.installRootfs).not.toHaveBeenCalled();
    await press('welcome-continue');
    // The download starts behind the password and permission steps.
    expect(actions.installRootfs).toHaveBeenCalledTimes(1);
    expect(has('onboarding-password')).toBe(true);
    expect(renderer.root.findByProps({testID: 'onboarding-step'}).props.accessibilityLabel).toBe('Step 2 of 5');

    await enterPassword();
    expect(has('onboarding-permissions')).toBe(true);
    await press('permissions-continue');

    expect(actions.saveProfile).toHaveBeenCalledWith('correct horse', expect.objectContaining({skipTools: false}));
    expect(actions.installRootfs).toHaveBeenCalledTimes(1);
    expect(has('onboarding-keys')).toBe(true);
    expect(has('onboarding-key-card')).toBe(true);
    const keyLabels = renderer.root.findAllByType(Text).map(node => node.props.children);
    for (const key of ['ESC', 'CTRL', 'ALT', 'TAB', 'PASTE', '↑']) expect(keyLabels).toContain(key);

    await press('onboarding-done');
    expect(actions.done).toHaveBeenCalledTimes(1);
    await ReactTestRenderer.act(async () => { renderer.unmount(); });
  });

  test('checks the password length and that both entries match', async () => {
    const {renderer, has, press, enterPassword} = await render(fakeActions());
    await press('welcome-continue');

    await enterPassword('123');
    expect(renderer.root.findByProps({testID: 'profile-error'}).props.children).toBe('Use at least 4 characters.');
    await enterPassword('1234', '1235');
    expect(renderer.root.findByProps({testID: 'profile-error'}).props.children).toBe('Passwords don’t match.');
    expect(has('onboarding-permissions')).toBe(false);
    await enterPassword('1234');
    expect(has('onboarding-permissions')).toBe(true);
    await ReactTestRenderer.act(async () => { renderer.unmount(); });
  });

  test('asks for each missing permission, and Not now skips them', async () => {
    const {renderer, permissions, has, press, enterPassword} = await render(fakeActions(), {granted: false});
    await press('welcome-continue');
    await enterPassword();
    const continueText = () => renderer.root.findByProps({testID: 'permissions-continue'}).findByType(Text).props.children;
    expect(continueText()).toBe('ALLOW');

    await press('permissions-continue');
    expect(permissions.requestNotifications).toHaveBeenCalledTimes(1);
    expect(has('permission-notifications-granted')).toBe(true);
    expect(continueText()).toBe('ALLOW');
    expect(has('permissions-skip')).toBe(true);

    await press('permissions-skip');
    expect(permissions.requestBattery).not.toHaveBeenCalled();
    expect(has('onboarding-setup') || has('onboarding-keys')).toBe(true);
    await ReactTestRenderer.act(async () => { renderer.unmount(); });
  });

  test('shows each setup task while it runs', async () => {
    const download = deferred<RootfsSetupResult>();
    const save = deferred<ProfileSetupResult>();
    let toolsReady: () => void = () => undefined;
    const actions = fakeActions({
      installRootfs: jest.fn(() => download.promise),
      saveProfile: jest.fn((_password: string, options: {onToolsReady: () => void}) => {
        toolsReady = options.onToolsReady;
        return save.promise;
      }),
    });
    const {renderer, has, press, enterPassword} = await render(actions);
    await press('welcome-continue');
    await enterPassword();
    await press('permissions-continue');

    expect(has('setup-task-linux-working')).toBe(true);
    expect(has('setup-task-tools-waiting')).toBe(true);
    expect(actions.saveProfile).not.toHaveBeenCalled();

    await ReactTestRenderer.act(async () => { download.resolve({kind: 'success'}); await Promise.resolve(); });
    expect(has('setup-task-linux-done')).toBe(true);
    expect(has('setup-task-tools-working')).toBe(true);

    await ReactTestRenderer.act(async () => { toolsReady(); });
    expect(has('setup-task-tools-done')).toBe(true);
    expect(has('setup-task-profile-working')).toBe(true);

    await ReactTestRenderer.act(async () => { save.resolve({kind: 'success'}); await Promise.resolve(); });
    expect(has('onboarding-keys')).toBe(true);
    await ReactTestRenderer.act(async () => { renderer.unmount(); });
  });

  test('offers the file picker when Alpine cannot be downloaded', async () => {
    const actions = fakeActions({installRootfs: jest.fn(async (): Promise<RootfsSetupResult> => ({kind: 'error', errorCode: 'download_failed'}))});
    jest.mocked(actions.importRootfs)
      .mockResolvedValueOnce({kind: 'error', errorCode: 'import_cancelled'})
      .mockResolvedValueOnce({kind: 'success'});
    const {renderer, has, press, enterPassword} = await render(actions);
    await press('welcome-continue');
    await enterPassword();
    await press('permissions-continue');

    expect(has('setup-linux-failed')).toBe(true);
    expect(has('setup-task-linux-failed')).toBe(true);
    expect(has('setup-linux-import')).toBe(true);
    expect(actions.saveProfile).not.toHaveBeenCalled();

    // Cancelling the picker keeps the offline explanation up.
    await press('setup-linux-import');
    expect(has('setup-linux-failed')).toBe(true);

    await press('setup-linux-import');
    expect(actions.importRootfs).toHaveBeenCalledTimes(2);
    expect(actions.saveProfile).toHaveBeenCalledTimes(1);
    expect(has('onboarding-keys')).toBe(true);
    await ReactTestRenderer.act(async () => { renderer.unmount(); });
  });

  test('lets the user skip the tools when they cannot be downloaded', async () => {
    const actions = fakeActions();
    jest.mocked(actions.saveProfile).mockResolvedValueOnce({kind: 'error', stage: 'alpine-tools'});
    const {renderer, has, press, enterPassword} = await render(actions, {rootfsInstalled: true});
    await press('welcome-continue');
    expect(actions.installRootfs).not.toHaveBeenCalled();
    await enterPassword();
    await press('permissions-continue');

    expect(has('setup-tools-failed')).toBe(true);
    await press('setup-tools-skip');
    expect(actions.saveProfile).toHaveBeenLastCalledWith('correct horse', expect.objectContaining({skipTools: true}));
    expect(has('onboarding-keys')).toBe(true);
    await ReactTestRenderer.act(async () => { renderer.unmount(); });
  });
});
