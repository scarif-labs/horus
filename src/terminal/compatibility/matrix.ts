/**
 * Bounded Phase 6 compatibility-matrix contract.
 *
 * This records results; it does not install CLIs, authenticate accounts, or
 * imply that a host-side fixture is an Alpine/device pass.
 */
export const CLI_COMPATIBILITY_SCHEMA = 'alpine-cli-compatibility/v1' as const;

export const CLI_IDS = [
  'claude-code',
  'codex-cli',
  'opencode',
  'gemini-cli',
] as const;

export type CliId = (typeof CLI_IDS)[number];
export type CompatibilityStatus = 'pass' | 'fail' | 'not-tested' | 'unsupported';
export type CompatibilityStageId = 'install' | 'version-doctor' | 'execution' | 'auth-resume';

export type CompatibilityStage = Readonly<{
  status: CompatibilityStatus;
  version?: string;
  detail?: string;
}>;

export type CliSandboxResult = Readonly<{
  status: CompatibilityStatus;
  detail?: string;
}>;

export type CliCompatibilityRecord = Readonly<{
  cli: CliId;
  stages: Readonly<Record<CompatibilityStageId, CompatibilityStage>>;
  sandbox?: CliSandboxResult;
  limitations: readonly string[];
}>;

export type CliCompatibilityMatrix = Readonly<{
  schema: typeof CLI_COMPATIBILITY_SCHEMA;
  records: readonly CliCompatibilityRecord[];
}>;

export type CompatibilityValidationError = Readonly<{
  code: 'invalid-shape' | 'unknown-cli' | 'duplicate-cli' | 'invalid-status' | 'invalid-stage' | 'unsafe-text';
  detail: string;
}>;

export type CompatibilityValidationResult =
  | Readonly<{valid: true; matrix: CliCompatibilityMatrix}>
  | Readonly<{valid: false; errors: readonly CompatibilityValidationError[]}>;

const STAGE_IDS: readonly CompatibilityStageId[] = [
  'install',
  'version-doctor',
  'execution',
  'auth-resume',
];
const STATUSES: readonly CompatibilityStatus[] = ['pass', 'fail', 'not-tested', 'unsupported'];
const MAX_TEXT_LENGTH = 256;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isCliId(value: unknown): value is CliId {
  return typeof value === 'string' && CLI_IDS.includes(value as CliId);
}

function isStageId(value: unknown): value is CompatibilityStageId {
  return typeof value === 'string' && STAGE_IDS.includes(value as CompatibilityStageId);
}

function isStatus(value: unknown): value is CompatibilityStatus {
  return typeof value === 'string' && STATUSES.includes(value as CompatibilityStatus);
}

function isSafeText(value: unknown): value is string {
  return typeof value === 'string' &&
    value.length <= MAX_TEXT_LENGTH &&
    !/[\u0000-\u001f\u007f]/.test(value) &&
    !/(?:bearer\s+|sk-[a-z0-9]|api[_-]?key\s*[:=]|authorization\s*[:=])/i.test(value);
}

function notTestedStage(): CompatibilityStage {
  return {status: 'not-tested'};
}

/** Build an explicit matrix with no implied CLI passes. */
export function createEmptyCompatibilityMatrix(): CliCompatibilityMatrix {
  return {
    schema: CLI_COMPATIBILITY_SCHEMA,
    records: CLI_IDS.map(cli => ({
      cli,
      stages: {
        install: notTestedStage(),
        'version-doctor': notTestedStage(),
        execution: notTestedStage(),
        'auth-resume': notTestedStage(),
      },
      sandbox: {status: 'not-tested', detail: 'not-tested'},
      limitations: [],
    })),
  };
}

/** Validate a persisted or imported matrix without accepting unknown stages. */
export function validateCompatibilityMatrix(value: unknown): CompatibilityValidationResult {
  const errors: CompatibilityValidationError[] = [];
  if (!isRecord(value) || value.schema !== CLI_COMPATIBILITY_SCHEMA || !Array.isArray(value.records)) {
    return {valid: false, errors: [{code: 'invalid-shape', detail: 'matrix schema or records are invalid'}]};
  }
  const seen = new Set<string>();
  for (const record of value.records) {
    if (!isRecord(record) || !isCliId(record.cli) || !isRecord(record.stages) || !Array.isArray(record.limitations)) {
      errors.push({code: 'invalid-shape', detail: 'CLI record shape is invalid'});
      continue;
    }
    const stages = record.stages;
    if (seen.has(record.cli)) errors.push({code: 'duplicate-cli', detail: `duplicate CLI ${record.cli}`});
    seen.add(record.cli);
    for (const [stageId, stage] of Object.entries(stages)) {
      if (!isStageId(stageId)) {
        errors.push({code: 'invalid-stage', detail: `unknown stage ${stageId}`});
        continue;
      }
      if (!isRecord(stage) || !isStatus(stage.status)) {
        errors.push({code: 'invalid-status', detail: `invalid status for ${record.cli}.${stageId}`});
        continue;
      }
      if (stage.version !== undefined && !isSafeText(stage.version)) {
        errors.push({code: 'unsafe-text', detail: `unsafe version for ${record.cli}.${stageId}`});
      }
      if (stage.detail !== undefined && !isSafeText(stage.detail)) {
        errors.push({code: 'unsafe-text', detail: `unsafe detail for ${record.cli}.${stageId}`});
      }
    }
    for (const limitation of record.limitations) {
      if (!isSafeText(limitation)) errors.push({code: 'unsafe-text', detail: `unsafe limitation for ${record.cli}`});
    }
    if (record.sandbox !== undefined) {
      if (!isRecord(record.sandbox) || !isStatus(record.sandbox.status)) {
        errors.push({code: 'invalid-status', detail: `invalid sandbox status for ${record.cli}`});
      } else if (record.sandbox.detail !== undefined && !isSafeText(record.sandbox.detail)) {
        errors.push({code: 'unsafe-text', detail: `unsafe sandbox detail for ${record.cli}`});
      }
    }
    if (Object.keys(stages).length !== STAGE_IDS.length || STAGE_IDS.some(stage => !(stage in stages))) {
      errors.push({code: 'invalid-stage', detail: `incomplete stages for ${record.cli}`});
    }
  }
  if (seen.size !== CLI_IDS.length || CLI_IDS.some(cli => !seen.has(cli))) {
    errors.push({code: 'unknown-cli', detail: 'matrix must contain exactly one record for every supported CLI'});
  }
  if (errors.length > 0) return {valid: false, errors};
  return {valid: true, matrix: value as unknown as CliCompatibilityMatrix};
}

export function summarizeCompatibilityMatrix(matrix: CliCompatibilityMatrix): Readonly<{
  passedStages: number;
  failedStages: number;
  untestedStages: number;
  unsupportedStages: number;
}> {
  const counts = {passedStages: 0, failedStages: 0, untestedStages: 0, unsupportedStages: 0};
  for (const record of matrix.records) {
    for (const stageId of STAGE_IDS) {
      const status = record.stages[stageId].status;
      if (status === 'pass') counts.passedStages += 1;
      if (status === 'fail') counts.failedStages += 1;
      if (status === 'not-tested') counts.untestedStages += 1;
      if (status === 'unsupported') counts.unsupportedStages += 1;
    }
  }
  return counts;
}
