import {Linking} from 'react-native';
import {
  GITHUB_AUTH_LOGIN_COMMAND,
  findGithubDeviceLoginUrl,
  isGithubDeviceLoginUrl,
  openGithubDeviceLoginUrl,
} from '../src/projects/githubDeviceLogin';

describe('GitHub device login browser handoff', () => {
  afterEach(() => jest.restoreAllMocks());

  test('uses a no-op gh browser after the app opens the device URL', () => {
    expect(GITHUB_AUTH_LOGIN_COMMAND).toBe('GH_BROWSER=/bin/true gh auth login');
  });

  test('finds only an official device-login URL with output boundaries', () => {
    expect(findGithubDeviceLoginUrl('Press Enter to open https://github.com/login/device in your browser')).toBe('https://github.com/login/device');
    expect(findGithubDeviceLoginUrl('Press Enter to open https://github.com/login/device')).toBe('https://github.com/login/device');
    expect(findGithubDeviceLoginUrl('\u001b[36mhttps://github.com/login/device\u001b[0m')).toBe('https://github.com/login/device');
    expect(findGithubDeviceLoginUrl('https://github.com/login/device?foo=bar\n')).toBe('https://github.com/login/device?foo=bar');
    expect(findGithubDeviceLoginUrl('https://github.com/login/device-malware')).toBeUndefined();
    expect(findGithubDeviceLoginUrl('https://example.com/login/device')).toBeUndefined();
  });

  test('opens a validated URL through the platform browser link handler', async () => {
    const openURL = jest.spyOn(Linking, 'openURL').mockResolvedValue(undefined);
    expect(isGithubDeviceLoginUrl('https://github.com/login/device')).toBe(true);
    await openGithubDeviceLoginUrl('https://github.com/login/device');
    expect(openURL).toHaveBeenCalledWith('https://github.com/login/device');
    await expect(openGithubDeviceLoginUrl('https://example.com/login/device')).rejects.toThrow('invalid_github_device_login_url');
    expect(openURL).toHaveBeenCalledTimes(1);
  });
});
