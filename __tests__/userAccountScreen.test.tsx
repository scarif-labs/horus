import React from 'react';
import ReactTestRenderer from 'react-test-renderer';
import type {ProfileUser} from '../src/profile/profileStore';
import {UserAccountScreen} from '../src/ui/UserAccountScreen';

const ALPINE: ProfileUser = {distro: 'alpine', username: 'horus', avatar: 'falcon'};
const DEBIAN: ProfileUser = {distro: 'debian', username: 'deb', avatar: 'ibis'};

async function render(element: React.ReactElement) {
  let renderer!: ReactTestRenderer.ReactTestRenderer;
  await ReactTestRenderer.act(async () => {
    renderer = ReactTestRenderer.create(element);
    await Promise.resolve();
  });
  const find = (testID: string) => renderer.root.findAllByProps({testID});
  return {renderer, find};
}

describe('UserAccountScreen', () => {
  test('shows the signed-in user and offers to add the missing distribution', async () => {
    const onAddUser = jest.fn();
    const {renderer, find} = await render(<UserAccountScreen onAddUser={onAddUser} onBack={() => undefined} user={ALPINE} users={[ALPINE]} />);
    expect(find('user-account-name')[0].props.children).toBe('horus');
    expect(find('user-account-home')[0].props.children).toBe('  ·  /home/horus');
    await ReactTestRenderer.act(async () => { find('user-account-add-user')[0].props.onPress(); });
    expect(onAddUser).toHaveBeenCalledTimes(1);

    await ReactTestRenderer.act(async () => {
      renderer.update(<UserAccountScreen onAddUser={onAddUser} onBack={() => undefined} user={ALPINE} users={[ALPINE, DEBIAN]} />);
    });
    expect(find('user-account-add-user')).toHaveLength(0);
    // The signed-in user is the card; the list holds the others.
    expect(find('user-account-user-alpine')).toHaveLength(0);
    expect(find('user-account-user-debian')[0].props.accessibilityLabel).toBe('Switch to deb');
    await ReactTestRenderer.act(async () => { renderer.unmount(); });
  });

  test('logs out only on the second tap, and reports a failed stop', async () => {
    const onLogout = jest.fn(async () => false);
    const {renderer, find} = await render(<UserAccountScreen onBack={() => undefined} onLogout={onLogout} user={ALPINE} users={[ALPINE]} />);
    const logout = () => find('user-account-logout')[0];
    expect(logout().props.accessibilityLabel).toBe('Log out');
    await ReactTestRenderer.act(async () => { logout().props.onPress(); });
    expect(onLogout).not.toHaveBeenCalled();
    expect(logout().props.accessibilityLabel).toBe('Tap again to log out');

    await ReactTestRenderer.act(async () => {
      logout().props.onPress();
      await Promise.resolve();
    });
    expect(onLogout).toHaveBeenCalledTimes(1);
    expect(logout().props.accessibilityLabel).toBe('Log out');
    expect(renderer.root.findAllByProps({children: 'Couldn’t stop every session. Try again.'}).length).toBeGreaterThan(0);
    await ReactTestRenderer.act(async () => { renderer.unmount(); });
  });

  test('edits only the signed-in user’s name and avatar', async () => {
    const onUpdateUser = jest.fn(async () => ({kind: 'success'} as const));
    const {renderer, find} = await render(<UserAccountScreen onBack={() => undefined} onUpdateUser={onUpdateUser} user={ALPINE} users={[ALPINE, DEBIAN]} />);
    // The other user is listed but has no editor of its own.
    expect(find('user-account-user-debian').length).toBeGreaterThan(0);
    await ReactTestRenderer.act(async () => { find('user-account-edit')[0].props.onPress(); });
    // One editor: the hieroglyphs and the name together.
    expect(find('account-avatars').length).toBeGreaterThan(0);
    expect(find('user-account-username').length).toBeGreaterThan(0);
    expect(find('user-account-edit-save')[0].props.disabled).toBe(true);

    await ReactTestRenderer.act(async () => { find('user-account-username')[0].props.onChangeText('Root!'); });
    await ReactTestRenderer.act(async () => { find('user-account-edit-save')[0].props.onPress(); });
    expect(onUpdateUser).not.toHaveBeenCalled();
    expect(find('user-account-edit-error').length).toBeGreaterThan(0);

    await ReactTestRenderer.act(async () => { find('user-account-username')[0].props.onChangeText('ra'); });
    await ReactTestRenderer.act(async () => { find('account-avatar-scarab')[0].props.onPress(); });
    await ReactTestRenderer.act(async () => {
      find('user-account-edit-save')[0].props.onPress();
      await Promise.resolve();
    });
    expect(onUpdateUser).toHaveBeenCalledWith({distro: 'alpine', username: 'ra', avatar: 'scarab'});
    expect(find('user-account-editor')).toHaveLength(0);
    await ReactTestRenderer.act(async () => { renderer.unmount(); });
  });

  test('the whole card switches users on a second tap, since every session stops', async () => {
    const onSwitchTo = jest.fn(async () => false);
    const {renderer, find} = await render(<UserAccountScreen onBack={() => undefined} onSwitchTo={onSwitchTo} user={ALPINE} users={[ALPINE, DEBIAN]} />);
    const card = () => find('user-account-user-debian')[0];
    await ReactTestRenderer.act(async () => { card().props.onPress(); });
    expect(onSwitchTo).not.toHaveBeenCalled();
    expect(card().props.accessibilityLabel).toBe('Tap again to switch to deb');

    await ReactTestRenderer.act(async () => {
      card().props.onPress();
      await Promise.resolve();
    });
    expect(onSwitchTo).toHaveBeenCalledWith(DEBIAN);
    expect(card().props.accessibilityLabel).toBe('Switch to deb');
    expect(renderer.root.findAllByProps({children: 'Couldn’t stop every session. Try again.'}).length).toBeGreaterThan(0);
    await ReactTestRenderer.act(async () => { renderer.unmount(); });
  });
});
