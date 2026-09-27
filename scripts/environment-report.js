#!/usr/bin/env node

const fs = require('node:fs');
const path = require('node:path');
const {spawnSync} = require('node:child_process');

const projectRoot = path.resolve(__dirname, '..');
const outputPath = path.join(projectRoot, 'artifacts', 'environment.json');

function commandOutput(command, args) {
  const result = spawnSync(command, args, {cwd: projectRoot, encoding: 'utf8'});
  return `${result.stdout || ''}\n${result.stderr || ''}`.trim();
}

function toolPath(relativePath, fallbackCommand) {
  const sdkRoot = process.env.ANDROID_SDK_ROOT || process.env.ANDROID_HOME;
  if (sdkRoot) {
    const candidate = path.join(sdkRoot, relativePath);
    if (fs.existsSync(candidate)) return candidate;
  }
  return fallbackCommand;
}

function firstMatch(value, pattern, fallback = 'unavailable') {
  const match = value.match(pattern);
  return match ? match[1] : fallback;
}

function packageVersion(packageName) {
  try {
    const packagePath = path.join(projectRoot, 'node_modules', packageName, 'package.json');
    return JSON.parse(fs.readFileSync(packagePath, 'utf8')).version;
  } catch {
    return 'unavailable';
  }
}

function readText(relativePath) {
  try {
    return fs.readFileSync(path.join(projectRoot, relativePath), 'utf8');
  } catch {
    return '';
  }
}

function configuredValue(text, key, fallback = 'unavailable') {
  const match = text.match(new RegExp(`${key}\\s*=\\s*(?:["']([^"']+)["']|([0-9.]+))`));
  return match ? (match[1] || match[2]) : fallback;
}

function versionLine(value, pattern) {
  return firstMatch(value, pattern);
}

function safeDeviceModel(value) {
  const sanitized = value.replace(/[^A-Za-z0-9._ -]/g, '').trim().slice(0, 80);
  return sanitized || 'unavailable';
}

function connectedDevices() {
  const lines = commandOutput('adb', ['devices']).split(/\r?\n/);
  const deviceSerials = lines
    .slice(1)
    .map(line => line.trim().split(/\s+/))
    .filter(parts => parts.length >= 2 && parts[1] === 'device')
    .map(parts => parts[0]);

  return deviceSerials.map(serial => {
    const model = commandOutput('adb', ['-s', serial, 'shell', 'getprop', 'ro.product.model']);
    const apiLevel = commandOutput('adb', ['-s', serial, 'shell', 'getprop', 'ro.build.version.sdk']);
    return {
      model: safeDeviceModel(model),
      apiLevel: /^\d+$/.test(apiLevel) ? Number(apiLevel) : null,
    };
  });
}

const rootGradle = readText('android/build.gradle');
const wrapper = readText('android/gradle/wrapper/gradle-wrapper.properties');
const gradleVersions = readText('node_modules/@react-native/gradle-plugin/gradle/libs.versions.toml');
const reactNativePackage = (() => {
  try {
    return JSON.parse(readText('node_modules/react-native/package.json'));
  } catch {
    return {dependencies: {}};
  }
})();

const report = {
  schemaVersion: 1,
  generatedAt: new Date().toISOString(),
  toolchain: {
    node: process.versions.node,
    npm: commandOutput('npm', ['--version']) || 'unavailable',
    java: firstMatch(commandOutput('java', ['-version']), /version\s+"([^"]+)"/),
    gradle: firstMatch(wrapper, /gradle-([0-9.]+)-bin\.zip/),
    androidGradlePlugin: configuredValue(gradleVersions, 'agp'),
    kotlin: configuredValue(gradleVersions, 'kotlin', configuredValue(rootGradle, 'kotlinVersion')),
    reactNative: packageVersion('react-native'),
    hermes: {
      enabled: /(^|\n)hermesEnabled\s*=\s*true\s*($|\n)/.test(readText('android/gradle.properties')),
      compiler: reactNativePackage.dependencies?.['hermes-compiler'] || 'unavailable',
    },
  },
  androidSdk: {
    compileSdk: Number(configuredValue(rootGradle, 'compileSdkVersion', '0')) || null,
    targetSdk: Number(configuredValue(rootGradle, 'targetSdkVersion', '0')) || null,
    minSdk: Number(configuredValue(rootGradle, 'minSdkVersion', '0')) || null,
    buildTools: configuredValue(rootGradle, 'buildToolsVersion'),
    ndk: configuredValue(rootGradle, 'ndkVersion'),
    commandLineTools: versionLine(
      commandOutput(toolPath('cmdline-tools/latest/bin/sdkmanager', 'sdkmanager'), ['--version']),
      /(?:^|\n)\s*(\d+(?:\.\d+)+)\s*(?:\n|$)/,
    ),
    emulator: versionLine(
      commandOutput(toolPath('emulator/emulator', 'emulator'), ['-version']),
      /Android emulator version\s+([0-9.]+)/,
    ),
  },
  devices: connectedDevices(),
};

fs.mkdirSync(path.dirname(outputPath), {recursive: true});
fs.writeFileSync(outputPath, `${JSON.stringify(report, null, 2)}\n`, 'utf8');
console.log(`Wrote sanitized environment report: artifacts/environment.json`);
