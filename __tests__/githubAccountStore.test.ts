import {NativeModules} from 'react-native';
import {clearStoredGithubAccount, readStoredGithubAccount, saveGithubAccount} from '../src/projects/githubAccountStore';

type GithubModuleMock = {
  getGithubAccount: jest.Mock;
  saveGithubAccount: jest.Mock;
  clearGithubAccount: jest.Mock;
};

const nativeModules = NativeModules as unknown as {HorusDevice?: GithubModuleMock};
const githubModule: GithubModuleMock = {
  getGithubAccount: jest.fn(),
  saveGithubAccount: jest.fn(),
  clearGithubAccount: jest.fn(),
};

describe('GitHub account persistence', () => {
  beforeEach(() => {
    nativeModules.HorusDevice = githubModule;
    githubModule.getGithubAccount.mockReset();
    githubModule.saveGithubAccount.mockReset();
    githubModule.clearGithubAccount.mockReset();
  });

  test('reads a cached non-secret identity', async () => {
    githubModule.getGithubAccount.mockResolvedValue({username: 'octocat', avatarUrl: 'https://avatars.githubusercontent.com/u/1'});
    await expect(readStoredGithubAccount()).resolves.toEqual({username: 'octocat', avatarUrl: 'https://avatars.githubusercontent.com/u/1'});
  });

  test('rejects malformed cached identity', async () => {
    githubModule.getGithubAccount.mockResolvedValue({username: 'octocat', avatarUrl: 'https://example.com/token'});
    await expect(readStoredGithubAccount()).resolves.toBeUndefined();
  });

  test('saves only a validated identity', async () => {
    githubModule.saveGithubAccount.mockResolvedValue({status: 'success'});
    await expect(saveGithubAccount({username: 'octocat', avatarUrl: 'https://avatars.githubusercontent.com/u/1'})).resolves.toBe(true);
    expect(githubModule.saveGithubAccount).toHaveBeenCalledWith({username: 'octocat', avatarUrl: 'https://avatars.githubusercontent.com/u/1'});
    await expect(saveGithubAccount({username: 'octocat', avatarUrl: 'https://example.com/token'})).resolves.toBe(false);
  });

  test('clears the cached GitHub identity', async () => {
    githubModule.clearGithubAccount.mockResolvedValue({status: 'success'});
    await expect(clearStoredGithubAccount()).resolves.toBe(true);
    expect(githubModule.clearGithubAccount).toHaveBeenCalledTimes(1);
  });
});
