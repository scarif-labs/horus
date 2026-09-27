/**
 * Alpine toolchain preflight.
 *
 * The probe is intentionally a fixed argv. It has no user-controlled values,
 * does not use eval or a nested shell string, and always emits a small,
 * line-oriented record even when individual tools are absent.
 */

export const TOOLCHAIN_PROBE_SCHEMA = 'alpine-toolchain-preflight/v1' as const;
export const TOOLCHAIN_PROBE_BEGIN = 'ALPINE_TOOLCHAIN_PROBE_V1_BEGIN' as const;
export const TOOLCHAIN_PROBE_END = 'ALPINE_TOOLCHAIN_PROBE_V1_END' as const;
export const TOOLCHAIN_INSTALL_BEGIN = 'ALPINE_TOOLCHAIN_INSTALL_V1_BEGIN' as const;
export const TOOLCHAIN_INSTALL_END = 'ALPINE_TOOLCHAIN_INSTALL_V1_END' as const;
export const TOOLCHAIN_INSTALL_RESULT_PREFIX = 'ALPINE_TOOLCHAIN_INSTALL_V1_RESULT' as const;
export const TOOLCHAIN_CHECK_PREFIX = 'ALPINE_TOOLCHAIN_CHECK' as const;
export const TOOLCHAIN_FIELD_PREFIX = 'ALPINE_TOOLCHAIN_FIELD' as const;

export const TOOLCHAIN_PROBE_MAX_OUTPUT_BYTES = 32_768;
export const TOOLCHAIN_PROBE_MAX_LINES = 128;
export const TOOLCHAIN_PROBE_MAX_FIELD_LENGTH = 128;
export const TOOLCHAIN_PROBE_TIMEOUT_MS = 30_000;
export const TOOLCHAIN_INSTALL_MAX_OUTPUT_BYTES = 64 * 1024;
export const TOOLCHAIN_INSTALL_TIMEOUT_MS = 120_000;

export const TOOLCHAIN_CHECK_IDS = [
  'apk',
  'shell',
  'transfer',
  'certificates',
  'git',
  'ssh',
  'node-npm',
  'python-pip',
  'ripgrep',
  'native-libraries',
  'linux-arm64',
] as const;

export type ToolchainCheckId = (typeof TOOLCHAIN_CHECK_IDS)[number];
export type ToolchainCheckStatus = 'pass' | 'fail' | 'missing';

export type ToolchainFieldName =
  | 'path'
  | 'version'
  | 'shell'
  | 'bash'
  | 'client'
  | 'npm'
  | 'certificate'
  | 'platform'
  | 'architecture'
  | 'nodeIdentity';

export type ToolchainCheckResult = Readonly<{
  id: ToolchainCheckId;
  status: ToolchainCheckStatus;
  fields: Readonly<Partial<Record<ToolchainFieldName, string>>>;
}>;

export type ToolchainProbeReport = Readonly<{
  schema: typeof TOOLCHAIN_PROBE_SCHEMA;
  status: ToolchainCheckStatus;
  exitCode: number;
  checks: readonly ToolchainCheckResult[];
}>;

export type ToolchainProbeParseError = Readonly<{
  code:
    | 'not_string'
    | 'output_too_large'
    | 'too_many_lines'
    | 'missing_begin'
    | 'missing_end'
    | 'unexpected_line'
    | 'duplicate_check'
    | 'duplicate_field'
    | 'unknown_check'
    | 'unknown_field'
    | 'invalid_status'
    | 'invalid_field'
    | 'missing_check'
    | 'invalid_exit_code';
  detail: string;
}>;

export type ToolchainProbeParseResult =
  | Readonly<{valid: true; report: ToolchainProbeReport}>
  | Readonly<{valid: false; errors: readonly ToolchainProbeParseError[]}>;

export type ToolchainProbeCommand = Readonly<{
  executable: '/bin/sh';
  args: readonly ['-c', string];
  timeoutMs: typeof TOOLCHAIN_PROBE_TIMEOUT_MS;
  maxOutputBytes: typeof TOOLCHAIN_PROBE_MAX_OUTPUT_BYTES;
}>;

export type ToolchainInstallCommand = Readonly<{
  executable: '/bin/sh';
  args: readonly ['-c', string];
  timeoutMs: typeof TOOLCHAIN_INSTALL_TIMEOUT_MS;
  maxOutputBytes: typeof TOOLCHAIN_INSTALL_MAX_OUTPUT_BYTES;
}>;

export type ToolchainInstallStatus = 'pass' | 'fail';
export type ToolchainInstallParseResult =
  | Readonly<{valid: true; status: ToolchainInstallStatus}>
  | Readonly<{valid: false; error: 'not_string' | 'missing_begin' | 'missing_end' | 'invalid_result' | 'unexpected_line'}>;

const CHECK_SET = new Set<string>(TOOLCHAIN_CHECK_IDS);
const FIELD_SET = new Set<string>([
  'path',
  'version',
  'shell',
  'bash',
  'client',
  'npm',
  'certificate',
  'platform',
  'architecture',
  'nodeIdentity',
]);
const STATUS_SET = new Set<ToolchainCheckStatus>(['pass', 'fail', 'missing']);

/**
 * This script is static by construction. Keep all command names and paths
 * literal: callers must not append arguments or interpolate environment data.
 */
const TOOLCHAIN_PROBE_SCRIPT = String.raw`set +e

emit() {
  printf '%s\n' "$1"
}

emit 'ALPINE_TOOLCHAIN_PROBE_V1_BEGIN'

value() {
  printf '%s' "$1" | tr '\n\r|' '   ' | head -c 128
}

check_command() {
  name=$1
  command_name=$2
  shift 2
  if ! command -v "$command_name" >/dev/null 2>&1; then
    emit "ALPINE_TOOLCHAIN_CHECK|$name|missing"
    return
  fi
  path=$(command -v "$command_name" 2>/dev/null)
  version=$("$@" 2>/dev/null | head -c 96 | tr '\n\r|' '   ')
  command_status=$?
  if [ "$command_status" -ne 0 ] || [ -z "$version" ]; then
    emit "ALPINE_TOOLCHAIN_CHECK|$name|fail"
    return
  fi
  emit "ALPINE_TOOLCHAIN_CHECK|$name|pass"
  emit "ALPINE_TOOLCHAIN_FIELD|$name|path|$(value "$path")"
  emit "ALPINE_TOOLCHAIN_FIELD|$name|version|$(value "$version")"
}

check_command apk apk apk --version

sh_path=$(command -v sh 2>/dev/null)
bash_path=$(command -v bash 2>/dev/null)
if [ -z "$sh_path" ] || [ -z "$bash_path" ]; then
  emit 'ALPINE_TOOLCHAIN_CHECK|shell|missing'
else
  emit 'ALPINE_TOOLCHAIN_CHECK|shell|pass'
  emit "ALPINE_TOOLCHAIN_FIELD|shell|shell|$(value "$sh_path")"
  emit "ALPINE_TOOLCHAIN_FIELD|shell|bash|$(value "$bash_path")"
fi

if command -v curl >/dev/null 2>&1; then
  check_command transfer curl curl --version
elif command -v wget >/dev/null 2>&1; then
  check_command transfer wget wget --version
else
  emit 'ALPINE_TOOLCHAIN_CHECK|transfer|missing'
fi

certificate_path=
if [ -s /etc/ssl/certs/ca-certificates.crt ]; then
  certificate_path=/etc/ssl/certs/ca-certificates.crt
elif [ -s /etc/ssl/cert.pem ]; then
  certificate_path=/etc/ssl/cert.pem
fi
if [ -n "$certificate_path" ]; then
  emit 'ALPINE_TOOLCHAIN_CHECK|certificates|pass'
  emit "ALPINE_TOOLCHAIN_FIELD|certificates|certificate|$certificate_path"
else
  emit 'ALPINE_TOOLCHAIN_CHECK|certificates|missing'
fi

check_command git git git --version

# OpenSSH prints its version banner on stderr, so capture it explicitly
# instead of going through check_command's stdout-only version pipe.
ssh_path=$(command -v ssh 2>/dev/null)
if [ -z "$ssh_path" ]; then
  emit 'ALPINE_TOOLCHAIN_CHECK|ssh|missing'
else
  ssh_version=$(ssh -V 2>&1 | head -c 96 | tr '\n\r|' '   ')
  ssh_status=$?
  if [ "$ssh_status" -ne 0 ] || [ -z "$ssh_version" ]; then
    emit 'ALPINE_TOOLCHAIN_CHECK|ssh|fail'
  else
    emit 'ALPINE_TOOLCHAIN_CHECK|ssh|pass'
    emit "ALPINE_TOOLCHAIN_FIELD|ssh|path|$(value "$ssh_path")"
    emit "ALPINE_TOOLCHAIN_FIELD|ssh|version|$(value "$ssh_version")"
  fi
fi

node_path=$(command -v node 2>/dev/null)
npm_path=$(command -v npm 2>/dev/null)
if [ -z "$node_path" ] || [ -z "$npm_path" ]; then
  emit 'ALPINE_TOOLCHAIN_CHECK|node-npm|missing'
else
  node_version=$(node --version 2>/dev/null | head -c 96 | tr '\n\r|' '   ')
  node_status=$?
  npm_version=$(npm --version 2>/dev/null | head -c 96 | tr '\n\r|' '   ')
  npm_status=$?
  node_identity=$(node -p 'process.platform + "/" + process.arch' 2>/dev/null | head -c 64 | tr '\n\r|' '   ')
  identity_status=$?
  if [ "$node_status" -ne 0 ] || [ "$npm_status" -ne 0 ] || [ "$identity_status" -ne 0 ] || [ -z "$node_version" ] || [ -z "$npm_version" ] || [ -z "$node_identity" ]; then
    emit 'ALPINE_TOOLCHAIN_CHECK|node-npm|fail'
  else
    emit 'ALPINE_TOOLCHAIN_CHECK|node-npm|pass'
    emit "ALPINE_TOOLCHAIN_FIELD|node-npm|path|$(value "$node_path")"
    emit "ALPINE_TOOLCHAIN_FIELD|node-npm|version|$(value "$node_version")"
    emit "ALPINE_TOOLCHAIN_FIELD|node-npm|nodeIdentity|$(value "$node_identity")"
    emit "ALPINE_TOOLCHAIN_FIELD|node-npm|npm|$(value "$npm_path")"
  fi
fi

python_path=$(command -v python3 2>/dev/null)
if [ -z "$python_path" ] || ! command -v pip3 >/dev/null 2>&1 && ! python3 -m pip --version >/dev/null 2>&1; then
  emit 'ALPINE_TOOLCHAIN_CHECK|python-pip|missing'
else
  python_version=$(python3 --version 2>&1 | head -c 96 | tr '\n\r|' '   ')
  python_status=$?
  pip_version=$(python3 -m pip --version 2>/dev/null | head -c 96 | tr '\n\r|' '   ')
  pip_status=$?
  if [ "$python_status" -ne 0 ] || [ "$pip_status" -ne 0 ] || [ -z "$python_version" ] || [ -z "$pip_version" ]; then
    emit 'ALPINE_TOOLCHAIN_CHECK|python-pip|fail'
  else
    emit 'ALPINE_TOOLCHAIN_CHECK|python-pip|pass'
    emit "ALPINE_TOOLCHAIN_FIELD|python-pip|path|$(value "$python_path")"
    emit "ALPINE_TOOLCHAIN_FIELD|python-pip|version|$(value "$python_version")"
    emit "ALPINE_TOOLCHAIN_FIELD|python-pip|client|$(value "$pip_version")"
  fi
fi

check_command ripgrep rg rg --version

if command -v apk >/dev/null 2>&1 && apk info -e libgcc >/dev/null 2>&1 && apk info -e libstdc++ >/dev/null 2>&1; then
  emit 'ALPINE_TOOLCHAIN_CHECK|native-libraries|pass'
else
  emit 'ALPINE_TOOLCHAIN_CHECK|native-libraries|missing'
fi

kernel_name=$(uname -s 2>/dev/null)
machine=$(uname -m 2>/dev/null)
if [ "$kernel_name" = Linux ] && { [ "$machine" = aarch64 ] || [ "$machine" = arm64 ]; }; then
  emit 'ALPINE_TOOLCHAIN_CHECK|linux-arm64|pass'
else
  if [ -z "$kernel_name" ] || [ -z "$machine" ]; then
    emit 'ALPINE_TOOLCHAIN_CHECK|linux-arm64|missing'
  else
    emit 'ALPINE_TOOLCHAIN_CHECK|linux-arm64|fail'
  fi
fi
if [ -n "$kernel_name" ]; then emit "ALPINE_TOOLCHAIN_FIELD|linux-arm64|platform|$(value "$kernel_name")"; fi
if [ -n "$machine" ]; then emit "ALPINE_TOOLCHAIN_FIELD|linux-arm64|architecture|$(value "$machine")"; fi

emit 'ALPINE_TOOLCHAIN_PROBE_V1_END'`;

export const ALPINE_TOOLCHAIN_PROBE_SCRIPT = TOOLCHAIN_PROBE_SCRIPT;

/**
 * Installs only the documented base profile. Package names and arguments are
 * static; the user cannot inject shell text through this action. The shell is
 * deliberately kept alive after apk returns so the terminal remains usable.
 */
const TOOLCHAIN_INSTALL_SCRIPT = String.raw`set +e
printf '%s\n' '${TOOLCHAIN_INSTALL_BEGIN}'
if command -v apk >/dev/null 2>&1; then
  apk add --no-cache --no-progress bash curl ca-certificates git openssh-client-default nodejs npm python3 py3-pip ripgrep libgcc libstdc++
  install_status=$?
else
  install_status=127
fi
if [ "$install_status" -eq 0 ]; then
  printf '%s|pass\n' '${TOOLCHAIN_INSTALL_RESULT_PREFIX}'
else
  printf '%s|fail\n' '${TOOLCHAIN_INSTALL_RESULT_PREFIX}'
fi
printf '%s\n' '${TOOLCHAIN_INSTALL_END}'`;

export const ALPINE_TOOLCHAIN_INSTALL_SCRIPT = TOOLCHAIN_INSTALL_SCRIPT;

/** Return the only supported invocation for the fixed base-profile install. */
export function buildToolchainInstallCommand(): ToolchainInstallCommand {
  return {
    executable: '/bin/sh',
    args: ['-c', TOOLCHAIN_INSTALL_SCRIPT],
    timeoutMs: TOOLCHAIN_INSTALL_TIMEOUT_MS,
    maxOutputBytes: TOOLCHAIN_INSTALL_MAX_OUTPUT_BYTES,
  };
}

/** Extract one exact install result from a noisy terminal transcript. */
export function extractToolchainInstallBlock(output: string): string | undefined {
  const lines = output.split('\n').map(line => line.endsWith('\r') ? line.slice(0, -1) : line);
  const begin = lines.findIndex(line => line.trim() === TOOLCHAIN_INSTALL_BEGIN);
  if (begin < 0) return undefined;
  const end = lines.findIndex((line, index) => index > begin && line.trim() === TOOLCHAIN_INSTALL_END);
  if (end < 0) return undefined;
  const resultLines = lines
    .slice(begin + 1, end)
    .map(line => line.trim())
    .filter(line => line.startsWith(`${TOOLCHAIN_INSTALL_RESULT_PREFIX}|`));
  return [TOOLCHAIN_INSTALL_BEGIN, ...resultLines, TOOLCHAIN_INSTALL_END].join('\n');
}

/** Parse the exact install result without accepting marker prefixes. */
export function parseToolchainInstall(output: unknown): ToolchainInstallParseResult {
  if (typeof output !== 'string') return {valid: false, error: 'not_string'};
  const lines = output.endsWith('\n') ? output.slice(0, -1).split('\n') : output.split('\n');
  if (lines[0] !== TOOLCHAIN_INSTALL_BEGIN) return {valid: false, error: 'missing_begin'};
  if (lines[lines.length - 1] !== TOOLCHAIN_INSTALL_END) return {valid: false, error: 'missing_end'};
  if (lines.length !== 3) return {valid: false, error: 'unexpected_line'};
  const result = lines[1];
  if (result === `${TOOLCHAIN_INSTALL_RESULT_PREFIX}|pass`) return {valid: true, status: 'pass'};
  if (result === `${TOOLCHAIN_INSTALL_RESULT_PREFIX}|fail`) return {valid: true, status: 'fail'};
  return {valid: false, error: 'invalid_result'};
}

/** Return the only supported invocation for the deterministic probe. */
export function buildToolchainProbeCommand(): ToolchainProbeCommand {
  return {
    executable: '/bin/sh',
    args: ['-c', TOOLCHAIN_PROBE_SCRIPT],
    timeoutMs: TOOLCHAIN_PROBE_TIMEOUT_MS,
    maxOutputBytes: TOOLCHAIN_PROBE_MAX_OUTPUT_BYTES,
  };
}

function isCheckId(value: string): value is ToolchainCheckId {
  return CHECK_SET.has(value);
}

function isStatus(value: string): value is ToolchainCheckStatus {
  return STATUS_SET.has(value as ToolchainCheckStatus);
}

function isFieldName(value: string): value is ToolchainFieldName {
  return FIELD_SET.has(value);
}

function isBoundedField(value: string): boolean {
  return (
    value.length > 0 &&
    value.length <= TOOLCHAIN_PROBE_MAX_FIELD_LENGTH &&
    !/[\u0000-\u001f\u007f|\r\n]/.test(value)
  );
}

function error(
  code: ToolchainProbeParseError['code'],
  detail: string,
): ToolchainProbeParseError {
  return {code, detail};
}

function overallStatus(
  checks: readonly ToolchainCheckResult[],
  exitCode: number,
): ToolchainCheckStatus {
  if (exitCode !== 0 || checks.some(check => check.status === 'fail')) return 'fail';
  if (checks.some(check => check.status === 'missing')) return 'missing';
  return 'pass';
}

/**
 * Parse the exact probe protocol. Unknown lines, duplicate records, marker
 * prefixes, extra delimiters, and unbounded fields are rejected.
 */
export function parseToolchainProbe(
  output: unknown,
  exitCode = 0,
): ToolchainProbeParseResult {
  const errors: ToolchainProbeParseError[] = [];
  if (typeof output !== 'string') {
    return {valid: false, errors: [error('not_string', 'probe output must be a string')]};
  }
  if (!Number.isSafeInteger(exitCode) || exitCode < 0 || exitCode > 255) {
    return {
      valid: false,
      errors: [error('invalid_exit_code', 'exit code must be an integer from 0 through 255')],
    };
  }
  if (new TextEncoder().encode(output).byteLength > TOOLCHAIN_PROBE_MAX_OUTPUT_BYTES) {
    return {valid: false, errors: [error('output_too_large', 'probe output exceeds the byte limit')]};
  }

  const lines = output.endsWith('\n') ? output.slice(0, -1).split('\n') : output.split('\n');
  if (lines.length > TOOLCHAIN_PROBE_MAX_LINES) {
    return {valid: false, errors: [error('too_many_lines', 'probe output exceeds the line limit')]};
  }
  if (lines[0] !== TOOLCHAIN_PROBE_BEGIN) {
    errors.push(error('missing_begin', 'probe begin marker is not exact or is missing'));
  }
  if (lines[lines.length - 1] !== TOOLCHAIN_PROBE_END) {
    errors.push(error('missing_end', 'probe end marker is not exact or is missing'));
  }
  if (errors.length > 0) return {valid: false, errors};

  const checkMap = new Map<ToolchainCheckId, ToolchainCheckResult>();
  const fields = new Map<ToolchainCheckId, Map<ToolchainFieldName, string>>();
  for (let index = 1; index < lines.length - 1; index += 1) {
    const line = lines[index];
    if (line.startsWith(`${TOOLCHAIN_CHECK_PREFIX}|`)) {
      const parts = line.split('|');
      if (parts.length !== 3 || parts[0] !== TOOLCHAIN_CHECK_PREFIX) {
        errors.push(error('unexpected_line', `check marker at line ${index + 1} is not exact`));
        continue;
      }
      const [, rawId, rawStatus] = parts;
      if (!isCheckId(rawId)) {
        errors.push(error('unknown_check', `unknown check ${rawId}`));
        continue;
      }
      if (!isStatus(rawStatus)) {
        errors.push(error('invalid_status', `invalid status for ${rawId}`));
        continue;
      }
      if (checkMap.has(rawId)) {
        errors.push(error('duplicate_check', `duplicate check ${rawId}`));
        continue;
      }
      checkMap.set(rawId, {id: rawId, status: rawStatus, fields: {}});
      continue;
    }
    if (line.startsWith(`${TOOLCHAIN_FIELD_PREFIX}|`)) {
      const parts = line.split('|');
      if (parts.length !== 4 || parts[0] !== TOOLCHAIN_FIELD_PREFIX) {
        errors.push(error('unexpected_line', `field marker at line ${index + 1} is not exact`));
        continue;
      }
      const [, rawId, rawField, rawValue] = parts;
      if (!isCheckId(rawId)) {
        errors.push(error('unknown_check', `unknown field check ${rawId}`));
        continue;
      }
      if (!isFieldName(rawField)) {
        errors.push(error('unknown_field', `unknown field ${rawField}`));
        continue;
      }
      if (!isBoundedField(rawValue)) {
        errors.push(error('invalid_field', `field ${rawId}.${rawField} is empty, unsafe, or too long`));
        continue;
      }
      const checkFields = fields.get(rawId) ?? new Map<ToolchainFieldName, string>();
      if (checkFields.has(rawField)) {
        errors.push(error('duplicate_field', `duplicate field ${rawId}.${rawField}`));
        continue;
      }
      checkFields.set(rawField, rawValue);
      fields.set(rawId, checkFields);
      continue;
    }
    errors.push(error('unexpected_line', `unexpected line ${index + 1}`));
  }

  for (const id of TOOLCHAIN_CHECK_IDS) {
    if (!checkMap.has(id)) errors.push(error('missing_check', `missing check ${id}`));
  }
  if (errors.length > 0) return {valid: false, errors};

  const checks = TOOLCHAIN_CHECK_IDS.map(id => {
    const check = checkMap.get(id)!;
    const checkFields = fields.get(id);
    return {
      ...check,
      fields: checkFields ? Object.fromEntries(checkFields) : {},
    } satisfies ToolchainCheckResult;
  });
  const report: ToolchainProbeReport = {
    schema: TOOLCHAIN_PROBE_SCHEMA,
    status: overallStatus(checks, exitCode),
    exitCode,
    checks,
  };
  return {valid: true, report};
}

/** Extract one exact marker block from a noisy terminal transcript. */
export function extractToolchainProbeBlock(output: string): string | undefined {
  const lines = output.split('\n').map(line => line.endsWith('\r') ? line.slice(0, -1) : line);
  const begin = lines.findIndex(line => line.trim() === TOOLCHAIN_PROBE_BEGIN);
  if (begin < 0) return undefined;
  const end = lines.findIndex((line, index) => index > begin && line.trim() === TOOLCHAIN_PROBE_END);
  if (end < 0) return undefined;
  const records = lines
    .slice(begin + 1, end)
    .map(line => line.trim())
    .filter(line =>
      line.startsWith(`${TOOLCHAIN_CHECK_PREFIX}|`) ||
      line.startsWith(`${TOOLCHAIN_FIELD_PREFIX}|`),
    );
  return [TOOLCHAIN_PROBE_BEGIN, ...records, TOOLCHAIN_PROBE_END].join('\n');
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/** Strictly validate the typed report after parsing or before persistence. */
export function isToolchainProbeReport(value: unknown): value is ToolchainProbeReport {
  if (!isRecord(value) || value.schema !== TOOLCHAIN_PROBE_SCHEMA) return false;
  if (!isStatus(String(value.status))) return false;
  if (
    typeof value.exitCode !== 'number' ||
    !Number.isSafeInteger(value.exitCode) ||
    value.exitCode < 0 ||
    value.exitCode > 255
  ) {
    return false;
  }
  if (!Array.isArray(value.checks) || value.checks.length !== TOOLCHAIN_CHECK_IDS.length) {
    return false;
  }
  const seen = new Set<string>();
  for (const item of value.checks) {
    if (!isRecord(item) || !isCheckId(String(item.id)) || seen.has(String(item.id))) return false;
    seen.add(String(item.id));
    if (!isStatus(String(item.status)) || !isRecord(item.fields)) return false;
    for (const [field, fieldValue] of Object.entries(item.fields)) {
      if (!isFieldName(field) || typeof fieldValue !== 'string' || !isBoundedField(fieldValue)) {
        return false;
      }
    }
  }
  return TOOLCHAIN_CHECK_IDS.every(id => seen.has(id));
}

export function getToolchainCheck(
  report: ToolchainProbeReport,
  id: ToolchainCheckId,
): ToolchainCheckResult {
  return report.checks.find(check => check.id === id)!;
}
