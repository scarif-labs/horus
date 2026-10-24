# Provisioning process cleanup

Baseline: upstream `main` at `539f7d6` (includes package-lock PR #94 and diagnostics PR #100). Work is isolated on `fix/provisioning-process-cleanup`; the original dirty checkout and its contributor branch were preserved.

## Root causes and scope

Background preparation originally ran Android service → Java `ProcessBuilder` → PRoot tracer → guest shell → logger/tee/watchdog → apt/apk/dpkg and package scripts. Its timeout/interruption handler destroyed only the tracer, waited two seconds, then forcibly destroyed that same process. It did not own or await the guest process hierarchy. PRoot's pinned `tracee/event.c::event_loop` explicitly ignores TERM; `kill_all_tracees` is an exit/signal-handler cleanup, not a guarantee after SIGKILL. Package-lock descriptors can remain inherited by a surviving child.

The service also had a publication race: `onDestroy` could run before the worker published its newly spawned process. Cancellation was not rechecked after registration, and a process that subsequently exited zero could be reported successful. `finished` was called regardless of whether stop had completed, so a bounded stop could relinquish ownership prematurely. Admission checked readiness before checking for in-progress work. Explicit service shutdown did not promptly interrupt the preparation executor while the service remained bound. A request still queued at shutdown could also be discarded by `shutdownNow` without replying; the final cancellation helper executes its no-spawn stopping branch so it completes as cancelled.

Two design issues were caught and corrected during physical verification, before delivery:

- Samsung's Android kernel does **not** expose `/proc/<pid>/task/<pid>/children`. The first supervisor implementation failed closed and waited indefinitely; it was replaced with same-UID proc stat scanning plus kernel child-ownership checks. Failed disposable test processes were cleaned up; the production app was untouched.
- Successful Debian preparation can leave a logger helper to reap. Treating the mere presence of that helper as failure produced exit 125 with stage `ready`. The final supervisor cleans helpers and preserves the installer's original status. Cancellation always overrides success.

No defect was assumed solely from an installation error report. The package-lock scripts, recovery commands, readiness checks, target installers, React UI and diagnostic allowlists are unchanged.

## Lifecycle assessment

| Scenario | Existing behavior and final handling |
| --- | --- |
| User cancels a visible install | Visible tool preparation uses `toolchainSessionLaunchSpec`, the PTY supervisor and `stopSession`, rather than the background preparation API. Its existing TERM/KILL and session-ID sweep remain intact; exit reasons already prevent a user stop from becoming install success. No new background cancellation UI/API is introduced. |
| Background preparation times out | Previously stopped only the tracer. Now TERM requests cleanup from an owning native supervisor; timeout remains `timed_out`, independently of the cleanup exit status. |
| Service destruction / explicit stop | Registration is followed by a cancellation check. Destruction sends a nonblocking stop request and interrupts the worker; explicit stop does so too. The Android main thread no longer waits for preparation teardown. |
| Worker interrupted | Pre-spawn and post-registration checks prevent launching/accepting success after observed cancellation. Interrupt status is restored after the bounded cleanup wait. |
| Child outlives its parent | The native subreaper adopts and reaps it, including a child that creates another session. Completion waits for adopted children. |
| Cancellation while holding a package lock | Locks are released by terminating/reaping their owners, not by deleting lock files. The isolated Alpine/Debian tests take the existing production lock and successfully retry afterward. |
| Interrupted dpkg configuration | Existing `dpkg_interrupted` / `dpkg_recover` logic is preserved. A disposable package's real postinst is interrupted, then successfully configured with `dpkg --configure -a`. This is a recoverable example, not a promise about every maintainer script. |
| Immediate retry / concurrent requests | Admission checks active work and any still-live previous supervisor before readiness/log changes. Existing single-executor/CAS serialization remains. If cleanup outlasts the caller's bounded wait, ownership is retained and retry receives `install_in_progress`. |
| Background and reopen | Existing foreground service, wake lock, durable-work accounting and binding behavior are preserved. Backgrounding is not cancellation. Hard Android process death still has platform-dependent limits described below. |

Rootfs acquisition is separate: downloads/extraction use staging and atomic active-record publication, preserving the previous installation on failure. The short guest probes are not package transactions; no new failure was demonstrated there.

## Implementation and signal safety

`provision_supervisor.c` is an app-owned executable built with the pinned NDK and packaged as `libhorus_provision.so` in executable native-library storage. Only `toolchainProvisionLaunchSpec` is wrapped. Interactive PTYs and SSH launches retain their established terminal/session policy.

The final background hierarchy is:

```text
Android TerminalSessionService / preparation worker
  └─ native supervisor (new session, subreaper, parent-death signal)
       └─ PRoot (verified private process group; leader kept waitable)
            └─ guest shell → logger / apt / apk / dpkg / maintainer scripts
```

The supervisor establishes a new session before forking and sets/verifies its child's distinct process group on both sides of fork. It never signals the Android app's process group. Cancellation is blocked across group creation; the guest restores default dispositions and the prior mask before exec. The group leader is observed with `waitid(..., WNOWAIT)` and is not reaped until cleanup ends, preventing its PID/PGID being reused for an unrelated group during teardown.

On cancellation or leader exit, the supervisor sends TERM to the private group, allows two seconds, then sends KILL if necessary. Adopted children receive one TERM and their own two-second grace window; repeated TERM must not continually interrupt a cleanup trap. Parent relationships come from proc stat, filtered by real UID, and `waitid(P_PID)` revalidates that a signal target is our direct child. Children cannot have their PID reused before this single-threaded supervisor reaps them. Fixed bookkeeping bounds adopted-child tracking to 4,096 entries; excess children are forcibly terminated. Proc scans occur during teardown, not throughout a long installation.

A parent-death TERM requests the same cleanup if the launching parent dies. The supervisor does not exec over itself. It retains ownership after escalation until children exit; Kotlin waits up to six seconds, preserves interruption, and **does not forcibly kill the supervisor** if the kernel has not completed teardown. A live supervisor remains registered and blocks retry. This favors retained ownership over pretending cleanup succeeded when an uninterruptible task remains.

`ProvisioningProcess` keeps PR #100's bounded UTF-8 drain, fixed safe summaries, stage/status extraction and fresh private-log fallback. It now registers inside the teardown-protected region, checks cancellation before spawn/after publication/after exit, stops even when registration throws, and calls `finished` only after the owned process is dead. Normal exit zero is accepted only through the existing readiness check. Timeout, cancellation, spawn failure, nonzero status and incomplete readiness remain distinct.

## Reproducible before/after evidence

Run `bash scripts/native/test-provision-supervisor.sh`. It compiles the exact production C source with `-Wall -Wextra -Werror`, then runs isolated temporary-directory fixtures. The baseline fixture intentionally kills only a host shell, verifies its child and inherited flock remain, then cleans the test-owned child. The fixed fixture cancels the supervisor, verifies reaping, and reacquires the same lock.

Representative captured output (PIDs vary):

```text
BEFORE parent=1305979 status=-15 child=1305981 alive=true lock=held
AFTER supervisor=1306142 status=143 child=1306145 reaped lock=released
```

The baseline is a real host process/FD reproduction of parent-only teardown; it is not a claim that every Android kernel releases PRoot tracees in exactly the same way. Physical after-tests use the actual packaged PRoot and guest package managers.

The native suite covers successful/nonzero exits, inherited locks, TERM-ignoring descendants, graceful traps, parent exit, a new-session orphan, an adopted orphan's graceful trap, 20 immediate cancel/retry cycles, concurrent supervisors alongside an unrelated process, and launching-parent death. JVM tests cover pre-spawn cancellation, interrupted-before-spawn, registration cancellation/exception, cancellation observed after successful exit, and unfinished cleanup retaining ownership with only one stop wait, alongside all prior diagnostics cases.

## Test results

All host/build commands use Node **24.19.0** from `.node-version` and the cached JDK 17 / pinned Android SDK and NDK.

| Check | Result |
| --- | --- |
| `npm run typecheck` | Pass |
| `npm run lint` | Pass: zero errors, four existing inline-style warnings |
| `npm run test:unit` | 44 suites, 434 tests pass |
| `:app:testDebugUnitTest` | 299 discovered, 295 pass, 4 existing skips; zero failures/errors |
| `ProvisioningDiagnosticsTest` | 19 pass, including the existing diagnostics coverage |
| Existing native script | Socket translation, PRoot exit status and hardlink storage locking binaries pass |
| New native supervisor script | 12 real-process tests pass; CI now runs these too |
| `:app:assembleDebug :app:assembleRelease` | Both pass from final production source |
| `scripts/ci/verify-apk.js` | Pass: unsigned release, correct identity/version, ARM64, manifest digests and supervisor ELF presence |
| Physical Android | 17 tests pass in 359.926 seconds: 5 cleanup/lifecycle, 10 diagnostics, 2 existing package-lock regressions |

The four JVM skips are the opt-in native benchmark, the gzip parameter of an xz-specific extractor case, and two zsh PTY assumption cases. They are not counted as passes. Release APK SHA-256: `9dbdc4e20e331e761636261044539294b01dbf64c3f0b35e9cb7469556ae4197`.

Re-run the host checks with the pinned Node, then:

```sh
npm run typecheck
npm run lint
npm run test:unit
bash scripts/native/test-unix-socket-translation.sh
bash scripts/native/test-provision-supervisor.sh
cd android
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease --no-daemon
```

A physical ordinary-install trial initially used a five-minute fixture deadline and timed out during Debian package work. It returned `timed_out` / exit 143 and removed its owned processes. The fixture now matches the existing production fifteen-minute deadline; no production timeout was increased. Setup/fixture failures and their corrections are distinguished from product regressions above.

## Physical Android procedure

Device: Samsung SM-S918B, Android API 36, ARM64. The validation build uses separate ID `com.scariflabs.horus.diagnosticscleanup20261024`; its instrumentation refuses the ordinary application ID. Every distro fixture creates a new store under that disposable application's private files directory. No production app was uninstalled, updated, reset, cleared, or re-signed. No production rootfs, project, account or credential was used.

Build from current source using Node `.node-version` (24.19.0), the repository's SDK/NDK and JDK 17. A temporary Gradle init file changes only the validation application ID:

```groovy
allprojects { p ->
    p.plugins.withId('com.android.application') {
        p.extensions.getByName('androidComponents').finalizeDsl { dsl ->
            dsl.defaultConfig.applicationId = 'com.scariflabs.horus.diagnosticscleanup20261024'
        }
    }
}
```

Build `:app:assembleDebug :app:assembleDebugAndroidTest -I /path/to/isolated.gradle`, verify the APK application ID with `aapt dump badging`, and install only those separate packages. Run `ProvisioningCleanupDeviceTest`, `ProvisioningDiagnosticsDeviceTest`, and `PackageLockFollowupDeviceTest` with `am instrument`. The cleanup test writes test-only PID/status observations to `files/cleanup-evidence.txt` in the disposable app. It never exports guest output through the production diagnostic bridge.

### Final physical evidence

The final APK was built from the current source, including the queued-cancellation fix. Its SHA-256 is `afb7a62d275d8be0f8325d93282dc26443badd2c1f0942aadd908780c3563e63`. Final instrumentation output: `OK (17 tests)`.

The fixtures use pinned Alpine **3.24.0** and Debian **13.7**, freshly extracted in the disposable app. Selected observations from the final run:

```text
android parent_death parent=143 supervisor=24493 child=24495 alive=false
service queued request cancellation=cancelled reply=delivered spawn=false
host timeout outcome=timed_out exit=143 child=24506 alive=false retry=success
alpine child=24560: PPID=24556 PGID=24554 SID=24552
alpine cancel=cancelled exit=143 child=24560 alive=false package_lock_retry=success
alpine ordinary_shell_provision=success
debian child=25009: PPID=25004 PGID=25002 SID=25000
debian cancel=cancelled exit=143 child=25009 alive=false package_lock_retry=success
debian interrupted postinst=cancelled dpkg_configure_retry=success status=installed
debian ordinary_shell_provision=success
```

PPID/PGID/SID values come from actual proc stat records, not guest PID guesses. The production lock-taking retry and real dpkg configuration verify that the transactions can proceed after cancellation. A final ADB process listing contained no remaining fixture supervisor, PRoot, apt or dpkg processes. App-specific `dumpsys activity exit-info` recorded no crashes or ANRs; recorded exits are instrumentation/explicit test teardown (`USER REQUESTED / FORCE STOP`).

The production package still reports version 1.1.1 / code 7 and exactly the same installation timestamps before and after validation (`lastUpdateTime=2026-10-24 05:43:49`, `firstInstallTime=2026-10-18 12:38:22`, device-reported times). Its data was not cleared or inspected as a fixture.

## Remaining limitations

- No blanket guarantee for Android force-stop, LMK, reboot, or simultaneous SIGKILL of the app and its supervisor. Parent-death cleanup is best effort while the supervisor remains runnable. OEM behavior and older Android versions require additional physical validation.
- The Android parent-death fixture terminates an intermediate app-owned host shell. It exercises the actual kernel parent-death mechanism; it does not simulate every service/LMK/cgroup kill policy.
- A kernel task stuck in uninterruptible I/O may delay teardown indefinitely. The supervisor retains ownership and same-service retries remain blocked; there is no persisted cross-service cleanup/reconciliation ledger.
- The visible PTY path retains its existing bounded process/session sweep. New-session escape and hard app death guarantees for interactive terminals were not established by these background-preparation tests.
- A successful controlled dpkg recovery does not make arbitrary third-party postinst scripts transactional. Forced termination can leave recoverable package state; lock files and package databases are not deleted to conceal it.
- No exhaustive on-device foreground/background/reopen or multi-request UI stress test is claimed. Existing lifecycle/JVM/Jest coverage is preserved; these device fixtures focus on actual processes, locks and package recovery.

## Related GitHub reports

Installation reports include [#36](https://github.com/scarif-labs/horus/issues/36), [#89](https://github.com/scarif-labs/horus/issues/89), [#90](https://github.com/scarif-labs/horus/issues/90), [#97](https://github.com/scarif-labs/horus/issues/97), and [#101](https://github.com/scarif-labs/horus/issues/101). Their titles/reports do not establish this lifecycle defect as their cause. No unrelated issue is closed and no duplicate issue is created. PRs #94 and #100 remain the baseline, with their regression tests retained.
