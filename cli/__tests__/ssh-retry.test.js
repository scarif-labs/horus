'use strict';

const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const {isPreAuthenticationRefusal, runSsh} = require('../lib/ssh-retry');

test('only identifies key-exchange refusals as safe to retry', () => {
  expect(isPreAuthenticationRefusal('kex_exchange_identification: read: Connection reset by peer')).toBe(true);
  expect(isPreAuthenticationRefusal('kex_exchange_identification: Connection closed by remote host')).toBe(true);
  expect(isPreAuthenticationRefusal('Connection closed by 127.0.0.1 port 1234')).toBe(false);
  expect(isPreAuthenticationRefusal('Connection closed by 127.0.0.1 port 1234', {connected: true})).toBe(true);
  expect(isPreAuthenticationRefusal('Connection closed by 127.0.0.1 port 1234', {connected: true, authenticated: true})).toBe(false);
  expect(isPreAuthenticationRefusal('Permission denied (publickey).')).toBe(false);
});

test('retries a real child refusal and preserves non-refusal errors', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'horus-ssh-retry-'));
  const savedPath = process.env.PATH;
  const savedCount = process.env.HORUS_TEST_SSH_COUNT;
  const error = jest.spyOn(process.stderr, 'write').mockImplementation(() => true);
  try {
    const bin = path.join(dir, 'ssh');
    fs.writeFileSync(bin, '#!/bin/sh\nlog="$3"\nif [ ! -f "$HORUS_TEST_SSH_COUNT" ]; then touch "$HORUS_TEST_SSH_COUNT"; printf "debug1: Connection established.\\nConnection closed by 127.0.0.1 port 1234\\n" >> "$log"; exit 255; fi\necho "Permission denied (publickey)." >> "$log"\nexit 255\n', {mode: 0o755});
    process.env.PATH = `${dir}:${savedPath}`;
    process.env.HORUS_TEST_SSH_COUNT = path.join(dir, 'count');
    expect(await runSsh([], {wait: async () => {}, random: () => 0})).toBe(255);
    const output = error.mock.calls.map(call => call[0]).join('');
    expect(output).toContain('Permission denied (publickey).');
    expect(output).not.toContain('Connection closed');
  } finally {
    error.mockRestore();
    process.env.PATH = savedPath;
    if (savedCount === undefined) delete process.env.HORUS_TEST_SSH_COUNT;
    else process.env.HORUS_TEST_SSH_COUNT = savedCount;
    fs.rmSync(dir, {recursive: true, force: true});
  }
});

test('stops at the deadline without starting another command', async () => {
  let time = 0;
  let calls = 0;
  const error = jest.spyOn(process.stderr, 'write').mockImplementation(() => true);
  try {
    const status = await runSsh([], {
      timeoutMs: 250,
      now: () => time,
      random: () => 0.5,
      wait: async ms => { time += ms; },
      attempt: async () => { calls += 1; return {status: 255, retryable: true, diagnostic: 'refused\n'}; },
    });
    expect(status).toBe(255);
    expect(calls).toBe(2);
    expect(time).toBe(250);
    expect(error).toHaveBeenCalledWith('refused\n');
  } finally { error.mockRestore(); }
});

test('does not retry a disconnect after authentication', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'horus-ssh-after-auth-'));
  const savedPath = process.env.PATH;
  const savedCount = process.env.HORUS_TEST_SSH_COUNT;
  const error = jest.spyOn(process.stderr, 'write').mockImplementation(() => true);
  try {
    fs.writeFileSync(path.join(dir, 'ssh'), '#!/bin/sh\nprintf "x\\n" >> "$HORUS_TEST_SSH_COUNT"\nprintf "debug1: Connection established.\\nAuthenticated to 127.0.0.1 using publickey.\\nConnection closed by 127.0.0.1 port 1234\\n" >> "$3"\nexit 255\n', {mode: 0o755});
    process.env.PATH = `${dir}:${savedPath}`;
    process.env.HORUS_TEST_SSH_COUNT = path.join(dir, 'count');
    expect(await runSsh([], {wait: async () => { throw new Error('must not retry'); }})).toBe(255);
    expect(fs.readFileSync(process.env.HORUS_TEST_SSH_COUNT, 'utf8')).toBe('x\n');
    expect(error.mock.calls.map(call => call[0]).join('')).toContain('Connection closed by');
  } finally {
    error.mockRestore();
    process.env.PATH = savedPath;
    if (savedCount === undefined) delete process.env.HORUS_TEST_SSH_COUNT;
    else process.env.HORUS_TEST_SSH_COUNT = savedCount;
    fs.rmSync(dir, {recursive: true, force: true});
  }
});
