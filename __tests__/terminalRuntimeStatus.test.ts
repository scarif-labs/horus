import nativeTerminalRuntime from '../src/native/NativeTerminalRuntime';
import type {TerminalRuntimeStatusResponse} from '../src/native/NativeTerminalRuntime';

jest.mock('../src/native/NativeTerminalRuntime', () => {
  const actual = jest.requireActual('../src/native/NativeTerminalRuntime');
  return {
    ...actual,
    __esModule: true,
    default: {getRuntimeStatus: jest.fn()},
  };
});

import {
  activateRootfs,
  readProvisionProgress,
  isTerminalRuntimeStatusResponse,
  importRootfs,
  installRootfs,
  readTerminalDebugLog,
  resetRuntime,
  readTerminalRuntimeStatus,
  TERMINAL_RUNTIME_SCHEMA_VERSION,
} from '../src/terminal/runtimeStatus';
import {
  DEBIAN_ROOTFS,
  PINNED_ALPINE_RELEASE,
  PINNED_PROBE_MARKERS,
  PINNED_ROOTFS_ID,
  PINNED_ROOTFS_SHA256,
  PINNED_ROOTFS_SIZE_BYTES,
} from '../src/terminal/distroContract';

const statusMock = (nativeTerminalRuntime as unknown as {getRuntimeStatus: jest.Mock}).getRuntimeStatus;

function successResponse(
  overrides: Partial<TerminalRuntimeStatusResponse> = {},
): TerminalRuntimeStatusResponse {
  return {
    status: 'success',
    schemaVersion: TERMINAL_RUNTIME_SCHEMA_VERSION,
    runtimeState: 'not_installed',
    runtimeVersion: 'p2-pty',
    abi: 'arm64-v8a',
    apiLevel: 35,
    appVersion: '0.0.1',
    storageRoot: '/data/user/0/com.scariflabs.horus/files/horus',
    prootAvailable: true,
    prootVersion: '5.4.0',
    installedVersionIds: [],
    ...overrides,
  };
}

describe('isTerminalRuntimeStatusResponse', () => {
  it('accepts a complete success response', () => {
    expect(isTerminalRuntimeStatusResponse(successResponse())).toBe(true);
  });

  it('rejects a success response missing capability fields', () => {
    const {storageRoot, ...missingStorageRoot} = successResponse();
    expect(storageRoot).toBeDefined();
    expect(isTerminalRuntimeStatusResponse(missingStorageRoot)).toBe(false);
    expect(isTerminalRuntimeStatusResponse(successResponse({abi: ''}))).toBe(false);
    expect(isTerminalRuntimeStatusResponse(successResponse({apiLevel: 0}))).toBe(false);
  });

  it('rejects an unexpected schema version or state', () => {
    expect(isTerminalRuntimeStatusResponse(successResponse({schemaVersion: 1}))).toBe(false);
    expect(
      isTerminalRuntimeStatusResponse(
        successResponse({runtimeState: 'installed' as TerminalRuntimeStatusResponse['runtimeState']}),
      ),
    ).toBe(false);
  });

  it('accepts the ready state with an active rootfs', () => {
    expect(
      isTerminalRuntimeStatusResponse(
        successResponse({
          runtimeState: 'ready',
          activeRootfsId: PINNED_ROOTFS_ID,
          activeDistro: 'alpine',
          activeRelease: PINNED_ALPINE_RELEASE,
          activeAlpineRelease: PINNED_ALPINE_RELEASE,
          activeRootfsSha256: PINNED_ROOTFS_SHA256,
          activeInstalledAt: '2026-09-08T00:00:00.000Z',
          installedVersionIds: [PINNED_ROOTFS_ID],
        }),
      ),
    ).toBe(true);
    expect(isTerminalRuntimeStatusResponse(successResponse({installedVersionIds: ['../escape']}))).toBe(false);
    expect(isTerminalRuntimeStatusResponse(successResponse({prootAvailable: undefined}))).toBe(false);
  });

  it('requires the active distro and release to match the catalog entry', () => {
    const ready = {
      runtimeState: 'ready' as const,
      activeRootfsId: PINNED_ROOTFS_ID,
      activeDistro: 'alpine',
      activeRelease: PINNED_ALPINE_RELEASE,
      activeAlpineRelease: PINNED_ALPINE_RELEASE,
      activeRootfsSha256: PINNED_ROOTFS_SHA256,
      activeInstalledAt: '2026-09-08T00:00:00.000Z',
      installedVersionIds: [PINNED_ROOTFS_ID],
    };
    expect(isTerminalRuntimeStatusResponse(successResponse(ready))).toBe(true);
    expect(isTerminalRuntimeStatusResponse(successResponse({...ready, activeDistro: undefined}))).toBe(false);
    expect(isTerminalRuntimeStatusResponse(successResponse({...ready, activeDistro: 'debian'}))).toBe(false);
    expect(isTerminalRuntimeStatusResponse(successResponse({...ready, activeRelease: '3.23.0'}))).toBe(false);
    // Alpine keeps reporting activeAlpineRelease, and it must agree.
    expect(isTerminalRuntimeStatusResponse(successResponse({...ready, activeAlpineRelease: undefined}))).toBe(false);
    expect(isTerminalRuntimeStatusResponse(successResponse({...ready, activeAlpineRelease: '3.23.0'}))).toBe(false);
    // A rootfs the catalog does not know is never ready.
    expect(
      isTerminalRuntimeStatusResponse(
        successResponse({...ready, activeRootfsId: 'debian-13-aarch64', installedVersionIds: ['debian-13-aarch64']}),
      ),
    ).toBe(false);
    expect(
      isTerminalRuntimeStatusResponse(successResponse({runtimeState: 'not_installed', activeDistro: 'alpine'})),
    ).toBe(false);
  });

  it('accepts an active debian rootfs, which never reports activeAlpineRelease', () => {
    const debian = {
      runtimeState: 'ready' as const,
      activeRootfsId: DEBIAN_ROOTFS.rootfsId,
      activeDistro: 'debian',
      activeRelease: DEBIAN_ROOTFS.release,
      activeRootfsSha256: DEBIAN_ROOTFS.sha256,
      activeInstalledAt: '2026-10-12T00:00:00.000Z',
      installedVersionIds: [PINNED_ROOTFS_ID, DEBIAN_ROOTFS.rootfsId],
    };
    expect(isTerminalRuntimeStatusResponse(successResponse(debian))).toBe(true);
    expect(isTerminalRuntimeStatusResponse(successResponse({...debian, activeAlpineRelease: '13.7'}))).toBe(false);
    expect(isTerminalRuntimeStatusResponse(successResponse({...debian, activeDistro: 'alpine'}))).toBe(false);
    expect(isTerminalRuntimeStatusResponse(successResponse({...debian, activeRootfsSha256: PINNED_ROOTFS_SHA256}))).toBe(false);
    expect(isTerminalRuntimeStatusResponse(successResponse({...debian, installedVersionIds: [PINNED_ROOTFS_ID]}))).toBe(false);
  });

  it('rejects active metadata that disagrees with runtime state', () => {
    expect(
      isTerminalRuntimeStatusResponse(
        successResponse({runtimeState: 'not_installed', activeRootfsId: PINNED_ROOTFS_ID}),
      ),
    ).toBe(false);
    expect(
      isTerminalRuntimeStatusResponse(
        successResponse({
          runtimeState: 'ready',
          activeRootfsId: PINNED_ROOTFS_ID,
          activeDistro: 'alpine',
          activeRelease: PINNED_ALPINE_RELEASE,
          activeAlpineRelease: PINNED_ALPINE_RELEASE,
          activeRootfsSha256: PINNED_ROOTFS_SHA256,
          activeInstalledAt: '2026-09-08T00:00:00.000Z',
          installedVersionIds: [],
        }),
      ),
    ).toBe(false);
    expect(isTerminalRuntimeStatusResponse({...successResponse(), rootfsBytes: 1})).toBe(false);
    expect(isTerminalRuntimeStatusResponse({...successResponse(), homeBytes: 1})).toBe(false);
  });

  it('accepts only the declared error shape', () => {
    expect(isTerminalRuntimeStatusResponse({status: 'error', errorCode: 'internal_error'})).toBe(true);
    expect(isTerminalRuntimeStatusResponse({status: 'error', errorCode: 'other'})).toBe(false);
    expect(isTerminalRuntimeStatusResponse({status: 'error'})).toBe(false);
  });

  it('rejects non-object and unknown-status values', () => {
    expect(isTerminalRuntimeStatusResponse(null)).toBe(false);
    expect(isTerminalRuntimeStatusResponse('success')).toBe(false);
    expect(isTerminalRuntimeStatusResponse({status: 'pending'})).toBe(false);
  });
});

describe('readTerminalRuntimeStatus', () => {
  beforeEach(() => {
    statusMock.mockReset();
  });

  it('returns the typed success view for a complete response', async () => {
    statusMock.mockResolvedValue(successResponse());
    await expect(readTerminalRuntimeStatus()).resolves.toEqual({
      kind: 'success',
      schemaVersion: TERMINAL_RUNTIME_SCHEMA_VERSION,
      runtimeState: 'not_installed',
      runtimeVersion: 'p2-pty',
      abi: 'arm64-v8a',
      apiLevel: 35,
      appVersion: '0.0.1',
      storageRoot: '/data/user/0/com.scariflabs.horus/files/horus',
      prootAvailable: true,
      prootVersion: '5.4.0',
      installedVersionIds: [],
    });
  });

  it('maps an invalid wire response to invalid_response', async () => {
    statusMock.mockResolvedValue({status: 'success'});
    await expect(readTerminalRuntimeStatus()).resolves.toEqual({
      kind: 'error',
      errorCode: 'invalid_response',
    });
  });

  it('maps a native rejection to internal_error', async () => {
    statusMock.mockRejectedValue(new Error('bridge failure'));
    await expect(readTerminalRuntimeStatus()).resolves.toEqual({
      kind: 'error',
      errorCode: 'internal_error',
    });
  });

  it('maps a declared native error response to internal_error', async () => {
    statusMock.mockResolvedValue({status: 'error', errorCode: 'internal_error'});
    await expect(readTerminalRuntimeStatus()).resolves.toEqual({
      kind: 'error',
      errorCode: 'internal_error',
    });
  });

  it('reports unavailable when the native module is missing', async () => {
    await expect(readTerminalRuntimeStatus(null)).resolves.toEqual({
      kind: 'error',
      errorCode: 'unavailable',
    });
  });
});

describe('readTerminalDebugLog', () => {
  it('returns the bounded native diagnostic tail', async () => {
    await expect(readTerminalDebugLog({
      getDebugLog: async () => ({
        status: 'success' as const,
        path: '/data/user/0/com.scariflabs.horus/files/horus/diagnostics/terminal-debug.log',
        externalPath: '/sdcard/Android/data/com.scariflabs.horus/files/diagnostics/terminal-debug.log',
        bytes: 128,
        tail: 'event=client_request_timeout',
      }),
    })).resolves.toEqual({
      kind: 'success',
      path: '/data/user/0/com.scariflabs.horus/files/horus/diagnostics/terminal-debug.log',
      externalPath: '/sdcard/Android/data/com.scariflabs.horus/files/diagnostics/terminal-debug.log',
      bytes: 128,
      tail: 'event=client_request_timeout',
    });
  });

  it('rejects an unbounded or malformed native dump', async () => {
    await expect(readTerminalDebugLog({
      getDebugLog: async () => ({
        status: 'success' as const,
        path: '/tmp/debug.log',
        bytes: 256 * 1024 + 1,
        tail: '',
      }),
    })).resolves.toEqual({kind: 'error', errorCode: 'invalid_response'});
    await expect(readTerminalDebugLog({
      getDebugLog: async () => ({status: 'error' as const, errorCode: 'internal_error' as const}),
    })).resolves.toEqual({kind: 'error', errorCode: 'internal_error'});
  });
});

describe('typed install/reset wrappers', () => {
  it('builds and validates the pinned install response', async () => {
    const runtime = {
      installRootfs: jest.fn().mockResolvedValue({
        requestId: 'install-1',
        status: 'success',
        rootfsId: PINNED_ROOTFS_ID,
        archiveSha256: PINNED_ROOTFS_SHA256,
        archiveBytes: PINNED_ROOTFS_SIZE_BYTES,
        extractionFiles: 356,
        probeExitCode: 0,
        probeMarkers: [...PINNED_PROBE_MARKERS],
        reusedCache: false,
        durationMs: 12,
      }),
    };

    await expect(installRootfs('install-1', runtime)).resolves.toEqual({
      kind: 'success',
      requestId: 'install-1',
      rootfsId: PINNED_ROOTFS_ID,
      archiveSha256: PINNED_ROOTFS_SHA256,
      archiveBytes: PINNED_ROOTFS_SIZE_BYTES,
      extractionFiles: 356,
      probeExitCode: 0,
      probeMarkers: [...PINNED_PROBE_MARKERS],
      reusedCache: false,
      durationMs: 12,
    });
    expect(runtime.installRootfs).toHaveBeenCalledWith({
      requestId: 'install-1',
      rootfsId: PINNED_ROOTFS_ID,
    });
  });

  it('installs another distribution beside the active one when asked', async () => {
    const runtime = {
      installRootfs: jest.fn().mockResolvedValue({
        requestId: 'install-2',
        status: 'success',
        rootfsId: DEBIAN_ROOTFS.rootfsId,
        archiveSha256: DEBIAN_ROOTFS.sha256,
        archiveBytes: DEBIAN_ROOTFS.sizeBytes,
        extractionFiles: 2677,
        probeExitCode: 0,
        probeMarkers: [...DEBIAN_ROOTFS.probeMarkers],
        reusedCache: false,
        durationMs: 14_513,
      }),
    };
    await expect(installRootfs('install-2', runtime, {rootfsId: DEBIAN_ROOTFS.rootfsId, makeActive: false}))
      .resolves.toMatchObject({kind: 'success', rootfsId: DEBIAN_ROOTFS.rootfsId});
    expect(runtime.installRootfs).toHaveBeenCalledWith({
      requestId: 'install-2',
      rootfsId: DEBIAN_ROOTFS.rootfsId,
      makeActive: false,
    });
    await expect(installRootfs('install-3', runtime, {rootfsId: 'debian-13-aarch64'}))
      .resolves.toEqual({kind: 'error', requestId: 'install-3', errorCode: 'invalid_request'});
  });

  it('passes on only well-formed package progress lines', async () => {
    const reply = (line: unknown) => ({readProvisionProgress: jest.fn().mockResolvedValue({status: 'success', line})});
    await expect(readProvisionProgress(reply('Downloading packages… 37%'))).resolves.toBe('Downloading packages… 37%');
    await expect(readProvisionProgress(reply('Installing packages… 100%'))).resolves.toBe('Installing packages… 100%');
    await expect(readProvisionProgress(reply(undefined))).resolves.toBeUndefined();
    await expect(readProvisionProgress(reply('rm -rf / … 5%'))).resolves.toBeUndefined();
    await expect(readProvisionProgress(reply('Installing packages… 1000%'))).resolves.toBeUndefined();
    await expect(readProvisionProgress({readProvisionProgress: jest.fn().mockRejectedValue(new Error('gone'))})).resolves.toBeUndefined();
  });

  it('activates an installed rootfs and validates the answer', async () => {
    const runtime = {
      activateRootfs: jest.fn().mockResolvedValue({requestId: 'act-1', status: 'success', rootfsId: DEBIAN_ROOTFS.rootfsId}),
    };
    await expect(activateRootfs('act-1', DEBIAN_ROOTFS.rootfsId, runtime))
      .resolves.toEqual({kind: 'success', requestId: 'act-1', rootfsId: DEBIAN_ROOTFS.rootfsId});
    expect(runtime.activateRootfs).toHaveBeenCalledWith({requestId: 'act-1', rootfsId: DEBIAN_ROOTFS.rootfsId});

    runtime.activateRootfs.mockResolvedValue({requestId: 'act-1', status: 'error', errorCode: 'not_installed'});
    await expect(activateRootfs('act-1', DEBIAN_ROOTFS.rootfsId, runtime))
      .resolves.toEqual({kind: 'error', requestId: 'act-1', errorCode: 'not_installed'});

    // A success for a different rootfs than asked for is not trusted.
    runtime.activateRootfs.mockResolvedValue({requestId: 'act-1', status: 'success', rootfsId: PINNED_ROOTFS_ID});
    await expect(activateRootfs('act-1', DEBIAN_ROOTFS.rootfsId, runtime))
      .resolves.toEqual({kind: 'error', requestId: 'act-1', errorCode: 'invalid_response'});
    await expect(activateRootfs('act-1', 'debian-13-aarch64', runtime))
      .resolves.toEqual({kind: 'error', requestId: 'act-1', errorCode: 'invalid_request'});
  });

  it('imports a picked archive through the same validation', async () => {
    const runtime = {
      importRootfs: jest.fn().mockResolvedValue({
        requestId: 'import-1',
        status: 'success',
        rootfsId: PINNED_ROOTFS_ID,
        archiveSha256: PINNED_ROOTFS_SHA256,
        archiveBytes: PINNED_ROOTFS_SIZE_BYTES,
        extractionFiles: 356,
        probeExitCode: 0,
        probeMarkers: [...PINNED_PROBE_MARKERS],
        reusedCache: false,
        durationMs: 12,
      }),
    };
    await expect(importRootfs('import-1', runtime)).resolves.toMatchObject({kind: 'success', requestId: 'import-1'});
    expect(runtime.importRootfs).toHaveBeenCalledWith({requestId: 'import-1', rootfsId: PINNED_ROOTFS_ID});

    runtime.importRootfs.mockResolvedValue({requestId: 'import-1', status: 'error', errorCode: 'import_cancelled'});
    await expect(importRootfs('import-1', runtime)).resolves.toEqual({
      kind: 'error',
      requestId: 'import-1',
      errorCode: 'import_cancelled',
    });

    runtime.importRootfs.mockResolvedValue({requestId: 'import-1', status: 'success', archiveBytes: 1});
    await expect(importRootfs('import-1', runtime)).resolves.toMatchObject({kind: 'error', errorCode: 'invalid_response'});
  });

  it('rejects malformed install errors and invalid requests before the bridge', async () => {
    const runtime = {installRootfs: jest.fn()};
    await expect(installRootfs('bad id', runtime)).resolves.toEqual({
      kind: 'error',
      requestId: 'bad id',
      errorCode: 'invalid_request',
    });
    expect(runtime.installRootfs).not.toHaveBeenCalled();

    runtime.installRootfs.mockResolvedValue({
      requestId: 'install-1',
      status: 'error',
      errorCode: 'not-a-real-error',
    });
    await expect(installRootfs('install-1', runtime)).resolves.toEqual({
      kind: 'error',
      requestId: 'install-1',
      errorCode: 'invalid_response',
    });
  });

  it('validates reset scope and prevents scope escalation', async () => {
    const runtime = {
      resetRuntime: jest.fn().mockResolvedValue({
        requestId: 'reset-1',
        status: 'success',
        removedVersionIds: [PINNED_ROOTFS_ID],
        homeRemoved: false,
        workspacesRemoved: false,
      }),
    };

    await expect(resetRuntime('reset-1', 'rootfs', runtime)).resolves.toEqual({
      kind: 'success',
      requestId: 'reset-1',
      removedVersionIds: [PINNED_ROOTFS_ID],
      homeRemoved: false,
      workspacesRemoved: false,
    });

    runtime.resetRuntime.mockResolvedValue({
      requestId: 'reset-1',
      status: 'success',
      removedVersionIds: [],
      homeRemoved: true,
      workspacesRemoved: false,
    });
    await expect(resetRuntime('reset-1', 'rootfs', runtime)).resolves.toEqual({
      kind: 'error',
      requestId: 'reset-1',
      errorCode: 'invalid_response',
    });

    runtime.resetRuntime.mockResolvedValue({
      requestId: 'reset-1',
      status: 'success',
      removedVersionIds: [],
      homeRemoved: true,
      workspacesRemoved: false,
    });
    await expect(resetRuntime('reset-1', 'home', runtime)).resolves.toEqual({
      kind: 'success',
      requestId: 'reset-1',
      removedVersionIds: [],
      homeRemoved: true,
      workspacesRemoved: false,
    });

    runtime.resetRuntime.mockResolvedValue({
      requestId: 'reset-1',
      status: 'success',
      removedVersionIds: [],
      homeRemoved: false,
      workspacesRemoved: false,
    });
    await expect(resetRuntime('reset-1', 'workspace', runtime)).resolves.toEqual({
      kind: 'error',
      requestId: 'reset-1',
      errorCode: 'invalid_response',
    });
  });
});
