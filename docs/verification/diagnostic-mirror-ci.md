# Diagnostic mirror CI failure

## Finding

This is an asynchronous **test synchronization bug**, not a diagnostic-ring regression or a demonstrated shared-state/test-ordering problem. The original test can inspect a correctly functioning mirror before all its input has been read. No production change is needed.

The investigation is local on `test/diagnostic-mirror-synchronization`, based on `main` at `539f7d6dc91fe3d0d0b5be81391833cd2d23739b`. PR #102 remains unchanged at `7a75a462868c385d39e441ab548a524405274c2d`. Nothing was merged, pushed, published, or rerun remotely.

## CI evidence and exact assertion

[Run 40105470557](https://github.com/scarif-labs/horus/actions/runs/40105470557), Android job `120906137961`, checked out `539f7d6`. The failing command was:

```sh
./gradlew :app:testDebugUnitTest :app:assembleRelease --no-daemon --stacktrace --max-workers=2
```

The log reports:

```text
TerminalSessionSupervisorTest > theDiagnosticMirrorKeepsOnlyTheBoundedTail FAILED
    java.lang.AssertionError at TerminalSessionSupervisorTest.kt:629
293 tests completed, 1 failed, 4 skipped
```

Line 629 is `assertTrue("mirror tail does not match the most recent bytes", tail.contentEquals(expected))`. The capacity assertion at line 623 did not fail. The CI log does not expose the actual bytes or precise thread interleaving; the controlled reproduction below establishes a valid schedule causing that same assertion failure.

## Why the original waits are insufficient

The fixture queues three **10 KiB writes**. `TerminalSessionSupervisor.READ_CHUNK_BYTES` is **8 KiB**, and the fake backend splits pending writes to that read-buffer size. These become six callbacks: `a8, a2, b8, b2, c8, c2` KiB.

The original first barrier waits for `listener.outputs.size >= 3`. After exactly three callbacks, only **18 KiB** has arrived. A full 16 KiB mirror then correctly contains **8 KiB of a + 8 KiB of b**, rather than the expected final **6 KiB of b + 10 KiB of c**.

The second barrier does not repair this: `snapshot().state` uses `exitEmitted`, which `finish()` sets **before** joining the reader for its bounded drain. `EXITED` therefore indicates the observed process exit, not that the terminal exit callback/drain has completed. The test can pass both waits and assert while the reader is still pending.

`BoundedOutputMirror.append` and `toByteArray` are synchronized. Their existing semantics are to retain the latest 16 KiB of bytes actually appended, not bytes merely queued at the backend. The observed intermediate tail obeys that contract.

## Reproduction and frequency

Ordinary runs execute the unchanged JUnit method in separate, repeated test instances without scheduling hooks. Controlled runs compile temporary copies of the same test/fixture and use latches to pause the real reader immediately after callback 3. No ring implementation, expected bytes, assertions, sleep duration or production drain deadline is changed.

The controlled scheduler waits until callback 3 is recorded before allowing the test's first output barrier to execute. If that barrier is not yet satisfied, the scheduler releases the reader so it can finish. An incorrect callback-count barrier is already satisfied at 18 KiB, so it proceeds to exit/assertion while the reader remains paused. A `finally` block releases and reaps the fixture, preventing leaked reader/reaper threads. This models preemption between reader callbacks; no timeout needs to expire to reproduce the assertion.

| Version | Ordinary executions | Controlled executions |
| --- | --- | --- |
| Unmodified main `539f7d6` | 2,000 pass / 0 fail | 0 pass / **20 fail** |
| Unmodified PR #102 `7a75a46` | 2,000 pass / 0 fail | 0 pass / **20 fail** |
| Corrected test on main | 2,000 pass / 0 fail | **20 pass** / 0 fail |
| Corrected test over PR #102 classes, temporary overlay only | Not separately repeated | **20 pass** / 0 fail |

Representative controlled observations:

```text
before: callbacks=3 received=18432 state=EXITED a=8192 b=8192 c=0
        mirror tail does not match the most recent bytes
 after: callbacks=6 received=30720 state=EXITED a=0 b=6144 c=10240
```

The 20/20 controlled rate demonstrates reproducibility under that specific schedule; it is **not** an estimate of the natural CI failure rate. No unforced local repetition reproduced it. The observed CI failure is one failing execution in the supplied run; its cross-run frequency is unknown.

For ordering, all 18 supervisor tests passed in each of **five distinct, seeded method orders**, before and after the change: 90 executions / zero failures per version. The controlled failure also needs no preceding test. Ordering can affect scheduling pressure, but no order-dependent shared fixture or state dependency was demonstrated.

## Minimal correction

Only `theDiagnosticMirrorKeepsOnlyTheBoundedTail` changes:

1. Wait until callback payloads total the complete 30 KiB, rather than counting three callbacks. Callback delivery happens after `mirror.append`, so this is a meaningful synchronization point for the mirror assertion.
2. After requesting fake-process exit, wait for the exit callback, which occurs after reader draining/closure, rather than the earlier snapshot state.

The 16 KiB capacity assertion, exact final byte-content assertion, input sizes, poll interval, timeouts and production mirror/supervisor code are unchanged. The separate `naturalExitDrainsTrailingMarkersBeforePublishingExit` test continues to cover drain/event ordering; this mirror-capacity test no longer races it accidentally.

## Relation to provisioning diagnostics and PR #102

Diffs for both `TerminalSessionSupervisor.kt` (including `BoundedOutputMirror`) and `TerminalSessionSupervisorTest.kt` are empty across `302ee56..539f7d6` (#100) and `539f7d6..7a75a46` (#102). The premature barriers are present in the repository history before #100.

The compiled main and PR #102 `TerminalSessionSupervisorTest.class` files are byte-identical, SHA-256 `adc58108d98e3f6e0b86444499972b716da9c3a6ac0bdcd0042af31092f3421f`. PR #102 reproduces the controlled failure and does not mask it. Applying the test-only correction as a temporary compiled overlay against its production classes succeeds; no dependency requires changing that PR.

## Verification and local evidence

Pinned Node **24.19.0**, cached JDK **17**, repository Kotlin **2.2.0**, and CI's `--max-workers=2` were used. The corrected branch passes the **exact CI command** above: **293 discovered JVM tests, 289 passed, four existing skips, zero failures/errors**, and a successful unsigned release build. It also passes the repetition and ordering matrices above.

The ignored local replay archive at `artifacts/diagnostic-mirror-ci/` contains the CI excerpt, preserved original main bytecode/source, Java repeat/order runners, temporary generated Kotlin scheduling probes, logs, classpaths and a README with replay commands. Scheduling instrumentation is confined to that archive; it is not included in the source correction. Initial local build setup encountered Metro's cross-checkout node_modules symlink restriction; using a local copy of the cached dependencies resolved it before the measurements. It is unrelated to the CI assertion.

This is a local test correction and evidence record only. No production buffer semantics, PR #102 files, remote refs, workflow runs or GitHub discussions were changed.
