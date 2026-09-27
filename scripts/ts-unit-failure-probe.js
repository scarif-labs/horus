#!/usr/bin/env node

const path = require('node:path');
const {spawnSync} = require('node:child_process');

const jestCommand = process.platform === 'win32' ? 'jest.cmd' : path.join('node_modules', '.bin', 'jest');
const result = spawnSync(jestCommand, ['--runInBand', '__tests__/runtimeDiagnostics.test.ts'], {
  env: {...process.env, HORUS_FORCE_TS_UNIT_FAILURE: 'true'},
  stdio: 'inherit',
});
process.exit(result.status === null ? 1 : result.status);
