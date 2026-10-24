'use strict';

const assert = require('node:assert/strict');
const {spawnSync} = require('node:child_process');
const {createHash} = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const {versionName, versionCode} = require('./check-version.js');

const apk = process.argv[2];
assert.ok(apk && fs.statSync(apk).size > 0, 'Pass the path to a nonempty unsigned release APK');
const sdk = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT;
assert.ok(sdk, 'Set ANDROID_HOME to the Android SDK');
const buildTools = path.join(sdk, 'build-tools', '37.0.0');
function run(command, args) {
  const result = spawnSync(command, args, {maxBuffer: 128 * 1024 * 1024});
  assert.equal(result.status, 0, `${command} failed: ${result.error || result.stderr}`);
  return result.stdout;
}
const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');
run('unzip', ['-t', apk]);
const badging = run(path.join(buildTools, 'aapt'), ['dump', 'badging', apk]).toString();
assert.ok(badging.includes(`package: name='com.scariflabs.horus' versionCode='${versionCode}' versionName='${versionName}'`), 'APK identity/version differs from source');
assert.match(badging, /^native-code: 'arm64-v8a'\s*$/m, 'APK must contain only ARM64 native code');
assert.doesNotMatch(badging, /^application-debuggable/m, 'Release APK must not be debuggable');
const entries = run('unzip', ['-Z1', apk]).toString().trim().split('\n');
assert.ok(entries.includes('classes.dex') && entries.includes('assets/index.android.bundle'), 'Missing DEX or production JavaScript bundle');
assert.ok(!entries.some(entry => /^META-INF\/.*\.(RSA|DSA|EC|SF)$/i.test(entry)), 'Unexpected JAR signing material');
const signature = spawnSync(path.join(buildTools, 'apksigner'), ['verify', apk], {encoding: 'utf8'});
assert.equal(signature.status, 1, `Expected unsigned APK: ${signature.error || signature.stderr}`);
assert.match(signature.stderr, /DOES NOT VERIFY|Missing META-INF\/MANIFEST.MF|No signatures/, 'Unexpected APK signature verification error');
const manifest = JSON.parse(run('unzip', ['-p', apk, 'assets/alpine-runtime/manifest.json']).toString());
assert.equal(manifest.target.abi, 'arm64-v8a');
assert.deepEqual(manifest.target.artifacts.map(artifact => artifact.jniName).sort(), ['libproot.so', 'libproot_loader.so']);
for (const name of ['libproot.so', 'libproot_loader.so', 'libhorus_pty.so', 'libhorus_provision.so']) {
  const bytes = run('unzip', ['-p', apk, `lib/arm64-v8a/${name}`]);
  assert.equal(bytes.subarray(0, 4).toString('hex'), '7f454c46', `${name} is not ELF`);
  assert.equal(bytes[4], 2, `${name} is not ELF64`);
  assert.equal(bytes[5], 1, `${name} is not little endian`);
  assert.equal(bytes.readUInt16LE(18), 183, `${name} is not AArch64`);
  const artifact = manifest.target.artifacts.find(item => item.jniName === name);
  if (artifact) {
    assert.equal(artifact.sizeBytes, bytes.length, `${name} manifest size mismatch`);
    assert.equal(artifact.sha256, sha256(bytes), `${name} manifest digest mismatch`);
  }
}
const metadata = {
  commit: run('git', ['rev-parse', 'HEAD']).toString().trim(),
  runUrl: process.env.GITHUB_RUN_ID ? `${process.env.GITHUB_SERVER_URL}/${process.env.GITHUB_REPOSITORY}/actions/runs/${process.env.GITHUB_RUN_ID}` : null,
  applicationId: 'com.scariflabs.horus', versionName, versionCode,
  abi: 'arm64-v8a', signed: false, sha256: sha256(fs.readFileSync(apk)),
  node: process.version, runtime: manifest,
};
fs.mkdirSync('artifacts/ci', {recursive: true});
fs.writeFileSync('artifacts/ci/apk-metadata.json', JSON.stringify(metadata, null, 2) + '\n');
console.log(`Validated unsigned production APK: ${versionName} (${versionCode}), ARM64, SHA-256 ${metadata.sha256}`);
