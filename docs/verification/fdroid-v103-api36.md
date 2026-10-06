# F-Droid v1.0.3 physical-device verification

Tested on 2026-10-06, using a Samsung Galaxy S23 Ultra (`SM-S918B`),
Android 16 / API 36. These are physical-device results, not emulator results.

## Baseline and setup

`scarif-labs/horus` main and tag `v1.0.3` both resolved to
`3a089349a893894b67c6c655aa6c3a55eaed9fb0`. The initially provided workspace
had a different repository remote; validation used an isolated checkout of the
requested repository. No implementation was changed before reproduction.
There are no GitHub Actions workflows in the reviewed baseline.

Device confirmation:

```text
adb devices                         # one authorized USB device
adb shell getprop ro.product.model  # SM-S918B
adb shell getprop ro.build.version.release  # 16
adb shell getprop ro.build.version.sdk      # 36
```

Built the unchanged baseline with `npm ci --ignore-scripts`,
`git submodule update --init`, and `./gradlew :app:assembleDebug --no-daemon`.
The installed release was unconfigured, with remote access disabled, no keys,
and no running service. Its signing key differed, so it was uninstalled for the
requested clean local baseline install. Debug APKs embed JS and need no Metro.
Notifications were allowed; no device-wide developer settings were changed.

The host SDK had malformed NDK symlink placeholders and lacked CMake 3.22.1.
A temporary SDK copy repaired those links and installed CMake. Gradle used:

```sh
env -u ANDROID_SDK_ROOT \
  JAVA_HOME=/home/dpr/.gradle/jdks/eclipse_adoptium-17-amd64-linux.2 \
  ANDROID_HOME=/tmp/horus-android-sdk ./gradlew <tasks> --no-daemon
```

## Before fix: reproduced twice on service recreation

1. Install the locally built unchanged APK and clear logcat:
   `adb install <baseline.apk>`; `adb logcat -c`.
2. Launch `adb shell am start -W -n com.scariflabs.horus/.MainActivity`.
   Let debug bootstrap install Alpine and open the bare terminal.
3. Enter `echo HORUS_BASELINE_OK`; observe the returned marker and shell prompt.
4. Use the terminal's **Back to home** control, then **TERMINATE** on the only
   active session. Wait for the journal entry to disappear. No provisioning or
   remote-access work remained (remote provider reported disabled/stopped).
5. Background with `adb shell input keyevent 3`.
6. Inspect state using:

   ```sh
   adb shell dumpsys activity services com.scariflabs.horus
   adb shell dumpsys activity processes
   adb shell pidof com.scariflabs.horus
   adb shell pidof com.scariflabs.horus:terminal
   adb shell run-as com.scariflabs.horus cat files/horus/sessions/active-sessions.json
   adb shell content call --uri content://com.scariflabs.horus.remote --method status
   ```

   The session journal was absent, but the demoted service still had:

   ```text
   getFgsAllowStart=DENIED
   startRequested=true delayedStop=false stopIfKilled=false callStart=true lastStartId=1
   startCommandResult=1
   ```

7. First tried `adb shell am kill com.scariflabs.horus`. Killing the UI while
   termination was completing allowed the service's client-death cleanup to
   stop it; that attempt did not reproduce the exception.
8. Repeat the bare-terminal/final-termination/background flow. After observing
   the fully idle but still started service, kill the service and UI processes
   together, service first:

   ```sh
   adb shell run-as com.scariflabs.horus kill -9 5885 6555
   adb logcat -d -v threadtime > baseline-reclaim-logcat.txt
   ```

   The PIDs are from this run; obtain fresh PIDs before repeating. `run-as`
   requires the locally built debuggable APK. This is a targeted process-death
   simulation, without force-stop semantics or real device-wide memory pressure.
   Android retained and restarted the sticky service record.

Relevant logcat evidence (device log timestamps):

```text
10-06 12:43:32.938 7282 7282 E AndroidRuntime: FATAL EXCEPTION: main
10-06 12:43:32.938 7282 7282 E AndroidRuntime: Caused by: android.app.ForegroundServiceStartNotAllowedException: Service.startForeground() not allowed due to mAllowStartForeground false: service com.scariflabs.horus/.terminal.TerminalSessionService
10-06 12:43:32.938 7282 7282 E AndroidRuntime: at com.scariflabs.horus.terminal.TerminalSessionService.onCreate(TerminalSessionService.kt:234)
10-06 12:43:32.991 2967 4267 W ActivityManager: Scheduling restart of crashed service com.scariflabs.horus/.terminal.TerminalSessionService in 4000ms for start-requested
10-06 12:43:37.304 7333 7333 E AndroidRuntime: FATAL EXCEPTION: main
10-06 12:43:37.304 7333 7333 E AndroidRuntime: Caused by: android.app.ForegroundServiceStartNotAllowedException: Service.startForeground() not allowed due to mAllowStartForeground false: service com.scariflabs.horus/.terminal.TerminalSessionService
10-06 12:43:37.304 7333 7333 E AndroidRuntime: at com.scariflabs.horus.terminal.TerminalSessionService.onCreate(TerminalSessionService.kt:234)
```

## After fix: repeated physical lifecycle acceptance

Installed the fixed debug APK with `adb install -r <fixed.apk>` and cleared
logcat. Repeated three complete cycles:

1. Start a bare terminal and export a unique `HORUS_RECLAIM_TOKEN`.
2. Background using Android Home while the terminal is running.
3. Confirm `isForeground=true`, `startRequested=true`, and sticky result `1`.
4. Kill only the UI PID with `adb shell run-as com.scariflabs.horus kill -9 <ui-pid>`.
5. Confirm the terminal service PID and journal session ID are unchanged and
   the service remains foregrounded.
6. Relaunch Horus; confirm the same session ID. Enter
   `echo $HORUS_RECLAIM_TOKEN` and observe the cycle's original token, proving
   that the original shell remained alive. All three token screenshots were
   inspected.
7. Return to Horus Home; confirm the session is still active. Terminate it.
8. Wait until the journal is empty/absent and the bound service shows
   `startRequested=false` with no foreground notification.
9. Background Horus and kill both processes, service first, using the exact
   ordering that reproduced the baseline. Also run `am kill`.
10. Observe for eight seconds per cycle, inspect service state and full logcat.
    No service record or idle restart appeared. No foreground-service exception.

| Cycle | Service PID before idle reclaim | Idle `startRequested` | After reclaim | Resumed shell token |
| --- | --- | --- | --- | --- |
| 1 | 10049 | false | no service record | `physical_s23_cycle_1` |
| 2 | 11649 | false | no service record | `physical_s23_cycle_2` |
| 3 | 12159 | false | no service record | `physical_s23_cycle_3` |

A bound object can remain available to RN after `stopSelf()`; its started
lifetime is cleared. The old `startCommandResult=1` can remain in dumpsys as
historical data, but `startRequested=false` means it is no longer eligible for
a started sticky restart. The physical unbind test also verifies destruction.
Each successive cycle started a new terminal after idle shutdown successfully.

Additionally killed **both active processes** while a terminal was foregrounded:

```text
10-06 13:06:32.454 2967 4678 W ActivityManager: Scheduling restart of crashed service com.scariflabs.horus/.terminal.TerminalSessionService in 1000ms for start-requested
10-06 13:06:33.745 17572 17572 I HorusTerminal: service_start null_intent=true durable=true
```

The recreated service was foregrounded, restored the same persisted session ID
`s-1791272163521-1`, and the resumed terminal printed
`HORUS_PERSISTED_RESTART_OK`. This is session restoration after service death;
the separate UI-only reclaim tests preserved the original live shell.

## Automated validation and additional device scenarios

| Check actually run | Final result |
| --- | --- |
| `npm run typecheck` | pass |
| `npm run lint` (ESLint) | pass |
| `npm run test:unit` | 38 suites / 333 tests pass |
| `npm run scan:terminal-boundary` | pass |
| `./gradlew :app:testDebugUnitTest --no-daemon` | 206 JVM tests pass, including 6 new lifecycle cases |
| `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon` | pass |
| `./gradlew :app:assembleRelease --no-daemon` | pass, unsigned release APK |
| `./gradlew :app:lintDebug --no-daemon` | existing 177 errors / 56 warnings; same on unchanged v1.0.3, no added diagnostics ignoring shifted lines |
| `git diff --check` | pass |
| Removed asset filenames/vendor URLs and original asset hash audit | no remaining standalone copies; old launcher screenshot copies replaced |

The first sandboxed Jest invocation denied seven shell fixtures with
`spawnSync /bin/sh EPERM`; the full suite passed when rerun on the host.
Initial device-test assertions were corrected to handle omitted dumpsys fields,
parcelled session lists, and flushing a test profile before cross-process reads.
The original PTY fixture assumed BusyBox's prompt but selected the app's default
zsh. It now explicitly launches `/bin/sh -l` for its byte/CPR gate. Final device
results include the repaired gate, rather than treating its initial failures as
passes. Gradle's first connected run failed these initial test assertions and
removed its APKs; final verification used direct ADB instrumentation to retain
the bootstrapped runtime fixture.

Final physical-device command:

```sh
adb install -r android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb logcat -c
adb shell am instrument -w -r \
  -e class com.scariflabs.horus.terminal.TerminalSessionServiceDeviceTest,com.scariflabs.horus.terminal.TerminalRuntimeDeviceTest,com.scariflabs.horus.terminal.TerminalPtyDeviceTest \
  com.scariflabs.horus.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -d -v threadtime > fixed-final-logcat.txt
```

Result: **`OK (7 tests)`**, zero failed/skipped, 31.267 seconds. Scenarios include:

- idle binding: no foreground promotion or started lifetime; destruction on unbind;
- three cycles of two real sessions: stopping one retains the foreground service,
  stopping the last clears started lifetime, then a new terminal works;
- real GitHub CLI provisioning without terminal sessions: foreground retention
  during provisioning and idle shutdown on completion;
- opt-in remote access without terminal sessions: foreground retention until the
  real SSH server reaches `running`; disabling it destroys the idle service;
- fresh/repeated rootfs installation, device/application status, and PTY byte,
  interrupt, resize, output-draining and orphan-cleanup checks.

Final logcat was searched for `HorusTerminal`, `TerminalSessionService`,
`ActivityManager`, the Horus package/process, `ForegroundServiceStartNotAllowedException`,
and `FATAL EXCEPTION`. The last two had **zero matches**. Final remote status was
`configured=false`, `enabled=false`, `state=stopped`, `keys=0`; no test profile,
terminal session or provisioning job was left running.

Full raw logs, command transcript and screenshots were retained locally in
`artifacts/fdroid-v103-review` in the original workspace, outside the PR's
tracked files. Only this verification record and intentionally refreshed
store/documentation screenshots are included in the PR; no APK/build output.

The all-harness CLI/toolchain and seccomp probe suites were not run; they are
outside these three review findings. The signed release onboarding/lock flow
was not device-tested; the release APK was built, while before/after physical
lifecycle verification used locally built debug APKs with the same native
service implementation. A final signed-release smoke check remains useful
before distribution.

## Lifecycle authority

The implementation distinguishes a binding from a started lifetime, promotes
only actual work, returns `START_NOT_STICKY` and stops an empty restart, and
keeps active/persisted work sticky. `stopForeground()` alone does not stop a
started service. Android documents these rules in the
[Service API](https://developer.android.com/reference/android/app/Service),
[bound-service lifecycle](https://developer.android.com/develop/background-work/services/bound-services),
and [foreground background-start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start).
