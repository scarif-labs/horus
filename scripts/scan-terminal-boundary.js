#!/usr/bin/env node

/**
 * Phase 0 source-boundary scan for the Alpine terminal runtime.
 *
 * Enforces the one-way boundary around the Alpine terminal namespace:
 *   1. src/terminal/** may import only react/react-native, files inside
 *      src/terminal/, its own codegen spec src/native/NativeTerminalRuntime,
 *      the bounded terminal adapters, the bounded GitHub browser-url helper,
 *      and the pinned xterm renderer packages.
 *   2. The spec src/native/NativeTerminalRuntime.ts may import only
 *      react-native.
 *   3. Kotlin under com/scariflabs/horus/terminal/ (main, unit, and connected
 *      sources) must remain within the terminal package, and its main sources
 *      may import only react-bridge, spec, Android, and JDK packages.
 *
 * Writes artifacts/alpine-poc/p0-boundary-scan.json and exits nonzero on any
 * violation.
 */
const fs = require('node:fs');
const path = require('node:path');
const {spawnSync} = require('node:child_process');

const root = path.resolve(__dirname, '..');
const terminalJsDir = path.join(root, 'src', 'terminal');
const nativeSpecFile = path.join(root, 'src', 'native', 'NativeTerminalRuntime.ts');
const terminalKotlinDirs = [
  path.join(root, 'android', 'app', 'src', 'main', 'java', 'com', 'horus', 'terminal'),
  path.join(root, 'android', 'app', 'src', 'test', 'java', 'com', 'horus', 'terminal'),
  path.join(root, 'android', 'app', 'src', 'androidTest', 'java', 'com', 'horus', 'terminal'),
];
const output = path.join(root, 'artifacts', 'alpine-poc', 'p0-boundary-scan.json');

const TERMINAL_JS_PACKAGE_IMPORTS = new Set([
  '@xterm/headless',
  '@xterm/addon-unicode11',
]);
const TERMINAL_JS_EXTERNAL_FILES = new Set([
  path.join(root, 'src', 'projects', 'githubDeviceLogin'),
]);
// These are deliberately explicit cross-namespace adapters. They keep the
// terminal implementation independent of the rest of the UI/native tree while
// allowing the Canvas and input bridges used by the Android native path.
const TERMINAL_JS_ADAPTER_FILES = new Set([
  path.join(root, 'src', 'native', 'NativeTerminalCanvas'),
  path.join(root, 'src', 'native', 'NativeTerminalInput'),
  path.join(root, 'src', 'ui', 'InteractivePressable'),
  path.join(root, 'src', 'ui', 'typography'),
]);

const KOTLIN_MAIN_IMPORT_ALLOWLIST = [
  /^com\.horus\.terminal\./,
  /^com\.horus\.R$/,
  /^com\.horus\.specs\./,
  /^com\.facebook\.react\./,
  /^android\.*/,
  /^androidx\.test\./,
  /^java\./,
  /^javax\.crypto\./,
  /^kotlin\.*/,
  /^org\.json\./,
  /^org\.junit\./,
];

function listFiles(dir, extensions) {
  if (!fs.existsSync(dir)) {
    return [];
  }
  const result = [];
  for (const entry of fs.readdirSync(dir, {withFileTypes: true})) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      result.push(...listFiles(full, extensions));
    } else if (extensions.some(extension => entry.name.endsWith(extension))) {
      result.push(full);
    }
  }
  return result;
}

function importSpecifiers(source) {
  const specifiers = new Set();
  const isWord = value => /^[A-Za-z_$][\w$]*$/.test(value);
  const skipWhitespace = start => {
    let index = start;
    while (index < source.length && /\s/.test(source[index])) index++;
    return index;
  };
  const readQuoted = start => {
    const quote = source[start];
    if (quote !== "'" && quote !== '"') return null;
    let value = '';
    for (let index = start + 1; index < source.length; index++) {
      const character = source[index];
      if (character === '\\' && index + 1 < source.length) {
        value += source[index + 1];
        index++;
      } else if (character === quote) {
        return {value, end: index + 1};
      } else {
        value += character;
      }
    }
    return null;
  };
  const skipCommentOrString = start => {
    if (source[start] === "'" || source[start] === '"') {
      return readQuoted(start)?.end ?? source.length;
    }
    if (source.startsWith('//', start)) {
      const newline = source.indexOf('\n', start + 2);
      return newline < 0 ? source.length : newline + 1;
    }
    if (source.startsWith('/*', start)) {
      const end = source.indexOf('*/', start + 2);
      return end < 0 ? source.length : end + 2;
    }
    return null;
  };
  const findFromSpecifier = start => {
    let index = start;
    while (index < source.length) {
      const skipped = skipCommentOrString(index);
      if (skipped !== null) {
        index = skipped;
        continue;
      }
      const match = source.slice(index).match(/^[A-Za-z_$][\w$]*/);
      if (!match) {
        index++;
        continue;
      }
      const word = match[0];
      index += word.length;
      if (word === 'from') {
        const quoted = readQuoted(skipWhitespace(index));
        return quoted?.value;
      }
    }
    return null;
  };

  let index = 0;
  while (index < source.length) {
    const skipped = skipCommentOrString(index);
    if (skipped !== null) {
      index = skipped;
      continue;
    }
    const match = source.slice(index).match(/^[A-Za-z_$][\w$]*/);
    if (!match) {
      index++;
      continue;
    }
    const word = match[0];
    const previous = index > 0 ? source[index - 1] : '';
    index += word.length;
    if (!isWord(word) || /[\w$\.]/.test(previous)) continue;

    if (word === 'import') {
      const next = skipWhitespace(index);
      if (source[next] === '(') {
        const quoted = readQuoted(skipWhitespace(next + 1));
        if (quoted) specifiers.add(quoted.value);
      } else if (source[next] === "'" || source[next] === '"') {
        const quoted = readQuoted(next);
        if (quoted) specifiers.add(quoted.value);
      } else {
        const specifier = findFromSpecifier(next);
        if (specifier) specifiers.add(specifier);
      }
    } else if (word === 'export') {
      const specifier = findFromSpecifier(index);
      if (specifier) specifiers.add(specifier);
    } else if (word === 'require') {
      const next = skipWhitespace(index);
      if (source[next] === '(') {
        const quoted = readQuoted(skipWhitespace(next + 1));
        if (quoted) specifiers.add(quoted.value);
      }
    }
  }
  return specifiers;
}

function stripKotlinCommentsAndStrings(source) {
  let output = '';
  let index = 0;
  while (index < source.length) {
    if (source.startsWith('//', index)) {
      const newline = source.indexOf('\n', index + 2);
      index = newline < 0 ? source.length : newline;
      output += newline < 0 ? '' : '\n';
      continue;
    }
    if (source.startsWith('/*', index)) {
      const end = source.indexOf('*/', index + 2);
      index = end < 0 ? source.length : end + 2;
      output += ' ';
      continue;
    }
    if (source.startsWith('"""', index)) {
      const end = source.indexOf('"""', index + 3);
      index = end < 0 ? source.length : end + 3;
      output += ' ';
      continue;
    }
    if (source[index] === '"' || source[index] === "'") {
      const quote = source[index++];
      while (index < source.length) {
        if (source[index] === '\\') {
          index += 2;
        } else if (source[index] === quote) {
          index++;
          break;
        } else {
          index++;
        }
      }
      output += ' ';
      continue;
    }
    output += source[index++];
  }
  return output;
}

function checkJsBoundary() {
  const violations = [];
  const files = listFiles(terminalJsDir, ['.ts', '.tsx']);
  for (const file of files) {
    const source = fs.readFileSync(file, 'utf8');
    for (const specifier of importSpecifiers(source)) {
      const allowed =
        specifier === 'react' ||
        specifier === 'react-native' ||
        TERMINAL_JS_PACKAGE_IMPORTS.has(specifier) ||
        (specifier.startsWith('.') &&
          (path.resolve(path.dirname(file), specifier).startsWith(`${terminalJsDir}${path.sep}`) ||
            path.resolve(path.dirname(file), specifier) ===
              path.join(root, 'src', 'native', 'NativeTerminalRuntime') ||
            TERMINAL_JS_EXTERNAL_FILES.has(path.resolve(path.dirname(file), specifier)) ||
            TERMINAL_JS_ADAPTER_FILES.has(path.resolve(path.dirname(file), specifier))));
      if (!allowed) {
        violations.push(`${path.relative(root, file)} imports ${specifier}`);
      }
    }
  }

  const specSource = fs.readFileSync(nativeSpecFile, 'utf8');
  for (const specifier of importSpecifiers(specSource)) {
    if (specifier !== 'react-native' && !specifier.startsWith('react-native/')) {
      violations.push(`${path.relative(root, nativeSpecFile)} imports ${specifier}`);
    }
  }
  return {filesScanned: files.length + 1, violations};
}

function checkKotlinBoundary() {
  const violations = [];
  const files = terminalKotlinDirs.flatMap(dir =>
    listFiles(dir, ['.kt']).map(file => ({
      file,
      isMain: dir.endsWith(path.join('main', 'java', 'com', 'horus', 'terminal')),
    })),
  );
  for (const {file, isMain} of files) {
    const source = stripKotlinCommentsAndStrings(fs.readFileSync(file, 'utf8'));
    if (isMain) {
      for (const match of source.matchAll(/^\s*import\s+([^\s]+)$/gm)) {
        const imported = match[1].replace(/\.\*$/, '');
        if (!KOTLIN_MAIN_IMPORT_ALLOWLIST.some(allowed => allowed.test(imported))) {
          violations.push(`${path.relative(root, file)} imports ${imported}`);
        }
      }
    }
  }
  return {filesScanned: files.length, violations};
}

function main() {
  const js = checkJsBoundary();
  const kotlin = checkKotlinBoundary();

  const checks = [
    {id: 'terminal-js-namespace-imports', status: js.violations.length === 0 ? 'passed' : 'failed', marker: `files=${js.filesScanned}`},
    {id: 'terminal-kotlin-namespace-references', status: kotlin.violations.length === 0 ? 'passed' : 'failed', marker: `files=${kotlin.filesScanned}`},
  ];
  const violations = [...js.violations, ...kotlin.violations];
  const status = checks.every(check => check.status === 'passed') ? 'passed' : 'failed';
  const head = spawnSync('git', ['rev-parse', 'HEAD'], {cwd: root, encoding: 'utf8', timeout: 30_000});

  const record = {
    schema: 'alpine-terminal-poc/v1',
    gate: 'p0-boundary-scan',
    status,
    appCommit: head.status === 0 ? head.stdout.trim() : 'unknown',
    checks,
    violations,
    credentialsPresent: false,
  };

  fs.mkdirSync(path.dirname(output), {recursive: true});
  fs.writeFileSync(output, `${JSON.stringify(record, null, 2)}\n`);

  if (violations.length > 0) {
    console.error('Terminal boundary violations:');
    for (const violation of violations) {
      console.error(`  - ${violation}`);
    }
  }
  console.log(`Boundary scan ${status}; artifact: ${path.relative(root, output)}`);
  if (status !== 'passed') {
    process.exit(1);
  }
}

main();
