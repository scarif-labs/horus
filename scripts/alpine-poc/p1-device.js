#!/usr/bin/env node
/* global Atomics, SharedArrayBuffer */

/**
 * Alpine terminal PoC Phase 1 physical-device gate.
 *
 * The connected test performs the real pinned archive download, extraction,
 * PRoot launch, exact guest-marker validation, and repeat install. The final
 * release APK is then installed and launched without Metro. Only a fully
 * observed pass writes p1-device.json; failures write a separate failure
 * record so a prior pass cannot be overwritten.
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
const baselinePath = path.join(artifactsDir, 'p1-baseline.json');
const packageName = 'com.scariflabs.horus';
const connectedTestClass = 'com.scariflabs.horus.terminal.TerminalRuntimeDeviceTest';
const adbTimeout = 30_000;
const expectedUiMarkers = [
  'terminal-screen',
  'terminal-status',
  'terminal-start',
];
const expectedLogMarkers = [
  'ALPINE_P1_INSTALL_SUCCESS',
  'ALPINE_P1_PROBE_MARKERS=alpine_probe_begin|aarch64|3.24.0|/root|/sbin/apk|/bin/sh|alpine_probe_end',
  'ALPINE_P1_REPEAT_SUCCESS',
];

let deviceSerial = null;
let currentStage = 'startup';

function fail(message) {
  const error = new Error(message);
  error.safeMessage = message;
  throw error;
}

function run(command, args, options = {}) {
  const result = spawnSync(command, args, {
    cwd: options.cwd ?? root,
    encoding: 'utf8',
    timeout: options.timeout ?? adbTimeout,
    stdio: options.stdio ?? ['ignore', 'pipe', 'pipe'],
    env: options.env,
  });
  if (result.status !== 0) {
    const reason =
      result.error?.code === 'ETIMEDOUT' || result.signal
        ? 'bounded_timeout'
        : result.error
          ? `spawn_failed_${result.error.code ?? result.error.name}`
          : 'command_failed';
    fail(`${options.label ?? command}:${reason}`);
  }
  return result.stdout || '';
}

function adb(args, options = {}) {
  if (!deviceSerial) fail('adb:no_device_selected');
  return run('adb', ['-s', deviceSerial, ...args], options);
}

function git(args) {
  const result = spawnSync('git', args, {
    cwd: root,
    encoding: 'utf8',
    timeout: adbTimeout,
  });
  return result.status === 0 ? result.stdout.trim() : 'unknown';
}

function sha256File(file) {
  return crypto
    .createHash('sha256')
    .update(fs.readFileSync(file))
    .digest('hex');
}

function writeAtomic(filename, record) {
  fs.mkdirSync(artifactsDir, { recursive: true });
  const destination = path.join(artifactsDir, filename);
  const temporary = `${destination}.tmp`;
  fs.writeFileSync(temporary, `${JSON.stringify(record, null, 2)}\n`);
  fs.renameSync(temporary, destination);
}

function writeFailure(reason) {
  writeAtomic('p1-device-failure.json', {
    schema: 'alpine-terminal-poc/v1',
    gate: 'p1-device',
    status: 'failed',
    appCommit: git(['rev-parse', 'HEAD']),
    failure: { stage: currentStage, reason },
    credentialsPresent: false,
  });
}

function readPassingBaseline() {
  if (!fs.existsSync(baselinePath)) fail('baseline:missing_p1_baseline');
  let record;
  try {
    record = JSON.parse(fs.readFileSync(baselinePath, 'utf8'));
  } catch {
    fail('baseline:invalid_p1_baseline');
  }
  if (
    record.status !== 'passed' ||
    record.gate !== 'p1-baseline' ||
    record.credentialsPresent !== false
  ) {
    fail('baseline:not_a_passing_host_gate');
  }
  if (
    !record.apk ||
    record.apk.path !== path.relative(root, apkPath) ||
    !Number.isInteger(record.apk.sizeBytes) ||
    !/^[a-f0-9]{64}$/.test(record.apk.sha256)
  ) {
    fail('baseline:missing_release_checksum');
  }
  if (!fs.existsSync(apkPath)) fail('artifact:release_apk_missing');
  const sizeBytes = fs.statSync(apkPath).size;
  const sha256 = sha256File(apkPath);
  if (sizeBytes !== record.apk.sizeBytes || sha256 !== record.apk.sha256) {
    fail('artifact:release_apk_does_not_match_host_gate');
  }
  return { sizeBytes, sha256 };
}

function verifyPackagedRuntime() {
  const listing = run('unzip', ['-Z1', apkPath], {
    label: 'artifact:apk_listing',
  });
  const entries = new Set(
    listing
      .split(/\r?\n/)
      .map(line => line.trim())
      .filter(Boolean),
  );
  const required = [
    'assets/alpine-runtime/manifest.json',
    'lib/arm64-v8a/libproot.so',
    'lib/arm64-v8a/libproot_loader.so',
  ];
  const missing = required.filter(entry => !entries.has(entry));
  if (missing.length > 0)
    fail(`artifact:packaged_runtime_missing:${missing.join(',')}`);
}

function findDevice() {
  const output = run('adb', ['devices'], { label: 'device:adb_devices' });
  const serials = output
    .split(/\r?\n/)
    .slice(1)
    .map(line => line.trim().split(/\s+/))
    .filter(parts => parts.length >= 2 && parts[1] === 'device')
    .map(parts => parts[0]);
  if (serials.length === 0) fail('device:no_attached_device');

  const property = (serial, key) => {
    const result = spawnSync('adb', ['-s', serial, 'shell', 'getprop', key], {
      cwd: root,
      encoding: 'utf8',
      timeout: 5_000,
    });
    return result.status === 0 ? (result.stdout || '').trim() : '';
  };
  const candidates = serials.map(serial => ({
    serial,
    model: property(serial, 'ro.product.model'),
    api: Number(property(serial, 'ro.build.version.sdk')),
    abi: property(serial, 'ro.product.cpu.abi'),
    boot: property(serial, 'sys.boot_completed'),
    emulator: property(serial, 'ro.kernel.qemu') === '1',
  }));
  const selected = candidates.find(
    candidate =>
      candidate.boot === '1' &&
      candidate.abi === 'arm64-v8a' &&
      !candidate.emulator,
  );
  if (!selected) fail('device:no_booted_arm64_physical_device');
  deviceSerial = selected.serial;
  return selected;
}

function removeMetroReverseRoute() {
  const before = adb(['reverse', '--list']);
  if (!before.split(/\r?\n/).some(line => line.trim().endsWith('tcp:8081')))
    return false;
  adb(['reverse', '--remove', 'tcp:8081']);
  const after = adb(['reverse', '--list']);
  if (after.split(/\r?\n/).some(line => line.trim().endsWith('tcp:8081')))
    fail('device:metro_reverse_route_remains');
  return true;
}

function waitForProcessGone() {
  const deadline = Date.now() + 20_000;
  while (Date.now() < deadline) {
    const processes = adb(['shell', 'ps', '-A']);
    const alive = processes
      .split(/\r?\n/)
      .some(line =>
        new RegExp(`(?:^|\\s)${packageName.replace('.', '\\.')}(?:\\s|$)`).test(
          line,
        ),
      );
    if (!alive) return;
    Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 250);
  }
  fail('device:process_did_not_disappear_after_force_stop');
}

function escapeRegex(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function hasExactMarker(hierarchy, marker) {
  const pattern = new RegExp(
    `(?:^|[\\s>"'])${escapeRegex(marker)}(?=$|[\\s<"'])`,
  );
  return pattern.test(hierarchy);
}

function deviceUiObservable() {
  const power = adb(['shell', 'dumpsys', 'power']);
  const window = adb(['shell', 'dumpsys', 'window']);
  return /mWakefulness=Awake/.test(power) && /mDreamingLockscreen=false/.test(window);
}

function wakeAndDismissKeyguard() {
  adb(['shell', 'input', 'keyevent', 'KEYCODE_WAKEUP']);
  adb(['shell', 'wm', 'dismiss-keyguard']);
  const deadline = Date.now() + 10_000;
  while (Date.now() < deadline) {
    if (deviceUiObservable()) return;
    Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 250);
  }
  fail('device:ui_not_observable_after_wake');
}

function waitForReleaseMarkers() {
  const deadline = Date.now() + 30_000;
  let hierarchy = '';
  while (Date.now() < deadline) {
    // Never dump the hierarchy while the app is backgrounded or the device is
    // locked; first-launch evidence must describe an observable foreground UI.
    if (!deviceUiObservable()) {
      wakeAndDismissKeyguard();
    }
    const dump = spawnSync(
      'adb',
      ['-s', deviceSerial, 'exec-out', 'uiautomator', 'dump', '/dev/tty'],
      {
        cwd: root,
        encoding: 'utf8',
        timeout: adbTimeout,
      },
    );
    hierarchy = dump.stdout || '';
    if (expectedUiMarkers.every(marker => hasExactMarker(hierarchy, marker)))
      return;
    Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 500);
  }
  const missing = expectedUiMarkers.filter(
    marker => !hasExactMarker(hierarchy, marker),
  );
  fail(`device:release_launch_missing_exact_marker:${missing.join(',')}`);
}

function runConnectedPhase1Test() {
  adb(['logcat', '-c']);
  const gradle = process.platform === 'win32' ? 'gradlew.bat' : './gradlew';
  run(
    gradle,
    [
      ':app:connectedDebugAndroidTest',
      `-Pandroid.testInstrumentationRunnerArguments.class=${connectedTestClass}`,
      '--no-daemon',
    ],
    {
      label: 'connected:phase1_install_probe',
      timeout: 900_000,
      cwd: path.join(root, 'android'),
      stdio: 'inherit',
      env: { ...process.env, ANDROID_SERIAL: deviceSerial },
    },
  );
  const logcat = adb([
    'logcat',
    '-d',
    '-v',
    'brief',
    '-s',
    'AlpineP1DeviceTest:I',
    '*:S',
  ]);
  const markerLines = logcat
    .split(/\r?\n/)
    .map(line =>
      line.replace(/^[VDIWEF]\/AlpineP1DeviceTest(?:\(\d+\))?:\s*/, '').trim(),
    )
    .filter(Boolean);
  const missing = expectedLogMarkers.filter(
    marker => !markerLines.includes(marker),
  );
  if (missing.length > 0)
    fail(`connected:exact_success_marker_missing:${missing.join(',')}`);
}

function runReleaseLaunch() {
  adb(['install', '-r', apkPath], {
    timeout: 120_000,
    label: 'release:install',
  });
  adb(['shell', 'am', 'force-stop', packageName]);
  waitForProcessGone();
  wakeAndDismissKeyguard();
  adb(['shell', 'monkey', '-p', packageName, '1']);
  waitForReleaseMarkers();
  return adb(['exec-out', 'screencap', '-p'], {
    timeout: 60_000,
    label: 'release:screenshot',
  });
}

try {
  currentStage = 'baseline';
  const apk = readPassingBaseline();
  currentStage = 'packaged_runtime';
  verifyPackagedRuntime();
  currentStage = 'device_selection';
  const device = findDevice();
  currentStage = 'connected_install_probe';
  const metroRouteExisted = removeMetroReverseRoute();
  runConnectedPhase1Test();
  currentStage = 'release_launch';
  const screenshot = runReleaseLaunch();

  fs.mkdirSync(artifactsDir, { recursive: true });
  // Keep each passing screenshot immutable by run. A later failure can then
  // write only p1-device-failure.json and cannot damage prior evidence.
  const screenshotFilename = `p1-device-${process.pid}-${Date.now()}.png`;
  fs.writeFileSync(path.join(artifactsDir, screenshotFilename), screenshot);
  writeAtomic('p1-device.json', {
    schema: 'alpine-terminal-poc/v1',
    gate: 'p1-device',
    status: 'passed',
    appCommit: git(['rev-parse', 'HEAD']),
    device: {
      model: device.model,
      androidApi: device.api,
      abi: device.abi,
      emulator: device.emulator,
    },
    apk,
    checks: [
      {
        id: 'connected-phase1-install-probe',
        status: 'passed',
        exitCode: 0,
        marker: expectedLogMarkers.join('+'),
        storageScope: 'app-private/test-owned',
        resetScope: 'rootfs-only',
        rootfs: {
          id: 'alpine-3.24.0-aarch64',
          archiveBytes: 4043766,
          archiveSha256:
            '4b8cd66a6688b2a87276c39843ed89c3a06d9534fc6a5823c586aff2696c1f2a',
          probeMarkers: [
            'alpine_probe_begin',
            'aarch64',
            '3.24.0',
            '/root',
            '/sbin/apk',
            '/bin/sh',
            'alpine_probe_end',
          ],
        },
      },
      {
        id: 'release-launch-without-metro',
        status: 'passed',
        exitCode: 0,
        marker: expectedUiMarkers.join('+'),
      },
    ],
    metroRouteExistedBeforeLaunch: metroRouteExisted,
    screenshot: `artifacts/alpine-poc/${screenshotFilename}`,
    credentialsPresent: false,
  });
  console.log(
    'Alpine p1 device gate passed; evidence written to artifacts/alpine-poc/.',
  );
} catch (error) {
  const reason =
    error instanceof Error && error.safeMessage
      ? error.safeMessage
      : 'unexpected_failure';
  writeFailure(reason);
  console.error(`Alpine p1 device gate failed at ${currentStage}: ${reason}`);
  process.exit(1);
}
