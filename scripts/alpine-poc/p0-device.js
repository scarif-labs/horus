#!/usr/bin/env node
/* global SharedArrayBuffer, Atomics */

/**
 * Alpine terminal PoC Phase 0 device gate.
 *
 * 1. Installs the release APK, removes any Metro reverse route, force-stops,
 *    relaunches, and waits for the Alpine terminal shell. This proves
 *    the branch still ships a release app that launches without Metro.
 * 2. Runs the focused connected test for the new terminal namespace so the
 *    no-op status call is proven on the real module object.
 *
 * Writes artifacts/alpine-poc/p0-device.json plus a screenshot. No device
 * serials, user paths, or payloads are recorded.
 */
const fs = require('node:fs');
const path = require('node:path');
const {spawnSync} = require('node:child_process');

const root = path.resolve(__dirname, '..', '..');
const artifactsDir = path.join(root, 'artifacts', 'alpine-poc');
const apk = path.join(root, 'android', 'app', 'build', 'outputs', 'apk', 'release', 'app-release.apk');
const packageName = 'com.scariflabs.horus';
const connectedTestClass = 'com.scariflabs.horus.terminal.TerminalRuntimeDeviceTest';
const timeout = 30_000;
// The Alpine terminal shell must appear without any Metro route.
const expectedMarkers = ['terminal-screen', 'terminal-status', 'terminal-start'];

let deviceSerial;

function adb(args, options = {}) {
  const result = spawnSync('adb', ['-s', deviceSerial, ...args], {
    cwd: root,
    encoding: 'utf8',
    timeout: options.timeout ?? timeout,
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  if (result.status !== 0) {
    throw new Error(`adb ${args[0]} failed with exit ${result.status ?? 1}`);
  }
  return result.stdout || '';
}

/**
 * Removes any Metro reverse route without failing when none exists. `adb
 * reverse --remove tcp:8081` exits 1 if the route was never set, which is
 * already the desired no-Metro state. Returns whether a route existed.
 */
function removeMetroReverseRoute() {
  const before = adb(['reverse', '--list']);
  if (!(before.includes('tcp:8081'))) {
    return false;
  }
  adb(['reverse', '--remove', 'tcp:8081']);
  return !adb(['reverse', '--list']).includes('tcp:8081');
}

function findDevice() {
  const devices = spawnSync('adb', ['devices'], {cwd: root, encoding: 'utf8', timeout});
  if (devices.status !== 0) throw new Error('adb devices failed');
  const serials = (devices.stdout || '').split(/\r?\n/).slice(1)
    .map(line => line.trim().split(/\s+/))
    .filter(parts => parts.length >= 2 && parts[1] === 'device')
    .map(parts => parts[0]);
  if (serials.length === 0) throw new Error('no attached Android device; connect the ARM64 test device first');
  const property = (serial, key) => {
    const value = spawnSync('adb', ['-s', serial, 'shell', 'getprop', key], {
      cwd: root, encoding: 'utf8', timeout: 5_000,
    });
    return value.status === 0 ? (value.stdout || '').trim() : '';
  };
  const candidates = serials.map(serial => ({
    serial,
    api: Number(property(serial, 'ro.build.version.sdk')),
    abi: property(serial, 'ro.product.cpu.abi'),
    boot: property(serial, 'sys.boot_completed'),
    qemu: property(serial, 'ro.kernel.qemu') === '1',
  }));
  const selected =
    candidates.find(c => c.boot === '1' && c.abi === 'arm64-v8a' && !c.qemu) ??
    candidates.find(c => c.boot === '1');
  if (!selected) throw new Error('no booted Android device found');
  if (selected.abi !== 'arm64-v8a') {
    throw new Error(`device ABI ${selected.abi} is not arm64-v8a; the PoC requires an ARM64 device`);
  }
  return selected;
}

function waitForMarkers() {
  const waitUntil = Date.now() + 20_000;
  let hierarchy = '';
  while (Date.now() < waitUntil) {
    const dump = spawnSync('adb', ['-s', deviceSerial, 'exec-out', 'uiautomator', 'dump', '/dev/tty'], {
      cwd: root, encoding: 'utf8', timeout,
    });
    hierarchy = dump.stdout || '';
    if (expectedMarkers.every(marker => hierarchy.includes(marker))) return;
    const shared = new SharedArrayBuffer(4);
    Atomics.wait(new Int32Array(shared), 0, 0, 500);
  }
  const missing = expectedMarkers.filter(marker => !hierarchy.includes(marker));
  throw new Error(`release launch missing ${missing.join(', ')}`);
}

function runConnectedTerminalTest() {
  const result = spawnSync(
    process.platform === 'win32' ? 'gradlew.bat' : './gradlew',
    [
      ':app:connectedDebugAndroidTest',
      `-Pandroid.testInstrumentationRunnerArguments.class=${connectedTestClass}`,
      '--no-daemon',
    ],
    {
      cwd: path.join(root, 'android'),
      stdio: 'inherit',
      env: {...process.env, ANDROID_SERIAL: deviceSerial},
      timeout: 600_000,
    },
  );
  if (result.status !== 0) {
    throw new Error(`connected terminal test failed with exit ${result.status ?? 1}`);
  }
}

try {
  if (!fs.existsSync(apk)) throw new Error('release APK is missing; run scripts/alpine-poc/p0-baseline.js first');
  const device = findDevice();
  deviceSerial = device.serial;

  // Release launch without Metro.
  const metroRouteExisted = removeMetroReverseRoute();
  adb(['install', '-r', apk], {timeout: 120_000});
  adb(['shell', 'am', 'force-stop', packageName]);
  adb(['shell', 'monkey', '-p', packageName, '1']);
  waitForMarkers();
  const screenshot = adb(['exec-out', 'screencap', '-p'], {timeout: 60_000});

  // Focused connected proof of the no-op status call on the real module.
  runConnectedTerminalTest();

  fs.mkdirSync(artifactsDir, {recursive: true});
  const head = spawnSync('git', ['rev-parse', 'HEAD'], {cwd: root, encoding: 'utf8', timeout});
  const record = {
    schema: 'alpine-terminal-poc/v1',
    gate: 'p0-device',
    status: 'passed',
    appCommit: head.status === 0 ? head.stdout.trim() : 'unknown',
    device: {
      model: adb(['shell', 'getprop', 'ro.product.model']).trim(),
      androidApi: device.api,
      abi: device.abi,
    },
    checks: [
      {id: 'release-launch-without-metro', status: 'passed', marker: expectedMarkers.join('+')},
      {id: 'terminal-runtime-noop-status-connected', status: 'passed', marker: connectedTestClass},
    ],
    metroRouteExistedBeforeLaunch: metroRouteExisted,
    emulator: device.qemu,
    screenshot: 'artifacts/alpine-poc/p0-device.png',
    credentialsPresent: false,
  };
  fs.writeFileSync(path.join(artifactsDir, 'p0-device.png'), screenshot);
  fs.writeFileSync(path.join(artifactsDir, 'p0-device.json'), `${JSON.stringify(record, null, 2)}\n`);
  console.log('Alpine p0 device gate passed; evidence written to artifacts/alpine-poc/.');
} catch (error) {
  console.error(`Alpine p0 device gate failed: ${error instanceof Error ? error.message : String(error)}`);
  process.exit(1);
}
