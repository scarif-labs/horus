'use strict';

/**
 * Options every ssh/scp/rsync call uses. The host key is pinned per phone
 * (HostKeyAlias) in Horus's own known_hosts. The first key is accepted
 * without a prompt because it arrives over the USB/adb link, not a network.
 */
function sshOptions({port, key, knownHosts, serial}) {
  return [
    '-o', `Port=${port}`,
    '-o', `IdentityFile=${key}`,
    '-o', 'IdentitiesOnly=yes',
    '-o', `UserKnownHostsFile=${knownHosts}`,
    '-o', `HostKeyAlias=${hostKeyAlias(serial)}`,
    '-o', 'StrictHostKeyChecking=accept-new',
    '-o', 'ServerAliveInterval=30',
    '-o', 'PasswordAuthentication=no',
    '-o', 'KbdInteractiveAuthentication=no',
  ];
}

function hostKeyAlias(serial) {
  return `horus-${String(serial).replace(/[^A-Za-z0-9._-]/g, '_')}`;
}

/** Quotes one argument for the remote POSIX shell. */
function shellQuote(value) {
  if (/^[A-Za-z0-9_@%+=:,./-]+$/.test(value)) return value;
  return `'${String(value).replace(/'/g, `'\\''`)}'`;
}

/**
 * `phone:path` → {device: 'phone', path}; local paths stay local. A single
 * letter before the colon is a Windows drive, not a phone.
 */
function parseRemotePath(spec) {
  const match = /^([A-Za-z0-9][A-Za-z0-9._-]*):(.*)$/.exec(spec);
  if (!match || match[1].length === 1) return {device: null, path: spec};
  return {device: match[1], path: match[2] === '' ? '.' : match[2]};
}

/** One ssh_config block per phone; ProxyCommand re-creates the adb forward. */
function sshConfigBlock({name, serial, username, key, knownHosts, horusCommand}) {
  return [
    `Host ${name}`,
    '  HostName 127.0.0.1',
    `  User ${username}`,
    `  IdentityFile ${key}`,
    '  IdentitiesOnly yes',
    `  UserKnownHostsFile ${knownHosts}`,
    `  HostKeyAlias ${hostKeyAlias(serial)}`,
    '  StrictHostKeyChecking accept-new',
    '  ServerAliveInterval 30',
    `  ProxyCommand ${horusCommand} proxy ${serial}`,
    '',
  ].join('\n');
}

module.exports = {hostKeyAlias, parseRemotePath, shellQuote, sshConfigBlock, sshOptions};
