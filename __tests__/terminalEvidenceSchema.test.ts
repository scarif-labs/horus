import {
  ALPINE_TERMINAL_POC_EVIDENCE_SCHEMA,
  isAlpineGateRecord,
} from '../src/terminal/evidence/gateRecord';

/** Reference example gate record. */
const planExampleRecord = {
  schema: 'alpine-terminal-poc/v1',
  gate: 'p2-pty',
  status: 'passed',
  appCommit: '0123456789abcdef0123456789abcdef01234567',
  device: {
    model: 'Pixel 6a',
    androidApi: 35,
    abi: 'arm64-v8a',
  },
  runtime: {
    prootVersion: '5.4.0',
    rootfsId: 'alpine-3.22-aarch64',
    rootfsSha256: 'a'.repeat(64),
  },
  checks: [
    {
      id: 'pty-ctrl-c',
      status: 'passed',
      exitCode: 0,
      marker: 'pty_ctrl_c_returned',
    },
  ],
  credentialsPresent: false,
};

describe('isAlpineGateRecord', () => {
  it('accepts the reference example record', () => {
    expect(isAlpineGateRecord(planExampleRecord)).toBe(true);
  });

  it('accepts a minimal host-only record without device or runtime blocks', () => {
    expect(
      isAlpineGateRecord({
        schema: ALPINE_TERMINAL_POC_EVIDENCE_SCHEMA,
        gate: 'p0-baseline',
        status: 'passed',
        appCommit: '88b2aa2checkpoint',
        checks: [{id: 'typecheck', status: 'passed', exitCode: 0}],
        credentialsPresent: false,
      }),
    ).toBe(true);
  });

  it('rejects a wrong schema identifier', () => {
    expect(isAlpineGateRecord({...planExampleRecord, schema: 'alpine-terminal-poc/v2'})).toBe(false);
  });

  it('rejects records that claim credentials are present', () => {
    expect(
      isAlpineGateRecord({...planExampleRecord, credentialsPresent: true}),
    ).toBe(false);
  });

  it('rejects unknown gate or check statuses', () => {
    expect(isAlpineGateRecord({...planExampleRecord, status: 'complete'})).toBe(false);
    expect(
      isAlpineGateRecord({
        ...planExampleRecord,
        checks: [{id: 'probe', status: 'unknown'}],
      }),
    ).toBe(false);
  });

  it('rejects a missing app commit or check identifier', () => {
    expect(isAlpineGateRecord({...planExampleRecord, appCommit: ''})).toBe(false);
    expect(
      isAlpineGateRecord({
        ...planExampleRecord,
        checks: [{id: '', status: 'passed'}],
      }),
    ).toBe(false);
  });

  it('rejects malformed device and runtime blocks', () => {
    expect(isAlpineGateRecord({...planExampleRecord, device: {model: 'Pixel 6a'}})).toBe(false);
    expect(
      isAlpineGateRecord({...planExampleRecord, runtime: {rootfsSha256: 'not-a-digest'}}),
    ).toBe(false);
    expect(isAlpineGateRecord({...planExampleRecord, runtime: 'none'})).toBe(false);
  });
});
