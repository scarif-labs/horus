import {updateUnlockGrant} from '../src/profile/unlockGrant';

describe('updateUnlockGrant', () => {
  afterEach(() => {
    jest.useRealTimers();
  });

  it('reports the service grant for the matching request', async () => {
    const runtime = {
      updateUnlockGrant: jest.fn(async ({requestId}: {requestId: string; op: string}) => ({
        requestId,
        status: 'success' as const,
        unlocked: true,
      })),
    };
    await expect(updateUnlockGrant('resume', runtime)).resolves.toBe(true);
    expect(runtime.updateUnlockGrant).toHaveBeenCalledWith(expect.objectContaining({op: 'resume'}));
    expect(runtime.updateUnlockGrant.mock.calls[0][0].requestId).toMatch(/^unlock-resume-[0-9a-z]+$/);
  });

  it('stays locked on errors, mismatched replies, rejections, or a missing module', async () => {
    await expect(updateUnlockGrant('resume', null)).resolves.toBe(false);
    await expect(updateUnlockGrant('resume', {
      updateUnlockGrant: async ({requestId}) => ({requestId, status: 'error', errorCode: 'internal_error'}),
    })).resolves.toBe(false);
    await expect(updateUnlockGrant('resume', {
      updateUnlockGrant: async () => ({requestId: 'other', status: 'success', unlocked: true}),
    })).resolves.toBe(false);
    await expect(updateUnlockGrant('resume', {
      updateUnlockGrant: async () => {
        throw new Error('bridge gone');
      },
    })).resolves.toBe(false);
  });

  it('stays locked when the service does not answer in time and clears its timer', async () => {
    jest.useFakeTimers();
    const pending = updateUnlockGrant('resume', {updateUnlockGrant: () => new Promise(() => undefined)}, 100);
    jest.advanceTimersByTime(100);
    await expect(pending).resolves.toBe(false);
    expect(jest.getTimerCount()).toBe(0);
  });
});
