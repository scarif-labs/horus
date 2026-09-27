import {consumeLaunchSessionId} from '../src/terminal/session/launchSession';

describe('consumeLaunchSessionId', () => {
  it('returns a valid session id from a tapped notification', async () => {
    await expect(consumeLaunchSessionId({consumeLaunchSessionId: async () => 's-1790170421367-2'}))
      .resolves.toBe('s-1790170421367-2');
  });

  it('ignores missing, malformed, or failing lookups', async () => {
    await expect(consumeLaunchSessionId(null)).resolves.toBeUndefined();
    await expect(consumeLaunchSessionId({consumeLaunchSessionId: async () => null})).resolves.toBeUndefined();
    await expect(consumeLaunchSessionId({consumeLaunchSessionId: async () => '../etc'})).resolves.toBeUndefined();
    await expect(consumeLaunchSessionId({
      consumeLaunchSessionId: async () => {
        throw new Error('bridge gone');
      },
    })).resolves.toBeUndefined();
  });
});
