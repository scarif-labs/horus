# Provisioning diagnostics — trace and controlled regression evidence

## Trace recorded before production changes

Baseline: main `a44907cf48c5600f13420fcd992b22eb1a30453a`. The isolated branch is `fix/provisioning-error-reporting`; PR #94 and PR #70 are not changed. The original triage/device evidence remains in the original checkout.

Issues #36/#89/#90 show missing diagnostics, but do not establish a shared package/network failure. #36's latest screenshots identify 1.1.0/API 33 and successful Debian download followed by failed tools setup; its auto-report is empty. Fresh successful installation on another device does not resolve these reports.

Exact baseline loss points:

1. `ProotSessionLauncher.kt::run_logged` (1720–1761) writes per-command private logs and emits `HORUS_INSTALL_STAGE` / `HORUS_INSTALL_COMMAND_EXIT` markers. Failure before this logger starts need not create a target log. `PACKAGE_MANAGER_SCRIPT` must remain unchanged by this fix.
2. `TerminalSessionService.kt::runBoundedProvision` (711–762) drains merged stdout/stderr into a discarded 4096-byte buffer. The return value is Boolean; nonzero exit, timeout and interruption all become false. The process status/output never reaches onboarding.
3. `handleProvision` (637–709) uses `classifyProvisionFailure` (1415–1458). Classification reads a bounded private log/stage but returns only a code. Missing/unreadable log files become empty strings. Pre-spawn/spec exceptions may have no log at all.
4. `TerminalRuntimeModule.kt::provisionResponse` (1068–1069), the service Bundle protocol, and `NativeTerminalRuntime.ts::ProvisionToolchainResponse` (222–226) expose only request/status/error code. `runtimeStatus.ts::provisionAlpineToolchain` (361–383) returns the same reduced shape.
5. `src/profile/onboardingSetup.ts::setupOnboardingProfile` (24–25) reduces even that error code to `stage: alpine-tools`. `OnboardingScreen.tsx::SetupStep` (300–406) consequently shows a generic internet message with no diagnostics/report action.
6. Terminal sessions are a separate path: `TerminalScreen.tsx` keeps only output received by its attachment. `showInstallFailure` (373–382) uses that visible tail; `finishSession` (408–423) previously does not pass exit status into the failure. A pre-attachment failure can have no captured text. The issue URL generator (`InstallFailedOverlay.tsx`, line 32) explicitly substitutes `(empty)`.

Scope: preserve bounded, sanitized diagnostic context and explicit outcomes, without changing package locking, installers, credentials, SSH setup or process-tree termination policy. No raw output will be newly persisted or sent to Android logs. Shareable text will use a conservative allowlist of fixed diagnostic summaries; arbitrary output/command arguments, URLs, paths and secrets are not exported. Unknown output gets an explicit omission/fallback message rather than being treated as safe by a token regex alone.

## Controlled before/after evidence

Before production changes, pinned Node 24.19.0 ran three desired-behavior regressions: **3/3 fail**. The native reply loses stage/status/log; the issue body says `(empty)`; and the issue body includes synthetic secret/command/path values without redaction.

A temporary host-JVM fixture invoked the unchanged service method without an Android UI. It recorded all **7 cases**: before-attachment failure, nonzero 37, useful stderr/exit 6, genuinely empty output/exit 19, 50-ms timeout, worker cancellation, and success. All six failures return only Boolean false; success returns only true. No stage/exit/output fields survive. The characterization test passes because it asserts these observed baseline losses, not because the desired behavior is present. Its source/XML/stdout and failing Jest log are archived locally under ignored `artifacts/provisioning-diagnostics/`.

Initial isolated-build setup failures (unpopulated submodule, Metro dependency symlinks, and the temporary fixture's direct JDK-internal import) were corrected before this completed baseline; they are not product failures. These fixtures use host processes/fake responses, not a physical Android device or an existing Linux installation.

## Implemented path and boundaries

- `android/app/src/main/java/com/scariflabs/horus/terminal/ProvisioningDiagnostics.kt:121` drains merged process output on a daemon thread and returns a structured outcome. Native line retention is limited to 2,048 characters; only the finite set of fixed summaries is retained, with an explicit 4,096-character export cap. Stage and command-exit markers have allowlisted values. Spawn exceptions are never exported. Timeout and interruption remain distinct from a command failure.
- `TerminalSessionService.kt:651` supplies a fallback even when provisioning cannot start. Lines 673–688 use only a fresh stage and changed private log, retain process/command exit values, classify the safe summaries, and require both a successful process and the existing readiness check. Exit zero with a failed stage or missing readiness is `incomplete`. The existing 15-minute timeout, stop policy and package-lock scripts are unchanged.
- `TerminalRuntimeModule.kt:1068`, `src/native/NativeTerminalRuntime.ts:222`, and `src/terminal/runtimeStatus.ts:362` carry the optional primitive fields. The JS wrapper rejects malformed fields and contradictory success-with-failure-diagnostics responses; old native responses remain supported.
- `src/profile/onboardingSetup.ts:26` retains the code and diagnostics without saving the profile or calling tools-ready after failure. `src/ui/OnboardingScreen.tsx:338` retains them in state; lines 410–417 display them with retry, skip and a report-draft action.
- `src/terminal/TerminalScreen.tsx:374` retains terminal-session exit and command status, handles split stage markers using the bounded tail, and gives output-free failures a fallback. `src/terminal/installFailure.ts:87` and `src/terminal/InstallFailedOverlay.tsx:19` share the same safe formatting for display and GitHub drafts. Reports allowlist metadata and re-project the output to fixed summaries even for older/raw terminal failures. No new raw log persistence or Android logging is added.

Arbitrary output is deliberately omitted, rather than trusting a password/token pattern to discover every possible secret. A recognized error retains its fixed explanation; unknown nonempty output says it was omitted; genuinely empty output says no diagnostic output was captured. Unknown stages/statuses remain unavailable rather than fabricated. Native and TypeScript policies share a 19-case regression corpus.

## Observed after results

The original three red regressions now pass. The native desired-behavior suite has 12 passing tests, including the seven baseline scenarios:

| Controlled case | Before | After |
| --- | --- | --- |
| Before terminal attachment: stage `apk_failed`, stderr ENOSPC, exit 23 | Boolean false | `failed`, stage retained, process/command status 23, safe storage explanation |
| Shell exit 37 | Boolean false | `failed`, exit 37 |
| DNS stderr, exit 6 | Boolean false | `failed`, exit 6, safe DNS explanation and network classification |
| Empty output, exit 19 | Boolean false | `failed`, exit 19, explicit no-output fallback |
| Timeout | Boolean false | `timed_out`, actual exit status if available |
| Worker cancellation | Boolean false | `cancelled`, interrupt preserved, actual exit status if available |
| Successful process | Boolean true | Success; onboarding still requires existing readiness checks |

The JVM tests additionally cover spawn failure, a failed stage despite exit zero, private-log fallback, classification, a 5,000-line output flood, oversized lines, invalid markers, sanitization parity, and bounded return when a child holds the output pipe. The process-ownership callback is released after timeout. These are process/diagnostic assertions, not proof of descendant process-tree cleanup.

Host validation uses repository-pinned **Node 24.19.0** and JDK 17:

- Full Jest: **429 passed / 429**, **44 suites**. The final affected-suite rerun also passes **79/79** after the bounded-input adjustment.
- Full JVM: **290 total, 286 passed, 4 skipped, 0 failures/errors**, **32 suites**. This includes the 12 new native diagnostics tests; baseline main had 278 total.
- TypeScript typecheck passes; ESLint has **0 errors**, with the same **4 existing inline-style warnings** in `MetroHomeScreen.tsx`.
- The CI intentional failure probe exits 1 as expected. Version/changelog validation passes for 1.1.1 (7).
- Final full JVM/unsigned release build passes; CI APK verification confirms **1.1.1 (7), ARM64 only, unsigned, non-debuggable**, valid runtime hashes and a production JS bundle. APK SHA-256: `8f008e75d6596759ba5deb1925c3b287ea65baad0e9f02370716cefd40d31c78`. No APK is committed or published.
- Native regression script: **3 groups passed** — Unix sockets, PRoot exit status, and hardlink/locking storage.

An initial restricted host run could not spawn the existing sandbox probe's shell or bind Unix sockets. The permitted unrestricted rerun passes; these environmental failures are not presented as product regressions.

### Physical Android evidence for this change

On a physical **Android API 36** device, `ProvisioningDiagnosticsDeviceTest` reports **10/10 passed**. These are safe `/system/bin/sh` fixtures, an actual service response when the isolated app has no rootfs, and the actual Bundle-to-React-Native map conversion. The cases cover pre-attachment failure, nonzero exit, stderr, empty output, timeout, cancellation, success/incomplete, and an inherited output pipe.

The test APKs used the previously absent application IDs `com.scariflabs.horus.diagnostics20261024` and `.test`. APK identity was checked before installation. Both newly created packages were removed afterward; the original Horus package path was unchanged. No original app update, data clearing, Linux install modification, package installation, login, or active-session operation was performed. The first isolation configuration produced the original APK identity; the guard rejected it **before installation**. The fixture's React context and build-isolation setup were corrected before the successful run.

Local raw test output is under ignored `artifacts/provisioning-diagnostics/`; it is not a shareable user log. The original physical validation report and triage report are untouched. All **206 existing device-evidence checksums** still match. This device run is distinct from the earlier real Alpine/Debian installation evidence and does not replace it.

## Integration scope and limitations

PR #94 and PR #70 remain unchanged. The tracked patch applies cleanly to PR #94 head `f62369ae224f4417eddc544ab095ead4f7d9e540` using an isolated index. The package-manager/SSH/logger shell scripts are unchanged. Full JVM coverage includes existing Debian, Alpine, existing-install and SSH launcher regressions; a combined PR #94 device install matrix was not rerun.

This fixes the observed information-loss mechanisms, not the unknown underlying cause of every report in #36/#89/#90. Reporter reproduction on an affected installation is still needed. Unknown raw output is intentionally not shareable. Timeout/cancellation process-tree cleanup, full SSH login and authenticated agent workflows remain separate and unverified by this change. No issue is closed, no PR is merged, and no release is published.

## Final publication review

Current upstream `main` remains `a44907cf48c5600f13420fcd992b22eb1a30453a`. Final review added only tests/evidence; the production implementation and previously validated release APK are unchanged. The committed investigation is this repository document, not a dependency on a temporary worktree path.

The shared native/TypeScript corpus now includes CSI color escapes, OSC hyperlinks, multiline synthetic secrets and malformed-decoding replacement characters. A native process emits actual invalid UTF-8 bytes (`ff fe c0 0a`) mixed with ANSI output and multiline synthetic secrets; the exported result contains only the fixed storage explanation. JS also exercises invalid-byte decoding through the bridge wrapper, UI formatter and issue draft. Existing extremely long/repeated output and genuinely empty-output cases remain covered. No raw secret, URL, escape, invalid character or command argument appears in displayed/shared diagnostics.

Final local counts: **434/434 Jest tests (44 suites)**; **291 JVM tests: 287 passed, 4 skipped, 0 failures/errors (32 suites)**, including **13 diagnostics JVM tests**. Typecheck passes; lint has no errors and only the four existing `MetroHomeScreen.tsx` warnings. The successful-install/legacy-success/onboarding paths remain green. The prior **10/10 physical-device fixtures** remain separate evidence; this final review adds no new physical-install claims.

The release APK was inspected to confirm no diagnostic test classes, synthetic payloads, diagnostic test-package ID, private log files, or corpus JSON were packaged. Test instrumentation exists only under `src/androidTest`; there are no production test hooks. The Git diff contains source/tests plus this document, with no logs, device identifiers, credentials or generated APKs. The full patch and final tests apply cleanly atop PR #94 using a temporary index and object store, without changing that branch or its checkout.

## Re-running the focused regressions

Use `.node-version` (24.19.0), the repository Android SDK/JDK configuration, and populated pinned submodules:

```sh
npm run test:unit -- --runTestsByPath __tests__/provisioningDiagnosticsRegression.test.ts __tests__/onboardingSetup.test.ts __tests__/onboardingScreen.test.tsx __tests__/terminalScreen.test.tsx
cd android
./gradlew :app:testDebugUnitTest --tests '*ProvisioningDiagnosticsTest' --no-daemon
```

The Android fixture refuses to run against the normal application ID. For a separate disposable application, supply a temporary Gradle init file with this DSL hook, then build `:app:assembleDebug :app:assembleDebugAndroidTest -I /path/to/init.gradle`:

```groovy
allprojects { p ->
    p.plugins.withId('com.android.application') {
        p.extensions.getByName('androidComponents').finalizeDsl { dsl ->
            dsl.defaultConfig.applicationId = 'com.scariflabs.horus.diagnostics20261024'
        }
    }
}
```

Check both APK identities with `aapt dump badging`. Both diagnostic package IDs must be absent on the selected device. Install the diagnostic APKs with `adb install -t` (without `-r`), stopping on any failure. Run only `com.scariflabs.horus.terminal.ProvisioningDiagnosticsDeviceTest` with `am instrument -w -e class`, using `com.scariflabs.horus.diagnostics20261024.test/androidx.test.runner.AndroidJUnitRunner`. Remove only the two diagnostic packages newly created by that run. Do not clear or replace the normal Horus package, or run the full existing installation instrumentation suite against a user's data.
