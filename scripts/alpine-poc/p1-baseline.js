#!/usr/bin/env node

/**
 * Alpine terminal PoC Phase 1 host gate.
 *
 * This gate deliberately excludes the connected-device run. It produces a
 * release APK checksum that p1-device.js must match before it will run the
 * physical-device install/probe gate. Failed runs write a separate failure
 * record and never replace a passing p1-baseline.json.
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

function git(args) {
  const result = spawnSync('git', args, {
    cwd: root,
    encoding: 'utf8',
    timeout: 30_000,
  });
  return result.status === 0 ? result.stdout.trim() : 'unknown';
}

function run(label, command, args, timeout = 900_000, options = {}) {
  console.log(`\n=== Alpine p1 baseline: ${label} ===`);
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
  });
  if (exitCode !== 0) {
    writeFailure({ failedCheck: label, exitCode, timedOut });
    console.error(
      `\nAlpine p1 baseline failed at "${label}" with exit ${exitCode}.`,
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
    gate: 'p1-baseline',
    status,
    appCommit: git(['rev-parse', 'HEAD']),
    checks,
    apk: apkEvidence(),
    deviceGate: {
      status: 'pending',
      script: 'scripts/alpine-poc/p1-device.js',
      note: 'Run once after this host gate passes; it requires a current release APK, an ARM64 physical device, and a reachable pinned rootfs archive.',
    },
    credentialsPresent: false,
    ...extra,
  };
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
    'p1-baseline-failure.json',
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

run('typecheck', npmCommand, ['run', 'typecheck']);
run('focused-terminal-jest', npmCommand, [
  'exec',
  '--',
  'jest',
  '--runInBand',
  '--detectOpenHandles',
  '__tests__/terminalRuntimeStatus.test.ts',
  '__tests__/terminalEvidenceSchema.test.ts',
  '__tests__/terminalDistroContract.test.ts',
]);
run(
  'focused-phase1-kotlin-unit-tests',
  process.platform === 'win32' ? 'gradlew.bat' : './gradlew',
  [
    ':app:testDebugUnitTest',
    '--no-daemon',
    '--tests',
    'com.scariflabs.horus.terminal.DistroStoreCoreTest',
    '--tests',
    'com.scariflabs.horus.terminal.SafeTarGzExtractorTest',
    '--tests',
    'com.scariflabs.horus.terminal.TerminalRuntimeContractTest',
  ],
  900_000,
  { cwd: path.join(root, 'android') },
);
run('full-jest', npmCommand, ['run', 'test:unit', '--', '--detectOpenHandles']);
run('terminal-boundary-scan', process.execPath, [
  path.join(root, 'scripts', 'scan-terminal-boundary.js'),
]);
run('codegen-kotlin-compile', npmCommand, ['run', 'test:codegen:kotlin']);
run('kotlin-unit-tests', npmCommand, ['run', 'test:kotlin']);
run('release-assembly', npmCommand, ['run', 'build:android:release']);

const apk = apkEvidence();
if (!apk) {
  writeFailure({
    failedCheck: 'release-artifact',
    exitCode: 1,
    timedOut: false,
    reason: 'release_apk_missing',
  });
  console.error(
    '\nAlpine p1 baseline failed: release APK is missing after release assembly.',
  );
  process.exit(1);
}

writeAtomic('p1-baseline.json', recordBase('passed', { apk }));
console.log(
  'Alpine p1 host baseline passed; run the separate device gate exactly once at phase completion.',
);
