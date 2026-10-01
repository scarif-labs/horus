import {buildZshScriptCommand} from '../terminal/commandFactory';
import {
  TerminalSessionClient,
  type TerminalSessionAttachment,
} from '../terminal/session/sessionClient';
import type {TerminalSessionOperationErrorCode} from '../terminal/session/sessionContract';
import type {ProjectSummary} from './projectTypes';

export const GITHUB_REPO_LIST_LIMIT = 100 as const;
export const GITHUB_REPO_LIST_TIMEOUT_MS = 30_000 as const;

const GITHUB_ACCOUNT_BEGIN = 'HORUS_GITHUB_ACCOUNT_BEGIN';
const GITHUB_ACCOUNT_END = 'HORUS_GITHUB_ACCOUNT_END';
const GITHUB_REPO_LIST_BEGIN = 'HORUS_GITHUB_REPOS_BEGIN';
const GITHUB_REPO_LIST_END = 'HORUS_GITHUB_REPOS_END';
const GITHUB_REPO_LIST_MAX_OUTPUT_BYTES = 64 * 1024;
const GITHUB_USERNAME_PATTERN = /^[A-Za-z0-9][A-Za-z0-9-]{0,38}$/;
const GITHUB_REPO_LIST_NAME_PATTERN = /^[A-Za-z0-9._-]{1,100}$/;
const GITHUB_REPO_LIST_OWNER_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._-]{0,38}$/;
const GITHUB_AVATAR_PATH_PATTERN = /^https:\/\/avatars\.githubusercontent\.com\/[A-Za-z0-9._~!$'()*+,;=:@%/-]{1,256}$/;

function isSafeGithubRepositoryName(value: string): boolean {
  return value !== '.' && value !== '..' && GITHUB_REPO_LIST_NAME_PATTERN.test(value);
}

export type GithubAccount = Readonly<{
  username: string;
  avatarUrl?: string;
}>;

export type GithubRepositoryListErrorCode =
  | TerminalSessionOperationErrorCode
  | 'command_failed'
  | 'invalid_output'
  | 'output_too_large'
  | 'protocol_error'
  | 'timeout'
  | 'teardown_failed';

export type GithubRepositoryOutputIssue =
  | 'account_marker_missing_or_incomplete'
  | 'account_record_invalid'
  | 'repositories_marker_missing_or_incomplete'
  | 'repository_row_invalid';

export type GithubRepositoryListResult =
  | Readonly<{kind: 'success'; account: GithubAccount; repositories: readonly ProjectSummary[]}>
  | Readonly<{kind: 'error'; account?: GithubAccount; errorCode: GithubRepositoryListErrorCode; exitCode?: number; outputIssue?: GithubRepositoryOutputIssue}>;

let requestSequence = 0;

function nextRequestId(prefix: string): string {
  requestSequence = requestSequence >= Number.MAX_SAFE_INTEGER ? 1 : requestSequence + 1;
  return `github-${prefix}-${requestSequence.toString(36)}`;
}

/** Runs a non-interactive, authenticated gh query inside the persistent guest. */
export function buildGithubRepositoryListCommand(): string {
  return buildZshScriptCommand(
    `set -e; mkdir -p /workspace/projects; cd /workspace/projects; set +e; export GH_PAGER=cat; export PAGER=cat; export NO_COLOR=1; printf '%s\\n' '${GITHUB_ACCOUNT_BEGIN}'; gh api user --jq '[.login, .avatar_url] | @tsv'; accountExitCode=$?; printf '%s|%s\\n' '${GITHUB_ACCOUNT_END}' "$accountExitCode"; if [ "$accountExitCode" -ne 0 ]; then exit "$accountExitCode"; fi; printf '%s\\n' '${GITHUB_REPO_LIST_BEGIN}'; gh api 'user/repos?affiliation=owner,collaborator,organization_member&per_page=${GITHUB_REPO_LIST_LIMIT}' --jq '.[] | [.name, .full_name] | @tsv'; repoExitCode=$?; printf '%s|%s\\n' '${GITHUB_REPO_LIST_END}' "$repoExitCode"; exit "$repoExitCode"`,
  );
}

function stripTerminalControlSequences(value: string): string {
  return value
    // Stop at the first BEL or ST; spanning two OSC frames deletes query data.
    .replace(/\u001b\](?:[^\u0007\u001b]|\u001b(?!\\))*(?:\u0007|\u001b\\)/g, '')
    .replace(/\u001b\[[0-?]*[ -/]*[@-~]/g, '')
    .replace(/\r/g, '');
}

type ParsedMarkerBlock = Readonly<{
  beginLine: number;
  endLine: number;
  lines: readonly string[];
  exitCode: number;
}>;

function parseMarkerBlock(
  lines: readonly string[],
  beginMarker: string,
  endMarker: string,
  startAt = 0,
): ParsedMarkerBlock | undefined {
  let beginLine = -1;
  for (let index = startAt; index < lines.length; index += 1) {
    if (lines[index] === beginMarker) {
      beginLine = index;
      break;
    }
  }
  if (beginLine < 0) return undefined;

  const endPattern = new RegExp(`^${endMarker}\\|([0-9]{1,3})$`);
  for (let index = beginLine + 1; index < lines.length; index += 1) {
    const match = endPattern.exec(lines[index]);
    if (match === null) continue;
    const exitCode = Number(match[1]);
    if (!Number.isSafeInteger(exitCode) || exitCode < 0 || exitCode > 255) return undefined;
    return {beginLine, endLine: index, lines: lines.slice(beginLine + 1, index), exitCode};
  }
  return undefined;
}

function safeGithubAvatarUrl(value: string): string | undefined {
  const withoutQuery = value.split(/[?#]/, 1)[0];
  return GITHUB_AVATAR_PATH_PATTERN.test(withoutQuery) ? withoutQuery : undefined;
}

/** Parses only the exact marker block emitted by buildGithubRepositoryListCommand. */
export function parseGithubRepositoryListOutput(output: string): GithubRepositoryListResult {
  const cleanOutput = stripTerminalControlSequences(output);
  const lines = cleanOutput.split('\n');
  const accountBlock = parseMarkerBlock(lines, GITHUB_ACCOUNT_BEGIN, GITHUB_ACCOUNT_END);
  if (accountBlock === undefined) return {kind: 'error', errorCode: 'invalid_output', outputIssue: 'account_marker_missing_or_incomplete'};
  if (accountBlock.exitCode !== 0) return {kind: 'error', errorCode: 'command_failed', exitCode: accountBlock.exitCode};
  if (accountBlock.lines.length !== 1) return {kind: 'error', errorCode: 'invalid_output', outputIssue: 'account_record_invalid'};
  const accountFields = accountBlock.lines[0].split('\t');
  if (accountFields.length !== 2 || !GITHUB_USERNAME_PATTERN.test(accountFields[0])) {
    return {kind: 'error', errorCode: 'invalid_output', outputIssue: 'account_record_invalid'};
  }
  const account: GithubAccount = {
    username: accountFields[0],
    ...(safeGithubAvatarUrl(accountFields[1]) === undefined ? {} : {avatarUrl: safeGithubAvatarUrl(accountFields[1])}),
  };

  const repoBlock = parseMarkerBlock(lines, GITHUB_REPO_LIST_BEGIN, GITHUB_REPO_LIST_END, accountBlock.endLine + 1);
  if (repoBlock === undefined) return {kind: 'error', account, errorCode: 'invalid_output', outputIssue: 'repositories_marker_missing_or_incomplete'};
  if (repoBlock.exitCode !== 0) return {kind: 'error', account, errorCode: 'command_failed', exitCode: repoBlock.exitCode};

  const repositories: ProjectSummary[] = [];
  const seen = new Set<string>();
  for (const line of repoBlock.lines) {
    if (line.length === 0) continue;
    const fields = line.split('\t');
    if (fields.length !== 2) return {kind: 'error', account, errorCode: 'invalid_output', outputIssue: 'repository_row_invalid'};
    const [name, nameWithOwner] = fields;
    const separator = nameWithOwner.indexOf('/');
    const owner = separator > 0 ? nameWithOwner.slice(0, separator) : '';
    const repoName = separator > 0 ? nameWithOwner.slice(separator + 1) : '';
    if (
      !isSafeGithubRepositoryName(name) ||
      !GITHUB_REPO_LIST_OWNER_PATTERN.test(owner) ||
      !isSafeGithubRepositoryName(repoName) ||
      name !== repoName
    ) {
      return {kind: 'error', account, errorCode: 'invalid_output', outputIssue: 'repository_row_invalid'};
    }
    if (seen.has(nameWithOwner)) continue;
    seen.add(nameWithOwner);
    repositories.push({
      name,
      path: nameWithOwner,
      remote: `https://github.com/${nameWithOwner}.git`,
    });
  }
  return {kind: 'success', account, repositories};
}

function appendChunk(chunks: Uint8Array[], chunk: Uint8Array, currentBytes: number): number {
  if (currentBytes + chunk.byteLength > GITHUB_REPO_LIST_MAX_OUTPUT_BYTES) return -1;
  chunks.push(chunk.slice());
  return currentBytes + chunk.byteLength;
}

function joinChunks(chunks: readonly Uint8Array[], byteLength: number): string {
  const bytes = new Uint8Array(byteLength);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return new TextDecoder().decode(bytes);
}

/** Executes the list command through the same bounded PTY contract as the terminal UI. */
export async function listGithubRepositories(
  client: TerminalSessionClient = new TerminalSessionClient(),
  onAccount?: (account: GithubAccount) => void,
): Promise<GithubRepositoryListResult> {
  const started = await client.startSession(nextRequestId('list-start'), {
    command: buildGithubRepositoryListCommand(),
    toolchain: 'github',
    // A background query, not an app the user opened.
    countsAgainstSessionLimit: false,
  });
  if (started.kind === 'error') {
    client.dispose();
    return {kind: 'error', errorCode: started.errorCode};
  }

  return new Promise(resolve => {
    const chunks: Uint8Array[] = [];
    let outputBytes = 0;
    let accountPublished = false;
    let settled = false;
    let timer: ReturnType<typeof setTimeout> | undefined;

    const clearTimer = () => {
      if (timer !== undefined) clearTimeout(timer);
      timer = undefined;
    };
    const finish = (result: GithubRepositoryListResult) => {
      if (settled) return;
      settled = true;
      clearTimer();
      client.dispose();
      resolve(result);
    };
    const accountFromOutput = (): GithubAccount | undefined => {
      const accountOutput = parseGithubRepositoryListOutput(joinChunks(chunks, outputBytes));
      return accountOutput.account;
    };
    const publishAccount = () => {
      if (accountPublished) return;
      const account = accountFromOutput();
      if (account === undefined) return;
      accountPublished = true;
      onAccount?.(account);
    };
    const stopAndFinish = async (result: GithubRepositoryListResult) => {
      if (settled) return;
      settled = true;
      clearTimer();
      client.dispose();
      const stopped = await client.stopSession(nextRequestId('list-stop'), started.sessionId, 'screen_detach');
      client.dispose();
      if (stopped.kind === 'error' || stopped.kind === 'incomplete') {
        resolve({kind: 'error', errorCode: 'teardown_failed'});
        return;
      }
      const account = result.kind === 'error' && result.account === undefined ? accountFromOutput() : undefined;
      resolve(account === undefined || result.kind === 'success' ? result : {...result, account});
    };

    const attachment: TerminalSessionAttachment = {
      onOutput: chunk => {
        if (settled) return;
        outputBytes = appendChunk(chunks, chunk.bytes, outputBytes);
        if (outputBytes < 0) {
          void stopAndFinish({kind: 'error', errorCode: 'output_too_large'});
          return;
        }
        publishAccount();
      },
      onExit: exit => {
        if (settled) return;
        const parsed = parseGithubRepositoryListOutput(joinChunks(chunks, outputBytes));
        if (parsed.kind === 'error') {
          finish(parsed);
          return;
        }
        if (exit.exitCode !== undefined && exit.exitCode !== 0) {
          finish({kind: 'error', errorCode: 'command_failed', exitCode: exit.exitCode});
          return;
        }
        finish(parsed);
      },
      onProtocolError: () => {
        void stopAndFinish({kind: 'error', errorCode: 'protocol_error'});
      },
    };

    void (async () => {
      try {
        const subscription = await client.attachAndSubscribe(
          nextRequestId('list-subscribe'),
          started.sessionId,
          attachment,
        );
        if (subscription.kind === 'error') {
          await stopAndFinish({kind: 'error', errorCode: subscription.errorCode});
          return;
        }
        if (!settled) {
          timer = setTimeout(() => {
            void stopAndFinish({kind: 'error', errorCode: 'timeout'});
          }, GITHUB_REPO_LIST_TIMEOUT_MS);
        }
      } catch {
        await stopAndFinish({kind: 'error', errorCode: 'invalid_response'});
      }
    })();
  });
}
