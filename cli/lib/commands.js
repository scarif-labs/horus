'use strict';

const fs = require('node:fs');
const net = require('node:net');
const os = require('node:os');
const path = require('node:path');
const {spawn} = require('node:child_process');
const adb = require('./adb');
const state = require('./state');
const {hostKeyAlias, parseRemotePath, shellQuote, sshConfigBlock, sshOptions} = require('./ssh');

const START_TIMEOUT_MS = 10 * 60 * 1000;
const POLL_MS = 1500;
const THERMAL = ['none', 'light', 'moderate', 'severe', 'critical', 'emergency', 'shutdown'];

class UsageError extends Error {}

function log(message) {
  process.stderr.write(`${message}\n`);
}

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

/** Connected, authorized phones with this computer's pairing state. */
async function discover() {
  const key = state.ensureKey();
  const names = state.readDevices();
  const connected = (await adb.listDevices()).filter(device => device.state === 'device');
  return Promise.all(connected.map(async device => {
    let status = null;
    let problem = null;
    try {
      status = await adb.callHorus(device.serial, 'status', {fingerprint: key.fingerprint});
    } catch (error) {
      problem = error.message;
    }
    return {
      serial: device.serial,
      model: device.model,
      name: names[device.serial]?.name ?? null,
      status,
      problem,
      paired: status?.authorized === true,
    };
  }));
}

/** Picks one phone by name or serial, or the only candidate if none is named. */
async function resolveDevice(wanted, {requirePaired = true} = {}) {
  const devices = await discover();
  if (wanted) {
    const match = devices.find(device => device.name === wanted || device.serial === wanted);
    if (!match) {
      const known = Object.entries(state.readDevices()).find(([serial, entry]) => entry.name === wanted || serial === wanted);
      throw new UsageError(known ? `${wanted} is not connected. Plug it in over USB and allow USB debugging.` : `No phone called ${wanted}. Run \`horus devices\`.`);
    }
    if (requirePaired && !match.paired) throw new UsageError(`${wanted} is not paired with this computer. Run \`horus pair ${wanted}\`.`);
    return match;
  }
  const candidates = devices.filter(device => (requirePaired ? device.paired : device.status !== null));
  if (candidates.length === 1) return candidates[0];
  if (candidates.length === 0) {
    if (devices.length === 0) throw new UsageError('No phone is connected. Plug one in over USB and allow USB debugging.');
    const reason = devices.find(device => device.problem)?.problem;
    throw new UsageError(requirePaired ? 'No paired phone is connected. Run `horus pair` first.' : reason ?? 'Horus is not installed on the connected phone.');
  }
  throw new UsageError(`Several phones are connected: ${candidates.map(device => device.name ?? device.serial).join(', ')}. Name one.`);
}

/**
 * Makes sure the SSH server is up: restarts it after a reboot and opens Horus
 * if Android will not start it from the background. Returns the latest status.
 */
async function ensureServer(device, {quiet = false} = {}) {
  const say = quiet ? () => undefined : log;
  const deadline = Date.now() + START_TIMEOUT_MS;
  let status = device.status;
  let announced = '';
  let openedApp = false;
  let asked = false;
  while (Date.now() < deadline) {
    if (!status.enabled) {
      throw new UsageError(`Remote access is off on ${device.name ?? device.serial}. Turn it on in Horus → Settings, or run \`horus pair\` again.`);
    }
    if (status.state === 'running') return status;
    if (status.state === 'failed' && asked) {
      throw new UsageError(`The SSH server on the phone failed (${status.detail || 'unknown'}). Run \`horus status --log\` for details.`);
    }
    if ((status.state === 'stopped' || status.state === 'failed') && !asked) {
      asked = true;
      const result = await adb.callHorus(device.serial, 'start');
      if (result.status === 'start_refused' && !openedApp) {
        say('Android would not start remote access in the background; opening Horus on the phone…');
        openedApp = true;
        await adb.openApp(device.serial).catch(() => undefined);
      }
    }
    if (status.state === 'installing' && announced !== 'installing') {
      announced = 'installing';
      say('Installing the SSH server on the phone (first time only)…');
    }
    await sleep(POLL_MS);
    status = await adb.callHorus(device.serial, 'status');
  }
  throw new UsageError('Timed out waiting for the SSH server on the phone.');
}

async function connection(device, options) {
  const status = await ensureServer(device, options);
  const port = await adb.ensureForward(device.serial, status.port);
  const files = state.paths();
  return {
    port,
    username: status.username,
    options: sshOptions({port, key: files.key, knownHosts: files.knownHosts, serial: device.serial}),
  };
}

function run(command, args, {stdio = 'inherit'} = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, {stdio});
    child.on('error', error => {
      reject(error.code === 'ENOENT' ? new Error(`${command} was not found. Install OpenSSH${command === 'rsync' ? ' and rsync' : ''}.`) : error);
    });
    child.on('exit', (code, signal) => resolve(signal ? 128 + (os.constants.signals[signal] ?? 1) : code ?? 1));
  });
}

async function readPassword(prompt) {
  if (process.env.HORUS_PASSWORD) return process.env.HORUS_PASSWORD;
  const input = process.stdin;
  if (!input.isTTY) {
    // Piped: the first line is the password.
    let data = '';
    for await (const chunk of input) {
      data += chunk;
      if (data.includes('\n')) break;
    }
    return data.split('\n')[0].replace(/\r$/, '');
  }
  process.stderr.write(prompt);
  input.setRawMode(true);
  input.resume();
  input.setEncoding('utf8');
  return new Promise((resolve, reject) => {
    let value = '';
    const onData = text => {
      for (const character of text) {
        if (character === '\r' || character === '\n') {
          finish();
          resolve(value);
          return;
        }
        if (character === '\u0003') {
          finish();
          reject(new UsageError('Cancelled.'));
          return;
        }
        if (character === '\u007f' || character === '\b') value = value.slice(0, -1);
        else value += character;
      }
    };
    const finish = () => {
      input.removeListener('data', onData);
      input.setRawMode(false);
      input.pause();
      process.stderr.write('\n');
    };
    input.on('data', onData);
  });
}

function label(device) {
  return device.name ?? device.serial;
}

// ---------------------------------------------------------------------------

async function devicesCommand() {
  const devices = await discover();
  if (devices.length === 0) {
    log('No phone is connected over USB. Plug one in and allow USB debugging.');
    return 0;
  }
  const rows = devices.map(device => {
    const status = device.status;
    let summary;
    if (!status) summary = device.problem;
    else if (!status.configured) summary = 'Horus is not set up yet';
    else if (!device.paired) summary = 'not paired (run `horus pair`)';
    else if (!status.enabled) summary = 'paired, remote access off';
    else summary = `paired, server ${status.state}`;
    const battery = typeof status?.battery === 'number' ? `${status.battery}%${status.charging ? '+' : ''}` : '';
    return [device.name ?? '-', device.serial, status?.model ?? device.model, battery, summary];
  });
  const header = ['NAME', 'SERIAL', 'MODEL', 'BATTERY', 'STATUS'];
  const widths = header.map((title, column) => Math.max(title.length, ...rows.map(row => String(row[column]).length)));
  for (const row of [header, ...rows]) {
    process.stdout.write(`${row.map((cell, column) => String(cell).padEnd(widths[column])).join('  ').trimEnd()}\n`);
  }
  return 0;
}

async function pairCommand(args) {
  const device = await resolveDevice(args.positional[0], {requirePaired: false});
  const status = device.status;
  if (!status) throw new UsageError(device.problem);
  if (!status.configured) throw new UsageError('Open Horus on the phone and finish setup first.');
  const key = state.ensureKey();
  const devices = state.readDevices();
  const requested = args.flags.name;
  if (requested !== undefined && !state.isValidName(requested)) {
    throw new UsageError('Names use lowercase letters, digits, and dashes (up to 32).');
  }
  const name = requested ?? devices[device.serial]?.name ?? state.uniqueName(status.model || device.model, devices, device.serial);
  if (requested && Object.entries(devices).some(([serial, entry]) => serial !== device.serial && entry.name === requested)) {
    throw new UsageError(`Another phone is already called ${requested}.`);
  }

  log(`Pairing ${status.model} (${device.serial}) as "${name}".`);
  const password = await readPassword('Horus password on the phone: ');
  const result = await adb.callHorus(device.serial, 'pair', {
    password,
    keyType: key.type,
    keyData: key.data,
    label: state.computerLabel(),
  });
  switch (result.status) {
    case 'ok':
      break;
    case 'incorrect':
      throw new UsageError('That is not the Horus password. Too many wrong tries lock pairing and the app for a while.');
    case 'locked':
      throw new UsageError(`Too many wrong passwords. Try again in ${Math.ceil(result.retryAfterMs / 1000)} seconds.`);
    case 'not_configured':
      throw new UsageError('Open Horus on the phone and finish setup first.');
    default:
      throw new UsageError(`Pairing failed: ${result.status}`);
  }
  devices[device.serial] = {name, model: status.model};
  state.writeDevices(devices);
  log(`Paired. The phone now trusts this computer's key ${result.fingerprint}.`);

  // Pairing happens over USB, so it may re-trust a phone whose host key
  // changed (Horus reinstalled or its data cleared). Other connections still
  // refuse a changed key.
  const files = state.paths();
  if (fs.existsSync(files.knownHosts)) {
    await run('ssh-keygen', ['-R', hostKeyAlias(device.serial), '-f', files.knownHosts], {stdio: 'ignore'}).catch(() => undefined);
  }
  const paired = {...device, name, paired: true, status: await adb.callHorus(device.serial, 'status')};
  await ensureServer(paired);
  const {options, username} = await connection(paired, {quiet: true});
  // Pin the phone's host key now, over USB, so later connections are checked.
  await run('ssh', [...options, '-o', 'BatchMode=yes', `${username}@127.0.0.1`, 'true'], {stdio: 'ignore'});
  if (status.batteryUnrestricted === false) {
    log('Tip: allow Horus unrestricted battery use on the phone so Android does not pause it.');
  }
  if (status.sdk >= 31) {
    log('Tip: Android may kill busy background processes. Run `horus tune` once to lift that limit.');
  }
  log(`Ready. Open a shell with: horus ssh ${name}`);
  return 0;
}

async function sshCommand(args) {
  const device = await resolveDevice(args.positional[0]);
  const {options, username} = await connection(device);
  // Like ssh: arguments are joined with spaces and run by the phone's shell.
  const remote = args.rest.length > 0 ? [args.rest.join(' ')] : [];
  // A one-off command gets a terminal only when there is one to give it.
  const tty = args.rest.length > 0 && process.stdin.isTTY ? ['-t'] : [];
  return run('ssh', [...options, ...tty, `${username}@127.0.0.1`, ...remote]);
}

/** Runs one command on one or all paired phones; output lines are prefixed. */
async function execCommand(args) {
  if (args.rest.length === 0) throw new UsageError('Usage: horus exec <phone|--all> -- <command>');
  const targets = args.flags.all
    ? (await discover()).filter(device => device.paired)
    : [await resolveDevice(args.positional[0])];
  if (targets.length === 0) throw new UsageError('No paired phone is connected.');
  const command = args.rest.join(' ');
  if (targets.length === 1) {
    const {options, username} = await connection(targets[0]);
    return run('ssh', [...options, `${username}@127.0.0.1`, command]);
  }
  const width = Math.max(...targets.map(device => label(device).length));
  const codes = await Promise.all(targets.map(async device => {
    const prefix = `${label(device).padEnd(width)} | `;
    try {
      const {options, username} = await connection(device, {quiet: true});
      return await new Promise(resolve => {
        const child = spawn('ssh', [...options, '-o', 'BatchMode=yes', `${username}@127.0.0.1`, command], {stdio: ['ignore', 'pipe', 'pipe']});
        const pipe = (stream, target) => {
          let buffered = '';
          stream.setEncoding('utf8');
          stream.on('data', text => {
            buffered += text;
            const lines = buffered.split('\n');
            buffered = lines.pop();
            for (const line of lines) target.write(`${prefix}${line}\n`);
          });
          stream.on('end', () => { if (buffered) target.write(`${prefix}${buffered}\n`); });
        };
        pipe(child.stdout, process.stdout);
        pipe(child.stderr, process.stderr);
        child.on('error', () => resolve(1));
        child.on('exit', code => resolve(code ?? 1));
      });
    } catch (error) {
      process.stderr.write(`${prefix}${error.message}\n`);
      return 1;
    }
  }));
  return codes.find(code => code !== 0) ?? 0;
}

/** scp with `phone:path` on one side. */
async function cpCommand(args) {
  if (args.positional.length < 2) throw new UsageError('Usage: horus cp [-r] <src>... <dst>   (use phone:path for the phone side)');
  const specs = args.positional.map(parseRemotePath);
  const remoteNames = [...new Set(specs.filter(spec => spec.device).map(spec => spec.device))];
  if (remoteNames.length !== 1) throw new UsageError('Exactly one phone must appear, as phone:path.');
  const device = await resolveDevice(remoteNames[0]);
  const {options, username} = await connection(device);
  const operands = specs.map(spec => (spec.device ? `${username}@127.0.0.1:${spec.path}` : spec.path));
  return run('scp', [...options, ...(args.flags.r ? ['-r'] : []), ...operands]);
}

/** rsync over the same pinned SSH connection. */
async function syncCommand(args) {
  if (args.positional.length !== 2) throw new UsageError('Usage: horus sync <src> <dst>   (use phone:path for the phone side)');
  const specs = args.positional.map(parseRemotePath);
  const remote = specs.filter(spec => spec.device);
  if (remote.length !== 1) throw new UsageError('Exactly one side must be phone:path.');
  const device = await resolveDevice(remote[0].device);
  const {options, username} = await connection(device);
  const sshCommandLine = ['ssh', ...options].map(shellQuote).join(' ');
  const operands = specs.map(spec => (spec.device ? `${username}@127.0.0.1:${spec.path}` : spec.path));
  const extra = args.flags.delete ? ['--delete'] : [];
  return run('rsync', ['-az', ...extra, '-e', sshCommandLine, ...operands]);
}

async function forwardCommand(args) {
  const [wanted, remoteText, localText] = args.positional.length >= 2 && !/^\d+$/.test(args.positional[0])
    ? args.positional
    : [undefined, ...args.positional];
  const remotePort = Number(remoteText);
  const localPort = localText === undefined ? remotePort : Number(localText);
  if (!Number.isInteger(remotePort) || remotePort < 1 || remotePort > 65535 || !Number.isInteger(localPort) || localPort < 1 || localPort > 65535) {
    throw new UsageError('Usage: horus forward [phone] <phone-port> [local-port]');
  }
  const device = await resolveDevice(wanted);
  await adb.forward(device.serial, localPort, remotePort);
  log(`http://localhost:${localPort} → ${label(device)} port ${remotePort} (until the phone is unplugged)`);
  return 0;
}

/** Writes ~/.horus/ssh_config so plain `ssh <phone>` and VS Code work. */
async function configCommand(args) {
  const devices = await discover();
  const saved = state.readDevices();
  const files = state.paths();
  const horusCommand = process.argv[1] ? `${shellQuote(process.execPath)} ${shellQuote(path.resolve(process.argv[1]))}` : 'horus';
  const known = new Map(devices.filter(device => device.paired).map(device => [device.serial, device.status.username]));
  const blocks = Object.entries(saved).map(([serial, entry]) => sshConfigBlock({
    name: entry.name,
    serial,
    username: known.get(serial) ?? 'horus',
    key: files.key,
    knownHosts: files.knownHosts,
    horusCommand,
  }));
  if (blocks.length === 0) throw new UsageError('No phone is paired yet. Run `horus pair` first.');
  state.ensureHome();
  fs.writeFileSync(files.sshConfig, `# Written by \`horus config\`. Re-run it after pairing another phone.\n\n${blocks.join('\n')}`, {mode: 0o600});
  log(`Wrote ${files.sshConfig} with ${blocks.length} phone(s).`);
  const include = `Include ${files.sshConfig}`;
  const userConfig = path.join(os.homedir(), '.ssh', 'config');
  const existing = fs.existsSync(userConfig) ? fs.readFileSync(userConfig, 'utf8') : '';
  if (existing.split('\n').some(line => line.trim() === include)) {
    log('~/.ssh/config already includes it.');
  } else if (args.flags.install) {
    fs.mkdirSync(path.dirname(userConfig), {recursive: true, mode: 0o700});
    // Include must come before any Host block to apply everywhere.
    fs.writeFileSync(userConfig, `${include}\n\n${existing}`, {mode: 0o600});
    log('Added it to the top of ~/.ssh/config.');
  } else {
    log(`Add this line to the top of ~/.ssh/config (or re-run with --install):\n  ${include}`);
  }
  log(`Then: ssh ${Object.values(saved)[0].name}  (also works in VS Code Remote-SSH)`);
  return 0;
}

/** ProxyCommand helper: pipes stdin/stdout to the phone's SSH port. */
async function proxyCommand(args) {
  const device = await resolveDevice(args.positional[0]);
  const {port} = await connection(device, {quiet: true});
  return new Promise(resolve => {
    const socket = net.connect(port, '127.0.0.1');
    socket.on('connect', () => {
      process.stdin.pipe(socket);
      socket.pipe(process.stdout);
    });
    socket.on('error', error => {
      log(`horus proxy: ${error.message}`);
      resolve(1);
    });
    socket.on('close', () => resolve(0));
  });
}

async function statusCommand(args) {
  const device = await resolveDevice(args.positional[0], {requirePaired: false});
  const status = device.status;
  if (!status) throw new UsageError(device.problem);
  const lines = [
    ['Phone', `${status.model} (${device.serial}), Android SDK ${status.sdk}`],
    ['Name', device.name ?? '(not paired from this computer)'],
    ['Horus', status.appVersion],
    ['Paired', device.paired ? 'yes' : 'no'],
    ['Remote access', status.enabled ? `on, server ${status.state}${status.detail ? ` (${status.detail})` : ''}` : 'off'],
    ['Paired computers', String(status.keys)],
    ['Battery', typeof status.battery === 'number' ? `${status.battery}%${status.charging ? ', charging' : ''}${status.batteryUnrestricted ? '' : ', optimized by Android'}` : 'unknown'],
    ['Temperature', typeof status.thermal === 'number' ? THERMAL[status.thermal] ?? String(status.thermal) : 'unknown'],
  ];
  const width = Math.max(...lines.map(([title]) => title.length));
  for (const [title, value] of lines) process.stdout.write(`${title.padEnd(width)}  ${value}\n`);
  if (args.flags.log) {
    const result = await adb.callHorus(device.serial, 'log');
    process.stdout.write(`\n--- server log ---\n${result.log || '(empty)'}\n`);
  }
  return 0;
}

async function unpairCommand(args) {
  const device = await resolveDevice(args.positional[0]);
  const key = state.ensureKey();
  const result = await adb.callHorus(device.serial, 'unpair', {fingerprint: key.fingerprint});
  const devices = state.readDevices();
  delete devices[device.serial];
  state.writeDevices(devices);
  log(result.status === 'ok' ? `${label(device)} no longer trusts this computer.` : `${label(device)} did not have this computer's key.`);
  return 0;
}

async function stopCommand(args) {
  const device = await resolveDevice(args.positional[0]);
  await adb.callHorus(device.serial, 'stop');
  log(`Remote access is off on ${label(device)}. Turn it back on in Horus → Settings or with \`horus pair\`.`);
  return 0;
}

/**
 * Android 12+ kills background child processes beyond a small global limit,
 * which cuts builds and agents short. This lifts it for the whole phone.
 */
async function tuneCommand(args) {
  const device = await resolveDevice(args.positional[0], {requirePaired: false});
  const sdk = device.status?.sdk ?? 0;
  if (sdk < 31) {
    log('This Android version has no phantom process limit. Nothing to do.');
    return 0;
  }
  log(`Lifting Android's background process limit on ${label(device)} (affects every app on the phone):`);
  const steps = [
    ['device_config', 'set_sync_disabled_for_tests', 'persistent'],
    ['device_config', 'put', 'activity_manager', 'max_phantom_processes', '2147483647'],
  ];
  if (sdk >= 34) steps.push(['settings', 'put', 'global', 'settings_enable_monitor_phantom_procs', 'false']);
  for (const step of steps) {
    log(`  adb shell ${step.join(' ')}`);
    await adb.adb(['shell', ...step], {serial: device.serial});
  }
  log('Done. It lasts until a factory reset; undo with `device_config set_sync_disabled_for_tests none`.');
  return 0;
}

const COMMANDS = {
  devices: devicesCommand,
  ls: devicesCommand,
  pair: pairCommand,
  ssh: sshCommand,
  shell: sshCommand,
  exec: execCommand,
  cp: cpCommand,
  sync: syncCommand,
  forward: forwardCommand,
  config: configCommand,
  proxy: proxyCommand,
  status: statusCommand,
  unpair: unpairCommand,
  stop: stopCommand,
  tune: tuneCommand,
};

module.exports = {COMMANDS, UsageError};
