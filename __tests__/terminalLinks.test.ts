import {Linking} from 'react-native';
import {
  findTrustedTerminalLinks,
  isTrustedTerminalUrl,
  openTrustedTerminalLink,
} from '../src/terminal/terminalLinks';

describe('terminal links', () => {
  afterEach(() => jest.restoreAllMocks());

  test.each([
    'https://github.com/login/device',
    'https://api.github.com/repos',
    'https://console.anthropic.com',
    'https://claude.ai/oauth/authorize',
    'https://auth.openai.com',
    'https://chatgpt.com',
    'https://opencode.ai',
    'http://localhost:5173',
    'http://127.0.0.1:3000',
    'http://[::1]:3000',
  ])('allows %s', url => {
    expect(isTrustedTerminalUrl(url)).toBe(true);
  });

  test.each([
    'https://github.com.evil.example',
    'https://evilgithub.com',
    'https://anthropic.com.attacker.example',
    'http://localhost.evil.example',
    'https://openai.com:99999',
    'https://user@github.com',
    'ftp://github.com',
    'java' + 'script://github.com',
  ])('rejects %s', url => {
    expect(isTrustedTerminalUrl(url)).toBe(false);
  });

  test('finds trusted row links and removes prose punctuation', () => {
    expect(findTrustedTerminalLinks('Login: https://claude.ai/oauth/authorize?code=abc).')).toEqual([
      {
        url: 'https://claude.ai/oauth/authorize?code=abc',
        startIndex: 7,
        endIndex: 49,
      },
    ]);
    expect(findTrustedTerminalLinks('embeddedhttps://github.com/path')).toEqual([]);
    expect(findTrustedTerminalLinks('https://github.com.evil.example/path')).toEqual([]);
  });

  test('opens only trusted links in the platform browser', async () => {
    const openURL = jest.spyOn(Linking, 'openURL').mockResolvedValue(undefined);
    await openTrustedTerminalLink('http://localhost:3000/');
    await expect(openTrustedTerminalLink('https://example.com')).rejects.toThrow('untrusted_terminal_link');
    expect(openURL).toHaveBeenCalledTimes(1);
    expect(openURL).toHaveBeenCalledWith('http://localhost:3000/');
  });
});
