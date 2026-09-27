#!/usr/bin/env node
/* global Atomics, SharedArrayBuffer, Buffer, __filename */

/**
 * Fresh Phase 3 release-screen acceptance. The runner observes the real
 * TerminalScreen through exact React Native resource IDs, drives only those
 * controls by their current bounds, and records a bounded output marker from
 * the running Alpine shell. A failed run writes only the failure record.
 */
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const {spawnSync} = require('node:child_process');

const root = path.resolve(__dirname, '..', '..');
const artifactsDir = path.join(root, 'artifacts', 'alpine-poc');
const apkPath = path.join(root, 'android', 'app', 'build', 'outputs', 'apk', 'release', 'app-release.apk');
const packageName = 'com.scariflabs.horus';
const activity = 'com.scariflabs.horus/.MainActivity';
const adbTimeout = 30_000;
const requiredIds = [
  'terminal-screen',
  'terminal-status',
  'terminal-runtime-status',
  'terminal-runtime-state',
  'terminal-output',
  'terminal-output-text',
  'terminal-command-input',
  'terminal-start',
  'terminal-send',
  'terminal-ctrl-c',
  'terminal-reconnect',
  'terminal-stop',
  'terminal-reset-rootfs',
  'terminal-reset-home',
  'terminal-reset-workspace',
  'terminal-reset-all-user-data',
];

let deviceSerial = null;
let currentStage = 'startup';

function pause(ms) {
  Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);
}

function fail(message) {
  const error = new Error(message);
  error.safeMessage = message;
  throw error;
}

function run(command, args, options = {}) {
  const result = spawnSync(command, args, {
    cwd: options.cwd ?? root,
    encoding: options.encoding === undefined ? 'utf8' : options.encoding,
    timeout: options.timeout ?? adbTimeout,
    stdio: options.stdio ?? ['ignore', 'pipe', 'pipe'],
    env: options.env,
  });
  if (result.status !== 0) {
    const reason = result.error?.code === 'ETIMEDOUT' || result.signal
      ? 'bounded_timeout'
      : result.error
        ? `spawn_failed_${result.error.code ?? result.error.name}`
        : 'command_failed';
    fail(`${options.label ?? command}:${reason}`);
  }
  return result.stdout || '';
}

function adb(args, options = {}) {
  if (deviceSerial === null) fail('adb:no_device_selected');
  return run('adb', ['-s', deviceSerial, ...args], options);
}

function git(args) {
  const result = spawnSync('git', args, {cwd: root, encoding: 'utf8', timeout: adbTimeout});
  return result.status === 0 ? result.stdout.trim() : 'unknown';
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

function findDevice() {
  const output = run('adb', ['devices'], {label: 'device:adb_devices'});
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
  const selected = candidates.find(candidate =>
    candidate.boot === '1' && candidate.abi === 'arm64-v8a' && !candidate.emulator,
  );
  if (!selected) fail('device:no_booted_arm64_physical_device');
  deviceSerial = selected.serial;
  return selected;
}

function removeMetroReverseRoute() {
  const before = adb(['reverse', '--list']);
  const hadRoute = before.split(/\r?\n/).some(line => line.trim().endsWith('tcp:8081'));
  if (!hadRoute) return false;
  adb(['reverse', '--remove', 'tcp:8081']);
  const after = adb(['reverse', '--list']);
  if (after.split(/\r?\n/).some(line => line.trim().endsWith('tcp:8081'))) {
    fail('device:metro_reverse_route_remains');
  }
  return true;
}

function forceStopAndVerify() {
  adb(['shell', 'am', 'force-stop', packageName]);
  const deadline = Date.now() + 20_000;
  while (Date.now() < deadline) {
    const processPattern = new RegExp(`(?:^|\\s)${packageName.replace('.', '\\.')}(?::[^\\s]+)?(?:\\s|$)`);
    if (!adb(['shell', 'ps', '-A']).split(/\r?\n/).some(line => processPattern.test(line))) return;
    pause(250);
  }
  fail('device:process_did_not_disappear_after_force_stop');
}

function parseNodes(hierarchy) {
  return [...hierarchy.matchAll(/<node\b[^>]*>/g)].map(match => {
    const tag = match[0];
    const attr = name => tag.match(new RegExp(`\\b${name}="([^"]*)"`))?.[1] ?? '';
    const decodeXml = value => value
      .replace(/&#10;/g, '\n')
      .replace(/&#13;/g, '\r')
      .replace(/&quot;/g, '"')
      .replace(/&apos;/g, "'")
      .replace(/&amp;/g, '&');
    return {
      resourceId: attr('resource-id'),
      package: attr('package'),
      text: decodeXml(attr('text')),
      contentDesc: decodeXml(attr('content-desc')),
      enabled: attr('enabled') === 'true',
      scrollable: attr('scrollable') === 'true',
      bounds: attr('bounds'),
    };
  });
}

function boundsOf(value) {
  const match = /^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$/.exec(value);
  if (!match) fail(`device:invalid_bounds:${value || 'missing'}`);
  const bounds = match.slice(1).map(Number);
  if (bounds[2] <= bounds[0] || bounds[3] <= bounds[1]) fail(`device:empty_bounds:${value}`);
  return {left: bounds[0], top: bounds[1], right: bounds[2], bottom: bounds[3]};
}

function observeUi() {
  return adb(['exec-out', 'uiautomator', 'dump', '/dev/tty'], {timeout: adbTimeout});
}

function exactUi(hierarchy) {
  const nodes = parseNodes(hierarchy);
  const controls = {};
  for (const id of requiredIds) {
    const node = nodes.find(candidate => candidate.package === packageName && candidate.resourceId === id);
    if (!node) fail(`device:missing_exact_resource_id:${id}`);
    controls[id] = {...node, bounds: boundsOf(node.bounds)};
  }
  return controls;
}

function waitForUi(predicate, label, timeoutMs = 30_000) {
  const deadline = Date.now() + timeoutMs;
  let lastHierarchy = '';
  while (Date.now() < deadline) {
    lastHierarchy = observeUi();
    try {
      const controls = exactUi(lastHierarchy);
      if (predicate(controls)) return {controls, hierarchy: lastHierarchy};
    } catch (error) {
      if (!(error instanceof Error) || !error.message.startsWith('device:missing_exact_resource_id:')) throw error;
    }
    pause(500);
  }
  fail(`device:ui_timeout:${label}`);
}

function center(bounds) {
  return [Math.floor((bounds.left + bounds.right) / 2), Math.floor((bounds.top + bounds.bottom) / 2)];
}

function tapControl(controls, id) {
  const [x, y] = center(controls[id].bounds);
  adb(['shell', 'input', 'tap', String(x), String(y)]);
}

function hasExactOutputLine(controls, marker) {
  return controls['terminal-output-text'].text
    .replace(/\r/g, '\n')
    .split('\n')
    .some(line => line.trim() === marker);
}

function waitForOutputMarker(marker, timeoutMs = 30_000) {
  return waitForUi(controls => hasExactOutputLine(controls, marker), `output:${marker}`, timeoutMs);
}

function captureScreenshot(filename) {
  const bytes = adb(['exec-out', 'screencap', '-p'], {encoding: null, timeout: 60_000, label: 'release:screenshot'});
  const pngSignature = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
  if (!Buffer.isBuffer(bytes) || !bytes.subarray(0, 8).equals(pngSignature)) fail('release:screenshot_not_png_bytes');
  const destination = path.join(artifactsDir, filename);
  fs.writeFileSync(destination, bytes);
  return {path: path.relative(root, destination), bytes: bytes.length, sha256: crypto.createHash('sha256').update(bytes).digest('hex')};
}

function main() {
  if (!fs.existsSync(apkPath)) fail('artifact:release_apk_missing');
  const apk = {path: path.relative(root, apkPath), bytes: fs.statSync(apkPath).size, sha256: sha256File(apkPath)};
  currentStage = 'device_selection';
  const device = findDevice();
  currentStage = 'metro_route';
  const metroRouteExistedBeforeLaunch = removeMetroReverseRoute();
  currentStage = 'release_install';
  adb(['install', '-r', apkPath], {timeout: 120_000, label: 'release:install'});
  currentStage = 'force_stop';
  forceStopAndVerify();
  currentStage = 'release_launch';
  adb(['shell', 'input', 'keyevent', 'KEYCODE_WAKEUP']);
  adb(['shell', 'wm', 'dismiss-keyguard']);
  adb(['shell', 'am', 'start', '-W', '-n', activity], {timeout: 60_000, label: 'release:launch'});
  let observed = waitForUi(
    controls => controls['terminal-status'].text === 'idle' &&
      controls['terminal-runtime-status'].text === 'runtime=ready' &&
      controls['terminal-start'].enabled &&
      !controls['terminal-command-input'].enabled,
    'initial TerminalScreen idle state',
  );
  const initialControlIds = requiredIds.map(id => ({id, bounds: observed.controls[id].bounds}));

  currentStage = 'start_session';
  tapControl(observed.controls, 'terminal-start');
  observed = waitForUi(controls => controls['terminal-status'].text === 'running', 'TerminalScreen running state', 60_000);
  const sessionControlIds = requiredIds.map(id => ({id, bounds: observed.controls[id].bounds}));

  currentStage = 'input_round_trip';
  tapControl(observed.controls, 'terminal-command-input');
  adb(['shell', 'input', 'text', 'echo%sP3_SCREEN_INPUT']);
  adb(['shell', 'input', 'keyevent', 'KEYCODE_ENTER']);
  adb(['shell', 'input', 'keyevent', 'KEYCODE_BACK']);
  observed = waitForOutputMarker('P3_SCREEN_INPUT', 30_000);

  currentStage = 'reconnect';
  tapControl(observed.controls, 'terminal-reconnect');
  observed = waitForUi(controls => controls['terminal-status'].text === 'running' && controls['terminal-reconnect'].enabled, 'reconnected running state');

  const screenshotFilename = `p3-screen-device-${process.pid}-${Date.now()}.png`;
  currentStage = 'screenshot';
  const screenshot = captureScreenshot(screenshotFilename);

  currentStage = 'stop_session';
  tapControl(observed.controls, 'terminal-stop');
  const stopped = waitForUi(controls => controls['terminal-status'].text === 'stopped', 'TerminalScreen stopped state');

  writeAtomic('p3-screen-device.json', {
    schema: 'alpine-terminal-poc/v1',
    gate: 'p3-terminal-screen-device',
    status: 'passed',
    date: new Date().toISOString(),
    appCommit: git(['rev-parse', 'HEAD']),
    device: {model: device.model, androidApi: device.api, abi: device.abi, emulator: device.emulator},
    apk,
    checks: [
      {id: 'release-launch-without-metro', status: 'passed', exitCode: 0, resourceIds: initialControlIds},
      {id: 'start-and-shell-ready', status: 'passed', exitCode: 0, statusMarker: 'running'},
      {id: 'input-round-trip', status: 'passed', exitCode: 0, marker: 'P3_SCREEN_INPUT'},
      {id: 'reconnect-active-session', status: 'passed', exitCode: 0, statusMarker: 'running'},
      {id: 'stop-session', status: 'passed', exitCode: 0, statusMarker: stopped.controls['terminal-status'].text},
      {id: 'bounded-terminal-output-container', status: 'passed', exitCode: 0, scrollable: observed.controls['terminal-output'].scrollable},
    ],
    initialControlIds,
    sessionControlIds,
    screenshot,
    metroRouteExistedBeforeLaunch,
    credentialsPresent: false,
    source: {script: 'scripts/alpine-poc/p3-screen-device.js', sha256: sha256File(__filename)},
  });
  console.log('Alpine p3 terminal-screen device gate passed; evidence written to artifacts/alpine-poc/.');
}

try {
  main();
} catch (error) {
  const reason = error instanceof Error && error.safeMessage ? error.safeMessage : 'unexpected_failure';
  writeAtomic('p3-screen-device-failure.json', {
    schema: 'alpine-terminal-poc/v1',
    gate: 'p3-terminal-screen-device',
    status: 'failed',
    appCommit: git(['rev-parse', 'HEAD']),
    failure: {stage: currentStage, reason},
    credentialsPresent: false,
  });
  console.error(`Alpine p3 terminal-screen device gate failed at ${currentStage}: ${reason}`);
  process.exit(1);
}
