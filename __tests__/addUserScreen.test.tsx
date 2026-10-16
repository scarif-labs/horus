import React from 'react';
import ReactTestRenderer from 'react-test-renderer';
import type {ProfileUser} from '../src/profile/profileStore';
import {AddUserScreen, type AddUserActions} from '../src/ui/AddUserScreen';

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
    expect(actions.createUser).not.toHaveBeenCalled();
    await press('add-user-retry');
    expect(created).toHaveLength(1);
    await ReactTestRenderer.act(async () => { renderer.unmount(); });
  });

  test('offers nothing once every distribution has its user', async () => {
    const {renderer, has} = await render(fakeActions(), [ALPINE_USER, {distro: 'debian', username: 'deb', avatar: 'ibis'}]);
    expect(has('add-user-none')).toBe(true);
    expect(has('add-user-create')).toBe(false);
    await ReactTestRenderer.act(async () => { renderer.unmount(); });
  });
});
