'use strict';

const crypto = require('node:crypto');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const {execFileSync} = require('node:child_process');

/** Everything the CLI keeps lives in ~/.horus (or $HORUS_HOME). */
function horusHome() {
  return process.env.HORUS_HOME || path.join(os.homedir(), '.horus');
}

function paths() {
  const home = horusHome();
  return {
    home,
    key: path.join(home, 'id_ed25519'),
    publicKey: path.join(home, 'id_ed25519.pub'),
    knownHosts: path.join(home, 'known_hosts'),
    devices: path.join(home, 'devices.json'),
    sshConfig: path.join(home, 'ssh_config'),
  };
}

function ensureHome() {
  fs.mkdirSync(horusHome(), {recursive: true, mode: 0o700});
}

/** This computer's own key for Horus; created once with ssh-keygen. */
function ensureKey() {
  ensureHome();
  const {key, publicKey} = paths();
  if (!fs.existsSync(key) || !fs.existsSync(publicKey)) {
    const comment = `horus@${os.hostname()}`;
    try {
      execFileSync('ssh-keygen', ['-q', '-t', 'ed25519', '-N', '', '-C', comment, '-f', key], {stdio: 'ignore'});
    } catch (error) {
      if (error.code === 'ENOENT') throw new Error('ssh-keygen was not found. Install OpenSSH.');
      throw error;
    }
  }
  return readPublicKey(fs.readFileSync(publicKey, 'utf8'));
}

/** Splits `type base64 comment` and computes the OpenSSH SHA256 fingerprint. */
function readPublicKey(line) {
  const [type, data] = line.trim().split(/\s+/);
  if (!type || !data) throw new Error('The Horus public key is unreadable. Delete ~/.horus/id_ed25519* to make a new one.');
  return {type, data, fingerprint: fingerprintOf(data)};
}

function fingerprintOf(base64) {
  const digest = crypto.createHash('sha256').update(Buffer.from(base64, 'base64')).digest('base64');
  return `SHA256:${digest.replace(/[=]+$/, '')}`;
}

function readDevices() {
  try {
    const parsed = JSON.parse(fs.readFileSync(paths().devices, 'utf8'));
    return parsed && typeof parsed === 'object' ? parsed : {};
  } catch {
    return {};
  }
}

function writeDevices(devices) {
  ensureHome();
  const file = paths().devices;
  const temp = `${file}.tmp`;
  fs.writeFileSync(temp, `${JSON.stringify(devices, null, 2)}\n`, {mode: 0o600});
  fs.renameSync(temp, file);
}

/** Lowercase, shell- and ssh-friendly: "Xiaomi M2101K6P" → "xiaomi-m2101k6p". */
function slugify(text) {
  const slug = String(text).toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 32);
  return slug || 'phone';
}

/** A name no other paired phone uses: pixel-8, pixel-8-2, … */
function uniqueName(base, devices, serial) {
  const taken = new Set(Object.entries(devices).filter(([other]) => other !== serial).map(([, entry]) => entry.name));
  let name = slugify(base);
  for (let index = 2; taken.has(name); index += 1) name = `${slugify(base)}-${index}`;
  return name;
}

function isValidName(name) {
  return /^[a-z0-9][a-z0-9-]{0,31}$/.test(name);
}

/** The label the phone shows for this computer's key. */
function computerLabel() {
  const label = os.hostname().replace(/\.local$/, '').replace(/[^A-Za-z0-9._@-]+/g, '-').slice(0, 64);
  return label || 'computer';
}

module.exports = {
  computerLabel,
  ensureHome,
  ensureKey,
  fingerprintOf,
  isValidName,
  paths,
  readDevices,
  readPublicKey,
  slugify,
  uniqueName,
  writeDevices,
};
