#!/usr/bin/env node

const fs = require('node:fs');
const path = require('node:path');

const reportPath = path.resolve(__dirname, '..', 'artifacts', 'environment.json');
const report = JSON.parse(fs.readFileSync(reportPath, 'utf8'));

function fail(message) {
  throw new Error(`environment report validation failed: ${message}`);
}

function expectKeys(value, expected, label) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    fail(`${label} must be an object`);
  }
  const actual = Object.keys(value).sort();
  const wanted = [...expected].sort();
  if (JSON.stringify(actual) !== JSON.stringify(wanted)) {
    fail(`${label} keys were ${actual.join(',')}; expected ${wanted.join(',')}`);
  }
}

function expectString(value, label) {
  if (typeof value !== 'string' || value.length === 0) {
    fail(`${label} must be a non-empty string`);
  }
}

function expectRequiredString(value, label) {
  expectString(value, label);
  if (value === 'unavailable') fail(`${label} must not be unavailable`);
}

function expectRequiredInteger(value, label) {
  if (!Number.isInteger(value) || value < 0) {
    fail(`${label} must be a non-negative integer`);
  }
}

function expectNullableInteger(value, label) {
  if (value !== null && (!Number.isInteger(value) || value < 0)) {
    fail(`${label} must be a non-negative integer or null`);
  }
}

expectKeys(report, ['schemaVersion', 'generatedAt', 'toolchain', 'androidSdk', 'devices'], 'report');
if (report.schemaVersion !== 1) fail('schemaVersion must be 1');
if (!/^\d{4}-\d{2}-\d{2}T[^\s]+Z$/.test(report.generatedAt)) fail('generatedAt must be UTC ISO-8601');

expectKeys(report.toolchain, ['node', 'npm', 'java', 'gradle', 'androidGradlePlugin', 'kotlin', 'reactNative', 'hermes'], 'toolchain');
for (const key of ['node', 'npm', 'java', 'gradle', 'androidGradlePlugin', 'kotlin', 'reactNative']) {
  expectRequiredString(report.toolchain[key], `toolchain.${key}`);
}
expectKeys(report.toolchain.hermes, ['enabled', 'compiler'], 'toolchain.hermes');
if (report.toolchain.hermes.enabled !== true) fail('toolchain.hermes.enabled must be true');
expectRequiredString(report.toolchain.hermes.compiler, 'toolchain.hermes.compiler');

expectKeys(report.androidSdk, ['compileSdk', 'targetSdk', 'minSdk', 'buildTools', 'ndk', 'commandLineTools', 'emulator'], 'androidSdk');
for (const key of ['compileSdk', 'targetSdk', 'minSdk']) expectRequiredInteger(report.androidSdk[key], `androidSdk.${key}`);
for (const key of ['buildTools', 'ndk', 'commandLineTools', 'emulator']) expectRequiredString(report.androidSdk[key], `androidSdk.${key}`);

if (!Array.isArray(report.devices)) fail('devices must be an array');
for (const [index, device] of report.devices.entries()) {
  expectKeys(device, ['model', 'apiLevel'], `devices[${index}]`);
  expectString(device.model, `devices[${index}].model`);
  expectNullableInteger(device.apiLevel, `devices[${index}].apiLevel`);
}

const serialized = JSON.stringify(report);
for (const forbidden of [
  /\/Users\//i,
  /\/home\//i,
  /[A-Z]:\\/i,
  /[\\/]/,
  /(^|[^a-z])(token|secret|password|api[_-]?key|authorization|credential)([^a-z]|$)/i,
  /emulator-[0-9]+/i,
  /serial/i,
  /username/i,
]) {
  if (forbidden.test(serialized)) fail(`forbidden redaction pattern matched ${forbidden}`);
}

console.log('Environment report schema and redaction validation passed.');
