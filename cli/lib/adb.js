'use strict';

const {execFile} = require('node:child_process');

const DEFAULT_APP_ID = 'com.scariflabs.horus';

function appId() {
  return process.env.HORUS_APP_ID || DEFAULT_APP_ID;
}

function adbPath() {
  return process.env.ADB || 'adb';
}

/** Runs adb and resolves with stdout; rejects with a readable message. */
function adb(args, {serial, input, mergeStderr = false} = {}) {
  const fullArgs = serial ? ['-s', serial, ...args] : args;
  return new Promise((resolve, reject) => {
    const child = execFile(adbPath(), fullArgs, {maxBuffer: 16 * 1024 * 1024}, (error, stdout, stderr) => {
      if (error) {
        if (error.code === 'ENOENT') {
          reject(new Error('adb was not found. Install Android platform-tools or set ADB=/path/to/adb.'));
          return;
        }
        reject(new Error((stderr || stdout || error.message).trim()));
        return;
      }
      resolve(mergeStderr ? `${stdout}${stderr}` : stdout);
    });
    if (input !== undefined) child.stdin.end(input);
  });
}

/** `adb devices -l` → [{serial, state, model}] */
function parseDevices(output) {
  return output
    .split('\n')
    .slice(1)
    .map(line => line.trim())
    .filter(line => line.length > 0 && !line.startsWith('*'))
    .map(line => {
      const [serial, state, ...rest] = line.split(/\s+/);
      const model = rest.find(field => field.startsWith('model:'));
      return {serial, state, model: model ? model.slice('model:'.length).replace(/_/g, ' ') : ''};
    });
}

async function listDevices() {
  return parseDevices(await adb(['devices', '-l']));
}

/**
 * The phone answers `content call` with `Result: Bundle[{json={...}}]`.
 * Everything Horus returns lives in that one JSON string.
 */
function parseContentResult(output) {
  const match = /Result: Bundle\[\{json=(\{.*\})\}\]\s*$/s.exec(output.trim());
  if (!match) {
    if (/Unknown authority|Could not find provider|Error while accessing provider/i.test(output)) {
      const error = new Error('Horus is not installed on this phone, or it is too old for remote access.');
      error.code = 'not_installed';
      throw error;
    }
    if (/SecurityException/.test(output)) throw new Error('The phone refused the request.');
    throw new Error(`Unexpected answer from the phone: ${output.trim().slice(0, 200)}`);
  }
  return JSON.parse(match[1]);
}

/** Calls the Horus provider. The request travels as one base64 JSON argument. */
async function callHorus(serial, method, request = {}) {
  const arg = Buffer.from(JSON.stringify(request), 'utf8').toString('base64');
  const command = ['shell', 'content', 'call', '--uri', `content://${appId()}.remote`, '--method', method, '--arg', arg];
  let output;
  try {
    // `content` reports provider errors on stderr but still exits 0.
    output = await adb(command, {serial, mergeStderr: true});
  } catch (error) {
    // Older adb/Android print provider errors on stderr with a failing exit.
    return parseContentResult(error.message);
  }
  return parseContentResult(output);
}

/** `adb forward --list` → [{serial, local, remote}] */
function parseForwards(output) {
  return output
    .split('\n')
    .map(line => line.trim().split(/\s+/))
    .filter(fields => fields.length === 3)
    .map(([serial, local, remote]) => ({serial, local, remote}));
}

/** Reuses this phone's forward to the SSH port, or makes one on a free port. */
async function ensureForward(serial, remotePort) {
  const remote = `tcp:${remotePort}`;
  const existing = parseForwards(await adb(['forward', '--list'])).find(
    entry => entry.serial === serial && entry.remote === remote && entry.local.startsWith('tcp:'),
  );
  if (existing) return Number(existing.local.slice(4));
  const allocated = (await adb(['forward', 'tcp:0', remote], {serial})).trim();
  const port = Number(allocated);
  if (!Number.isInteger(port) || port <= 0) throw new Error(`adb could not forward a port: ${allocated}`);
  return port;
}

async function forward(serial, localPort, remotePort) {
  await adb(['forward', `tcp:${localPort}`, `tcp:${remotePort}`], {serial});
}

async function openApp(serial) {
  await adb(['shell', 'am', 'start', '-n', `${appId()}/.MainActivity`], {serial});
}

module.exports = {
  adb,
  appId,
  callHorus,
  ensureForward,
  forward,
  listDevices,
  openApp,
  parseContentResult,
  parseDevices,
  parseForwards,
};
