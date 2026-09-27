const {spawnSync} = require('node:child_process');
const {mkdtempSync, readFileSync, rmSync, writeFileSync} = require('node:fs');
const {tmpdir} = require('node:os');
const path = require('node:path');

const probe = path.resolve(__dirname, '../android/app/src/androidTest/assets/codex-sandbox-probe.sh');

describe('Codex sandbox capability probe under PRoot', () => {
  let directory;

  beforeEach(() => {
    directory = mkdtempSync(path.join(tmpdir(), 'horus-sandbox-test-'));
    // macOS has no coreutils timeout. Verify the guest deadline arguments,
    // then run the immediate fixture; spawnSync supplies the host deadline.
    writeFileSync(path.join(directory, 'timeout'), `#!/bin/sh
test "$1" = -s && test "$2" = KILL && test "$3" = 20 || exit 98
shift 3
exec "$@"
`, {mode: 0o700});
    writeFileSync(path.join(directory, 'codex'), `#!/bin/sh
test "$#" -eq 8 || exit 99
test "$1" = -c && test "$2" = features.use_legacy_landlock=false || exit 99
test "$3" = -c && test "$4" = 'sandbox_mode="read-only"' || exit 99
shift 4
test "$1" = sandbox && test "$2" = linux && test "$3" = -- && test "$4" = /bin/true || exit 99
printf 'private fixture stdout\\n'
printf 'private fixture stderr\\n' >&2
exit "$PROBE_FIXTURE_EXIT"
`, {mode: 0o700});
  });

  afterEach(() => {
    rmSync(directory, {recursive: true, force: true});
  });

  it.each([
    [182, 'fail', 'proot_loader_exit_182'],
    [0, 'unsupported', 'proot_namespace_isolation_unavailable'],
    [101, 'fail', 'sandbox_exit_101'],
    [1, 'fail', 'sandbox_exit_1'],
    [127, 'fail', 'sandbox_exit_127'],
    [137, 'fail', 'sandbox_timeout_or_signal'],
    [124, 'fail', 'sandbox_timeout_or_signal'],
  ])('records exit %i without claiming isolation', (exitCode, status, detail) => {
    const log = path.join(directory, 'private.log');
    const result = spawnSync('/bin/sh', [probe, path.join(directory, 'codex'), log], {
      encoding: 'utf8',
      timeout: 2000,
      env: {PATH: directory, PROBE_FIXTURE_EXIT: String(exitCode)},
    });
    expect(result.error).toBeUndefined();
    expect(result.status).toBe(0);
    expect(result.stdout).toBe(`P6_SANDBOX|codex-cli|${status}|v=unknown|d=${detail}\n`);
    expect(result.stderr).toBe('');
    expect(readFileSync(log, 'utf8')).toBe('private fixture stdout\nprivate fixture stderr\n');
  });
});
