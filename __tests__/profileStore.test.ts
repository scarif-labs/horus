import {NativeModules} from 'react-native';
import {lockedOutMessage, verifyUserPassword} from '../src/profile/profileStore';

function withNativeResponse(response: unknown): void {
  NativeModules.HorusDevice = {verifyPassword: jest.fn(async () => response)};
}

describe('verifyUserPassword', () => {
  afterEach(() => {
    delete NativeModules.HorusDevice;
  });

  it('maps native results to success, incorrect, and locked', async () => {
    withNativeResponse({status: 'success'});
    await expect(verifyUserPassword('secret')).resolves.toEqual({kind: 'success'});
    withNativeResponse({status: 'error'});
    await expect(verifyUserPassword('secret')).resolves.toEqual({kind: 'incorrect'});
    withNativeResponse({status: 'locked', retryAfterMs: 30_000});
    await expect(verifyUserPassword('secret')).resolves.toEqual({kind: 'locked', retryAfterMs: 30_000});
  });

  it('treats malformed or failing native responses as incorrect', async () => {
    withNativeResponse({status: 'locked'});
    await expect(verifyUserPassword('secret')).resolves.toEqual({kind: 'incorrect'});
    NativeModules.HorusDevice = {verifyPassword: jest.fn(async () => { throw new Error('boom'); })};
    await expect(verifyUserPassword('secret')).resolves.toEqual({kind: 'incorrect'});
  });
});

describe('lockedOutMessage', () => {
  it('rounds the wait up to seconds or minutes', () => {
    expect(lockedOutMessage(29_001)).toBe('Too many incorrect attempts. Try again in 30 seconds.');
    expect(lockedOutMessage(120_000)).toBe('Too many incorrect attempts. Try again in 2 minutes.');
  });
});
