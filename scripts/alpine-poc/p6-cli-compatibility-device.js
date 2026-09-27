#!/usr/bin/env node
/* global __filename */

/**
 * Phase 6 physical-device compatibility run. The Android test owns a clean
 * Alpine guest, installs each CLI through its documented package path, and
 * logs only bounded compatibility markers. A partial matrix is still useful:
 * authentication is never attempted by this automated run.
 */
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const {spawnSync} = require('node:child_process');

const root = path.resolve(__dirname, '..', '..');
const androidDir = path.join(root, 'android');
const artifactsDir = path.join(root, 'artifacts', 'alpine-poc');
const releaseApk = path.join(androidDir, 'app', 'build', 'outputs', 'apk', 'release', 'app-release.apk');
const debugApk = path.join(androidDir, 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk');
const testApk = path.join(androidDir, 'app', 'build', 'outputs', 'apk', 'androidTest', 'debug', 'app-debug-androidTest.apk');
const packageName = 'com.scariflabs.horus';
const testPackage = 'com.scariflabs.horus.test';
const instrumentation = `${testPackage}/androidx.test.runner.AndroidJUnitRunner`;
const testClass = 'com.scariflabs.horus.terminal.TerminalCliCompatibilityDeviceTest#recordsFourCliCompatibilityMatrixOnPhysicalAlpineGuest';
const logTag = 'AlpineP6CliMatrixDeviceTest';
const cliIds = ['claude-code', 'codex-cli', 'opencode', 'gemini-cli'];
const stageIds = ['install', 'version-doctor', 'execution', 'auth-resume'];
const adbTimeout = 30_000;
let deviceSerial = null;
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
  const startedAt = new Date().toISOString();
  const startedMs = Date.now();
  const result = spawnSync(command, args, {
    cwd: options.cwd ?? root,
    encoding: options.encoding ?? 'utf8',
    timeout: options.timeout ?? adbTimeout,
    stdio: options.inherit ? ['ignore', 'inherit', 'inherit'] : ['ignore', 'pipe', 'pipe'],
  });
  return {
    command: [command, ...args].join(' '),
    cwd: options.cwd ? path.relative(root, options.cwd) || '.' : '.',
    startedAt,
    durationMs: Date.now() - startedMs,
    exitCode: result.status === null ? 1 : result.status,
    timedOut: result.error?.code === 'ETIMEDOUT' || Boolean(result.signal),
    stdout: result.stdout || '',
    stderr: result.stderr || '',
  };
}

function adb(args, options = {}) {
  if (!deviceSerial) fail('device:no_device_selected');
  const result = run('adb', ['-s', deviceSerial, ...args], options);
  if (result.exitCode !== 0) fail(`${options.label ?? 'adb'}:exit_${result.exitCode}`);
  return result.stdout;
}

function git(args) {
  const result = spawnSync('git', args, {cwd: root, encoding: 'utf8', timeout: adbTimeout});
  return result.status === 0 ? result.stdout.trim() : 'unknown';
}

function findDevice() {
  const devices = run('adb', ['devices']);
  if (devices.exitCode !== 0) fail('device:adb_devices_failed');
  const serials = devices.stdout
    .split(/\r?\n/)
    .slice(1)
    .map(line => line.trim().split(/\s+/))
    .filter(parts => parts.length >= 2 && parts[1] === 'device')
    .map(parts => parts[0]);
  const property = (serial, key) => {
    const result = spawnSync('adb', ['-s', serial, 'shell', 'getprop', key], {
      cwd: root,
      encoding: 'utf8',
      timeout: 5_000,
    });
    return result.status === 0 ? (result.stdout || '').trim() : '';
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
  deviceSerial = selected.serial;
  return selected;
}

function parseLogLines(logcat) {
  return logcat
    .split(/\r?\n/)
    .map(line => line.replace(new RegExp(`^[VDIWEF]\\/${logTag}(?:\\(\\s*\\d+\\))?:\\s*`), '').trim())
    .filter(Boolean);
}

function exactlyOne(lines, pattern, name) {
  const matches = lines.map(line => pattern.exec(line)).filter(Boolean);
  pattern.lastIndex = 0;
  if (matches.length !== 1) fail(`evidence:${name}_count_${matches.length}`);
  return matches[0];
}

function readMatrix(logcat) {
  const lines = parseLogLines(logcat);
  const joined = lines.join('\n');
  if (/(?:bearer\s+|sk-[a-z0-9]|api[_-]?key\s*[:=]|authorization\s*[:=]|ANTHROPIC_API_KEY|OPENAI_API_KEY|GEMINI_API_KEY)/i.test(joined)) {
    fail('evidence:credential-shaped-marker');
  }
  exactlyOne(lines, /^ALPINE_P6_MATRIX_OK$/, 'matrix_complete');
  const rootfsMatch = exactlyOne(lines, /^P6_ROOTFS\|id=([a-z0-9._-]+)\|archive_sha256=([a-f0-9]{64})\|archive_bytes=([0-9]+)$/, 'rootfs');
  const relaunch = {};
  for (const cli of cliIds) {
    const match = exactlyOne(lines, new RegExp(`^P6_RELAUNCH\\|${cli}\\|(pass|not-tested)$`), `relaunch_${cli}`);
    relaunch[cli] = match[1];
  }
  exactlyOne(lines, /^P6_RELAUNCH_OK$/, 'relaunch_complete');
  exactlyOne(lines, /^P6_CODEX_UNSANDBOXED_OK$/, 'codex_unsandboxed_profile');
  const sandboxMatch = exactlyOne(
    lines,
    /^P6_SANDBOX\|codex-cli\|(pass|fail|not-tested|unsupported)\|v=([A-Za-z0-9._+\-]+)\|d=([a-z0-9_+\-]+)$/,
    'sandbox_codex-cli',
  );

  const records = cliIds.map(cli => {
    const stages = {};
    for (const stage of stageIds) {
      const match = exactlyOne(
        lines,
        new RegExp(`^P6_STAGE\\|${cli}\\|${stage}\\|(pass|fail|not-tested|unsupported)\\|v=([A-Za-z0-9._+\\-]+)\\|d=([a-z0-9_+\\-]+)$`),
        `${cli}_${stage}`,
      );
      stages[stage] = {status: match[1], version: match[2], detail: match[3]};
    }
    const limitations = [
      'authenticated_provider_round_trip_not_tested_credentials_absent',
      'relaunch_resume_of_authenticated_session_not_tested',
    ];
    if (stages.execution.detail.includes('offline_')) limitations.push('execution_was_command_help_only_without_provider_authentication');
    if (relaunch[cli] === 'not-tested') limitations.push('relaunch_persistence_not_tested_because_install_failed');
    const sandbox = cli === 'codex-cli'
      ? {status: sandboxMatch[1], version: sandboxMatch[2], detail: sandboxMatch[3]}
      : {status: 'not-tested', detail: 'not-applicable'};
    if (sandbox.status === 'unsupported' || (sandbox.status === 'fail' && sandbox.detail === 'proot_loader_exit_182')) limitations.push('linux_sandbox_unavailable_under_proot');
    if (cli === 'codex-cli') {
      limitations.push('proot_emulates_namespaces_kernel_isolation_not_established');
      limitations.push('codex_uses_explicit_unsandboxed_mobile_profile');
    }
    return {cli, stages, sandbox, limitations};
  });
  return {
    rootfs: {rootfsId: rootfsMatch[1], archiveSha256: rootfsMatch[2], archiveBytes: Number(rootfsMatch[3])},
    records,
    markerLines: lines.length,
  };
}

function main() {
  if (!fs.existsSync(releaseApk)) fail('artifact:release_apk_missing');
  currentStage = 'device_selection';
  const device = findDevice();
  currentStage = 'instrumentation_build';
  const build = run('./gradlew', [':app:assembleDebug', ':app:assembleAndroidTest', '--no-daemon'], {
    cwd: androidDir,
    timeout: 600_000,
    inherit: true,
  });
  if (build.exitCode !== 0) fail('instrumentation_build:exit_' + build.exitCode);
  if (!fs.existsSync(debugApk) || !fs.existsSync(testApk)) fail('artifact:instrumentation_apk_missing');

  currentStage = 'debug_install';
  const installTarget = run('adb', ['-s', device.serial, 'install', '-r', debugApk], {timeout: 120_000});
  if (installTarget.exitCode !== 0) fail('debug_install:exit_' + installTarget.exitCode);
  const installTest = run('adb', ['-s', device.serial, 'install', '-r', testApk], {timeout: 120_000});
  if (installTest.exitCode !== 0) fail('android_test_install:exit_' + installTest.exitCode);

  currentStage = 'instrumentation_run';
  adb(['logcat', '-c'], {label: 'logcat_clear'});
  const test = run('adb', [
    '-s', device.serial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class', testClass, instrumentation,
  ], {timeout: 1_900_000, inherit: true});
  if (test.exitCode !== 0) fail('instrumentation:exit_' + test.exitCode);

  currentStage = 'evidence_read';
  const logcat = adb(['logcat', '-d', '-v', 'brief', '-s', `${logTag}:I`, '*:S'], {timeout: 60_000, label: 'logcat_read'});
  const matrix = readMatrix(logcat);
  writeAtomic('p6-device.json', {
    schema: 'alpine-cli-compatibility/v1',
    evidenceSchema: 'alpine-terminal-poc/v1',
    gate: 'p6-cli-compatibility-device',
    status: 'recorded',
    date: new Date().toISOString(),
    appCommit: git(['rev-parse', 'HEAD']),
    device: {model: device.model, androidApi: device.androidApi, abi: device.abi, emulator: device.emulator},
    apk: {path: path.relative(root, releaseApk), bytes: fs.statSync(releaseApk).size, sha256: sha256File(releaseApk)},
    rootfs: matrix.rootfs,
    records: matrix.records,
    codexMode: 'unsandboxed_android_app_sandbox',
    authentication: {status: 'not-tested', reason: 'credentials_absent', credentialsPresent: false},
    runner: {
      testClass,
      logTag,
      markerCount: matrix.markerLines,
      installMethods: {
        'claude-code': 'official_native_installer',
        'codex-cli': 'npm:@openai/codex',
        opencode: 'npm:opencode-ai',
        'gemini-cli': 'npm:@google/gemini-cli',
      },
    },
    credentialsPresent: false,
    source: {script: 'scripts/alpine-poc/p6-cli-compatibility-device.js', sha256: sha256File(__filename)},
  });
  console.log('Alpine Phase 6 CLI compatibility matrix recorded in artifacts/alpine-poc/p6-device.json.');
}

try {
  main();
} catch (error) {
  const reason = error instanceof Error && error.safeMessage ? error.safeMessage : 'unexpected_failure';
  writeAtomic('p6-device-failure.json', {
    schema: 'alpine-terminal-poc/v1',
    gate: 'p6-cli-compatibility-device',
    status: 'failed',
    date: new Date().toISOString(),
    appCommit: git(['rev-parse', 'HEAD']),
    failure: {stage: currentStage, reason},
    credentialsPresent: false,
  });
  console.error(`Alpine Phase 6 CLI compatibility run failed at ${currentStage}: ${reason}`);
  process.exit(1);
}
