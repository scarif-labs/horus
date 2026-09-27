import {ensureAlpineToolchainReady, prewarmAlpineToolchains, PREWARM_TOOLCHAINS} from '../src/terminal/toolchainWarmup';

describe('Alpine toolchain warm-up', () => {
  test('provisions targets sequentially and reuses a ready target', async () => {
    const calls: string[] = [];
    let active = 0;
    let maximumActive = 0;
    const runtime = {
      provisionToolchain: jest.fn().mockImplementation(async (request: {requestId: string; target: string}) => {
        calls.push(request.target);
        active += 1;
        maximumActive = Math.max(maximumActive, active);
        await Promise.resolve();
        active -= 1;
        return {requestId: request.requestId, status: 'success'};
      }),
    };

    await prewarmAlpineToolchains(runtime);

    expect(calls).toEqual(PREWARM_TOOLCHAINS);
    expect(maximumActive).toBe(1);
    await expect(ensureAlpineToolchainReady('test-opencode', 'opencode', runtime)).resolves.toEqual({
      kind: 'success',
      requestId: 'test-opencode',
    });
    expect(runtime.provisionToolchain).toHaveBeenCalledTimes(PREWARM_TOOLCHAINS.length);
  });
});
