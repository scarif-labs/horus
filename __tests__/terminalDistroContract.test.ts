import {
  buildInstallRootfsRequest,
  buildResetRuntimeRequest,
  isInstallRootfsResponse,
  isResetRuntimeResponse,
  isValidTerminalRuntimeErrorCode,
  isValidRequestId,
  isValidRootfsId,
  isValidSha256Digest,
  PINNED_PROBE_MARKERS,
  PINNED_ROOTFS_ID,
  PINNED_ROOTFS_SHA256,
  PINNED_ROOTFS_SIZE_BYTES,
  TERMINAL_RUNTIME_SCHEMA_VERSION,
  TERMINAL_RUNTIME_VERSION,
} from '../src/terminal/distroContract';

describe('request builders', () => {
  it('builds the pinned install request and rejects malformed ids', () => {
    expect(buildInstallRootfsRequest('install-2026-09-07.1')).toEqual({
      requestId: 'install-2026-09-07.1',
      rootfsId: PINNED_ROOTFS_ID,
    });
    expect(buildInstallRootfsRequest('')).toBeNull();
    expect(buildInstallRootfsRequest('bad id!')).toBeNull();
    expect(buildInstallRootfsRequest('x'.repeat(65))).toBeNull();
  });

  it('builds scoped reset requests only', () => {
    expect(buildResetRuntimeRequest('reset-1', 'rootfs')).toEqual({requestId: 'reset-1', scope: 'rootfs'});
    expect(buildResetRuntimeRequest('reset-2', 'all-user-data')).toEqual({
      requestId: 'reset-2',
      scope: 'all-user-data',
    });
    expect(buildResetRuntimeRequest('reset-3', 'home')).toEqual({requestId: 'reset-3', scope: 'home'});
    expect(buildResetRuntimeRequest('reset-4', 'workspace')).toEqual({
      requestId: 'reset-4',
      scope: 'workspace',
    });
    expect(buildResetRuntimeRequest('reset-5', 'everything' as 'rootfs')).toBeNull();
    expect(buildResetRuntimeRequest('', 'rootfs')).toBeNull();
  });

  it('exposes the pinned catalog constants the native side mirrors', () => {
    expect(TERMINAL_RUNTIME_SCHEMA_VERSION).toBe(4);
    expect(TERMINAL_RUNTIME_VERSION).toBe('p2-pty');
    expect(PINNED_ROOTFS_ID).toBe('alpine-3.24.0-aarch64');
    expect(PINNED_ROOTFS_SHA256).toMatch(/^[a-f0-9]{64}$/);
  });
});

describe('rootfs id and request id validation', () => {
  it('accepts catalog-shaped rootfs ids and rejects traversal', () => {
    expect(isValidRootfsId('alpine-3.24.0-aarch64')).toBe(true);
    expect(isValidRootfsId('../escape')).toBe(false);
    expect(isValidRootfsId('/absolute')).toBe(false);
    expect(isValidRootfsId('')).toBe(false);
    expect(isValidRootfsId('UPPER')).toBe(false);
  });

  it('accepts bounded request ids from the documented alphabet', () => {
    expect(isValidRequestId('abc-1.2:3_x')).toBe(true);
    expect(isValidRequestId('has space')).toBe(false);
    expect(isValidRequestId('x'.repeat(65))).toBe(false);
  });

  it('accepts only the mirrored native error-code set', () => {
    expect(isValidTerminalRuntimeErrorCode('digest_mismatch')).toBe(true);
    expect(isValidTerminalRuntimeErrorCode('size_mismatch')).toBe(false);
    expect(isValidTerminalRuntimeErrorCode('')).toBe(false);
  });

  it('accepts only canonical lowercase SHA-256 digests', () => {
    expect(isValidSha256Digest(PINNED_ROOTFS_SHA256)).toBe(true);
    expect(isValidSha256Digest('A'.repeat(64))).toBe(false);
    expect(isValidSha256Digest('a'.repeat(63))).toBe(false);
    expect(isValidSha256Digest('a'.repeat(65))).toBe(false);
    expect(isValidSha256Digest(null)).toBe(false);
  });
});

describe('isInstallRootfsResponse', () => {
  const success = {
    requestId: 'install-1',
    status: 'success',
    rootfsId: PINNED_ROOTFS_ID,
    archiveSha256: PINNED_ROOTFS_SHA256,
    archiveBytes: PINNED_ROOTFS_SIZE_BYTES,
    extractionFiles: 356,
    probeExitCode: 0,
    probeMarkers: [...PINNED_PROBE_MARKERS],
    reusedCache: false,
    durationMs: 12_345,
  };

  it('accepts a complete success response', () => {
    expect(isInstallRootfsResponse(success)).toBe(true);
  });

  it('accepts the declared error shape and rejects malformed ones', () => {
    expect(isInstallRootfsResponse({requestId: 'install-1', status: 'error', errorCode: 'digest_mismatch'})).toBe(true);
    expect(isInstallRootfsResponse({requestId: 'install-1', status: 'error'})).toBe(false);
    expect(isInstallRootfsResponse({requestId: '../bad', status: 'error', errorCode: 'download_failed'})).toBe(false);
  });

  it('rejects a success with a malformed digest or unknown rootfs id', () => {
    expect(isInstallRootfsResponse({...success, archiveSha256: 'not-a-digest'})).toBe(false);
    expect(isInstallRootfsResponse({...success, rootfsId: '../escape'})).toBe(false);
    expect(isInstallRootfsResponse({...success, durationMs: 1.5})).toBe(false);
    expect(isInstallRootfsResponse({...success, probeMarkers: 'alpine_probe_begin'})).toBe(false);
    expect(isInstallRootfsResponse({...success, archiveBytes: Number.MAX_SAFE_INTEGER + 1})).toBe(false);
    expect(isInstallRootfsResponse({...success, probeExitCode: 1})).toBe(false);
    expect(isInstallRootfsResponse({...success, probeMarkers: [...success.probeMarkers.slice(0, -1), 'wrong-marker']})).toBe(false);
  });

  it('rejects unknown or contradictory install errors', () => {
    expect(isInstallRootfsResponse({requestId: 'install-1', status: 'error', errorCode: 'size_mismatch'})).toBe(false);
    expect(
      isInstallRootfsResponse({
        requestId: 'install-1',
        status: 'error',
        errorCode: 'digest_mismatch',
        rootfsId: PINNED_ROOTFS_ID,
      }),
    ).toBe(false);
  });
});

describe('isResetRuntimeResponse', () => {
  it('accepts a scoped success and rejects malformed variants', () => {
    expect(
      isResetRuntimeResponse({
        requestId: 'reset-1',
        status: 'success',
        removedVersionIds: [PINNED_ROOTFS_ID],
        homeRemoved: false,
        workspacesRemoved: false,
      }),
    ).toBe(true);
    expect(isResetRuntimeResponse({requestId: 'reset-1', status: 'error', errorCode: 'invalid_request'})).toBe(true);
    expect(isResetRuntimeResponse({requestId: 'reset-1', status: 'success', removedVersionIds: ['../escape']})).toBe(false);
    expect(isResetRuntimeResponse({requestId: 'reset-1', status: 'success', removedVersionIds: [PINNED_ROOTFS_ID, PINNED_ROOTFS_ID], homeRemoved: false, workspacesRemoved: false})).toBe(false);
    expect(isResetRuntimeResponse({requestId: 'reset-1', status: 'success', homeRemoved: 'no'})).toBe(false);
    expect(isResetRuntimeResponse({status: 'success'})).toBe(false);
    expect(isResetRuntimeResponse({requestId: 'reset-1', status: 'error', errorCode: 'not-real'})).toBe(false);
    expect(isResetRuntimeResponse({requestId: 'reset-1', status: 'error', errorCode: 'internal_error', homeRemoved: false})).toBe(false);
  });
});
