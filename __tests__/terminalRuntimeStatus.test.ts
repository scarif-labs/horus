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
  isTerminalRuntimeStatusResponse,
  importRootfs,
  installRootfs,
  readTerminalDebugLog,
  resetRuntime,
  readTerminalRuntimeStatus,
  TERMINAL_RUNTIME_SCHEMA_VERSION,
} from '../src/terminal/runtimeStatus';
import {
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
