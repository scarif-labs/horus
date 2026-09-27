import {
  ALPINE_TOOLCHAIN_INSTALL_SCRIPT,
  ALPINE_TOOLCHAIN_PROBE_SCRIPT,
  TOOLCHAIN_CHECK_IDS,
  TOOLCHAIN_PROBE_BEGIN,
  TOOLCHAIN_PROBE_END,
  TOOLCHAIN_PROBE_MAX_FIELD_LENGTH,
  buildToolchainProbeCommand,
  buildToolchainInstallCommand,
  extractToolchainInstallBlock,
  extractToolchainProbeBlock,
  getToolchainCheck,
  isToolchainProbeReport,
  parseToolchainInstall,
  parseToolchainProbe,
} from '../src/terminal/toolchain';

function validProbeLines(): string[] {
  const lines: string[] = [TOOLCHAIN_PROBE_BEGIN];
  for (const id of TOOLCHAIN_CHECK_IDS) {
    lines.push(`ALPINE_TOOLCHAIN_CHECK|${id}|pass`);
  }
  lines.push('ALPINE_TOOLCHAIN_FIELD|apk|version|apk-tools 2.14.4');
  lines.push('ALPINE_TOOLCHAIN_FIELD|linux-arm64|platform|Linux');
  lines.push('ALPINE_TOOLCHAIN_FIELD|linux-arm64|architecture|aarch64');
  lines.push(TOOLCHAIN_PROBE_END);
  return lines;
}

/**
 * Every record the fixed probe script emits on a fully provisioned guest,
 * including the node-npm `npm` path field whose absence from the accepted
 * field vocabulary produced B-014's `toolchain=invalid` on device.
 */
function fullProvisionedRecords(): string[] {
  return [
    'ALPINE_TOOLCHAIN_CHECK|apk|pass',
    'ALPINE_TOOLCHAIN_FIELD|apk|path|/sbin/apk',
    'ALPINE_TOOLCHAIN_FIELD|apk|version|apk-tools 2.14.4',
    'ALPINE_TOOLCHAIN_CHECK|shell|pass',
    'ALPINE_TOOLCHAIN_FIELD|shell|shell|/bin/sh',
    'ALPINE_TOOLCHAIN_FIELD|shell|bash|/bin/bash',
    'ALPINE_TOOLCHAIN_CHECK|transfer|pass',
    'ALPINE_TOOLCHAIN_FIELD|transfer|path|/usr/bin/curl',
    'ALPINE_TOOLCHAIN_FIELD|transfer|version|curl 8.16.0',
    'ALPINE_TOOLCHAIN_CHECK|certificates|pass',
    'ALPINE_TOOLCHAIN_FIELD|certificates|certificate|/etc/ssl/certs/ca-certificates.crt',
    'ALPINE_TOOLCHAIN_CHECK|git|pass',
    'ALPINE_TOOLCHAIN_FIELD|git|path|/usr/bin/git',
    'ALPINE_TOOLCHAIN_FIELD|git|version|git version 2.47.1',
    'ALPINE_TOOLCHAIN_CHECK|ssh|pass',
    'ALPINE_TOOLCHAIN_FIELD|ssh|path|/usr/bin/ssh',
    'ALPINE_TOOLCHAIN_FIELD|ssh|version|OpenSSH_9.9p2, OpenSSL 3.5.0',
    'ALPINE_TOOLCHAIN_CHECK|node-npm|pass',
    'ALPINE_TOOLCHAIN_FIELD|node-npm|path|/usr/bin/node',
    'ALPINE_TOOLCHAIN_FIELD|node-npm|version|v24.13.1',
    'ALPINE_TOOLCHAIN_FIELD|node-npm|nodeIdentity|linux/arm64',
    'ALPINE_TOOLCHAIN_FIELD|node-npm|npm|/usr/bin/npm',
    'ALPINE_TOOLCHAIN_CHECK|python-pip|pass',
    'ALPINE_TOOLCHAIN_FIELD|python-pip|path|/usr/bin/python3',
    'ALPINE_TOOLCHAIN_FIELD|python-pip|version|Python 3.12.9',
    'ALPINE_TOOLCHAIN_FIELD|python-pip|client|pip 25.1.1',
    'ALPINE_TOOLCHAIN_CHECK|ripgrep|pass',
    'ALPINE_TOOLCHAIN_FIELD|ripgrep|path|/usr/bin/rg',
    'ALPINE_TOOLCHAIN_FIELD|ripgrep|version|ripgrep 14.1.1',
    'ALPINE_TOOLCHAIN_CHECK|native-libraries|pass',
    'ALPINE_TOOLCHAIN_CHECK|linux-arm64|pass',
    'ALPINE_TOOLCHAIN_FIELD|linux-arm64|platform|Linux',
    'ALPINE_TOOLCHAIN_FIELD|linux-arm64|architecture|aarch64',
  ];
}

describe('Alpine toolchain probe command', () => {
  it('returns a fixed shell-safe argv with bounded execution settings', () => {
    const command = buildToolchainProbeCommand();
    expect(command.executable).toBe('/bin/sh');
    expect(command.args[0]).toBe('-c');
    expect(command.args[1]).toBe(ALPINE_TOOLCHAIN_PROBE_SCRIPT);
    expect(command.timeoutMs).toBeGreaterThan(0);
    expect(command.maxOutputBytes).toBeGreaterThan(0);
    expect(JSON.stringify(command)).not.toMatch(/\$\{.*\}|eval|`/);
    expect(ALPINE_TOOLCHAIN_PROBE_SCRIPT).toContain("node -p 'process.platform + \"/\" + process.arch'");
    expect(ALPINE_TOOLCHAIN_PROBE_SCRIPT).toContain('ALPINE_TOOLCHAIN_CHECK|linux-arm64|pass');
    expect(ALPINE_TOOLCHAIN_PROBE_SCRIPT).toContain('ssh -V 2>&1');
  });

  it('does not schedule timers or leave asynchronous work for teardown', () => {
    jest.useFakeTimers();
    const before = jest.getTimerCount();
    try {
      const result = parseToolchainProbe(validProbeLines().join('\n'), 0);
      expect(result.valid).toBe(true);
      expect(jest.getTimerCount()).toBe(before);
    } finally {
      jest.useRealTimers();
    }
  });
});

describe('Alpine toolchain install command', () => {
  it('returns a fixed package-install argv with bounded execution settings', () => {
    const command = buildToolchainInstallCommand();
    expect(command.executable).toBe('/bin/sh');
    expect(command.args[0]).toBe('-c');
    expect(command.args[1]).toBe(ALPINE_TOOLCHAIN_INSTALL_SCRIPT);
    expect(command.timeoutMs).toBeGreaterThan(0);
    expect(command.maxOutputBytes).toBeGreaterThan(0);
    expect(ALPINE_TOOLCHAIN_INSTALL_SCRIPT).toContain('apk add --no-cache --no-progress');
    expect(ALPINE_TOOLCHAIN_INSTALL_SCRIPT).toContain('openssh-client-default');
    expect(JSON.stringify(command)).not.toMatch(/\$\{.*\}|eval|`/);
  });

  it('accepts only the exact bounded result block', () => {
    const block = [
      'ALPINE_TOOLCHAIN_INSTALL_V1_BEGIN',
      'ALPINE_TOOLCHAIN_INSTALL_V1_RESULT|pass',
      'ALPINE_TOOLCHAIN_INSTALL_V1_END',
    ].join('\n');
    expect(parseToolchainInstall(block)).toEqual({valid: true, status: 'pass'});
    expect(parseToolchainInstall(extractToolchainInstallBlock(`apk output\n${block}\n# prompt`)!)).toEqual({valid: true, status: 'pass'});
    expect(parseToolchainInstall(block.replace('|pass', '|pass-extra'))).toEqual({valid: false, error: 'invalid_result'});
    expect(parseToolchainInstall(`${block}\nextra`)).toEqual({valid: false, error: 'missing_end'});
  });

  it('extracts exact install markers through echoed shell and apk noise', () => {
    const noisy = [
      'localhost:/workspace# printf marker',
      'ALPINE_TOOLCHAIN_INSTALL_V1_BEGIN',
      'localhost:/workspace# apk add --no-cache ...',
      '(1/2) Installing package',
      'ALPINE_TOOLCHAIN_INSTALL_V1_RESULT|pass',
      'localhost:/workspace# printf marker',
      'ALPINE_TOOLCHAIN_INSTALL_V1_END',
      'localhost:/workspace# ',
    ].join('\n');
    const block = extractToolchainInstallBlock(noisy);
    expect(block).toBe([
      'ALPINE_TOOLCHAIN_INSTALL_V1_BEGIN',
      'ALPINE_TOOLCHAIN_INSTALL_V1_RESULT|pass',
      'ALPINE_TOOLCHAIN_INSTALL_V1_END',
    ].join('\n'));
    expect(parseToolchainInstall(block)).toEqual({valid: true, status: 'pass'});
  });
});

describe('strict Alpine toolchain probe parser', () => {
  it('parses ordered typed pass/fail/missing results and bounded fields', () => {
    const lines = validProbeLines();
    lines.splice(2, 1, 'ALPINE_TOOLCHAIN_CHECK|shell|missing');
    lines.splice(3, 1, 'ALPINE_TOOLCHAIN_CHECK|transfer|fail');
    const result = parseToolchainProbe(lines.join('\n'), 0);

    expect(result.valid).toBe(true);
    if (!result.valid) return;
    expect(result.report.status).toBe('fail');
    expect(getToolchainCheck(result.report, 'shell').status).toBe('missing');
    expect(getToolchainCheck(result.report, 'transfer').status).toBe('fail');
    expect(getToolchainCheck(result.report, 'apk').fields.version).toBe('apk-tools 2.14.4');
    expect(isToolchainProbeReport(result.report)).toBe(true);
  });

  it('rejects malformed records and missing checks', () => {
    const lines = validProbeLines();
    lines.splice(2, 1, 'ALPINE_TOOLCHAIN_CHECK|not-a-check|pass');
    const result = parseToolchainProbe(lines.join('\n'), 0);
    expect(result.valid).toBe(false);
    if (result.valid) return;
    expect(result.errors.map(item => item.code)).toEqual(
      expect.arrayContaining(['unknown_check', 'missing_check']),
    );

    expect(parseToolchainProbe('ALPINE_TOOLCHAIN_PROBE_V1_BEGIN\nALPINE_TOOLCHAIN_PROBE_V1_END\n', 0)).toEqual(
      expect.objectContaining({valid: false}),
    );
    expect(parseToolchainProbe(null, 0)).toEqual(
      expect.objectContaining({valid: false}),
    );
    expect(parseToolchainProbe(validProbeLines().join('\n'), 256)).toEqual(
      expect.objectContaining({valid: false}),
    );
  });

  it('rejects marker-prefix variants instead of accepting raw substrings', () => {
    const prefixedBegin = validProbeLines();
    prefixedBegin[0] = `${TOOLCHAIN_PROBE_BEGIN}_EXTRA`;
    expect(parseToolchainProbe(prefixedBegin.join('\n'), 0)).toEqual(
      expect.objectContaining({valid: false}),
    );

    const prefixedStatus = validProbeLines();
    prefixedStatus[1] = 'ALPINE_TOOLCHAIN_CHECK|apk|pass-extra';
    const result = parseToolchainProbe(prefixedStatus.join('\n'), 0);
    expect(result.valid).toBe(false);
    if (!result.valid) expect(result.errors.map(item => item.code)).toContain('invalid_status');

    const prefixedEnd = validProbeLines();
    prefixedEnd[prefixedEnd.length - 1] = `${TOOLCHAIN_PROBE_END}_EXTRA`;
    expect(parseToolchainProbe(prefixedEnd.join('\n'), 0)).toEqual(
      expect.objectContaining({valid: false}),
    );
  });

  it('rejects duplicate, oversized, unsafe, and unknown fields', () => {
    const duplicate = validProbeLines();
    duplicate.splice(2, 0, 'ALPINE_TOOLCHAIN_CHECK|apk|pass');
    expect(parseToolchainProbe(duplicate.join('\n'), 0)).toEqual(
      expect.objectContaining({valid: false}),
    );

    const oversized = validProbeLines();
    oversized.splice(2, 0, `ALPINE_TOOLCHAIN_FIELD|apk|version|${'x'.repeat(TOOLCHAIN_PROBE_MAX_FIELD_LENGTH + 1)}`);
    const oversizedResult = parseToolchainProbe(oversized.join('\n'), 0);
    expect(oversizedResult.valid).toBe(false);
    if (!oversizedResult.valid) expect(oversizedResult.errors.map(item => item.code)).toContain('invalid_field');

    const unsafe = validProbeLines();
    unsafe.splice(2, 0, 'ALPINE_TOOLCHAIN_FIELD|apk|version|safe|extra');
    expect(parseToolchainProbe(unsafe.join('\n'), 0)).toEqual(
      expect.objectContaining({valid: false}),
    );

    const unknown = validProbeLines();
    unknown.splice(2, 0, 'ALPINE_TOOLCHAIN_FIELD|apk|not-a-field|value');
    const unknownResult = parseToolchainProbe(unknown.join('\n'), 0);
    expect(unknownResult.valid).toBe(false);
    if (!unknownResult.valid) expect(unknownResult.errors.map(item => item.code)).toContain('unknown_field');
  });

  it('extracts one exact marker block from a noisy terminal transcript', () => {
    const block = validProbeLines().join('\n');
    expect(extractToolchainProbeBlock(`localhost:~# echoed command\n${block}\nlocalhost:~#`)).toBe(block);
    const interleaved = [
      TOOLCHAIN_PROBE_BEGIN,
      'localhost:~# emit checks',
      'ALPINE_TOOLCHAIN_CHECK|apk|pass',
      '(package output)',
      'ALPINE_TOOLCHAIN_PROBE_V1_END',
    ].join('\n');
    expect(extractToolchainProbeBlock(interleaved)).toBe([
      TOOLCHAIN_PROBE_BEGIN,
      'ALPINE_TOOLCHAIN_CHECK|apk|pass',
      TOOLCHAIN_PROBE_END,
    ].join('\n'));
    expect(extractToolchainProbeBlock(`${TOOLCHAIN_PROBE_BEGIN}_EXTRA\n${TOOLCHAIN_PROBE_END}`)).toBeUndefined();
    expect(extractToolchainProbeBlock(`${TOOLCHAIN_PROBE_BEGIN}\nALPINE_TOOLCHAIN_CHECK|apk|pass`)).toBeUndefined();
  });

  it('accepts the complete record set the fixed script emits on a provisioned guest', () => {
    // Drift guard: every literal field record in the script must be part of
    // the fixture below, so the parser is exercised against each field name
    // the real probe can emit.
    const literalFields = Array.from(
      ALPINE_TOOLCHAIN_PROBE_SCRIPT.matchAll(/ALPINE_TOOLCHAIN_FIELD\|[a-z0-9-]+\|[A-Za-z]+\|/g),
    );
    expect(literalFields.length).toBeGreaterThan(0);
    const records = fullProvisionedRecords().join('\n');
    for (const match of literalFields) {
      expect(records).toContain(match[0]);
    }

    const transcript = [
      'localhost:/workspace# set +e',
      'localhost:/workspace# emit \'ALPINE_TOOLCHAIN_PROBE_V1_BEGIN\'',
      'localhost:/workspace# ',
      TOOLCHAIN_PROBE_BEGIN,
      ...fullProvisionedRecords(),
      TOOLCHAIN_PROBE_END,
      'localhost:/workspace# ',
    ].join('\r\n');
    const block = extractToolchainProbeBlock(transcript);
    expect(block).toBe([TOOLCHAIN_PROBE_BEGIN, ...fullProvisionedRecords(), TOOLCHAIN_PROBE_END].join('\n'));

    const result = parseToolchainProbe(block, 0);
    expect(result.valid).toBe(true);
    if (!result.valid) return;
    expect(result.report.status).toBe('pass');
    expect(result.report.checks).toHaveLength(TOOLCHAIN_CHECK_IDS.length);
    expect(getToolchainCheck(result.report, 'ssh').status).toBe('pass');
    expect(getToolchainCheck(result.report, 'node-npm').fields.npm).toBe('/usr/bin/npm');
    expect(isToolchainProbeReport(result.report)).toBe(true);
  });
});
