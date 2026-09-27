#!/usr/bin/env node

/**
 * Alpine terminal PoC Phase 0 baseline gate (host side). Proves the branch
 * still builds the existing app, the terminal namespace passes its focused
 * tests, the source boundary holds, and a release APK is produced and
 * checksummed. The on-device launch check lives in p0-device.js; this record
 * notes it as pending until that script runs.
 */
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const {spawnSync} = require('node:child_process');

const root = path.resolve(__dirname, '..', '..');
const artifactsDir = path.join(root, 'artifacts', 'alpine-poc');
const apkPath = path.join(root, 'android', 'app', 'build', 'outputs', 'apk', 'release', 'app-release.apk');
const npmCommand = process.platform === 'win32' ? 'npm.cmd' : 'npm';
const checks = [];

function git(args) {
  const result = spawnSync('git', args, {cwd: root, encoding: 'utf8', timeout: 30_000});
  return result.status === 0 ? result.stdout.trim() : null;
}

function run(label, command, args) {
  console.log(`\n=== Alpine p0 baseline: ${label} ===`);
  const result = spawnSync(command, args, {cwd: root, stdio: 'inherit'});
  const exitCode = result.status === null ? 1 : result.status;
  checks.push({id: label, status: exitCode === 0 ? 'passed' : 'failed', exitCode});
  if (exitCode !== 0) {
    finishAndWrite('failed');
    console.error(`\nAlpine p0 baseline failed at "${label}" with exit ${exitCode}.`);
    process.exit(exitCode);
  }
}

function finishAndWrite(status, extra = {}) {
  const appCommit = git(['rev-parse', 'HEAD']) ?? 'unknown';
  let apk = null;
  if (fs.existsSync(apkPath)) {
    apk = {
      path: path.relative(root, apkPath),
      sizeBytes: fs.statSync(apkPath).size,
      sha256: crypto.createHash('sha256').update(fs.readFileSync(apkPath)).digest('hex'),
    };
  }
  const record = {
    schema: 'alpine-terminal-poc/v1',
    gate: 'p0-baseline',
    status,
    appCommit,
    checks,
    apk,
    boundaryScan: 'artifacts/alpine-poc/p0-boundary-scan.json',
    deviceGate: {
      status: 'pending',
      script: 'scripts/alpine-poc/p0-device.js',
      note: 'Run with an ARM64 device attached; verifies release launch without Metro and the on-device no-op status call.',
    },
    credentialsPresent: false,
    ...extra,
  };
  fs.mkdirSync(artifactsDir, {recursive: true});
  const output = path.join(artifactsDir, 'p0-baseline.json');
  fs.writeFileSync(output, `${JSON.stringify(record, null, 2)}\n`);
  console.log(`\nWrote ${path.relative(root, output)}`);
}

run('typecheck', npmCommand, ['run', 'typecheck']);
run('focused-terminal-jest', npmCommand, [
  'exec',
  '--',
  'jest',
  '--runInBand',
  '__tests__/terminalRuntimeStatus.test.ts',
  '__tests__/terminalEvidenceSchema.test.ts',
]);
run('full-jest', npmCommand, ['run', 'test:unit']);
run('terminal-boundary-scan', process.execPath, [path.join(root, 'scripts', 'scan-terminal-boundary.js')]);
run('codegen-kotlin-compile', npmCommand, ['run', 'test:codegen:kotlin']);
run('kotlin-unit-tests', npmCommand, ['run', 'test:kotlin']);
run('debug-assembly', npmCommand, ['run', 'build:android:debug']);
run('release-assembly', npmCommand, ['run', 'build:android:release']);

finishAndWrite('passed');
console.log('Alpine terminal PoC Phase 0 host baseline passed.');
