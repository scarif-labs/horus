'use strict';

const os = require('node:os');
const fs = require('node:fs');
const path = require('node:path');
const {StringDecoder} = require('node:string_decoder');
const {spawn, spawnSync} = require('node:child_process');

// A bare connection closure is retryable only when SSH's private debug log
// confirms a connection began and no authentication succeeded.
function isPreAuthenticationRefusal(firstLine, {connected = false, authenticated = false} = {}) {
  if (authenticated) return false;
  return /^kex_exchange_identification: (?:read: )?(?:Connection closed|Connection reset|banner exchange: Connection to .+ closed)/i.test(firstLine) ||
    (connected && /^Connection (?:closed|reset) by .+ port \d+\s*$/i.test(firstLine));
}

function lineWriter(target, prefix) {
  if (!prefix) return {write: text => target.write(text), end: () => {}};
  let pending = '';
  return {
    write(text) {
      pending += text;
      const lines = pending.split('\n');
      pending = lines.pop();
      for (const line of lines) target.write(`${prefix}${line}\n`);
    },
    end() { if (pending) target.write(`${prefix}${pending}\n`); },
  };
}

// Windows has no POSIX FIFO. Keep SSH's stderr in memory and only retry an
// explicit key-exchange failure; a bare closure is ambiguous without tracing.
function attemptWithoutDebug(args, {prefix = '', stdin = 'inherit'} = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn('ssh', args, {env: process.env, stdio: [stdin, prefix ? 'pipe' : 'inherit', 'pipe']});
    const out = lineWriter(process.stdout, prefix);
    const err = lineWriter(process.stderr, prefix);
    if (child.stdout) { child.stdout.setEncoding('utf8'); child.stdout.on('data', text => out.write(text)); }
    let pending = '';
    let classified = false;
    let candidate = false;
    child.stderr.setEncoding('utf8');
    child.stderr.on('data', text => {
      if (classified && !candidate) { err.write(text); return; }
      pending += text;
      if (!classified && (pending.includes('\n') || pending.length > 4096)) {
        candidate = isPreAuthenticationRefusal(pending.split('\n', 1)[0]);
        classified = true;
      }
      if (pending.length > 65536) candidate = false;
      if (classified && !candidate) { err.write(pending); pending = ''; }
    });
    child.on('error', error => reject(error.code === 'ENOENT' ? new Error('ssh was not found. Install OpenSSH.') : error));
    child.on('close', (code, signal) => {
      out.end();
      if (!classified) candidate = isPreAuthenticationRefusal(pending);
      const status = signal ? 128 + (os.constants.signals[signal] ?? 1) : code ?? 1;
      if (status !== 255 || !candidate) { if (pending) err.write(pending); pending = ''; }
      err.end();
      resolve({status, retryable: status === 255 && candidate, diagnostic: pending});
    });
  });
}

function attempt(args, {prefix = '', stdin = 'inherit'} = {}) {
  if (process.platform === 'win32') return attemptWithoutDebug(args, {prefix, stdin});
  return new Promise((resolve, reject) => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'horus-ssh-'));
    const filePath = path.join(dir, 'ssh.log');
    // A FIFO never stores SSH's debug output (which can include the remote
    // command) on disk, even if the CLI is interrupted.
    const created = spawnSync('mkfifo', ['-m', '600', filePath]);
    if (created.status !== 0) {
      fs.rmSync(dir, {recursive: true, force: true});
      attemptWithoutDebug(args, {prefix, stdin}).then(resolve, reject);
      return;
    }
    const logFd = fs.openSync(filePath, fs.constants.O_RDWR | fs.constants.O_NONBLOCK);
    const child = spawn('ssh', ['-v', '-E', filePath, ...args], {env: process.env, stdio: [stdin, prefix ? 'pipe' : 'inherit', 'pipe']});
    const out = lineWriter(process.stdout, prefix);
    const err = lineWriter(process.stderr, prefix);
    if (child.stdout) {
      child.stdout.setEncoding('utf8');
      child.stdout.on('data', text => out.write(text));
    }
    let connected = false;
    let authenticated = false;
    let remoteStderr = false;
    let candidate = '';
    let pending = '';
    const decoder = new StringDecoder('utf8');
    function handleLine(line) {
      if (/^debug\d: Connection established\./.test(line)) connected = true;
      if (/^(?:Authenticated to |debug\d: Authentication succeeded)/.test(line)) authenticated = true;
      if (/^(?:debug\d: |Authenticated to |Transferred: sent |Bytes per second: sent )/.test(line)) return;
      if (isPreAuthenticationRefusal(line.trimEnd(), {connected, authenticated})) candidate += line;
      else err.write(line);
    }
    function readLog(final = false) {
      const buffer = Buffer.allocUnsafe(8192);
      let count;
      for (;;) {
        try { count = fs.readSync(logFd, buffer, 0, buffer.length, null); }
        catch (error) { if (error.code === 'EAGAIN' || error.code === 'EWOULDBLOCK') break; throw error; }
        if (count <= 0) break;
        pending += decoder.write(buffer.subarray(0, count));
        let end;
        while ((end = pending.indexOf('\n')) !== -1) {
          handleLine(pending.slice(0, end + 1));
          pending = pending.slice(end + 1);
        }
      }
      if (final) {
        pending += decoder.end();
        if (pending) handleLine(pending);
        pending = '';
      }
    }
    child.stderr.setEncoding('utf8');
    child.stderr.on('data', text => { remoteStderr = true; err.write(text); });
    const timer = setInterval(() => readLog(), 100);
    let cleaned = false;
    const cleanup = () => {
      if (cleaned) return;
      cleaned = true;
      clearInterval(timer); fs.closeSync(logFd);
      fs.rmSync(dir, {recursive: true, force: true});
    };
    child.on('error', error => { cleanup(); reject(error.code === 'ENOENT' ? new Error('ssh was not found. Install OpenSSH.') : error); });
    child.on('close', (code, signal) => {
      if (cleaned) return;
      readLog(true);
      cleanup();
      out.end();
      const status = signal ? 128 + (os.constants.signals[signal] ?? 1) : code ?? 1;
      const retryable = status === 255 && !!candidate && !authenticated && !remoteStderr;
      if (!retryable && candidate) err.write(candidate);
      err.end();
      resolve({status, retryable, diagnostic: candidate});
    });
  });
}

async function runSsh(args, options = {}) {
  const runAttempt = options.attempt ?? (() => attempt(args, options));
  const now = options.now ?? Date.now;
  const wait = options.wait ?? (ms => new Promise(resolve => setTimeout(resolve, ms)));
  const random = options.random ?? Math.random;
  const deadline = now() + (options.timeoutMs ?? 30_000);
  let delay = 100;
  for (;;) {
    const result = await runAttempt();
    if (!result.retryable) return result.status;
    const remaining = deadline - now();
    if (remaining <= 0) {
      const writer = lineWriter(process.stderr, options.prefix ?? '');
      writer.write(result.diagnostic);
      writer.end();
      return result.status;
    }
    await wait(Math.min(remaining, Math.round(delay * (0.75 + random() * 0.5))));
    if (now() >= deadline) {
      const writer = lineWriter(process.stderr, options.prefix ?? '');
      writer.write(result.diagnostic);
      writer.end();
      return result.status;
    }
    delay = Math.min(delay * 2, 2000);
  }
}

module.exports = {isPreAuthenticationRefusal, runSsh};
