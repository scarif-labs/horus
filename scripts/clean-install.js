#!/usr/bin/env node

const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const {spawnSync} = require('node:child_process');

const projectRoot = path.resolve(__dirname, '..');
const candidate = fs.mkdtempSync(path.join(os.tmpdir(), 'horus-clean-'));

function included(source) {
  const relative = path.relative(projectRoot, source);
  if (!relative) return true;
  return !(
    relative === 'node_modules' || relative.startsWith(`node_modules${path.sep}`) ||
    relative === 'artifacts' || relative.startsWith(`artifacts${path.sep}`) ||
    relative === '.git' || relative.startsWith(`.git${path.sep}`) ||
    relative === '.gradle' || relative.startsWith(`.gradle${path.sep}`) ||
    relative === path.join('android', '.gradle') || relative.startsWith(`${path.join('android', '.gradle')}${path.sep}`) ||
    relative === path.join('android', 'app', 'build') || relative.startsWith(`${path.join('android', 'app', 'build')}${path.sep}`)
  );
}

function run(command, args, cwd, timeout) {
  const result = spawnSync(command, args, {cwd, stdio: 'inherit', timeout});
  if (result.status !== 0) process.exit(result.status === null ? 1 : result.status);
}

try {
  fs.cpSync(projectRoot, candidate, {recursive: true, filter: included});
  console.log(`Clean candidate copied to a disposable directory for npm ci and debug assembly.`);
  run('npm', ['ci'], candidate, 600_000);
  run('npm', ['run', 'build:android:debug'], candidate, 600_000);
  console.log('Clean candidate npm ci and debug assembly passed.');
} finally {
  fs.rmSync(candidate, {recursive: true, force: true});
}
