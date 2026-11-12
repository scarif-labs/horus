# libghostty-vt Migration — Rollout Gates & Stress Test Verification Report

**Branch:** `feat/libghostty-vt-engine`  
**Target Hardware:** Samsung Galaxy S23 Ultra (`SM-S918B`, Android 16 / API 36, arm64-v8a)  
**Host Toolchain:** Linux x86_64, Zig 0.14.0, Android NDK r27c  

---

## 1. Rollout Gate — Dual-Engine Architecture (`LEGACY` vs `LIBGHOSTTY`)

A runtime selectable backend architecture has been established to satisfy rollout gates:
- **Default Backend:** `TerminalEngineBackend.LIBGHOSTTY` (high-performance standalone C virtual terminal library).
- **Fallback Backend:** `TerminalEngineBackend.LEGACY` (original pure-Kotlin VT emulation engine with Unicode 11 `TerminalCharWidth` and `EmojiModifierBases`).
- **Control Plane:**
  - Persisted in `TerminalSessionSettings.readEngineBackend()` / `writeEngineBackend()`.
  - Configurable via `NativeTerminalEngineRegistry.setDefaultBackend(backend)`.
  - Initialized per session and recorded into `TerminalDebugLog` (`terminal_engine_init|sessionId=...|backend=...`).
  - Implements common `ITerminalBackend` contract across both implementations.

---

## 2. Mode 2027 (DEC Grapheme Clustering) Default & Conformance Audit

### Root Cause of Initial Divergences
In the initial migration commit, `GHOSTTY_MODE_GRAPHEME_CLUSTER` (DEC Mode 2027) was force-enabled (`value = true`) at initialization time. Per DEC/VT standard specification, Mode 2027 must remain **off by default** until explicitly requested by guest applications via `CSI ? 2027 h`.

### Audit Findings
When Mode 2027 was restored to default **off**:
1. **Differential Conformance:** Differential testing against `@xterm/headless` (`VtConformanceTest.kt`) revealed that 4 previously allowlisted divergences completely disappeared:
   - `emoji-text-presentation-vs16`: now matches `@xterm/headless` (width 1 without grapheme clustering)
   - `emoji-zwj-sequence`: now matches `@xterm/headless` (individual grapheme cells)
   - `emoji-skin-tone`: now matches `@xterm/headless` (base + modifier occupying separate cells)
   - `zero-width-space`: now matches `@xterm/headless`
2. **Result:** All **233 / 233** test cases in `VtConformanceTest` pass cleanly.
3. **Intentional Differences Parity (`TerminalEngineParityTest.kt`):**
   - **Mode 2027:**
     - Under default off: `LIBGHOSTTY` keeps base emoji and modifier separate (matching standard xterm); `LEGACY` always joins them.
     - Under `CSI ? 2027 h`: `LIBGHOSTTY` dynamically clusters `👍🏽` into a single 2-cell wide grapheme cluster.
   - **ECMA-48 REP (`CSI Ps b`):** `LIBGHOSTTY` implements standard graphic character repetition (`AAAAA`); `LEGACY` ignores it (`A`).
   - **DECAWM (DEC Mode 7):** When autowrap is disabled (`\u001b[?7l`), `LIBGHOSTTY` overwrites the right margin cell (`1235`); `LEGACY` ignores Mode 7 and soft-wraps.
   - **Resize Reflow:** Narrowing viewport reflows wrapped lines into subsequent rows in `LIBGHOSTTY`; `LEGACY` truncates unreflowed columns.

---

## 3. Phase 0 & Phase 1 Native Hardening

1. **CPU Baseline & Reproducibility (`build-libghostty-vt.sh`):**
   - Configured `-Dcpu=baseline` for `aarch64-linux-android` ensuring compatibility with ARMv8.0-A devices without requiring newer microarchitectures.
   - Configured `-Dstrip=true` to eliminate local build path metadata for bit-for-bit reproducible native builds.
2. **Bionic Libc Symbol Audit (`minSdk 24`):**
   - Inspected `libhorus_pty.so` via `readelf --dyn-syms`:
   - All standard Bionic imports resolve to `@LIBC` (API 21) except `preadv64@LIBC_N` (API 24).
   - Zero symbols require API > 24.
3. **Custom Tracking Allocator (`GhosttyAllocator`):**
   - Implemented `g_tracking_allocator` wrapping `posix_memalign` and `free` with atomic counters for current allocated bytes, blocks, peak bytes, and total allocations/frees.
   - Integrated `nativeSetAllocationFailureCap(capBytes)` allowing simulated native memory exhaustion testing.
   - Verified that `TerminalPtyJni.nativeAllocatedBytes()` returns `0L` when all engines are destroyed.
4. **Security & Protocol Hardening:**
   - **OSC 52 Reads:** Configured `GHOSTTY_TERMINAL_OPT_CLIPBOARD_READ` with `on_clipboard_read` returning `GHOSTTY_CLIPBOARD_READ_RESULT_DENIED`. Clipboard reading is strictly forbidden.
   - **Reply Allowlist:** `on_write_pty` enforces length checks (<= 4096 bytes) and allows only replies beginning with `ESC` (`0x1B`), `ENQ` (`0x05`), or `BEL` (`0x07`).

---

## 4. Automated Host Stress & Fuzz Suite (`LibGhosttyStressTest.kt`)

All 13 stress and fuzz tests passed:
- `openCloseCyclesReportZeroNativeLeaks`: 1,000 sequential create/feed/render/destroy cycles. Leaked bytes: **0**, leaked blocks: **0**.
- `concurrentSessionsStreamingAndResizingReportZeroLeaks`: 4 concurrent threads streaming ANSI output while resizing every 50ms and extracting text snapshots for 2 seconds. Leaked bytes: **0**, leaked blocks: **0**.
- `bomb1_overlongUtf8`: Overlong multi-byte sequence (`\xC0\xAF`) rejected cleanly without crash.
- `bomb2_splitSurrogates`: Unpaired surrogate codepoints (`\xED\xA0\x80`) and orphan continuation bytes handled safely.
- `bomb3_rep`: REP bomb `A\u001b[999999999b` bounded to terminal dimensions without memory explosion.
- `bomb4_giantCursor`: Cursor coordinates `\u001b[99999999;99999999H` safely clamped to terminal bounds.
- `bomb5_decaln`: 100 consecutive DEC screen alignment tests (`ESC # 8`) executed cleanly.
- `bomb6_degenerateScroll`: Degenerate scroll margins (`CSI 0;0 r`, `CSI 1;1 r`, `CSI 20;5 r`) handled without panic.
- `bomb7_hugeCsi`: >150 CSI parameters parsed and safely discarded.
- `bomb8_altScreenToggle`: 10,000 rapid alternate-screen switches executed without memory leaks or crashes.
- `bomb9_outputGap`: 50 rapid `recoverFromOutputGap()` reinitializations under active parser load.
- `largeOscPayloadTruncationStress`: 1MB OSC sequence cleanly parsed and safely truncated.
- `allocationFailureCapGracefulHandling`: Native allocation failure cap injected; `nativeEngineCreate` returns 0 safely without crashing the JVM process.

---

## 5. Live Physical Hardware Testing (Samsung Galaxy S23 Ultra)

### Instrumentation Test Results (`connectedDebugAndroidTest`)
- `TerminalEmojiDeviceTest`: **PASS** (Mode 2027 emoji skin tone sequence renders as single 2-wide cell with verified Android font glyph).
- `TerminalPtyDeviceTest`: **PASS** (PTY creation, bidirectional read/write, master fd resize ioctls).
- `TerminalModifierDeviceTest`: **PASS** (Full modifier keys, arrow navigation, cursor key modes).
- `TerminalRuntimeDeviceTest`: **PASS** (Runtime initialization, Messenger IPC binding).
- `TerminalCliCompatibilityDeviceTest`: **PASS** (Interactive CLI commands, cursor report query/response loop).

### Live App & Memory Footprint (`dumpsys meminfo`)
- **Main App (`com.scariflabs.horus`, PID 14135):**
  - Native Heap PSS: `21,572 KB`
  - Total PSS: `211,509 KB`
  - Zero crashes, zero `SIGSEGV`/`SIGABRT`, smooth React Native + NativeTerminalCanvas initialization.
- **Terminal Daemon (`com.scariflabs.horus:terminal`, PID 14272):**
  - Native Heap PSS: `3,636 KB`
  - Dalvik Heap PSS: `1,518 KB`
  - Total PSS: `38,680 KB`
  - Lean, low-memory background service footprint.
