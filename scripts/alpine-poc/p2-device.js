#!/usr/bin/env node
/* global __filename, Buffer */
/* global Atomics, SharedArrayBuffer */

/**
 * Alpine terminal PoC Phase 2 physical-device gate (native PTY and process
 * supervision). The connected test drives a real interactive guest shell
 * through the app-owned PTY helper and PRoot: echo/env/cat gates, escape-byte
 * round trip, Ctrl-C as a terminal interrupt, resize observed by stty, bulk
 * ordered output, exactly-one exit, and a stop whose teardown observation
 * proves no session process survives. The release APK is then installed and
 * launched without Metro to prove the app still boots with the new native
 * library. Only a fully observed pass writes p2-device.json; failures write
 * a separate failure record so a prior pass cannot be overwritten.
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
const baselinePath = path.join(artifactsDir, 'p2-baseline.json');
const packageName = 'com.scariflabs.horus';
const connectedTestClass = 'com.scariflabs.horus.terminal.TerminalPtyDeviceTest';
const adbTimeout = 30_000;
const expectedUiMarkers = [
  'terminal-screen',
  'terminal-status',
  'terminal-start',
];
const expectedLogMarkerPatterns = {
  ready: /^ALPINE_P2_PTY_READY sessionId=(s-[0-9]+-[0-9]+) pid=([0-9]+)$/,
  echo: /^ALPINE_P2_ECHO_OK$/,
  env: /^ALPINE_P2_ENV_OK$/,
  cat: /^ALPINE_P2_CAT_OK$/,
  escape: /^ALPINE_P2_ESC_OK$/,
  top: /^ALPINE_P2_TOP_OK$/,
  ctrlC: /^ALPINE_P2_CTRL_C_OK status=130$/,
  resize: /^ALPINE_P2_RESIZE_OK 24x80->40x120$/,
  yes: /^ALPINE_P2_YES_OK bytes=4096$/,
  exit: /^ALPINE_P2_EXIT_OK sessionId=(s-[0-9]+-[0-9]+) exitCode=0 signal=none reason=process_exit$/,
  stop: /^ALPINE_P2_STOP_OK sessionId=(s-[0-9]+-[0-9]+) remaining=0 stragglers=0 stoppedWithinDeadline=true exitSignal=([a-z0-9_-]+|none) exitCode=([0-9]+|none) reason=([a-z0-9_]+)$/,
  clean: /^ALPINE_P2_SESSIONS_CLEAN active=0 exits=2$/,
};

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
    // `null` is intentional for binary commands such as screencap. Using
    // nullish coalescing here silently converted those bytes to UTF-8 text.
    encoding: options.encoding === undefined ? 'utf8' : options.encoding,
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

function currentScriptSha256() {
  return sha256File(__filename);
}

function writeAtomic(filename, record) {
  fs.mkdirSync(artifactsDir, { recursive: true });
  const destination = path.join(artifactsDir, filename);
  const temporary = `${destination}.tmp`;
  fs.writeFileSync(temporary, `${JSON.stringify(record, null, 2)}\n`);
  fs.renameSync(temporary, destination);
}

function writeFailure(reason) {
  writeAtomic('p2-device-failure.json', {
    schema: 'alpine-terminal-poc/v1',
    gate: 'p2-device',
    status: 'failed',
    appCommit: git(['rev-parse', 'HEAD']),
    failure: { stage: currentStage, reason },
    credentialsPresent: false,
  });
}

function readPassingBaseline() {
  if (!fs.existsSync(baselinePath)) fail('baseline:missing_p2_baseline');
  let record;
  try {
    record = JSON.parse(fs.readFileSync(baselinePath, 'utf8'));
  } catch {
    fail('baseline:invalid_p2_baseline');
  }
  if (
    record.status !== 'passed' ||
    record.gate !== 'p2-baseline' ||
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
  if (
    record.source?.script !== 'scripts/alpine-poc/p2-baseline.js' ||
    !/^[a-f0-9]{64}$/.test(record.source?.sha256 ?? '')
  ) {
    fail('baseline:missing_source_provenance');
  }
  if (record.source.sha256 !== sha256File(path.join(root, record.source.script))) {
    fail('baseline:source_script_checksum_mismatch');
  }
  const expectedChecks = [
    'typecheck',
    'focused-terminal-jest',
    'focused-phase2-kotlin-unit-tests',
    'full-jest',
    'terminal-boundary-scan',
    'codegen-kotlin-compile',
    'kotlin-unit-tests',
    'release-assembly',
  ];
  if (
    !Array.isArray(record.checks) ||
    record.checks.length !== expectedChecks.length ||
    record.checks.some((check, index) =>
      check.id !== expectedChecks[index] ||
      check.status !== 'passed' ||
      check.exitCode !== 0 ||
      check.timedOut !== false ||
      typeof check.command !== 'string' ||
      !Array.isArray(check.args),
    )
  ) {
    fail('baseline:check_provenance_invalid');
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
    'lib/arm64-v8a/libhorus_pty.so',
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

function runConnectedPhase2Test() {
  adb(['logcat', '-c']);
  const gradle = process.platform === 'win32' ? 'gradlew.bat' : './gradlew';
  run(
    gradle,
    [
      // The project exposes instrumentation only for the debug test variant;
      // the release APK is installed and verified separately below.
      ':app:connectedDebugAndroidTest',
      `-Pandroid.testInstrumentationRunnerArguments.class=${connectedTestClass}`,
      '--no-daemon',
    ],
    {
      label: 'connected:phase2_pty_probe',
      timeout: 1_200_000,
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
    'AlpineP2DeviceTest:I',
    '*:S',
  ]);
  const markerLines = logcat
    .split(/\r?\n/)
    .map(line =>
      line.replace(/^[VDIWEF]\/AlpineP2DeviceTest(?:\(\d+\))?:\s*/, '').trim(),
    )
    .filter(Boolean);
  const observations = {};
  for (const [name, pattern] of Object.entries(expectedLogMarkerPatterns)) {
    const matches = markerLines
      .map(line => pattern.exec(line))
      .filter(match => match !== null);
    if (matches.length !== 1) {
      fail(`connected:exact_marker_count_${name}:${matches.length}`);
    }
    observations[name] = { line: markerLines.find(line => pattern.test(line)) };
    pattern.lastIndex = 0;
    const match = matches[0];
    if (name === 'ready') {
      observations.ready.sessionId = match[1];
      observations.ready.pid = Number(match[2]);
    } else if (name === 'exit') {
      observations.exit.sessionId = match[1];
      observations.exit.exitCode = 0;
      observations.exit.signal = null;
      observations.exit.reason = 'process_exit';
    } else if (name === 'stop') {
      observations.stop.sessionId = match[1];
      observations.stop.remainingProcessCount = 0;
      observations.stop.stragglerCount = 0;
      observations.stop.stoppedWithinDeadline = true;
      observations.stop.signal = match[2] === 'none' ? null : match[2];
      observations.stop.exitCode = match[3] === 'none' ? null : Number(match[3]);
      observations.stop.reason = match[4];
    }
  }
  if (observations.ready.sessionId === observations.stop.sessionId) {
    fail('connected:stop_session_reused_first_session_id');
  }
  return observations;
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
  const screenshot = adb(['exec-out', 'screencap', '-p'], {
    timeout: 60_000,
    label: 'release:screenshot',
    encoding: null,
  });
  const pngSignature = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
  if (!Buffer.isBuffer(screenshot) || !screenshot.subarray(0, 8).equals(pngSignature)) {
    fail('release:screenshot_not_png_bytes');
  }
  return screenshot;
}

function verifyReleaseArtifactUnchanged(expected) {
  if (!fs.existsSync(apkPath)) fail('artifact:release_apk_missing_after_instrumentation');
  const current = {
    sizeBytes: fs.statSync(apkPath).size,
    sha256: sha256File(apkPath),
  };
  if (current.sizeBytes !== expected.sizeBytes || current.sha256 !== expected.sha256) {
    fail('artifact:release_apk_changed_during_instrumentation');
  }
  return current;
}

try {
  currentStage = 'baseline';
  const apk = readPassingBaseline();
  currentStage = 'packaged_runtime';
  verifyPackagedRuntime();
  currentStage = 'device_selection';
  const device = findDevice();
  currentStage = 'connected_pty_probe';
  const metroRouteExisted = removeMetroReverseRoute();
  const teardown = runConnectedPhase2Test();
  verifyReleaseArtifactUnchanged(apk);
  currentStage = 'release_launch';
  const screenshot = runReleaseLaunch();

  fs.mkdirSync(artifactsDir, { recursive: true });
  // Keep each passing screenshot immutable by run. A later failure can then
  // write only p2-device-failure.json and cannot damage prior evidence.
  const screenshotFilename = `p2-device-${process.pid}-${Date.now()}.png`;
  fs.writeFileSync(path.join(artifactsDir, screenshotFilename), screenshot);
  writeAtomic('p2-device.json', {
    schema: 'alpine-terminal-poc/v1',
    gate: 'p2-device',
    status: 'passed',
    appCommit: git(['rev-parse', 'HEAD']),
    device: {
      model: device.model,
      androidApi: device.api,
      abi: device.abi,
      emulator: device.emulator,
    },
    apk,
    ptyBackend: {
      selection: 'app-owned-ndk-helper',
      library: 'lib/arm64-v8a/libhorus_pty.so',
    },
    checks: [
      {
        id: 'connected-phase2-pty-probe',
        status: 'passed',
        exitCode: 0,
        markerPatterns: Object.keys(expectedLogMarkerPatterns),
        gates: [
          'interactive /bin/sh -l through a real pty',
          'printf/stty/cat/sleep/yes behavior',
          'env: HOME=/root TERM=xterm-256color LANG=C.UTF-8',
          'escape-byte round trip intact',
          'top renders a process table and recovers from Ctrl-C',
          'ctrl-c delivered as VINTR (status 130)',
          'resize observed by stty size (24x80 -> 40x120)',
          'ordered output sequence strictly increasing',
          'exactly one exit event (exitCode=0)',
          'stop teardown: remaining=0, session-id sweep empty',
        ],
        teardownObservation: teardown,
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
    source: {
      script: 'scripts/alpine-poc/p2-device.js',
      sha256: currentScriptSha256(),
    },
    credentialsPresent: false,
  });
  console.log(
    'Alpine p2 device gate passed; evidence written to artifacts/alpine-poc/.',
  );
} catch (error) {
  const reason =
    error instanceof Error && error.safeMessage
      ? error.safeMessage
      : 'unexpected_failure';
  writeFailure(reason);
  console.error(`Alpine p2 device gate failed at ${currentStage}: ${reason}`);
  process.exit(1);
}
