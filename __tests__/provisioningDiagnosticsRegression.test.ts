import {provisionAlpineToolchain} from '../src/terminal/runtimeStatus';
import {installFailureDetails} from '../src/terminal/installFailure';
import {installFailureIssueUrl} from '../src/terminal/InstallFailedOverlay';

const wire = {
  requestId: 'diagnostic-1', status: 'error', errorCode: 'toolchain_install_failed',
  outcome: 'failed', stage: 'apk_failed', exitCode: 23,
  log: 'No space left on device.',
} as const;

test('preserves a native failure that happened before any terminal attachment', async () => {
  const result = await provisionAlpineToolchain('diagnostic-1', 'shell', {
    provisionToolchain: jest.fn(async () => wire),
  });
  expect(result).toMatchObject({kind: 'error', errorCode: 'toolchain_install_failed', diagnostics: {
    outcome: 'failed', stage: 'apk_failed', exitCode: 23, log: wire.log,
  }});
});

test('an output-free failure report explains that no diagnostic output exists', () => {
  const url = installFailureIssueUrl('shell', {kind: 'unknown'}, {androidVersion: '33'});
  const body = decodeURIComponent(url.split('&body=')[1]);
  expect(body).not.toContain('(empty)');
  expect(body).toContain('No diagnostic output');
});

test('shareable reports do not include raw commands, secrets, URLs or private paths', () => {
  const url = installFailureIssueUrl('codex', {kind: 'unknown', log:
    'echo --password SYNTHETIC_PASSWORD\nTOKEN=SYNTHETIC_TOKEN\nhttps://example.invalid/private?token=SYNTHETIC_URL_TOKEN\n/home/private-user/private-project\nNo space left on device.'}, {androidVersion: '33'});
  const body = decodeURIComponent(url.split('&body=')[1]);
  for (const value of ['SYNTHETIC_', 'private-user', 'example.invalid', 'echo --password']) expect(body).not.toContain(value);
  expect(body).toContain('No space left on device.');
});

// The same corpus is read by the JVM boundary test to prevent redaction drift.
const cases: Array<{raw: string; safe: string}> = require('../android/app/src/test/resources/provisioning-diagnostics.json');
const {sanitizeProvisionLog, EMPTY_DIAGNOSTICS, OMITTED_DIAGNOSTICS} = jest.requireActual('../src/terminal/provisioningDiagnostics');

test.each(cases)('safe diagnostic projection: $safe', ({raw, safe}) => {
  expect(sanitizeProvisionLog(raw)).toBe(safe);
  expect(sanitizeProvisionLog(safe)).toBe(safe);
  const failure = {kind: 'unknown' as const, log: raw};
  const display = installFailureDetails(failure);
  const report = decodeURIComponent(installFailureIssueUrl('shell', failure, {androidVersion: '36'}));
  expect(display).toContain(safe);
  expect(report).toContain(safe);
  for (const text of [display, report]) {
    expect(text).not.toMatch(/SYNTHETIC_|private\.invalid|\ufffd/);
    expect(text).not.toContain('\u001b');
    expect(text).not.toContain('\u0000');
  }
});

test.each(['timed_out', 'cancelled', 'spawn_failed', 'not_started', 'incomplete'] as const)('preserves %s outcome through the bridge wrapper', async outcome => {
  const result = await provisionAlpineToolchain('diagnostic-1', 'shell', {
    provisionToolchain: jest.fn(async () => ({...wire, outcome})),
  });
  expect(result).toMatchObject({kind: 'error', diagnostics: {outcome, stage: 'apk_failed', exitCode: 23}});
});

test.each([
  {outcome: 'succeeded'}, {outcome: 'private-value'}, {stage: '/private/home'},
  {exitCode: -1}, {exitCode: 1.5}, {commandExitCode: 999}, {log: 'x'.repeat(4097)}, {log: 4},
  {status: 'success'}, {errorCode: 'arbitrary secret'}, {requestId: 'wrong'},
])('rejects malformed or contradictory native diagnostics: %p', async change => {
  const result = await provisionAlpineToolchain('diagnostic-1', 'shell', {
    provisionToolchain: jest.fn(async () => ({...wire, ...change}) as never),
  });
  expect(result).toMatchObject({kind: 'error', errorCode: 'invalid_response'});
});

test('supports old native error replies and genuine success without diagnostics', async () => {
  for (const status of ['error', 'success'] as const) {
    const result = await provisionAlpineToolchain('diagnostic-1', 'shell', {
      provisionToolchain: jest.fn(async () => ({requestId: 'diagnostic-1', status, ...(status === 'error' ? {errorCode: 'toolchain_install_failed' as const} : {})})),
    });
    expect(result.kind).toBe(status === 'error' ? 'error' : 'success');
    expect(result).not.toHaveProperty('diagnostics');
  }
});

test('oversized, control-filled, unknown and repeated output is bounded and never raw', () => {
  expect(sanitizeProvisionLog('SECRET'.repeat(10000))).toBe(OMITTED_DIAGNOSTICS);
  expect(sanitizeProvisionLog('ENOSPC PRIVATE='.repeat(10000))).toBe(OMITTED_DIAGNOSTICS);
  expect(sanitizeProvisionLog(' \n\r\t')).toBe(EMPTY_DIAGNOSTICS);
  expect(sanitizeProvisionLog('\u001b[31mENOSPC password=SECRET\u001b[0m')).toBe('No space left on device.');
  const body = decodeURIComponent(installFailureIssueUrl('shell', {
    kind: 'unknown', errorCode: 'SECRET', stage: 'SECRET', log: 'No space left on device.\n'.repeat(10000),
  }, {appVersion: 'SECRET', androidVersion: 'SECRET'}));
  expect(body).not.toContain('SECRET');
  expect(body.length).toBeLessThan(2000);
});

test('invalid UTF-8 decoded output is sanitized before native response display and issue drafting', async () => {
  const raw = new TextDecoder('utf-8').decode(Uint8Array.from([0xff, 0xfe, 0xc0, 0x0a])) + 'ENOSPC password=SYNTHETIC_SECRET';
  expect(raw).toContain('\ufffd');
  const result = await provisionAlpineToolchain('diagnostic-1', 'shell', {
    provisionToolchain: jest.fn(async () => ({...wire, log: raw})),
  });
  expect(result).toMatchObject({kind: 'error', diagnostics: {log: 'No space left on device.'}});
  if (result.kind !== 'error') throw new Error('Expected controlled failure');
  const failure = {kind: 'unknown' as const, diagnostics: result.diagnostics};
  const display = installFailureDetails(failure);
  const report = decodeURIComponent(installFailureIssueUrl('shell', failure, {androidVersion: '36'}));
  for (const text of [display, report]) {
    expect(text).toContain('No space left on device.');
    expect(text).not.toMatch(/SYNTHETIC_|\ufffd/);
  }
});
