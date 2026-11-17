import {classifyInstallFailure, cleanInstallLog, installFailureCopy} from '../src/terminal/installFailure';

describe('installFailure', () => {
  test('trusts Android about being offline, then reads the log', () => {
    expect(classifyInstallFailure('', 'codex_failed', false)).toBe('offline');
    expect(classifyInstallFailure('npm error code ENOTFOUND', 'codex_failed', true)).toBe('network');
    expect(classifyInstallFailure('curl: (6) Could not resolve host: claude.ai', 'claude_failed', undefined)).toBe('network');
    expect(classifyInstallFailure('E: Failed to fetch http://deb.debian.org/debian/pool/x.deb', 'apk_failed', undefined)).toBe('network');
    expect(classifyInstallFailure('npm error code ENOSPC', 'codex_failed', true)).toBe('storage');
    expect(classifyInstallFailure('npm warn EBADENGINE Unsupported engine', 'codex_failed', true)).toBe('node_version');
    expect(classifyInstallFailure('EACCES: permission denied', 'codex_failed', true)).toBe('permissions');
    expect(classifyInstallFailure('ERROR: unsatisfiable constraints', 'apk_failed', true)).toBe('packages');
    expect(classifyInstallFailure('', 'codex_failed', true)).toBe('unknown');
  });

  test('reads Alpine apk network failures, raw or summarized, as network', () => {
    expect(classifyInstallFailure('ERROR: libcurl-8.16.0-r0: Network unreachable', 'apk_failed', true)).toBe('network');
    expect(classifyInstallFailure('WARNING: fetching https://x/main: DNS: transient error (try again later)', 'apk_failed', true)).toBe('network');
    expect(classifyInstallFailure('WARNING: fetching https://x/main: TLS: server certificate not trusted', 'apk_failed', true)).toBe('network');
    expect(classifyInstallFailure('The secure download connection failed (TLS).', 'apk_failed', undefined)).toBe('network');
    // A package name is not a TLS failure.
    expect(classifyInstallFailure('(3/40) Installing ca-certificates (20250911-r0)\nERROR: tzdata-2025b-r0: BAD signature', 'apk_failed', true)).toBe('packages');
    expect(classifyInstallFailure('Writing package files to the phone\'s storage failed.', 'apk_failed', undefined)).toBe('packages');
  });

  test('cleans escapes, markers, redraws, and repeated progress from the log', () => {
    const raw = '\u001b[32mHORUS_INSTALL_STAGE=codex\u001b[0m\r\nDownloading Codex… 10%\nDownloading Codex… 20%\nhalf\rwhole\nnpm error boom\n';
    expect(cleanInstallLog(raw)).toBe('Downloading Codex… 20%\nwhole\nnpm error boom');
    expect(cleanInstallLog('HORUS_INSTALL_STAGE=start\n')).toBeUndefined();
  });

  test('points to mirrors only for downloads that can use one', () => {
    expect(installFailureCopy({kind: 'network'}, 'codex').advice).toContain('Settings › Downloads');
    expect(installFailureCopy({kind: 'network'}, 'claude').advice).not.toContain('mirror');
    expect(installFailureCopy({kind: 'offline'}, 'opencode')).toEqual(expect.objectContaining({title: 'Couldn’t install OpenCode', reason: 'Your phone is offline.'}));
  });
});
