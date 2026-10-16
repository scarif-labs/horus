import React from 'react';
import {Text} from 'react-native';
import ReactTestRenderer from 'react-test-renderer';
import type {LinuxDistro, NewProfileUser} from '../src/profile/profileStore';
import type {DownloadSources} from '../src/terminal/downloadSources';
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
    installRootfs: jest.fn(async (_distro: LinuxDistro): Promise<RootfsSetupResult> => ({kind: 'success'})),
    importRootfs: jest.fn(async (_distro: LinuxDistro): Promise<RootfsSetupResult> => ({kind: 'success'})),
    saveProfile: jest.fn(async (_user: NewProfileUser, _options: {skipTools: boolean; onToolsReady: () => void}): Promise<ProfileSetupResult> => ({kind: 'success'})),
    readDownloadSources: jest.fn(async (): Promise<DownloadSources> => ({})),
    saveDownloadSources: jest.fn(async (_sources: DownloadSources) => true),
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
  const enterAccount = async ({username = 'vishesh', password = 'correct horse', confirmation}: {username?: string; password?: string; confirmation?: string} = {}) => {
    await type('account-username', username);
    await type('account-password', password);
    await type('account-password-confirm', confirmation ?? password);
    await press('profile-continue');
  };
  /** Welcome, then the distribution (Alpine unless named), which starts the download. */
  const startWith = async (distro: LinuxDistro = 'alpine') => {
    await press('welcome-continue');
    if (distro !== 'alpine') await press(`distro-${distro}`);
    await press('distro-continue');
  };
  return {renderer, permissions, has, press, type, enterAccount, startWith};
}

describe('OnboardingScreen', () => {
  test('walks through welcome, Linux, the user, permissions, setup and the key guide', async () => {
    const actions = fakeActions();
    const {renderer, has, press, enterAccount} = await render(actions);

    expect(has('onboarding-welcome')).toBe(true);
    await press('welcome-continue');
    expect(has('onboarding-distro')).toBe(true);
    // Alpine is the default; nothing downloads until the choice is made.
    expect(has('distro-alpine-selected')).toBe(true);
    expect(actions.installRootfs).not.toHaveBeenCalled();
    await press('distro-continue');
    // The download starts behind the user and permission steps.
    expect(actions.installRootfs).toHaveBeenCalledWith('alpine');
    expect(has('onboarding-account')).toBe(true);
    expect(renderer.root.findByProps({testID: 'onboarding-step'}).props.accessibilityLabel).toBe('Step 3 of 6');

    await press('account-avatar-scarab');
    await enterAccount();
    expect(has('onboarding-permissions')).toBe(true);
    await press('permissions-continue');

    expect(actions.saveProfile).toHaveBeenCalledWith(
      {distro: 'alpine', username: 'vishesh', avatar: 'scarab', password: 'correct horse'},
      expect.objectContaining({skipTools: false}),
    );
    expect(actions.installRootfs).toHaveBeenCalledTimes(1);
    expect(has('onboarding-keys')).toBe(true);
    expect(has('onboarding-key-card')).toBe(true);
    const keyLabels = renderer.root.findAllByType(Text).map(node => node.props.children);
    for (const key of ['ESC', 'CTRL', 'ALT', 'TAB', 'PASTE', '↑']) expect(keyLabels).toContain(key);
    // The key row is the terminal's own; tapping a key explains it.
    expect(renderer.root.findByProps({testID: 'onboarding-key-label'}).props.children).toBe('TAP A KEY');
    await press('terminal-key-esc');
    expect(renderer.root.findByProps({testID: 'onboarding-key-label'}).props.children).toBe('ESC');
    const ctrlSelected = () => renderer.root.findAllByProps({testID: 'terminal-key-ctrl'}).some(node => node.props.accessibilityState?.selected === true);
    await press('terminal-key-ctrl');
    expect(ctrlSelected()).toBe(true);
    await press('terminal-key-arrow-up');
    expect(renderer.root.findByProps({testID: 'onboarding-key-label'}).props.children).toBe('↑');
    expect(ctrlSelected()).toBe(false);

    await press('onboarding-done');
    expect(actions.done).toHaveBeenCalledTimes(1);
    await ReactTestRenderer.act(async () => { renderer.unmount(); });
  });

  test('installs Debian and creates the Debian user when Debian is picked', async () => {
    const actions = fakeActions();
    const {renderer, has, press, enterAccount, startWith} = await render(actions);
    await startWith('debian');
    expect(actions.installRootfs).toHaveBeenCalledWith('debian');
    expect(has('onboarding-account')).toBe(true);
    await enterAccount({username: 'deb'});
    await press('permissions-continue');
    expect(actions.saveProfile).toHaveBeenCalledWith(
      {distro: 'debian', username: 'deb', avatar: 'falcon', password: 'correct horse'},
      expect.objectContaining({skipTools: false}),
    );
    await ReactTestRenderer.act(async () => { renderer.unmount(); });
  });

  test('checks the username, the password length, and that both entries match', async () => {
    const {renderer, has, type, enterAccount, startWith} = await render(fakeActions());
    await startWith();
    const error = () => renderer.root.findByProps({testID: 'account-error'}).props.children;

    await enterAccount({username: 'root'});
    expect(error()).toBe('That name belongs to a system account. Pick another.');
    await enterAccount({username: '9lives'});
    expect(error()).toBe('Use lowercase letters, digits, _ or -, starting with a letter.');
    await enterAccount({password: '123'});
    expect(error()).toBe('Use at least 4 characters for the password.');
    await enterAccount({password: '1234', confirmation: '1235'});
    expect(error()).toBe('Passwords don’t match.');
    expect(has('onboarding-permissions')).toBe(false);
    // Usernames are Linux logins: typing folds to lowercase without spaces.
    await type('account-username', 'Vi Shesh');
    expect(renderer.root.findByProps({testID: 'account-username'}).props.value).toBe('vishesh');
    await enterAccount({password: '1234'});
    expect(has('onboarding-permissions')).toBe(true);
    await ReactTestRenderer.act(async () => { renderer.unmount(); });
  });

  test('asks for each missing permission, and Not now skips them', async () => {
    const {renderer, permissions, has, press, enterAccount, startWith} = await render(fakeActions(), {granted: false});
    await startWith();
    await enterAccount();
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
      saveProfile: jest.fn((_user: NewProfileUser, options: {onToolsReady: () => void}) => {
        toolsReady = options.onToolsReady;
        return save.promise;
      }),
    });
    const {renderer, has, press, enterAccount, startWith} = await render(actions);
    await startWith();
    await enterAccount();
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

  test('shows the package manager progress under Installing tools', async () => {
    const save = deferred<ProfileSetupResult>();
    const readToolsProgress = jest.fn(async () => 'Installing packages… 64%');
    const actions = fakeActions({readToolsProgress, saveProfile: jest.fn(() => save.promise)});
    const {renderer, has, press, enterAccount, startWith} = await render(actions);
    await startWith('debian');
    await enterAccount();
    await press('permissions-continue');
    expect(has('setup-task-tools-working')).toBe(true);
    expect(has('setup-task-tools-detail')).toBe(false);

    // The row polls once a second.
    await ReactTestRenderer.act(async () => { await new Promise(done => setTimeout(done, 1100)); });
    expect(renderer.root.findByProps({testID: 'setup-task-tools-detail'}).props.children).toBe('Installing packages… 64%');

    await ReactTestRenderer.act(async () => { save.resolve({kind: 'success'}); await Promise.resolve(); });
    const polls = readToolsProgress.mock.calls.length;
    await ReactTestRenderer.act(async () => { await new Promise(done => setTimeout(done, 1100)); });
    // Polling stops once the tools are done.
    expect(readToolsProgress.mock.calls.length).toBe(polls);
    await ReactTestRenderer.act(async () => { renderer.unmount(); });
  });

  test('offers the file picker when Alpine cannot be downloaded', async () => {
    const actions = fakeActions({installRootfs: jest.fn(async (): Promise<RootfsSetupResult> => ({kind: 'error', errorCode: 'download_failed'}))});
    jest.mocked(actions.importRootfs)
      .mockResolvedValueOnce({kind: 'error', errorCode: 'import_cancelled'})
      .mockResolvedValueOnce({kind: 'success'});
    const {renderer, has, press, enterAccount, startWith} = await render(actions);
    await startWith();
    await enterAccount();
    await press('permissions-continue');

    expect(has('setup-linux-failed')).toBe(true);
    expect(has('setup-task-linux-failed')).toBe(true);
    expect(has('setup-linux-import')).toBe(true);
    expect(actions.saveProfile).not.toHaveBeenCalled();

    // Picking a mirror saves it with the matching npm registry and retries.
    await press('setup-mirror-tuna');
    expect(actions.saveDownloadSources).toHaveBeenCalledWith({
      alpineMirror: 'https://mirrors.tuna.tsinghua.edu.cn/alpine',
      npmRegistry: 'https://registry.npmmirror.com',
    });
    expect(actions.installRootfs).toHaveBeenCalledTimes(2);
    expect(renderer.root.findByProps({testID: 'setup-rootfs-url'}).props.children)
      .toBe('https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.24/releases/aarch64/alpine-minirootfs-3.24.0-aarch64.tar.gz');

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
    const {renderer, has, press, enterAccount, startWith} = await render(actions, {rootfsInstalled: true});
    // Alpine left installed by a profile reset is used as it is.
    await startWith();
    expect(actions.installRootfs).not.toHaveBeenCalled();
    await enterAccount();
    await press('permissions-continue');

    expect(has('setup-tools-failed')).toBe(true);
    await press('setup-tools-skip');
    expect(actions.saveProfile).toHaveBeenLastCalledWith(
      expect.objectContaining({username: 'vishesh', password: 'correct horse'}),
      expect.objectContaining({skipTools: true}),
    );
    expect(has('onboarding-keys')).toBe(true);
    await ReactTestRenderer.act(async () => { renderer.unmount(); });
  });
});
