import React from 'react';
import ReactTestRenderer from 'react-test-renderer';
import type {ProfileUser} from '../src/profile/profileStore';
import {LoginScreen} from '../src/ui/LoginScreen';

const ALPINE_USER: ProfileUser = {distro: 'alpine', username: 'horus', avatar: 'falcon'};
const DEBIAN_USER: ProfileUser = {distro: 'debian', username: 'deb', avatar: 'scarab'};

async function render(users: readonly ProfileUser[]) {
  const logins: [ProfileUser, string][] = [];
  let renderer!: ReactTestRenderer.ReactTestRenderer;
  await ReactTestRenderer.act(async () => {
    renderer = ReactTestRenderer.create(
      <LoginScreen onLogin={async (user, password) => { logins.push([user, password]); }} users={users} />,
    );
  });
  const press = async (testID: string) => {
    await ReactTestRenderer.act(async () => {
      renderer.root.findByProps({testID}).props.onPress();
      await Promise.resolve();
    });
  };
  const type = async (text: string) => {
    await ReactTestRenderer.act(async () => { renderer.root.findByProps({testID: 'login-password'}).props.onChangeText(text); });
  };
  const has = (testID: string) => renderer.root.findAllByProps({testID}).length > 0;
  return {renderer, logins, press, type, has};
}

describe('LoginScreen', () => {
  test('signs the only user in with their password', async () => {
    const {renderer, logins, press, type, has} = await render([ALPINE_USER]);

    // The chosen user is the focus; the brand shows only with the user list.
    expect(has('login-logo')).toBe(false);
    expect(has('login-user-alpine-selected')).toBe(true);
    expect(renderer.root.findByProps({testID: 'login-password'}).props.placeholder).toBe('Enter Password');
    // One user has nobody to switch to.
    expect(has('login-back')).toBe(false);

    await press('login-submit');
    expect(renderer.root.findByProps({testID: 'login-validation-error'}).props.children).toBe('Enter horus’s password to unlock Horus.');

    await type('correct horse');
    await press('login-submit');
    expect(logins).toEqual([[ALPINE_USER, 'correct horse']]);
    await ReactTestRenderer.act(async () => { renderer.unmount(); });
  });

  test('shows every user like the Mac login window, then only the one picked', async () => {
    const {renderer, logins, press, type, has} = await render([ALPINE_USER, DEBIAN_USER]);
    // First only the users; no password until one is picked.
    expect(has('login-user-alpine')).toBe(true);
    expect(has('login-user-debian')).toBe(true);
    expect(has('login-password')).toBe(false);
    expect(renderer.root.findByProps({testID: 'login-logo'}).props.style).toMatchObject({height: 112, width: 112});
    expect(renderer.root.findByProps({testID: 'login-wordmark'}).props.children).toBe('HORUS');

    await press('login-user-alpine');
    expect(has('login-logo')).toBe(false);
    expect(has('login-user-alpine-selected')).toBe(true);
    expect(has('login-user-debian')).toBe(false);
    await type('typed for alpine');

    // Switch user goes back to the list and forgets what was typed.
    await press('login-back');
    expect(has('login-password')).toBe(false);
    await press('login-user-debian');
    expect(has('login-user-debian-selected')).toBe(true);
    expect(renderer.root.findByProps({testID: 'login-password'}).props.value).toBe('');
    expect(renderer.root.findByProps({testID: 'login-password'}).props.accessibilityLabel).toBe('deb’s password');

    await type('debian pass');
    await press('login-submit');
    expect(logins).toEqual([[DEBIAN_USER, 'debian pass']]);
    await ReactTestRenderer.act(async () => { renderer.unmount(); });
  });

  test('opens on the password of the user Account switched to', async () => {
    let renderer!: ReactTestRenderer.ReactTestRenderer;
    await ReactTestRenderer.act(async () => {
      renderer = ReactTestRenderer.create(<LoginScreen initialUser="debian" onLogin={async () => undefined} users={[ALPINE_USER, DEBIAN_USER]} />);
    });
    expect(renderer.root.findAllByProps({testID: 'login-user-debian-selected'}).length).toBeGreaterThan(0);
    expect(renderer.root.findAllByProps({testID: 'login-back'}).length).toBeGreaterThan(0);
    await ReactTestRenderer.act(async () => { renderer.unmount(); });
  });
});
