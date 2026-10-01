import {Linking} from 'react-native';

export const GITHUB_AUTH_LOGIN_COMMAND = 'GH_BROWSER=/bin/true gh auth login' as const;

const GITHUB_DEVICE_LOGIN_URL_SEARCH = /https:\/\/github\.com\/login\/device(?:\?[A-Za-z0-9._~!$'()*+,;=:@/?%&-]{1,256})?/g;
const GITHUB_DEVICE_LOGIN_URL_PATTERN = /^https:\/\/github\.com\/login\/device(?:\?[A-Za-z0-9._~!$'()*+,;=:@/?%&-]{1,256})?$/;

function stripTerminalControlSequences(value: string): string {
  let clean = '';
  for (let index = 0; index < value.length; index += 1) {
    if (value.charCodeAt(index) !== 0x1b) {
      clean += value[index];
      continue;
    }

    const introducer = value[index + 1];
    if (introducer === '[') {
      index += 2;
      while (index < value.length) {
        const code = value.charCodeAt(index);
        if (code >= 0x40 && code <= 0x7e) break;
        index += 1;
      }
    } else if (introducer === ']' || introducer === 'P' || introducer === '^' || introducer === '_' || introducer === 'X') {
      index += 2;
      while (index < value.length) {
        if (value.charCodeAt(index) === 0x07) break;
        if (value.charCodeAt(index) === 0x1b && value[index + 1] === '\\') {
          index += 1;
          break;
        }
        index += 1;
      }
    } else {
      index += introducer === undefined ? 0 : 1;
    }
  }
  return clean;
}

function isBoundary(character: string | undefined): boolean {
  return character === undefined || /[\s"'`<>(){}[\],.!?:;]/.test(character);
}

export function isGithubDeviceLoginUrl(value: string): boolean {
  return GITHUB_DEVICE_LOGIN_URL_PATTERN.test(value);
}

/** Finds only an official GitHub device-login URL in terminal output. */
export function findGithubDeviceLoginUrl(value: string): string | undefined {
  const cleanValue = stripTerminalControlSequences(value);
  const search = new RegExp(GITHUB_DEVICE_LOGIN_URL_SEARCH.source, 'g');
  let match: RegExpExecArray | null;
  while ((match = search.exec(cleanValue)) !== null) {
    const start = match.index;
    const end = start + match[0].length;
    if (isBoundary(cleanValue[start - 1]) && isBoundary(cleanValue[end]) && isGithubDeviceLoginUrl(match[0])) return match[0];
  }
  return undefined;
}

/** Opens a validated GitHub device-login URL in Android's default browser. */
export async function openGithubDeviceLoginUrl(value: string): Promise<void> {
  if (!isGithubDeviceLoginUrl(value)) throw new Error('invalid_github_device_login_url');
  await Linking.openURL(value);
}
