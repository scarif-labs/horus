/** Only fixed diagnostic summaries leave the installer; arbitrary output is omitted. */
export const MAX_DIAGNOSTIC_LOG_CHARS = 4096;
export const EMPTY_DIAGNOSTICS = 'No diagnostic output was captured.';
export const OMITTED_DIAGNOSTICS = 'Installer output was omitted because it contained no recognized safe diagnostic message.';
export const PROVISIONING_STAGES = ['start', 'apk', 'apk_failed', 'base_ready', 'github', 'github_failed', 'claude', 'claude_failed', 'codex', 'codex_failed', 'opencode', 'opencode_failed', 'normalize', 'normalize_failed', 'verify', 'verify_failed', 'ready'] as const;
export const PROVISIONING_OUTCOMES = ['failed', 'timed_out', 'cancelled', 'spawn_failed', 'not_started', 'incomplete'] as const;
export type ProvisioningDiagnostics = Readonly<{
  outcome: typeof PROVISIONING_OUTCOMES[number];
  stage?: string;
  exitCode?: number;
  commandExitCode?: number;
  log: string;
}>;
const RULES: ReadonlyArray<readonly [string, string]> = [
  [
    "no space left on device|ENOSPC",
    "No space left on device."
  ],
  [
    "could not get lock|unable to (acquire.*lock|lock database)|failed to open apk database",
    "Another package installation holds the package database lock."
  ],
  [
    "dpkg was interrupted",
    "Debian package configuration was interrupted."
  ],
  [
    "permission denied|EACCES",
    "Permission denied while installing packages."
  ],
  [
    "could not resolve|ENOTFOUND|EAI_AGAIN",
    "Could not resolve a download host."
  ],
  [
    "timed out|ETIMEDOUT",
    "A download connection timed out."
  ],
  [
    "ENETUNREACH|ECONN|failed to fetch|unable to fetch|fetch failed|network is unreachable",
    "Network is unreachable while downloading packages."
  ],
  [
    "EBADENGINE|unsupported engine",
    "EBADENGINE: incompatible Node.js version."
  ],
  [
    "EBADPLATFORM|unsupported platform|missing optional dependency",
    "A required platform package is unavailable."
  ],
  [
    "web page instead of.*installer",
    "The download returned a web page instead of an installer."
  ],
  [
    "curl: \\(22\\)|HTTP[^\\n]*(401|403|404|429|500|502|503)",
    "The download server returned an HTTP error."
  ],
  [
    "command not found|no such file or directory",
    "A required installation command or file was not found."
  ],
  [
    "^(E:|ERROR:|npm (error|ERR!)|dpkg: error|apk: error)",
    "The package manager reported an installation error."
  ]
];
const rules = RULES.map(([pattern, summary]) => [new RegExp(pattern, 'i'), summary] as const);
const safe = new Set([...RULES.map(([, summary]) => summary), EMPTY_DIAGNOSTICS, OMITTED_DIAGNOSTICS]);

/** Limits work, drops oversized lines whole and exports no raw values, even unknown secret formats. */
export function sanitizeProvisionLog(raw: string | undefined): string {
  const tail = raw?.slice(-32768);
  if (!tail?.trim()) return EMPTY_DIAGNOSTICS;
  const summaries = new Set<string>();
  for (const line of tail.split(/\r\n?|\n/)) {
    if (line.length > 2048) continue;
    const text = line.replace(/\u001b\[[0-?]*[ -/]*[@-~]/g, '').trim();
    if (safe.has(text)) summaries.add(text);
    else {
      const match = rules.find(([pattern]) => pattern.test(text));
      if (match !== undefined) summaries.add(match[1]);
    }
  }
  return Array.from(summaries).join('\n').slice(0, MAX_DIAGNOSTIC_LOG_CHARS) || OMITTED_DIAGNOSTICS;
}

export function safeProvisionStage(stage: unknown): string | undefined {
  return typeof stage === 'string' && (PROVISIONING_STAGES as readonly string[]).includes(stage) ? stage : undefined;
}
export function safeExitCode(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isInteger(value) && value >= 0 && value <= 255 ? value : undefined;
}

/** Validate optional wire fields before the UI can retain them; old native replies remain supported. */
export function readProvisionDiagnostics(value: Readonly<{outcome?: unknown; stage?: unknown; exitCode?: unknown; commandExitCode?: unknown; log?: unknown}>): ProvisioningDiagnostics | undefined {
  if (value.outcome === undefined && value.stage === undefined && value.exitCode === undefined && value.commandExitCode === undefined && value.log === undefined) return undefined;
  if (typeof value.outcome !== 'string' || !(PROVISIONING_OUTCOMES as readonly string[]).includes(value.outcome) ||
      (value.log !== undefined && (typeof value.log !== 'string' || value.log.length > MAX_DIAGNOSTIC_LOG_CHARS)) ||
      (value.stage !== undefined && safeProvisionStage(value.stage) === undefined) ||
      (value.exitCode !== undefined && safeExitCode(value.exitCode) === undefined) ||
      (value.commandExitCode !== undefined && safeExitCode(value.commandExitCode) === undefined)) return undefined;
  return {
    outcome: value.outcome as ProvisioningDiagnostics['outcome'],
    ...(value.stage === undefined ? {} : {stage: safeProvisionStage(value.stage)}),
    ...(value.exitCode === undefined ? {} : {exitCode: safeExitCode(value.exitCode)}),
    ...(value.commandExitCode === undefined ? {} : {commandExitCode: safeExitCode(value.commandExitCode)}),
    log: sanitizeProvisionLog(value.log as string | undefined),
  };
}
