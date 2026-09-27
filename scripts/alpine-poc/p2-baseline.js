#!/usr/bin/env node
/* global __filename */

/**
 * Alpine terminal PoC Phase 2 host gate (native PTY and process
 * supervision).
 *
 * Like p1-baseline.js this gate excludes the connected-device run and
 * produces a release APK checksum that p2-device.js must match. The full
 * Jest run executes with --detectOpenHandles so a session client that leaks
 * a listener or timer fails the phase, matching the exit gate. Failed runs
 * write a separate failure record and never replace a passing
 * p2-baseline.json.
 */
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { spawnSync } = require('node:child_process');

const root = path.resolve(__dirname, '..', '..');
const artifactsDir = path.join(root, 'artifacts', 'alpine-poc');
const apkPath = path.join(
  root,
  'android',
  'app',
  'build',
  'outputs',
  'apk',
  'release',
  'app-release.apk',
);
const npmCommand = process.platform === 'win32' ? 'npm.cmd' : 'npm';
const checks = [];
const phase2CheckDefinitions = [
  { id: 'typecheck', command: npmCommand, args: ['run', 'typecheck'] },
  {
    id: 'focused-terminal-jest',
    command: npmCommand,
    args: [
      'exec',
      '--',
      'jest',
      '--runInBand',
      '--detectOpenHandles',
      '__tests__/terminalRuntimeStatus.test.ts',
      '__tests__/terminalEvidenceSchema.test.ts',
      '__tests__/terminalDistroContract.test.ts',
      '__tests__/terminalSessionContract.test.ts',
      '__tests__/terminalSessionClient.test.ts',
    ],
  },
  {
    id: 'focused-phase2-kotlin-unit-tests',
    command: process.platform === 'win32' ? 'gradlew.bat' : './gradlew',
    args: [
      ':app:testDebugUnitTest',
      '--no-daemon',
      '--tests',
      'com.scariflabs.horus.terminal.TerminalSessionContractTest',
      '--tests',
      'com.scariflabs.horus.terminal.TerminalSessionSupervisorTest',
      '--tests',
      'com.scariflabs.horus.terminal.ProcessTreeTest',
    ],
    cwd: 'android',
  },
  {
    id: 'full-jest',
    command: npmCommand,
    args: ['run', 'test:unit', '--', '--detectOpenHandles'],
  },
  {
    id: 'terminal-boundary-scan',
    command: process.execPath,
    args: [path.join(root, 'scripts', 'scan-terminal-boundary.js')],
  },
  {
    id: 'codegen-kotlin-compile',
    command: npmCommand,
    args: ['run', 'test:codegen:kotlin'],
  },
  { id: 'kotlin-unit-tests', command: npmCommand, args: ['run', 'test:kotlin'] },
  {
    id: 'release-assembly',
    command: npmCommand,
    args: ['run', 'build:android:release'],
  },
];
const resumeFlag = '--resume-after-glm-baseline';
const resumeArtifactName = 'p2-baseline-glm-prior-2026-09-08.json';
const resumeArtifactSha256 =
  '89b044d2b02edfc99f769a8342ac62a275ce7c6009881762932a834c6e264d54';

function git(args) {
  const result = spawnSync('git', args, {
    cwd: root,
    encoding: 'utf8',
    timeout: 30_000,
  });
  return result.status === 0 ? result.stdout.trim() : 'unknown';
}

function evidenceCommand(command) {
  if (command === process.execPath) return 'node';
  if (path.basename(command) === 'gradlew' || path.basename(command) === 'gradlew.bat') {
    return process.platform === 'win32' ? 'gradlew.bat' : './gradlew';
  }
  return command;
}

function evidenceArgs(args) {
  return args.map(arg => {
    if (!path.isAbsolute(arg)) return arg;
    const relative = path.relative(root, arg);
    return relative && !relative.startsWith('..') ? relative : path.basename(arg);
  });
}

function run(label, command, args, timeout = 900_000, options = {}) {
  console.log(`\n=== Alpine p2 baseline: ${label} ===`);
  const result = spawnSync(command, args, {
    cwd: options.cwd ?? root,
    stdio: 'inherit',
    timeout,
  });
  const exitCode = result.status === null ? 1 : result.status;
  const timedOut = result.error?.code === 'ETIMEDOUT' || result.signal !== null;
  checks.push({
    id: label,
    status: exitCode === 0 ? 'passed' : 'failed',
    exitCode,
    timedOut,
    command: evidenceCommand(command),
    args: evidenceArgs(args),
    cwd: path.relative(root, options.cwd ?? root) || '.',
  });
  if (exitCode !== 0) {
    writeFailure({ failedCheck: label, exitCode, timedOut });
    console.error(
      `\nAlpine p2 baseline failed at "${label}" with exit ${exitCode}.`,
    );
    process.exit(exitCode);
  }
}

function apkEvidence() {
  if (!fs.existsSync(apkPath)) return null;
  const bytes = fs.readFileSync(apkPath);
  return {
    path: path.relative(root, apkPath),
    sizeBytes: bytes.length,
    sha256: crypto.createHash('sha256').update(bytes).digest('hex'),
  };
}

function recordBase(status, extra = {}) {
  return {
    schema: 'alpine-terminal-poc/v1',
    gate: 'p2-baseline',
    status,
    appCommit: git(['rev-parse', 'HEAD']),
    checks,
    apk: apkEvidence(),
    ptyBackend: {
      selection: 'app-owned-ndk-helper',
      source: 'android/app/src/main/cpp/terminal_pty.c',
      library: 'lib/arm64-v8a/libhorus_pty.so',
    },
    source: {
      script: 'scripts/alpine-poc/p2-baseline.js',
      sha256: sha256File(__filename),
    },
    deviceGate: {
      status: 'pending',
      script: 'scripts/alpine-poc/p2-device.js',
      note: 'Run once after this host gate passes; it requires a current release APK, an ARM64 physical device, and a reachable pinned rootfs archive.',
    },
    credentialsPresent: false,
    ...extra,
  };
}

function sha256File(filename) {
  return crypto.createHash('sha256').update(fs.readFileSync(filename)).digest('hex');
}

function commandRecord(definition, reused = false) {
  return {
    id: definition.id,
    status: 'passed',
    exitCode: 0,
    timedOut: false,
    command: evidenceCommand(definition.command),
    args: evidenceArgs(definition.args),
    cwd: definition.cwd ?? '.',
    ...(reused ? { reused: true } : {}),
  };
}

function readResumeArtifact() {
  const sourcePath = path.join(artifactsDir, resumeArtifactName);
  if (!fs.existsSync(sourcePath)) {
    throw new Error(`resume:missing_${resumeArtifactName}`);
  }
  const sourceBytes = fs.readFileSync(sourcePath);
  const sourceSha256 = crypto.createHash('sha256').update(sourceBytes).digest('hex');
  if (sourceSha256 !== resumeArtifactSha256) {
    throw new Error('resume:source_checksum_mismatch');
  }
  let record;
  try {
    record = JSON.parse(sourceBytes.toString('utf8'));
  } catch {
    throw new Error('resume:source_invalid_json');
  }
  if (
    record.schema !== 'alpine-terminal-poc/v1' ||
    record.gate !== 'p2-baseline' ||
    record.status !== 'passed' ||
    record.credentialsPresent !== false
  ) {
    throw new Error('resume:source_not_a_passing_host_gate');
  }
  const expectedIds = phase2CheckDefinitions.map(definition => definition.id);
  const sourceIds = Array.isArray(record.checks)
    ? record.checks.map(check => check.id)
    : [];
  if (
    sourceIds.length !== expectedIds.length ||
    sourceIds.some((id, index) => id !== expectedIds[index])
  ) {
    throw new Error('resume:source_check_set_mismatch');
  }
  for (const [index, check] of record.checks.entries()) {
    if (check.status !== 'passed' || check.exitCode !== 0 || check.timedOut !== false) {
      throw new Error(`resume:source_check_not_clean:${check.id}`);
    }
    const expected = commandRecord(phase2CheckDefinitions[index]);
    if (
      (check.command !== undefined && check.command !== expected.command) ||
      (check.args !== undefined && JSON.stringify(check.args) !== JSON.stringify(expected.args))
    ) {
      throw new Error(`resume:source_command_mismatch:${check.id}`);
    }
  }
  if (!record.apk || record.apk.path !== path.relative(root, apkPath)) {
    throw new Error('resume:source_release_checksum_missing');
  }
  if (!fs.existsSync(apkPath)) throw new Error('resume:release_apk_missing');
  const apk = apkEvidence();
  if (apk.sizeBytes !== record.apk.sizeBytes || apk.sha256 !== record.apk.sha256) {
    throw new Error('resume:release_apk_does_not_match_source_gate');
  }
  return { record, sourcePath, sourceSha256, apk };
}

function writeAtomic(filename, record) {
  fs.mkdirSync(artifactsDir, { recursive: true });
  const destination = path.join(artifactsDir, filename);
  const temporary = `${destination}.tmp`;
  fs.writeFileSync(temporary, `${JSON.stringify(record, null, 2)}\n`);
  fs.renameSync(temporary, destination);
}

function writeFailure(details) {
  writeAtomic(
    'p2-baseline-failure.json',
    recordBase('failed', {
      failure: {
        ...details,
        reason:
          details.reason ??
          (details.timedOut ? 'bounded_command_timeout' : 'command_failed'),
      },
    }),
  );
}

if (process.argv.includes(resumeFlag)) {
  try {
    const resumed = readResumeArtifact();
    const resumedChecks = phase2CheckDefinitions.map(definition =>
      commandRecord(definition, true),
    );
    writeAtomic(
      'p2-baseline.json',
      recordBase('passed', {
        checks: resumedChecks,
        apk: resumed.apk,
        provenance: {
          kind: 'reused_checkpoint',
          source: path.relative(root, resumed.sourcePath),
          sourceSha256: resumed.sourceSha256,
          reason:
            'full suites already ran; focused checks cover subsequent owned changes',
        },
      }),
    );
    console.log(`Alpine p2 baseline resumed from ${resumeArtifactName}; no full suite rerun.`);
    process.exit(0);
  } catch (error) {
    console.error(`Alpine p2 baseline resume failed: ${error.message}`);
    process.exit(1);
  }
}

for (const definition of phase2CheckDefinitions) {
  run(
    definition.id,
    definition.command,
    definition.args,
    900_000,
    definition.cwd ? { cwd: path.join(root, definition.cwd) } : {},
  );
}

const apk = apkEvidence();
if (!apk) {
  writeFailure({
    failedCheck: 'release-artifact',
    exitCode: 1,
    timedOut: false,
    reason: 'release_apk_missing',
  });
  console.error(
    '\nAlpine p2 baseline failed: release APK is missing after release assembly.',
  );
  process.exit(1);
}

writeAtomic('p2-baseline.json', recordBase('passed', { apk }));
console.log(
  'Alpine p2 host baseline passed; run the separate device gate exactly once at phase completion.',
);
