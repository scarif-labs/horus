import {NativeModules} from 'react-native';
import {lockedOutMessage, readUserProfile, saveUserProfile, usernameProblem, verifyUserPassword} from '../src/profile/profileStore';

function withNativeResponse(response: unknown): void {
  NativeModules.HorusDevice = {verifyPassword: jest.fn(async () => response)};
}

describe('verifyUserPassword', () => {
  afterEach(() => {
    delete NativeModules.HorusDevice;
  });

  it('maps native results to success, incorrect, and locked', async () => {
    withNativeResponse({status: 'success'});
    await expect(verifyUserPassword('alpine', 'secret')).resolves.toEqual({kind: 'success'});
    withNativeResponse({status: 'error'});
    await expect(verifyUserPassword('alpine', 'secret')).resolves.toEqual({kind: 'incorrect'});
    withNativeResponse({status: 'locked', retryAfterMs: 30_000});
    await expect(verifyUserPassword('alpine', 'secret')).resolves.toEqual({kind: 'locked', retryAfterMs: 30_000});
  });

  it('treats malformed or failing native responses as incorrect', async () => {
    withNativeResponse({status: 'locked'});
    await expect(verifyUserPassword('alpine', 'secret')).resolves.toEqual({kind: 'incorrect'});
    NativeModules.HorusDevice = {verifyPassword: jest.fn(async () => { throw new Error('boom'); })};
    await expect(verifyUserPassword('alpine', 'secret')).resolves.toEqual({kind: 'incorrect'});
  });
});

describe('lockedOutMessage', () => {
  it('rounds the wait up to seconds or minutes', () => {
    expect(lockedOutMessage(29_001)).toBe('Too many incorrect attempts. Try again in 30 seconds.');
    expect(lockedOutMessage(120_000)).toBe('Too many incorrect attempts. Try again in 2 minutes.');
  });
});

describe('verifyUserPassword request', () => {
  afterEach(() => {
    delete NativeModules.HorusDevice;
  });

  it('asks native about one distribution\'s user', async () => {
    const verifyPassword = jest.fn(async () => ({status: 'success'}));
    NativeModules.HorusDevice = {verifyPassword};
    await verifyUserPassword('debian', 'secret');
    expect(verifyPassword).toHaveBeenCalledWith({distro: 'debian', password: 'secret'});
  });
});

describe('readUserProfile', () => {
  afterEach(() => {
    delete NativeModules.HorusDevice;
  });

  function withProfile(response: unknown): void {
    NativeModules.HorusDevice = {getProfile: jest.fn(async () => response)};
  }

  it('reads one user per distribution, alpine first', async () => {
    withProfile({
      configured: true,
      hasPassword: true,
      users: [
        {distro: 'alpine', username: 'horus', avatar: 'falcon'},
        {distro: 'debian', username: 'deb', avatar: 'scarab'},
      ],
    });
    await expect(readUserProfile()).resolves.toEqual({
      users: [
        {distro: 'alpine', username: 'horus', avatar: 'falcon'},
        {distro: 'debian', username: 'deb', avatar: 'scarab'},
      ],
    });
  });

  it('rejects malformed, duplicate, or empty user lists', async () => {
    withProfile({configured: false, users: []});
    await expect(readUserProfile()).resolves.toBeNull();
    withProfile({configured: true, users: [{distro: 'alpine', username: 'Root!', avatar: 'falcon'}]});
    await expect(readUserProfile()).resolves.toBeNull();
    withProfile({configured: true, users: [{distro: 'alpine', username: 'a', avatar: 'eye'}]});
    await expect(readUserProfile()).resolves.toBeNull();
    withProfile({
      configured: true,
      users: [
        {distro: 'alpine', username: 'a', avatar: 'falcon'},
        {distro: 'alpine', username: 'b', avatar: 'ibis'},
      ],
    });
    await expect(readUserProfile()).resolves.toBeNull();
  });
});

describe('saveUserProfile', () => {
  afterEach(() => {
    delete NativeModules.HorusDevice;
  });

  it('sends the whole user and reports native error codes', async () => {
    const saveProfile = jest.fn(async () => ({status: 'success'}));
    NativeModules.HorusDevice = {saveProfile};
    const user = {distro: 'debian', username: 'deb', avatar: 'scarab', password: 'horus'} as const;
    await expect(saveUserProfile(user)).resolves.toEqual({kind: 'success'});
    expect(saveProfile).toHaveBeenCalledWith(user);

    NativeModules.HorusDevice = {saveProfile: jest.fn(async () => ({status: 'error', errorCode: 'exists'}))};
    await expect(saveUserProfile(user)).resolves.toEqual({kind: 'error', code: 'exists'});
  });

  it('rejects bad usernames and passwords before asking native', async () => {
    const saveProfile = jest.fn();
    NativeModules.HorusDevice = {saveProfile};
    await expect(saveUserProfile({distro: 'alpine', username: 'root', avatar: 'falcon', password: 'horus'}))
      .resolves.toEqual({kind: 'error', code: 'invalid_username'});
    await expect(saveUserProfile({distro: 'alpine', username: 'ok', avatar: 'falcon', password: 'abc'}))
      .resolves.toEqual({kind: 'error', code: 'invalid_password'});
    expect(saveProfile).not.toHaveBeenCalled();
  });
});

describe('usernameProblem', () => {
  it('matches the native username rules', () => {
    for (const name of ['vishesh', 'horus', 'dev_1', '_x', 'a-b']) expect(usernameProblem(name)).toBeUndefined();
    for (const name of ['', 'Vishesh', '1abc', 'root', 'nobody', 'harness_claude', 'a b', 'x'.repeat(33)]) {
      expect(usernameProblem(name)).toBeDefined();
    }
  });
});
