import {Linking} from 'react-native';

const TRUSTED_DOMAIN_ROOTS = [
  'github.com',
  'anthropic.com',
  'claude.ai',
  'claude.com',
  'openai.com',
  'chatgpt.com',
  'opencode.ai',
] as const;

const HTTP_LINK_PATTERN = /https?:\/\/[^\s<>"'\x60]+/gi;
const URL_BOUNDARIES = "\"'()<>{},;:=[]";

export type TerminalLinkMatch = Readonly<{
  url: string;
  startIndex: number;
  endIndex: number;
}>;

function isValidPort(port: string | undefined): boolean {
  if (port === undefined) return true;
  if (!/^\d{1,5}$/.test(port)) return false;
  const value = Number(port);
  return value >= 1 && value <= 65_535;
}

function trustedHostnameFromUrl(value: string): string | undefined {
  if (Array.from(value).some(character => character.charCodeAt(0) < 0x20 || character.charCodeAt(0) === 0x7f)) return undefined;
  const match = /^https?:\/\/([^/?#]+)/i.exec(value);
  if (match === null) return undefined;
  const authority = match[1];
  if (authority.includes('@')) return undefined;

  const ipv6Match = /^(\[[0-9a-f:]+\])(?::(\d{1,5}))?$/i.exec(authority);
  if (ipv6Match !== null) {
    if (!isValidPort(ipv6Match[2])) return undefined;
    const hostname = ipv6Match[1].slice(1, -1).toLowerCase();
    return hostname === '::1' ? hostname : undefined;
  }

  const hostMatch = /^([a-z0-9.-]+)(?::(\d{1,5}))?$/i.exec(authority);
  if (hostMatch === null || !isValidPort(hostMatch[2])) return undefined;
  const hostname = hostMatch[1].replace(/\.$/, '').toLowerCase();
  const labels = hostname.split('.');
  if (labels.some(label => !/^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$/i.test(label))) return undefined;
  return hostname;
}

/** Only HTTP(S) links for local development and named provider domains may open. */
export function isTrustedTerminalUrl(value: string): boolean {
  const hostname = trustedHostnameFromUrl(value);
  if (hostname === undefined) return false;
  if (
    hostname === 'localhost' ||
    hostname.endsWith('.localhost') ||
    hostname === '127.0.0.1' ||
    hostname === '::1'
  ) return true;
  return TRUSTED_DOMAIN_ROOTS.some(domain => hostname === domain || hostname.endsWith('.' + domain));
}

function trimTerminalPunctuation(value: string): string {
  let result = value.replace(/[.,;:!?]+$/, '');
  for (const [open, close] of [['(', ')'], ['[', ']'], ['{', '}']] as const) {
    while (result.endsWith(close)) {
      const openingCount = Array.from(result).filter(character => character === open).length;
      const closingCount = Array.from(result).filter(character => character === close).length;
      if (closingCount <= openingCount) break;
      result = result.slice(0, -1);
    }
  }
  return result;
}

function isUrlBoundary(character: string | undefined): boolean {
  return character === undefined || /\s/.test(character) || URL_BOUNDARIES.includes(character);
}

/** Finds trusted links in a single rendered terminal row, excluding prose punctuation. */
export function findTrustedTerminalLinks(value: string): readonly TerminalLinkMatch[] {
  const pattern = new RegExp(HTTP_LINK_PATTERN.source, 'gi');
  const links: TerminalLinkMatch[] = [];
  let match: RegExpExecArray | null;
  while ((match = pattern.exec(value)) !== null) {
    const startIndex = match.index;
    if (!isUrlBoundary(value[startIndex - 1])) continue;
    const url = trimTerminalPunctuation(match[0]);
    if (url.length === 0 || !isTrustedTerminalUrl(url)) continue;
    links.push({url, startIndex, endIndex: startIndex + url.length});
    pattern.lastIndex = startIndex + url.length;
  }
  return links;
}

export async function openTrustedTerminalLink(value: string): Promise<void> {
  if (!isTrustedTerminalUrl(value)) throw new Error('untrusted_terminal_link');
  await Linking.openURL(value);
}
