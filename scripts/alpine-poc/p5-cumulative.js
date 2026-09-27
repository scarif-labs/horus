#!/usr/bin/env node
/* global __filename */

/**
 * Cumulative Phase 5 release gate. Passing evidence is written only after
 * every bounded host, build, and physical-device step succeeds.
 */
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const {spawnSync} = require('node:child_process');

const root = path.resolve(__dirname, '..', '..');
const androidDir = path.join(root, 'android');
const artifactsDir = path.join(root, 'artifacts', 'alpine-poc');
const instrumentation = 'com.scariflabs.horus.test/androidx.test.runner.AndroidJUnitRunner';
const adbTimeout = 30_000;
let currentStage = 'startup';

function fail(message) {
  const error = new Error(message);
  error.safeMessage = message;
  throw error;
}

function sha256File(file) {
  return crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
}

function writeAtomic(filename, record) {
  fs.mkdirSync(artifactsDir, {recursive: true});
  const destination = path.join(artifactsDir, filename);
  const temporary = `${destination}.tmp`;
  fs.writeFileSync(temporary, `${JSON.stringify(record, null, 2)}\n`);
  fs.renameSync(temporary, destination);
}

function run(command, args, options = {}) {
  const result = spawnSync(command, args, {
    cwd: options.cwd ?? root,
    encoding: 'utf8',
    timeout: options.timeout ?? adbTimeout,
    stdio: options.inherit ? ['ignore', 'inherit', 'inherit'] : ['ignore', 'pipe', 'pipe'],
  });
  return {
    exitCode: result.status === null ? 1 : result.status,
    timedOut: result.error?.code === 'ETIMEDOUT' || (result.signal !== undefined && result.signal !== null),
  };
}

function git(args) {
  const result = spawnSync('git', args, {cwd: root, encoding: 'utf8', timeout: adbTimeout});
  return result.status === 0 ? result.stdout.trim() : 'unknown';
}

function findDevice() {
  const result = spawnSync('adb', ['devices'], {cwd: root, encoding: 'utf8', timeout: adbTimeout});
  if (result.status !== 0) fail('device:adb_devices_failed');
  const serials = (result.stdout || '')
    .split(/\r?\n/)
    .slice(1)
    .map(line => line.trim().split(/\s+/))
    .filter(parts => parts.length >= 2 && parts[1] === 'device')
    .map(parts => parts[0]);
  const property = (serial, key) => {
    const value = spawnSync('adb', ['-s', serial, 'shell', 'getprop', key], {
      cwd: root,
      encoding: 'utf8',
      timeout: 5_000,
    });
    return value.status === 0 ? (value.stdout || '').trim() : '';
  };
  const selected = serials.map(serial => ({
    serial,
    model: property(serial, 'ro.product.model'),
    androidApi: Number(property(serial, 'ro.build.version.sdk')),
    abi: property(serial, 'ro.product.cpu.abi'),
    boot: property(serial, 'sys.boot_completed'),
    emulator: property(serial, 'ro.kernel.qemu') === '1',
  })).find(device => device.boot === '1' && device.abi === 'arm64-v8a' && !device.emulator);
  if (!selected) fail('device:no_booted_arm64_physical_device');
  return selected;
}

function main() {
  const device = findDevice();
  const steps = [
    {id: 'typecheck', command: 'npm', args: ['run', 'typecheck'], timeout: 120_000},
    {id: 'jest-open-handles', command: 'npm', args: ['test', '--', '--runInBand', '--detectOpenHandles'], timeout: 180_000},
    {id: 'terminal-boundary-scan', command: 'npm', args: ['run', 'scan:terminal-boundary'], timeout: 120_000},
    {id: 'codegen-kotlin', command: 'npm', args: ['run', 'test:codegen:kotlin'], timeout: 300_000},
    {id: 'kotlin-unit', command: 'npm', args: ['run', 'test:kotlin'], timeout: 300_000},
    {id: 'lint', command: 'npm', args: ['run', 'lint'], timeout: 180_000},
    {id: 'release-build', command: 'npm', args: ['run', 'build:android:release'], timeout: 600_000},
    {id: 'p3-terminal-screen-device', command: 'npm', args: ['run', 'test:android:alpine-p3'], timeout: 600_000},
    {id: 'p4-lifecycle-reset-device', command: 'npm', args: ['run', 'test:android:alpine-p4'], timeout: 900_000},
    {id: 'instrumentation-build', command: './gradlew', args: [':app:assembleDebug', ':app:assembleAndroidTest', '--no-daemon'], cwd: androidDir, timeout: 600_000},
    {id: 'p5-toolchain-device', command: 'adb', args: [
      '-s', device.serial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
      'com.scariflabs.horus.terminal.TerminalToolchainDeviceTest#installsBaseProfileAndKeepsItAcrossNewShellSession',
      instrumentation,
    ], timeout: 900_000},
  ];
  const results = [];
  for (const step of steps) {
    currentStage = step.id;
    const startedAt = new Date().toISOString();
    const result = run(step.command, step.args, {...step, inherit: true});
    results.push({id: step.id, command: [step.command, ...step.args].join(' '), cwd: step.cwd ? path.relative(root, step.cwd) : '.', startedAt, ...result});
    if (result.exitCode !== 0) fail(`${step.id}:exit_${result.exitCode}`);
  }

  const releaseApk = path.join(root, 'android', 'app', 'build', 'outputs', 'apk', 'release', 'app-release.apk');
  const p3Artifact = path.join(artifactsDir, 'p3-screen-device.json');
  const p4Artifact = path.join(artifactsDir, 'p4-lifecycle-device.json');
  const p5Artifact = path.join(artifactsDir, 'p5-device.json');
  for (const artifact of [releaseApk, p3Artifact, p4Artifact, p5Artifact]) {
    if (!fs.existsSync(artifact)) fail(`evidence:missing_${path.basename(artifact)}`);
  }
  writeAtomic('p5-cumulative.json', {
    schema: 'alpine-terminal-poc/v1',
    gate: 'p5-cumulative-release',
    status: 'passed',
    date: new Date().toISOString(),
    appCommit: git(['rev-parse', 'HEAD']),
    device,
    apk: {path: path.relative(root, releaseApk), bytes: fs.statSync(releaseApk).size, sha256: sha256File(releaseApk)},
    steps,
    results,
    artifacts: [p3Artifact, p4Artifact, p5Artifact].map(artifact => ({path: path.relative(root, artifact), sha256: sha256File(artifact)})),
    credentialsPresent: false,
    source: {script: 'scripts/alpine-poc/p5-cumulative.js', sha256: sha256File(__filename)},
  });
  console.log('Alpine cumulative Phase 5 release gate passed; evidence written to artifacts/alpine-poc/.');
}

try {
  main();
} catch (error) {
  const reason = error instanceof Error && error.safeMessage ? error.safeMessage : 'unexpected_failure';
  writeAtomic('p5-cumulative-failure.json', {
    schema: 'alpine-terminal-poc/v1',
    gate: 'p5-cumulative-release',
    status: 'failed',
    date: new Date().toISOString(),
    appCommit: git(['rev-parse', 'HEAD']),
    failure: {stage: currentStage, reason},
    credentialsPresent: false,
  });
  console.error(`Alpine cumulative Phase 5 release gate failed at ${currentStage}: ${reason}`);
  process.exit(1);
}
