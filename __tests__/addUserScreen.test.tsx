import React from 'react';
import ReactTestRenderer from 'react-test-renderer';
import type {ProfileUser} from '../src/profile/profileStore';
import {AddUserScreen, addUserInstallDetail, type AddUserActions} from '../src/ui/AddUserScreen';

const ALPINE_USER: ProfileUser = {distro: 'alpine', username: 'horus', avatar: 'falcon'};

function fakeActions(overrides: Partial<AddUserActions> = {}) {
  return {
    installBeside: jest.fn(async () => ({kind: 'success'} as const)),
    createUser: jest.fn(async () => ({kind: 'success'} as const)),
    ...overrides,
  } as const;
}

async function render(actions: AddUserActions, users: readonly ProfileUser[] = [ALPINE_USER]) {
  const created: ProfileUser[] = [];
  let renderer!: ReactTestRenderer.ReactTestRenderer;
  await ReactTestRenderer.act(async () => {
    renderer = ReactTestRenderer.create(
      <AddUserScreen actions={actions} existingUsers={users} onBack={() => undefined} onCreated={user => { created.push(user); }} />,
    );
  });
  const press = async (testID: string) => {
    await ReactTestRenderer.act(async () => {
      renderer.root.findByProps({testID}).props.onPress();
      for (let index = 0; index < 4; index += 1) await Promise.resolve();
    });
  };
  const type = async (testID: string, text: string) => {
    await ReactTestRenderer.act(async () => { renderer.root.findByProps({testID}).props.onChangeText(text); });
  };
  const has = (testID: string) => renderer.root.findAllByProps({testID}).length > 0;
  const fill = async () => {
    await press('account-avatar-ibis');
    await type('account-username', 'deb');
    await type('account-password', 'debian pass');
    await type('account-password-confirm', 'debian pass');
  };
  return {renderer, created, press, has, fill};
}

describe('AddUserScreen', () => {
  test('formats only this request’s actual progress and handles missing sizes', () => {
    expect(addUserInstallDetail({status: 'working', requestId: 'horus-add-user-1', stage: 'download', downloadedBytes: 42, totalBytes: 100})).toBe('Downloading Linux… 42%');
    expect(addUserInstallDetail({status: 'working', requestId: 'horus-add-user-1', stage: 'download'})).toBe('Downloading Linux…');
    expect(addUserInstallDetail({status: 'working', requestId: 'other', stage: 'download', downloadedBytes: 42, totalBytes: 100})).toBeUndefined();
    expect(addUserInstallDetail({status: 'working', requestId: 'horus-add-user-1', stage: 'extract'})).toBe('Unpacking Linux…');
  });
  test('shows live installation detail while a download is running', async () => {
    let finish!: (value: {kind: 'success'}) => void;
    const installBeside = jest.fn(() => new Promise<{kind: 'success'}>(resolve => { finish = resolve; }));
    const actions = {...fakeActions({installBeside}), readInstallProgress: jest.fn(async () => 'Downloading Linux… 42%')};
    const {renderer, press, fill} = await render(actions);
    await fill();
    await press('add-user-create');
    expect(renderer.root.findAllByProps({children: 'Downloading Linux… 42%'}).length).toBeGreaterThan(0);
    await ReactTestRenderer.act(async () => { finish({kind: 'success'}); await Promise.resolve(); });
    await ReactTestRenderer.act(async () => { renderer.unmount(); });
  });
  test('rejects an existing username before installing', async () => {
    const actions = fakeActions();
    const {renderer, press, fill} = await render(actions);
    await fill();
    await ReactTestRenderer.act(async () => { renderer.root.findByProps({testID: 'account-username'}).props.onChangeText('horus'); });
    await press('add-user-create');
    expect(actions.installBeside).not.toHaveBeenCalled();
    expect(renderer.root.findAllByProps({children: 'That username is already in use.'}).length).toBeGreaterThan(0);
    await ReactTestRenderer.act(async () => { renderer.unmount(); });
  });
  test('shows the username limit and updates the count as the name changes', async () => {
    const {renderer} = await render(fakeActions());
    const input = () => renderer.root.findByProps({testID: 'account-username'});
    const count = () => renderer.root.findByProps({testID: 'account-username-count'});
    expect(input().props.maxLength).toBe(32);
    expect(count().props.children).toBe('0/32');
    for (const username of ['a'.repeat(32), 'Vi Shesh', '']) {
      await ReactTestRenderer.act(async () => { input().props.onChangeText(username); });
      const length = input().props.value.length;
      expect(count().props.children).toBe(`${length}/32`);
      expect(count().props.accessibilityLabel).toBe(`${length} of 32 characters used`);
    }
    await ReactTestRenderer.act(async () => { renderer.unmount(); });
  });

  test('installs Debian beside Alpine, then creates the Debian user', async () => {
    const actions = fakeActions();
    const {renderer, created, press, fill} = await render(actions);
    await fill();
    await press('add-user-create');

    expect(actions.installBeside).toHaveBeenCalledWith('debian');
    expect(actions.createUser).toHaveBeenCalledWith({distro: 'debian', username: 'deb', avatar: 'ibis', password: 'debian pass'});
    expect(created).toEqual([{distro: 'debian', username: 'deb', avatar: 'ibis'}]);
    await ReactTestRenderer.act(async () => { renderer.unmount(); });
  });

  test('shows the install and account-creation phases separately', async () => {
    let finishInstall!: (result: {kind: 'success'}) => void;
    let finishCreate!: (result: {kind: 'success'}) => void;
    const actions = fakeActions({
      installBeside: jest.fn(() => new Promise(resolve => { finishInstall = resolve; })),
      createUser: jest.fn(() => new Promise(resolve => { finishCreate = resolve; })),
    });
    const {renderer, press, fill, has} = await render(actions);
    await fill();
    await press('add-user-create');

    expect(has('add-user-task-install-working')).toBe(true);
    expect(has('add-user-task-create-waiting')).toBe(true);
    expect(renderer.root.findByProps({testID: 'add-user-task-install-detail'}).props.children).toContain('few minutes');

    await ReactTestRenderer.act(async () => {
      finishInstall({kind: 'success'});
      await Promise.resolve();
      await Promise.resolve();
    });
    expect(has('add-user-task-install-done')).toBe(true);
    expect(has('add-user-task-create-working')).toBe(true);
    expect(renderer.root.findByProps({testID: 'add-user-task-create-detail'}).props.children).toBe('Saving deb…');

    await ReactTestRenderer.act(async () => {
      finishCreate({kind: 'success'});
      await Promise.resolve();
    });
    await ReactTestRenderer.act(async () => { renderer.unmount(); });
  });

  test('never creates the user when the install fails, and can retry', async () => {
    const actions = fakeActions({
      installBeside: jest.fn()
        .mockResolvedValueOnce({kind: 'error', errorCode: 'download_failed'})
        .mockResolvedValueOnce({kind: 'success'}),
    });
    const {renderer, created, press, has, fill} = await render(actions);
    await fill();
    await press('add-user-create');

    expect(has('add-user-failed')).toBe(true);
    expect(has('add-user-task-install-failed')).toBe(true);
    expect(renderer.root.findByProps({testID: 'add-user-failure-message'}).props.children).toContain('Check your connection');
    expect(renderer.root.findByProps({testID: 'add-user-error-code'}).props.children).toContain('download_failed');
    expect(actions.createUser).not.toHaveBeenCalled();
    await press('add-user-retry');
    expect(created).toHaveLength(1);
    await ReactTestRenderer.act(async () => { renderer.unmount(); });
  });

  test('explains account creation failures and keeps the installed distribution when editing', async () => {
    const actions = fakeActions({
      createUser: jest.fn()
        .mockResolvedValueOnce({kind: 'error', code: 'duplicate_username'})
        .mockResolvedValueOnce({kind: 'success'}),
    });
    const {renderer, created, press, fill, has} = await render(actions);
    await fill();
    await press('add-user-create');

    expect(has('add-user-task-install-done')).toBe(true);
    expect(has('add-user-task-create-failed')).toBe(true);
    expect(renderer.root.findByProps({testID: 'add-user-failure-message'}).props.children).toContain('already in use');
    expect(actions.installBeside).toHaveBeenCalledTimes(1);

    await press('add-user-edit');
    await ReactTestRenderer.act(async () => { renderer.root.findByProps({testID: 'account-username'}).props.onChangeText('debe'); });
    await press('add-user-create');
    expect(actions.installBeside).toHaveBeenCalledTimes(1);
    expect(actions.createUser).toHaveBeenCalledTimes(2);
    expect(created).toEqual([{distro: 'debian', username: 'debe', avatar: 'ibis'}]);
    await ReactTestRenderer.act(async () => { renderer.unmount(); });
  });

  test('offers nothing once every distribution has its user', async () => {
    const {renderer, has} = await render(fakeActions(), [ALPINE_USER, {distro: 'debian', username: 'deb', avatar: 'ibis'}]);
    expect(has('add-user-none')).toBe(true);
    expect(has('add-user-create')).toBe(false);
    await ReactTestRenderer.act(async () => { renderer.unmount(); });
  });
});
