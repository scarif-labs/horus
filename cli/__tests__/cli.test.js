'use strict';

const {parseContentResult, parseDevices, parseForwards} = require('../lib/adb');
const {parseArgs} = require('../lib/args');
const {hostKeyAlias, parseRemotePath, shellQuote, sshConfigBlock, sshOptions} = require('../lib/ssh');
const {fingerprintOf, isValidName, slugify, uniqueName} = require('../lib/state');

describe('adb output parsing', () => {
  test('reads connected devices and their models', () => {
    const output = [
      'List of devices attached',
      '9b53f076               device usb:1-1 product:alioth model:M2101K6P device:alioth transport_id:3',
      'emulator-5554          offline',
      'R58M123                unauthorized usb:1-2 transport_id:4',
      '',
    ].join('\n');
    expect(parseDevices(output)).toEqual([
      {serial: '9b53f076', state: 'device', model: 'M2101K6P'},
      {serial: 'emulator-5554', state: 'offline', model: ''},
      {serial: 'R58M123', state: 'unauthorized', model: ''},
    ]);
  });

  test('reads the single JSON answer from content call, even with commas and braces', () => {
    const json = {status: 'ok', model: 'Xiaomi M2101K6P', log: 'a, b}]\nc'};
    expect(parseContentResult(`Result: Bundle[{json=${JSON.stringify(json)}}]\n`)).toEqual(json);
  });

  test('explains a missing or outdated app', () => {
    const output = 'Error while accessing provider:com.scariflabs.horus.remote\njava.lang.IllegalStateException: Could not find provider';
    expect(() => parseContentResult(output)).toThrow(/not installed/);
  });

  test('reads adb forwards', () => {
    expect(parseForwards('9b53f076 tcp:53355 tcp:8022\nother tcp:1 localabstract:x\n')).toEqual([
      {serial: '9b53f076', local: 'tcp:53355', remote: 'tcp:8022'},
      {serial: 'other', local: 'tcp:1', remote: 'localabstract:x'},
    ]);
  });
});

describe('arguments', () => {
  test('keeps everything after -- for the phone', () => {
    expect(parseArgs(['pixel', '--all', '-r', '--name', 'x', '--', 'ls', '--name', '-la'])).toEqual({
      positional: ['pixel'],
      flags: {all: true, r: true, name: 'x'},
      rest: ['ls', '--name', '-la'],
    });
    expect(parseArgs(['--name=pixel-8']).flags.name).toBe('pixel-8');
    expect(() => parseArgs(['--name'])).toThrow(/needs a value/);
  });

  test('splits phone:path but leaves local and Windows drive paths alone', () => {
    expect(parseRemotePath('pixel:/workspace/app')).toEqual({device: 'pixel', path: '/workspace/app'});
    expect(parseRemotePath('pixel:')).toEqual({device: 'pixel', path: '.'});
    expect(parseRemotePath('./local:file')).toEqual({device: null, path: './local:file'});
    expect(parseRemotePath('C:\\Users\\me')).toEqual({device: null, path: 'C:\\Users\\me'});
  });
});

describe('ssh', () => {
  test('pins the host key per phone and never falls back to passwords', () => {
    const options = sshOptions({port: 5000, key: '/k', knownHosts: '/kh', serial: 'ab:cd'}).join(' ');
    expect(options).toContain('HostKeyAlias=horus-ab_cd');
    expect(options).toContain('StrictHostKeyChecking=accept-new');
    expect(options).toContain('UserKnownHostsFile=/kh');
    expect(options).toContain('PasswordAuthentication=no');
    expect(options).toContain('IdentitiesOnly=yes');
    expect(hostKeyAlias('emulator-5554')).toBe('horus-emulator-5554');
  });

  test('quotes arguments for the remote shell', () => {
    expect(shellQuote('plain/path.txt')).toBe('plain/path.txt');
    expect(shellQuote("it's here")).toBe(`'it'\\''s here'`);
    expect(shellQuote('$(rm -rf ~)')).toBe(`'$(rm -rf ~)'`);
  });

  test('writes a ProxyCommand config block', () => {
    const block = sshConfigBlock({name: 'pixel', serial: 'S1', username: 'horus', key: '/k', knownHosts: '/kh', horusCommand: 'horus'});
    expect(block).toContain('Host pixel\n');
    expect(block).toContain('  ProxyCommand horus proxy S1\n');
    expect(block).toContain('  HostKeyAlias horus-S1\n');
  });
});

describe('local state', () => {
  test('fingerprints match ssh-keygen', () => {
    expect(fingerprintOf('AAAAC3NzaC1lZDI1NTE5AAAAIJINpCEzt+pD1+GOKdw3/TY7s14kwV8TvgKJIIiQAtDj'))
      .toBe('SHA256:F+7aOhltov2IBCyXTHR4u/ZdoL0pbUbDogAweeNEWr0');
  });

  test('names phones uniquely and simply', () => {
    expect(slugify('Xiaomi M2101K6P')).toBe('xiaomi-m2101k6p');
    expect(slugify('***')).toBe('phone');
    const devices = {A: {name: 'pixel-8'}, B: {name: 'pixel-8-2'}};
    expect(uniqueName('Pixel 8', devices, 'C')).toBe('pixel-8-3');
    expect(uniqueName('Pixel 8', devices, 'A')).toBe('pixel-8');
    expect(isValidName('pixel-8')).toBe(true);
    expect(isValidName('Pixel 8')).toBe(false);
    expect(isValidName('-x')).toBe(false);
  });
});
