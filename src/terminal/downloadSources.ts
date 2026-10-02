import nativeTerminalRuntime, {type DownloadSourcesResponse, type Spec} from '../native/NativeTerminalRuntime';
import {PINNED_ROOTFS_URL} from './distroContract';

/** Where Alpine and npm packages come from; undefined means the default server. */
export type DownloadSources = Readonly<{
  alpineMirror?: string;
  npmRegistry?: string;
}>;

export type DownloadSourcesResult = Readonly<
  | {kind: 'success'; sources: DownloadSources}
  | {kind: 'error'; errorCode: 'unavailable' | 'internal_error' | 'invalid_request' | 'invalid_response'}
>;

export const DEFAULT_ALPINE_MIRROR = 'https://dl-cdn.alpinelinux.org/alpine';
export const DEFAULT_NPM_REGISTRY = 'https://registry.npmjs.org';

export type MirrorOption = Readonly<{id: string; label: string; region: string; url?: string}>;

export const ALPINE_MIRRORS: readonly MirrorOption[] = [
  {id: 'default', label: 'Alpine CDN', region: 'Worldwide'},
  {id: 'tuna', label: 'Tsinghua', region: 'China', url: 'https://mirrors.tuna.tsinghua.edu.cn/alpine'},
  {id: 'ustc', label: 'USTC', region: 'China', url: 'https://mirrors.ustc.edu.cn/alpine'},
  {id: 'aliyun', label: 'Aliyun', region: 'China', url: 'https://mirrors.aliyun.com/alpine'},
];

export const NPM_REGISTRIES: readonly MirrorOption[] = [
  {id: 'default', label: 'npm', region: 'Worldwide'},
  {id: 'npmmirror', label: 'npmmirror', region: 'China', url: 'https://registry.npmmirror.com'},
];

/** The npm registry that goes with an Alpine mirror chosen during setup. */
export function npmRegistryFor(alpineMirror: string | undefined): string | undefined {
  const region = ALPINE_MIRRORS.find(option => option.url === alpineMirror)?.region;
  return region === 'China' ? NPM_REGISTRIES.find(option => option.region === 'China')?.url : undefined;
}

// Same rule as DownloadSources.kt: plain https, no credentials, query or fragment.
const MIRROR_URL = /^https:\/\/[A-Za-z0-9.-]+(:[0-9]{1,5})?(\/[A-Za-z0-9._~-]+)*\/?$/;
const MAX_URL_LENGTH = 200;

/** The URL without a trailing slash, or undefined when Horus would reject it. */
export function normalizeMirrorUrl(url: string): string | undefined {
  const trimmed = url.trim();
  if (trimmed.length > MAX_URL_LENGTH || !MIRROR_URL.test(trimmed)) return undefined;
  return trimmed.replace(/\/+$/, '');
}

/** The pinned Alpine archive on a mirror; mirrors keep the official layout. */
export function rootfsUrl(alpineMirror: string | undefined): string {
  return alpineMirror === undefined ? PINNED_ROOTFS_URL : `${alpineMirror}${PINNED_ROOTFS_URL.slice(DEFAULT_ALPINE_MIRROR.length)}`;
}

type DownloadSourcesRuntime = Pick<Spec, 'getDownloadSources' | 'setDownloadSources'>;

function parse(response: DownloadSourcesResponse | unknown): DownloadSourcesResult {
  if (typeof response !== 'object' || response === null) return {kind: 'error', errorCode: 'invalid_response'};
  const value = response as Record<string, unknown>;
  if (value.status === 'error') {
    return value.errorCode === 'internal_error' || value.errorCode === 'invalid_request'
      ? {kind: 'error', errorCode: value.errorCode}
      : {kind: 'error', errorCode: 'invalid_response'};
  }
  const field = (key: string): string | undefined | null => {
    const raw = value[key];
    if (raw === undefined || raw === null) return undefined;
    return typeof raw === 'string' ? normalizeMirrorUrl(raw) ?? null : null;
  };
  const alpineMirror = field('alpineMirror');
  const npmRegistry = field('npmRegistry');
  if (value.status !== 'success' || alpineMirror === null || npmRegistry === null) return {kind: 'error', errorCode: 'invalid_response'};
  return {kind: 'success', sources: {alpineMirror, npmRegistry}};
}

export async function readDownloadSources(
  runtime: DownloadSourcesRuntime | null = nativeTerminalRuntime,
): Promise<DownloadSourcesResult> {
  if (runtime === null) return {kind: 'error', errorCode: 'unavailable'};
  try {
    return parse(await runtime.getDownloadSources());
  } catch {
    return {kind: 'error', errorCode: 'internal_error'};
  }
}

export async function writeDownloadSources(
  sources: DownloadSources,
  runtime: DownloadSourcesRuntime | null = nativeTerminalRuntime,
): Promise<DownloadSourcesResult> {
  const request: {alpineMirror?: string; npmRegistry?: string} = {};
  for (const key of ['alpineMirror', 'npmRegistry'] as const) {
    const url = sources[key];
    if (url === undefined) continue;
    const normalized = normalizeMirrorUrl(url);
    if (normalized === undefined) return {kind: 'error', errorCode: 'invalid_request'};
    request[key] = normalized;
  }
  if (runtime === null) return {kind: 'error', errorCode: 'unavailable'};
  try {
    return parse(await runtime.setDownloadSources(request));
  } catch {
    return {kind: 'error', errorCode: 'internal_error'};
  }
}
