import {PermissionsAndroid, Platform} from 'react-native';
import nativeTerminalRuntime, {type Spec as TerminalRuntimeSpec} from '../native/NativeTerminalRuntime';
import {buildZshScriptCommand, shellQuote} from '../terminal/commandFactory';
import {createRequestIdFactory} from '../terminal/requestIds';
import {decodeBase64} from '../terminal/session/sessionContract';
import {
  TerminalSessionClient,
  type TerminalSessionAttachment,
} from '../terminal/session/sessionClient';
import {
  TERMINAL_SESSION_MAX_COMMAND_LENGTH,
  type TerminalSessionOperationErrorCode,
} from '../terminal/session/sessionContract';

export type GuestFileRoot = 'home' | 'workspace';
export type GuestFilePath = readonly string[];
export type GuestFileEntry = Readonly<{
  name: string;
  kind: 'directory' | 'file' | 'symlink' | 'other';
  sizeBytes: number;
}>;

export type GuestFileExplorerErrorCode =
  | TerminalSessionOperationErrorCode
  | 'invalid_path'
  | 'command_failed'
  | 'invalid_output'
  | 'output_too_large'
  | 'timeout'
  | 'not_found'
  | 'too_large'
  | 'binary_or_invalid_text'
  | 'teardown_failed';

export type GuestDirectoryResult =
  | Readonly<{
      kind: 'success';
      entries: readonly GuestFileEntry[];
      truncated: boolean;
      hiddenInvalidNameCount: number;
    }>
  | Readonly<{kind: 'error'; errorCode: GuestFileExplorerErrorCode}>;

export type GuestFileContentResult =
  | Readonly<{kind: 'success'; content: string; sizeBytes: number}>
  | Readonly<{kind: 'error'; errorCode: GuestFileExplorerErrorCode}>;

export const GUEST_FILE_LIST_LIMIT = 200 as const;
export const GUEST_FILE_PREVIEW_LIMIT_BYTES = 65_536 as const;
// Shell provisioning can install zsh on the first file-browser visit.
export const GUEST_FILE_QUERY_TIMEOUT_MS = 60_000 as const;
export const GUEST_FILE_QUERY_MAX_OUTPUT_BYTES = 131_072 as const;

const MAX_PATH_COMPONENTS = 16;
const MAX_PATH_COMPONENT_BYTES = 255;
const MAX_QUOTED_PATH_LENGTH = 2048;
const MARKER_PREFIX = 'HORUS_FILE_QUERY';
const BASE64_PATTERN = /^[A-Za-z0-9+/]*={0,2}$/;
const FILE_KINDS: readonly GuestFileEntry['kind'][] = [
  'directory',
  'file',
  'symlink',
  'other',
];
const DIRECTORY_STATUSES = ['ok', 'invalid_path', 'not_found', 'command_failed'] as const;
const CONTENT_STATUSES = ['ok', 'invalid_path', 'not_found', 'too_large', 'command_failed'] as const;

const nextRequestId = createRequestIdFactory('files');

function isGuestFileRoot(value: unknown): value is GuestFileRoot {
  return value === 'home' || value === 'workspace';
}

function hasWellFormedSurrogates(value: string): boolean {
  for (let index = 0; index < value.length; index += 1) {
    const code = value.charCodeAt(index);
    if (code >= 0xd800 && code <= 0xdbff) {
      const next = value.charCodeAt(index + 1);
      if (!(next >= 0xdc00 && next <= 0xdfff)) return false;
      index += 1;
    } else if (code >= 0xdc00 && code <= 0xdfff) {
      return false;
    }
  }
  return true;
}

function isValidGuestFilePath(path: unknown): path is GuestFilePath {
  if (!Array.isArray(path) || path.length > MAX_PATH_COMPONENTS) return false;
  let quotedLength = 0;
  for (const component of path) {
    if (
      typeof component !== 'string' ||
      component.length === 0 ||
      component === '.' ||
      component === '..' ||
      component.includes('/') ||
      component.includes('\0') ||
      !hasWellFormedSurrogates(component) ||
      new TextEncoder().encode(component).byteLength > MAX_PATH_COMPONENT_BYTES
    ) {
      return false;
    }
    quotedLength += shellQuote(component).length;
    if (quotedLength > MAX_QUOTED_PATH_LENGTH) return false;
  }
  return true;
}

function isValidMarkerId(value: string): boolean {
  return /^[A-Za-z0-9-]{1,32}$/.test(value);
}

function markerLines(markerId: string): {begin: string; end: string} {
  if (!isValidMarkerId(markerId)) throw new Error('invalid guest file query marker');
  return {
    begin: `${MARKER_PREFIX}_${markerId}_BEGIN`,
    end: `${MARKER_PREFIX}_${markerId}_END`,
  };
}

function buildBoundedZshCommand(lines: readonly string[]): string {
  const command = buildZshScriptCommand(lines.join('\n'));
  if (command.length > TERMINAL_SESSION_MAX_COMMAND_LENGTH) {
    throw new Error('guest file query command exceeds the terminal limit');
  }
  return command;
}

/** Builds a bounded one-level directory query. Every path part is shell-quoted separately. */
export function buildListGuestDirectoryCommand(
  root: GuestFileRoot,
  path: GuestFilePath,
  markerId = 'query',
): string {
  if (!isGuestFileRoot(root) || !isValidGuestFilePath(path)) {
    throw new Error('invalid guest file query path');
  }
  const {begin, end} = markerLines(markerId);
  const anchor = root === 'home' ? 'target="$HOME"' : "target='/workspace'";
  const lines = [
    `printf '%s\\n' '${begin}'`,
    anchor,
    'query_status=ok',
    'truncated=0',
    'if [ -L "$target" ] || [ ! -d "$target" ]; then query_status=not_found; fi',
  ];
  for (const component of path) {
    lines.push(
      `if [ "$query_status" = ok ]; then target="$target"/${shellQuote(component)}; if [ -L "$target" ]; then query_status=invalid_path; elif [ ! -e "$target" ] || [ ! -d "$target" ]; then query_status=not_found; fi; fi`,
    );
  }
  lines.push(
    'if [ "$query_status" = ok ]; then',
    '  count=0',
    '  while IFS= read -r -d \'\' entry; do',
    `    if [ "$count" -ge ${GUEST_FILE_LIST_LIMIT} ]; then truncated=1; break; fi`,
    '    name=${entry##*/}',
    "    name64=$(printf '%s' \"$name\" | base64 | tr -d '\\r\\n')",
    '    if [ -L "$entry" ]; then kind=symlink; size=0',
    '    elif [ -d "$entry" ]; then kind=directory; size=0',
    '    elif [ -f "$entry" ]; then',
    '      kind=file',
    '      if ! size=$(stat -c \'%s\' "$entry" 2>/dev/null); then query_status=command_failed; break; fi',
    '      case "$size" in \'\'|*[!0-9]*) query_status=command_failed; break ;; esac',
    '    else kind=other; size=0; fi',
    '    printf \'%s\\t%s\\t%s\\n\' "$name64" "$kind" "$size"',
    '    count=$((count + 1))',
    '  done < <(find "$target" -mindepth 1 -maxdepth 1 -print0 2>/dev/null)',
    'fi',
    `printf '%s|%s|%s\\n' '${end}' "$query_status" "$truncated"`,
    'exit 0',
  );
  return buildBoundedZshCommand(lines);
}

/** Builds a bounded UTF-8 text preview query anchored to the selected guest root. */
export function buildReadGuestTextFileCommand(
  root: GuestFileRoot,
  path: GuestFilePath,
  markerId = 'query',
): string {
  if (!isGuestFileRoot(root) || !isValidGuestFilePath(path) || path.length === 0) {
    throw new Error('invalid guest file query path');
  }
  const {begin, end} = markerLines(markerId);
  const anchor = root === 'home' ? 'target="$HOME"' : "target='/workspace'";
  const lines = [
    `printf '%s\\n' '${begin}'`,
    anchor,
    'query_status=ok',
    'size=0',
  ];
  for (let index = 0; index < path.length; index += 1) {
    const component = path[index];
    lines.push(
      `if [ "$query_status" = ok ]; then target="$target"/${shellQuote(component)}; if [ -L "$target" ]; then query_status=invalid_path; elif [ ! -e "$target" ]; then query_status=not_found; elif [ ${index === path.length - 1 ? '1' : '0'} -eq 0 ] && [ ! -d "$target" ]; then query_status=not_found; fi; fi`,
    );
  }
  lines.push(
    'if [ "$query_status" = ok ] && [ ! -f "$target" ]; then query_status=not_found; fi',
    'if [ "$query_status" = ok ] && [ ! -r "$target" ]; then query_status=command_failed; fi',
    'if [ "$query_status" = ok ]; then',
    '  if ! size=$(stat -c \'%s\' "$target" 2>/dev/null); then query_status=command_failed; fi',
    '  case "$size" in \'\'|*[!0-9]*) query_status=command_failed ;; esac',
    `  if [ "$query_status" = ok ] && [ "$size" -gt ${GUEST_FILE_PREVIEW_LIMIT_BYTES} ]; then query_status=too_large; fi`,
    '  if [ "$query_status" = ok ]; then',
    '    setopt PIPE_FAIL 2>/dev/null || true',
    '    payload=$(head -c 65537 "$target" 2>/dev/null | base64 2>/dev/null | tr -d \'\\r\\n\')',
    '    if [ "$?" -ne 0 ]; then query_status=command_failed; else printf \'DATA|%s\\n\' "$payload"; fi',
    '  fi',
    'fi',
    `printf '%s|%s|%s\\n' '${end}' "$query_status" "$size"`,
    'exit 0',
  );
  return buildBoundedZshCommand(lines);
}

function stripTerminalControlSequences(value: string): string {
  return value
    .replace(/\u001b\][^\u0007]*(?:\u0007|\u001b\\)/g, '')
    .replace(/\u001b\[[0-?]*[ -/]*[@-~]/g, '')
    .replace(/\r/g, '');
}

type MarkerBlock = Readonly<{lines: readonly string[]; footer: readonly string[]}>;

function parseMarkerBlock(output: string, markerId: string): MarkerBlock | null {
  if (!isValidMarkerId(markerId)) return null;
  const {begin, end} = markerLines(markerId);
  const lines = stripTerminalControlSequences(output).split('\n');
  const beginIndexes: number[] = [];
  const endIndexes: number[] = [];
  for (let index = 0; index < lines.length; index += 1) {
    if (lines[index] === begin) beginIndexes.push(index);
    if (lines[index] === end || lines[index].startsWith(`${end}|`)) endIndexes.push(index);
  }
  if (beginIndexes.length !== 1 || endIndexes.length !== 1) return null;
  const beginIndex = beginIndexes[0];
  const endIndex = endIndexes[0];
  if (beginIndex >= endIndex) return null;
  return {
    lines: lines.slice(beginIndex + 1, endIndex),
    footer: lines[endIndex].split('|'),
  };
}

function parseSafeSize(value: string): number | null {
  if (!/^(0|[1-9][0-9]*)$/.test(value)) return null;
  const size = Number(value);
  return Number.isSafeInteger(size) ? size : null;
}

function decodeStrictUtf8(bytes: Uint8Array): string | null {
  let offset = 0;
  while (offset < bytes.byteLength) {
    const first = bytes[offset];
    if (first <= 0x7f) {
      offset += 1;
      continue;
    }

    let continuationCount: number;
    if (first >= 0xc2 && first <= 0xdf) {
      continuationCount = 1;
    } else if (first >= 0xe0 && first <= 0xef) {
      continuationCount = 2;
    } else if (first >= 0xf0 && first <= 0xf4) {
      continuationCount = 3;
    } else {
      return null;
    }

    if (offset + continuationCount >= bytes.byteLength) return null;
    const second = bytes[offset + 1];
    if (
      (second & 0xc0) !== 0x80 ||
      (first === 0xe0 && second < 0xa0) ||
      (first === 0xed && second > 0x9f) ||
      (first === 0xf0 && second < 0x90) ||
      (first === 0xf4 && second > 0x8f)
    ) {
      return null;
    }
    for (let index = 2; index <= continuationCount; index += 1) {
      if ((bytes[offset + index] & 0xc0) !== 0x80) return null;
    }
    offset += continuationCount + 1;
  }

  try {
    return new TextDecoder('utf-8').decode(bytes);
  } catch {
    return null;
  }
}

function safeEntryName(value: string): boolean {
  return (
    value.length > 0 &&
    value !== '.' &&
    value !== '..' &&
    !value.includes('/') &&
    !value.includes('\0')
  );
}

/** Strictly parses only the unique, exact marker block for this query. */
export function parseGuestDirectoryOutput(
  output: string,
  markerId: string,
): GuestDirectoryResult {
  const block = parseMarkerBlock(output, markerId);
  const invalid = (): GuestDirectoryResult => ({kind: 'error', errorCode: 'invalid_output'});
  if (block === null) return invalid();
  const [endMarker, status, truncatedValue] = block.footer;
  if (
    endMarker !== markerLines(markerId).end ||
    block.footer.length !== 3 ||
    !(DIRECTORY_STATUSES as readonly string[]).includes(status) ||
    (truncatedValue !== '0' && truncatedValue !== '1')
  ) {
    return invalid();
  }
  if (status !== 'ok') {
    return {kind: 'error', errorCode: status as GuestFileExplorerErrorCode};
  }
  const entries: GuestFileEntry[] = [];
  const seenNames = new Set<string>();
  let hiddenInvalidNameCount = 0;
  for (const line of block.lines) {
    if (line.length === 0 || entries.length >= GUEST_FILE_LIST_LIMIT) {
      return invalid();
    }
    const fields = line.split('\t');
    if (fields.length !== 3) return invalid();
    if (fields[0].length % 4 !== 0 || !BASE64_PATTERN.test(fields[0])) {
      return invalid();
    }
    const nameBytes = decodeBase64(fields[0]);
    if (nameBytes === null) return invalid();
    const sizeBytes = parseSafeSize(fields[2]);
    if (!FILE_KINDS.includes(fields[1] as GuestFileEntry['kind'])) return invalid();
    if (sizeBytes === null) return invalid();
    const name = decodeStrictUtf8(nameBytes);
    if (name === null) {
      hiddenInvalidNameCount += 1;
      continue;
    }
    if (!safeEntryName(name)) return invalid();
    if (seenNames.has(name)) return invalid();
    seenNames.add(name);
    entries.push({name, kind: fields[1] as GuestFileEntry['kind'], sizeBytes});
  }
  const truncated = truncatedValue === '1';
  if (truncated && entries.length !== GUEST_FILE_LIST_LIMIT) {
    return invalid();
  }
  return {kind: 'success', entries, truncated, hiddenInvalidNameCount};
}

/** Strictly parses and validates the capped base64 preview block. */
export function parseGuestTextFileOutput(
  output: string,
  markerId: string,
): GuestFileContentResult {
  const block = parseMarkerBlock(output, markerId);
  if (block === null) return {kind: 'error', errorCode: 'invalid_output'};
  const [endMarker, status, rawSize] = block.footer;
  if (
    endMarker !== markerLines(markerId).end ||
    block.footer.length !== 3 ||
    !(CONTENT_STATUSES as readonly string[]).includes(status)
  ) {
    return {kind: 'error', errorCode: 'invalid_output'};
  }
  const sizeBytes = parseSafeSize(rawSize);
  if (sizeBytes === null) return {kind: 'error', errorCode: 'invalid_output'};
  if (status !== 'ok') return {kind: 'error', errorCode: status as GuestFileExplorerErrorCode};
  if (block.lines.length !== 1 || !block.lines[0].startsWith('DATA|')) {
    return {kind: 'error', errorCode: 'invalid_output'};
  }
  return decodePreviewPayload(block.lines[0].slice('DATA|'.length), sizeBytes);
}

/** Shared by the PTY parser and the native mapping so both classify bytes identically. */
function decodePreviewPayload(payload: string, sizeBytes: number): GuestFileContentResult {
  if (payload.length > Math.ceil(GUEST_FILE_PREVIEW_LIMIT_BYTES / 3) * 4) {
    return {kind: 'error', errorCode: 'too_large'};
  }
  const bytes = payload.length === 0 ? new Uint8Array() : decodeBase64(payload);
  if (bytes === null) return {kind: 'error', errorCode: 'invalid_output'};
  if (bytes.byteLength > GUEST_FILE_PREVIEW_LIMIT_BYTES) {
    return {kind: 'error', errorCode: 'too_large'};
  }
  if (bytes.byteLength !== sizeBytes) return {kind: 'error', errorCode: 'invalid_output'};
  const content = decodeStrictUtf8(bytes);
  if (content === null || /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/.test(content)) {
    return {kind: 'error', errorCode: 'binary_or_invalid_text'};
  }
  return {kind: 'success', content, sizeBytes};
}

function joinOutput(chunks: readonly Uint8Array[], byteLength: number): string {
  const bytes = new Uint8Array(byteLength);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return new TextDecoder().decode(bytes);
}

type QueryResult = GuestDirectoryResult | GuestFileContentResult;

async function runGuestQuery<T extends QueryResult>(
  action: 'list' | 'read',
  command: string,
  markerId: string,
  parser: (output: string, marker: string) => T,
  client: TerminalSessionClient,
): Promise<T> {
  return new Promise(resolve => {
    const chunks: Uint8Array[] = [];
    let outputBytes = 0;
    let sessionId: string | undefined;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let settled = false;

    const clearTimer = () => {
      if (timer !== undefined) clearTimeout(timer);
      timer = undefined;
    };
    const stopSession = async (id: string): Promise<boolean> => {
      client.dispose();
      try {
        const stopped = await client.stopSession(nextRequestId(`${action}-stop`), id, 'screen_detach');
        client.dispose();
        return stopped.kind === 'success';
      } catch {
        client.dispose();
        return false;
      }
    };
    const finish = async (result: T, shouldStop: boolean) => {
      if (settled) return;
      settled = true;
      clearTimer();
      if (sessionId !== undefined && shouldStop) {
        const stopped = await stopSession(sessionId);
        if (!stopped) {
          resolve({kind: 'error', errorCode: 'teardown_failed'} as T);
          return;
        }
      } else {
        client.dispose();
      }
      resolve(result);
    };

    timer = setTimeout(() => {
      void finish({kind: 'error', errorCode: 'timeout'} as T, true);
    }, GUEST_FILE_QUERY_TIMEOUT_MS);

    const onStart = async () => {
      let started;
      try {
        started = await client.startSession(nextRequestId(`${action}-start`), {
          command,
          toolchain: 'shell',
          countsAgainstSessionLimit: false,
        });
      } catch {
        await finish({kind: 'error', errorCode: 'internal_error'} as T, false);
        return;
      }
      if (started.kind === 'error') {
        await finish({kind: 'error', errorCode: started.errorCode} as T, false);
        return;
      }
      sessionId = started.sessionId;
      if (settled) {
        const stopped = await stopSession(sessionId);
        if (!stopped) client.dispose();
        return;
      }

      const attachment: TerminalSessionAttachment = {
        onOutput: chunk => {
          if (settled) return;
          if (outputBytes + chunk.bytes.byteLength > GUEST_FILE_QUERY_MAX_OUTPUT_BYTES) {
            void finish({kind: 'error', errorCode: 'output_too_large'} as T, true);
            return;
          }
          chunks.push(chunk.bytes.slice());
          outputBytes += chunk.bytes.byteLength;
        },
        onExit: exit => {
          if (settled) return;
          if (exit.exitCode !== undefined && exit.exitCode !== 0) {
            void finish({kind: 'error', errorCode: 'command_failed'} as T, false);
            return;
          }
          void finish(parser(joinOutput(chunks, outputBytes), markerId), false);
        },
        onProtocolError: () => {
          void finish({kind: 'error', errorCode: 'invalid_output'} as T, true);
        },
      };

      try {
        const subscription = await client.attachAndSubscribe(
          nextRequestId(`${action}-subscribe`),
          sessionId,
          attachment,
        );
        if (subscription.kind === 'error') {
          await finish({kind: 'error', errorCode: subscription.errorCode} as T, true);
        }
      } catch {
        await finish({kind: 'error', errorCode: 'invalid_output'} as T, true);
      }
    };

    void onStart();
  });
}

/** The two native direct-storage methods; absent on iOS, in tests, or older native builds. */
export type GuestFileNativeRuntime = Pick<TerminalRuntimeSpec, 'listGuestDirectory' | 'readGuestFile'>;

const NATIVE_ERROR_CODES: readonly GuestFileExplorerErrorCode[] = [
  'internal_error',
  'invalid_request',
  'invalid_path',
  'not_found',
  'too_large',
  'command_failed',
];

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function nativeRuntimeFor(
  runtime: Partial<GuestFileNativeRuntime> | null | undefined,
  method: keyof GuestFileNativeRuntime,
): GuestFileNativeRuntime | null {
  return runtime !== null && runtime !== undefined && typeof runtime[method] === 'function'
    ? (runtime as GuestFileNativeRuntime)
    : null;
}

function nativeErrorResult(response: Record<string, unknown>): Readonly<{kind: 'error'; errorCode: GuestFileExplorerErrorCode}> {
  const code = response.errorCode;
  return typeof code === 'string' && (NATIVE_ERROR_CODES as readonly string[]).includes(code)
    ? {kind: 'error', errorCode: code as GuestFileExplorerErrorCode}
    : {kind: 'error', errorCode: 'invalid_output'};
}

function isSafeSize(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0;
}

/**
 * Validates a native listing with the same bounds the PTY parser applies:
 * the entry cap, known kinds, safe sizes and names, unique names, and a
 * truncated flag only on a full page.
 */
export function mapNativeGuestDirectoryResponse(
  response: unknown,
  requestId: string,
): GuestDirectoryResult {
  const invalid = (): GuestDirectoryResult => ({kind: 'error', errorCode: 'invalid_output'});
  if (!isRecord(response) || response.requestId !== requestId) return invalid();
  if (response.status === 'error') return nativeErrorResult(response);
  if (response.status !== 'success') return invalid();
  const {entries: rawEntries, truncated, hiddenInvalidNameCount} = response;
  if (!Array.isArray(rawEntries) || typeof truncated !== 'boolean' || !isSafeSize(hiddenInvalidNameCount)) {
    return invalid();
  }
  if (rawEntries.length + hiddenInvalidNameCount > GUEST_FILE_LIST_LIMIT) return invalid();
  const entries: GuestFileEntry[] = [];
  const seenNames = new Set<string>();
  for (const raw of rawEntries) {
    if (!isRecord(raw)) return invalid();
    const {name, kind, sizeBytes} = raw;
    if (typeof name !== 'string' || !hasWellFormedSurrogates(name) || !safeEntryName(name)) return invalid();
    if (!FILE_KINDS.includes(kind as GuestFileEntry['kind'])) return invalid();
    if (!isSafeSize(sizeBytes)) return invalid();
    if (seenNames.has(name)) return invalid();
    seenNames.add(name);
    entries.push({name, kind: kind as GuestFileEntry['kind'], sizeBytes});
  }
  // Same rule as parseGuestDirectoryOutput: hidden names count toward the
  // cap, and a truncated page must carry exactly GUEST_FILE_LIST_LIMIT entries.
  if (truncated && entries.length !== GUEST_FILE_LIST_LIMIT) return invalid();
  return {kind: 'success', entries, truncated, hiddenInvalidNameCount};
}

/**
 * Maps a native preview to the PTY parser's result: size and cap checks,
 * then the same strict UTF-8 and control-character rules.
 */
export function mapNativeGuestFileResponse(
  response: unknown,
  requestId: string,
): GuestFileContentResult {
  if (!isRecord(response) || response.requestId !== requestId) {
    return {kind: 'error', errorCode: 'invalid_output'};
  }
  if (response.status === 'error') return nativeErrorResult(response);
  if (response.status !== 'success') return {kind: 'error', errorCode: 'invalid_output'};
  const {base64, sizeBytes} = response;
  if (typeof base64 !== 'string' || !isSafeSize(sizeBytes)) {
    return {kind: 'error', errorCode: 'invalid_output'};
  }
  return decodePreviewPayload(base64, sizeBytes);
}

async function listGuestDirectoryNatively(
  runtime: GuestFileNativeRuntime,
  root: GuestFileRoot,
  path: GuestFilePath,
): Promise<GuestDirectoryResult> {
  const requestId = nextRequestId('list');
  try {
    const response = await runtime.listGuestDirectory({requestId, root, path: [...path]});
    return mapNativeGuestDirectoryResponse(response, requestId);
  } catch {
    return {kind: 'error', errorCode: 'internal_error'};
  }
}

async function readGuestTextFileNatively(
  runtime: GuestFileNativeRuntime,
  root: GuestFileRoot,
  path: GuestFilePath,
): Promise<GuestFileContentResult> {
  const requestId = nextRequestId('read');
  try {
    const response = await runtime.readGuestFile({requestId, root, path: [...path]});
    return mapNativeGuestFileResponse(response, requestId);
  } catch {
    return {kind: 'error', errorCode: 'internal_error'};
  }
}

export type GuestExportErrorCode =
  | 'internal_error'
  | 'invalid_request'
  | 'invalid_path'
  | 'invalid_output'
  | 'not_found'
  | 'too_large'
  | 'command_failed'
  | 'permission_denied'
  | 'busy'
  | 'unsupported';

export type GuestExportResult =
  | Readonly<{
      kind: 'success';
      destination: string;
      fileCount: number;
      byteCount: number;
      skippedCount: number;
    }>
  | Readonly<{kind: 'error'; errorCode: GuestExportErrorCode}>;

/** Mirrors EXPORT_MAX_FILES / EXPORT_MAX_BYTES in GuestFileBrowser.kt. */
export const GUEST_FILE_EXPORT_MAX_FILES = 20_000 as const;
export const GUEST_FILE_EXPORT_MAX_BYTES = 2 * 1024 ** 3;

const EXPORT_ERROR_CODES: readonly GuestExportErrorCode[] = [
  'internal_error',
  'invalid_request',
  'invalid_path',
  'not_found',
  'too_large',
  'command_failed',
  'permission_denied',
  'busy',
];

export type GuestExportNativeRuntime = Pick<TerminalRuntimeSpec, 'exportGuestDirectory'>;

export function mapNativeGuestExportResponse(response: unknown, requestId: string): GuestExportResult {
  if (!isRecord(response) || response.requestId !== requestId) {
    return {kind: 'error', errorCode: 'invalid_output'};
  }
  if (response.status === 'error') {
    const code = response.errorCode;
    return typeof code === 'string' && (EXPORT_ERROR_CODES as readonly string[]).includes(code)
      ? {kind: 'error', errorCode: code as GuestExportErrorCode}
      : {kind: 'error', errorCode: 'invalid_output'};
  }
  const {destination, fileCount, byteCount, skippedCount} = response;
  if (
    response.status !== 'success' ||
    typeof destination !== 'string' ||
    destination.length === 0 ||
    !isSafeSize(fileCount) ||
    !isSafeSize(byteCount) ||
    !isSafeSize(skippedCount)
  ) {
    return {kind: 'error', errorCode: 'invalid_output'};
  }
  return {kind: 'success', destination, fileCount, byteCount, skippedCount};
}

/**
 * Android 7-9 need WRITE_EXTERNAL_STORAGE to write the public Download
 * folder; 10+ write through MediaStore without any permission.
 */
async function ensureDownloadsWritable(): Promise<boolean> {
  if (Platform.OS !== 'android' || Number(Platform.Version) >= 29) return true;
  try {
    const result = await PermissionsAndroid.request(PermissionsAndroid.PERMISSIONS.WRITE_EXTERNAL_STORAGE);
    return result === PermissionsAndroid.RESULTS.GRANTED;
  } catch {
    return false;
  }
}

/**
 * Copies every regular file below a guest directory into the shared
 * Download/Horus/<folder>-<timestamp> folder. Native-only: the PTY fallback
 * has no route to shared storage.
 */
export async function exportGuestDirectory(
  root: GuestFileRoot,
  path: GuestFilePath,
  runtime: Partial<GuestExportNativeRuntime> | null = nativeTerminalRuntime,
): Promise<GuestExportResult> {
  if (!isGuestFileRoot(root) || !isValidGuestFilePath(path)) {
    return {kind: 'error', errorCode: 'invalid_path'};
  }
  if (runtime === null || runtime === undefined || typeof runtime.exportGuestDirectory !== 'function') {
    return {kind: 'error', errorCode: 'unsupported'};
  }
  if (!(await ensureDownloadsWritable())) return {kind: 'error', errorCode: 'permission_denied'};
  const requestId = nextRequestId('export');
  try {
    const response = await runtime.exportGuestDirectory({requestId, root, path: [...path]});
    return mapNativeGuestExportResponse(response, requestId);
  } catch {
    return {kind: 'error', errorCode: 'internal_error'};
  }
}

/**
 * Lists one guest directory without crossing the selected private root.
 * Android reads app-private storage directly; the PTY query is the fallback
 * when the native method is unavailable.
 */
export function listGuestDirectory(
  root: GuestFileRoot,
  path: GuestFilePath,
  client?: TerminalSessionClient,
  runtime: Partial<GuestFileNativeRuntime> | null = nativeTerminalRuntime,
): Promise<GuestDirectoryResult> {
  if (!isGuestFileRoot(root) || !isValidGuestFilePath(path)) {
    client?.dispose();
    return Promise.resolve({kind: 'error', errorCode: 'invalid_path'});
  }
  const native = nativeRuntimeFor(runtime, 'listGuestDirectory');
  if (native !== null) {
    client?.dispose();
    return listGuestDirectoryNatively(native, root, path);
  }
  const queryClient = client ?? new TerminalSessionClient();
  const markerId = `q${(nextRequestId.currentSequence() + 1).toString(36)}`;
  let command: string;
  try {
    command = buildListGuestDirectoryCommand(root, path, markerId);
  } catch {
    queryClient.dispose();
    return Promise.resolve({kind: 'error', errorCode: 'invalid_path'});
  }
  return runGuestQuery('list', command, markerId, parseGuestDirectoryOutput, queryClient);
}

/**
 * Reads a UTF-8 text preview of a regular guest file, capped at 64 KiB.
 * Android reads app-private storage directly; the PTY query is the fallback.
 */
export function readGuestTextFile(
  root: GuestFileRoot,
  path: GuestFilePath,
  client?: TerminalSessionClient,
  runtime: Partial<GuestFileNativeRuntime> | null = nativeTerminalRuntime,
): Promise<GuestFileContentResult> {
  if (!isGuestFileRoot(root) || !isValidGuestFilePath(path) || path.length === 0) {
    client?.dispose();
    return Promise.resolve({kind: 'error', errorCode: 'invalid_path'});
  }
  const native = nativeRuntimeFor(runtime, 'readGuestFile');
  if (native !== null) {
    client?.dispose();
    return readGuestTextFileNatively(native, root, path);
  }
  const queryClient = client ?? new TerminalSessionClient();
  const markerId = `q${(nextRequestId.currentSequence() + 1).toString(36)}`;
  let command: string;
  try {
    command = buildReadGuestTextFileCommand(root, path, markerId);
  } catch {
    queryClient.dispose();
    return Promise.resolve({kind: 'error', errorCode: 'invalid_path'});
  }
  return runGuestQuery('read', command, markerId, parseGuestTextFileOutput, queryClient);
}
