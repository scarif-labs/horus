# Package-lock follow-ups to PR #93

These two fixes address remaining contention paths related to [#81](https://github.com/scarif-labs/horus/issues/81), [#87](https://github.com/scarif-labs/horus/issues/87), and [#91](https://github.com/scarif-labs/horus/issues/91), following [PR #93](https://github.com/scarif-labs/horus/pull/93). Version remains 1.1.1 / code 7.

## Reproductions and changes

1. Acquire the production package lock, run the production `run_logged` around `/bin/true`, release the parent descriptor, and immediately acquire the same file through a fresh descriptor with `flock -n`. Before: physical Alpine reports `LOCK_LEAK round=1`. The watchdog shell can be killed after forking `sleep`, leaving that child holding FD 7 for up to five seconds. After: close FD 7 on the watchdog subshell before it forks; the phone passed 20 consecutive immediate acquisitions. The JVM regression delays the watchdog kill to force this scheduling window.
2. Hold `/var/lib/horus/packages.lock` in one session and execute the generated SSH lazy `system_packages busybox` helper in another. Before: real `apk` finishes while the holder is alive. After: embed the existing shared helper inside `install-agent`, acquire before `apk`, save its exit status, release, then return that status. The phone queued and completed as both guest root and UID 1000. The JVM regression checks statuses 0, 23 and 127 under `set -eu`, then immediately acquires the lock again.

The existing per-tool/home FD 9 lock is acquired before this rootfs FD 7 lock. App provisioning and SSH-server setup never acquire that per-tool lock while holding FD 7, so this adds no reverse lock order. FD 7 is released before agent downloads, npm, or CLI execution. Package processes retain it during their transaction; the unrelated logger timer no longer does. No new process, trap, retry loop, or deadline is introduced. The existing logger watchdog and its possible short-lived orphan sleep remain bounded by five seconds.

The shared helper retains PR #93's existing best-effort behavior if the lock path cannot be created or `flock` is unavailable. Manual package commands outside Horus remain outside this advisory protocol. Lazy `system_packages` preserves package status; the existing top-level agent launcher still reports an installation failure as exit 127. This change does not redefine that CLI contract.

## Physical evidence: completed 2026-10-24

Samsung Galaxy S23 Ultra (SM-S918B), Android 16 / API 36, ARM64. Baseline: `a44907cf48c5600f13420fcd992b22eb1a30453a`. Follow-up: that base plus the two production fixes and their original JVM regressions. Debug APK SHA-256: `90369a299854caefb5332ffe44381c9d45415162880876da257ae52f9d8ad1b6`. Historical patch SHA-256: `6a4264c21554c1982e4ba561e026f1120f92696fcac663f91e04fd53e1566cb7`. Additional JVM status cases and the focused fixture extraction in this PR were added afterward; they are not claimed as additional phone runs.

[Selected original device logs](evidence/package-lock-followups-1.1.1/) are byte-for-byte copies with a SHA-256 manifest. The SSH result snapshot says `helper_exit=pending` because it was captured while the holder still owned the lock; the instrumentation subsequently awaited both processes and asserted exit 0. This is helper/package synchronization, not SSH login acceptance.

The complete local `evidence/DEVICE_VALIDATION_1_1_1.md` and its 206-entry checksum manifest are preserved unchanged, outside this focused PR. Report SHA-256: `88cdb0173d577cbe60ba56dd4a11a10a38a0513873954f165c6736c6beade6ab`. Historical JUnit accounting is retained without converting failed runs into passes:

| Physical run | Tests | Passed | Failed | Qualification |
| --- | ---: | ---: | ---: | --- |
| Original distribution matrix | 2 | 1 | 1 | Debian recovery fixture mode 700; other recorded subchecks pass |
| Corrected recovery + original support | 5 | 3 | 2 | Two genuine lock regressions; corrected real Debian recovery passes on unchanged app |
| Follow-up lock/input/runtime | 5 | 4 | 1 | Both lock fixes pass; existing PTY fixture stops answering later CPR queries |
| Corrected PTY rerun | 1 | 1 | 0 | Test-only CPR correction passes |

Also observed: three real overlapping package pairs each on Alpine and Debian serialize; controlled Claude HTML responses fail with exit 22 and no ready marker; normal installs and harness-user version commands pass for Codex 0.164.0, OpenCode 1.19.4 and Claude 2.1.310 on both distributions. These are install/version results, not authenticated workflows. SSH-server prerequisite synchronization passed, but its existing metadata warning and tolerated package error prevent claiming full SSH installation acceptance.

## Fresh host validation

Integration run on 2026-10-24 used repository-pinned Node **24.19.0**, Temurin JDK 17.0.20.1 and Gradle 9.4.1. Existing lockfile-installed dependencies were reused; no dependency changes were made.

- Jest: **391/391 passed, 43/43 suites**.
- Android JVM: **280 total, 276 passed, 4 skipped, 0 failures/errors**, including both new regressions. Skips: opt-in throughput benchmark, two host-Zsh cases (Zsh absent), and the TAR_GZ variant of an XZ-specific memory-cap test.
- TypeScript and version/changelog checks passed. ESLint: **0 errors, 4 existing inline-style warnings**.
- Expected Jest failure propagation probe returned **exit 1** with its deliberate failure marker.
- Native checks: **3/3 passed** (Unix sockets, PRoot process exit status, hardlink locking).
- `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease --no-daemon --stacktrace --max-workers=2`: **BUILD SUCCESSFUL**. The focused device fixture compiles; it was not newly executed on hardware.
- `node scripts/ci/verify-apk.js android/app/build/outputs/apk/release/app-release-unsigned.apk`: passed version 1.1.1 / code 7, ARM64, unsigned production APK. SHA-256: `82cb15579f90ef540ad19df2ed6f993a90c1074df620eb2a481ea5114c171c81`. This is a new host build, not the historical phone APK.
- Fresh host source-extraction probes reproduce the baseline logger leak for command statuses 0 and 23, then pass immediate reacquisition with both statuses after the fix. The SSH helper enters fake apk while held before the fix and queues until release afterward. These are host simulations, distinct from the real-package phone results above.

Fresh logs are retained locally in ignored `artifacts/integration-111/`; historical evidence was not overwritten. No physical device was accessed during this preparation.

## Scope and remaining release gates

Full SSH login, authenticated agent workflows/resume, release onboarding/password/lock behavior and F-Droid upgrade compatibility are unverified. Full agent TUI probes did not establish useful workflow evidence. Timeout/cancellation reasons still collapse into a Boolean/generic failure, and independent descendant cleanup after cancellation or a real interrupted package transaction is not established. Android 30 / low-memory SIGKILL reports need affected-device evidence. The waiting message was seen in stdout/progress files; direct on-screen waiting-state validation remains unperformed.

`PackageLockFollowupDeviceTest` extracts only the two lock fixtures from the completed validation, adds bounded teardown of tracked processes, and compiles in this PR. It downloads fresh disposable stores and is an opt-in physical/network test; follow the separate application-ID guidance in [Android ARM64 validation](android-arm64-validation.md). Do not run an unfiltered device suite against a user's installed package. The original broader fixtures and PTY correction are preserved locally and excluded from this PR. No device was reset, cleared, reinstalled, or otherwise accessed during this integration preparation.
