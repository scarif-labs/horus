# Plan: Debian as a second Linux distribution

Tracking: [#3](https://github.com/scarif-labs/horus/issues/3)

## Status (2026-10-16)

Ready for release as 1.1.0, pending merge. Testing is recorded in
`docs/plans/release-1.1.0-testing.md`: upgrade from 1.0.4, rename,
second user, downgrade (rename back to `horus` first), host tests, and
the device tests on an emulator all pass. Since 2026-10-15:

- `sudo` works from the first sign-in: python3 is installed with the base
  packages for every target (`d455985`).
- Renaming a user keeps `/home/<old name>` as a link to the new home.

Codex and OpenCode on Debian now install npm's own release instead of
apt's npm 9 (`3e60be9`), which removes the ~400 node-* packages and an
npm ENOENT race under PRoot.

Still open: the gid 1000 group name (cosmetic), SSH logins on Debian
cannot install Node.js, and the status-bar overlap on first launch.

## Status (2026-10-15)

Branch `debian-rootfs`, PR #21. Main (with #9 and #23) is merged in;
see `docs/plans/pr23-merge-and-fixes-2026-10-15.md`.

Verified on the test phone on 2026-10-15:

- Signing in as the other user switches distributions. The login screen
  works like the Mac's: users first, then the chosen user alone.
- Remote access over USB on both distributions. The phone keeps one host
  key across users, and a 1.0.4 key is adopted, so paired computers see no
  change. The server restarts as the new user after a switch, and an
  interactive login prints "Horus: <user> on <distro>".
- Codex on Debian through the app tile (apt installs Node.js 20) and over
  SSH. Codex over SSH on Alpine installs on first use.

Still open from that run:

- `sudo` is a python3 script, so it fails ("not found") until the Bare
  terminal has installed python3. Agents-only users have no sudo.
- The profile user shares gid 1000 with the harness accounts, and the group
  takes the name of whichever account came first (e.g. `harness_opencode`).
  Cosmetic.
- SSH logins on Debian cannot install Node.js; they now say to open the
  agent in the app once.

### Earlier status (2026-10-12)

Phases 1–5 are done; Phase 6 is partly done.

| Phase | State | Commits |
| --- | --- | --- |
| 1. Rootfs catalog | Done | `f7bbb4d` |
| 2. xz archives | Done | `9e274e8` |
| 3. Debian rootfs and probe | Done; asset published as release `rootfs-debian-13.7-20261005` | `9f80f63`, `3d4edcf` |
| 4. apt provisioning | Done | `ceaa8ad`, `de9c8d7` |
| 5. Users and UI | Done (see Phase 5) | `870987f`, `4657bbd`, `ae8f075`, `cf262bb`, `de9c8d7` |
| 6. Testing | Partly done (below) | |

Verified on the test phone (Redmi Note 10 Pro, Android 16):

- Onboarding with Debian, user `nefer`: the shell runs as `nefer`,
  `/etc/debian_version` is 13.7, and `$HOME` is `/home/nefer`.
  "Installing packages… N%" shows under "Installing tools".
- `DebianToolchainDeviceTest`: shell, gh, Claude Code (glibc build), Codex,
  and OpenCode all provision with apt and run as their users.
- `PackageProgressDeviceTest`: progress lines on both distributions. The
  shell install takes 188 s on Debian and 17 s on Alpine. Most of Debian's
  time goes to package setup scripts: `update-ca-certificates` (~32 s) and
  Python byte-compiling its standard library (~49 s).
- `DebianRootfsDeviceTest`, `TerminalRuntimeDeviceTest`: pass.
- Unit tests: 247 Kotlin and 356 JS tests pass.

Not done yet:

- Manual matrix: signing in as the other user to switch, adding the second
  user from Settings, remote access over USB on Debian, reset, upgrade from
  1.0.4, and downgrade.
- Removing a distribution, renaming a user, and a Debian mirror setting.
- On Debian, the SSH-only Claude messaging wrapper (Debian's zsh does not
  read `/etc/zsh/zshrc.d`).
- Optional: leave Python out of Debian's default tools to save about 50 s.

Failing on `main` too, so not caused by this branch:

- `TerminalToolchainDeviceTest#provisionsHarnessTargetsBeforeTheirInteractiveSessions`
  fails at "opencode repaired executable probe".
- `TerminalToolchainDeviceTest#installsBaseProfileAndKeepsItAcrossNewShellSession`
  waits for a `# ` prompt that the zsh theme no longer prints.

## Goal

Let users pick Debian instead of Alpine. Alpine uses musl, and a lot of
prebuilt tools assume glibc (some pip wheels, prebuilt CLIs, language
toolchains). Debian gives glibc and the apt archive.

Ground rules:

- Alpine stays the default. A user who never touches the new setting sees no
  change: same rootfs id, same `active.json`, same scripts.
- Debian is opt-in and labelled experimental for the first release.
- Switching distros never deletes home, workspaces, or the other distro.

## What exists today

| Area | State | Alpine-specific? |
| --- | --- | --- |
| Storage (`DistroStoreCore`, `DistroStorePaths`) | Each rootfs lives in `distro/versions/<id>/`, `active.json` picks one, install is staged and atomic | Only the record checks (below) |
| Catalog (`AlpineRootfsCatalog`) | One pinned entry: id, URL, sha256, size, release, probe | Yes |
| Records (`readRecordFile`, `isValidRecord`) | Reject anything whose `alpineRelease` is not `3.24.0` or whose probe markers differ | Yes |
| JS contract (`src/terminal/distroContract.ts`) | `activeRootfsId === PINNED_ROOTFS_ID`, pinned digest, pinned markers | Yes |
| Extractor (`SafeTarExtractor`, was `SafeTarGzExtractor`) | gzip and xz, picked by the catalog entry | No |
| PRoot (`ProotSessionLauncher`) | `-0`, `--link2symlink`, home/workspace binds | No; this is what dpkg needs too |
| Provisioning script (`ProotSessionLauncher` ~L1110-1640) | ~10 `apk add` calls: shell, gh, Claude, Codex/OpenCode, procps, remote access, agent installer; `/etc/apk/repositories` mirror rewrite | Yes |
| Probe (`ProotGuestProber`) | `/etc/alpine-release`, `/sbin/apk` | Yes |
| Download sources (`DownloadSources`, `downloadSources.ts`) | `alpineMirror`, `npmRegistry` | Mirror is Alpine-only |
| Homes | `home/` and `harness-homes/*` are bind-mounted into whatever rootfs runs | See risk below |

### The one real compatibility risk: shared home

Harness installs and their "ready" markers live in the bind-mounted home:
`~/.local` (Claude Code's `linux-arm64-musl` binary, Codex's musl native npm
package, OpenCode) and `~/.cache/horus/<target>.ready`. If Debian mounted the
same home, it would see `claude.ready`, skip the install, and run a musl binary
on glibc. Going back to Alpine after a Debian install would hit the opposite
problem.

**Decision (revised 2026-10-12): one Linux user per distribution, each with
a separate home.** Users asked for a login per distribution, like a normal
multi-user Linux machine, so the selective binds are not needed: Alpine keeps
`home/` and `harness-homes/` (no migration), Debian gets
`homes/debian/home` and `homes/debian/harness-homes/`. Workspaces stay
shared. Each user signs in to gh and the harnesses once. Paired computer keys
are copied into every user's home.

## Phases

### Phase 1: Generalise the catalog (no user-visible change)

1. Replace `AlpineRootfsCatalog` with a `RootfsCatalog` holding entries:
   `distro` (`alpine` | `debian`), `rootfsId`, `release`, `url`, `sha256`,
   `sizeBytes`, `archiveFormat` (`tar.gz` | `tar.xz`), probe command and
   expected markers, package manager (`apk` | `apt`).
   Alpine's entry keeps today's values exactly.
2. Records:
   - Alpine records keep being written as schema v1 (`alpineRelease`), byte for
     byte, so a downgrade to 1.0.x still reads them.
   - Non-Alpine records use schema v2 (`distro`, `release`).
   - `readRecordFile` accepts v1 (as Alpine) and v2; validation looks up the
     catalog entry by `rootfsId` instead of comparing to one constant.
3. `install()` takes a catalog entry instead of loose Alpine defaults.
4. JS: `distroContract.ts` validates against a catalog list mirrored from
   native (ids, digests, markers), and `getRuntimeStatus` gains
   `activeDistro` / `activeRelease`. Keep `activeAlpineRelease` populated for
   Alpine so nothing downstream breaks.
5. Tests: existing `DistroStoreCoreTest` cases pass unchanged; add one that a
   real v1 `active.json` from 1.0.4 still reads as active, and one that a v2
   record round-trips.

Ship-safe on its own. **Estimate: ~1 day.**

### Phase 2: xz archives

1. Add `org.tukaani:xz` (pure Java, 0BSD since 1.10; fine for F-Droid) and let
   the extractor pick the decompressor from the catalog's `archiveFormat`.
2. Keep every existing safety check (path traversal, symlink escape, device
   nodes, size caps). Debian's rootfs has more hardlinks and device entries
   than Alpine's minirootfs; check how `--link2symlink` and the extractor
   handle them.
3. Tests: a small fixture `.tar.xz` with the same unsafe-entry cases as the
   gzip tests.

**Estimate: ~0.5 day.**

### Phase 3: Debian rootfs and probe

1. Source: the official debuerreotype builds
   (`docker-debian-artifacts`, branch `dist-arm64v8`, `trixie/slim`). They
   are built from Debian's own archive and are reproducible.
   Checked 2026-10-12: that branch now ships `oci/blobs/rootfs.tar.gz`
   (30.2 MB, gzip; the old `rootfs.tar.xz` is gone), and its sha256 equals the
   OCI layer digest of `arm64v8/debian:trixie-slim`. But every build is one
   commit that replaces the previous one (the branch is force-pushed), so a
   commit-pinned raw URL can stop resolving once a newer build lands. So the
   pinned URL points at a Horus GitHub release asset: the same tarball,
   recompressed to `.tar.xz` (17.6 MB), with the upstream layer digest
   recorded next to it so anyone can check it against Docker Hub.
   Both formats of the 13.7 build extract cleanly with `SafeTarExtractor`:
   3,266 entries, 1 hardlink, no device nodes, 98 MiB unpacked.
   Published 2026-10-12 as release `rootfs-debian-13.7-20261005`:
   `debian-trixie-slim-13.7-arm64.tar.xz`, 17,386,584 bytes, sha256
   `c4a40504d8eddd7d47125d129296352d3758b1d0ce958bd517b44631aee74eb5`,
   built with `gunzip -c rootfs.tar.gz | xz -6 -T1` (XZ Utils 5.8.3,
   byte-identical on rerun). Catalog id: `debian-13.7-20261005-aarch64`; the
   build date is in the id because Debian rebuilds a point release with
   security fixes.
2. Size: ~18 MB download (xz) vs ~4 MB for Alpine. Show it in the picker.
3. Probe: `uname -m`, `/etc/debian_version`, `$HOME`, `command -v apt-get`,
   `command -v sh`, wrapped in `debian_probe_begin` / `debian_probe_end`.
   Debian is merged-usr, so the checks run `/usr/bin/*` and expect
   `/usr/bin/apt-get` and `/usr/bin/sh` under the prober's PATH.
   `DebianRootfsDeviceTest` passed on the Redmi Note 10 Pro (Android 16)
   on 2026-10-12: download, extraction, and probe in 14.5 s beside Alpine.
   It also confirmed `dpkg --print-architecture` is `arm64`, glibc's
   `/lib/ld-linux-aarch64.so.1` exists, and apt uses deb822
   `/etc/apt/sources.list.d/debian.sources`.
4. First-boot fix-ups inside the rootfs (run once, as root):
   - `/etc/apt/apt.conf.d/99horus`: `APT::Sandbox::User "root";` (apt cannot
     drop to `_apt` under PRoot), `Acquire::Retries "3"`,
     `Dpkg::Use-Pty "0"`.
   - `/etc/dpkg/dpkg.cfg.d/99horus`: skip docs/man/locales to save space
     (optional; matches Alpine's footprint better).
   - `DEBIAN_FRONTEND=noninteractive` for every provisioning call.
   - `/etc/resolv.conf` and `/etc/hosts` the same way Alpine gets them.

**Estimate: ~1 day, mostly on-device trial and error.**

### Phase 4: Package-manager abstraction in the provisioning script

1. Add `pkg_install` / `pkg_ready` shell functions to the provisioning script.
   They dispatch on the package manager the catalog passes in
   (`HORUS_PKG=apk|apt`). `apt` runs `apt-get update` once per boot, guarded
   by a stamp file, then `apt-get install -y --no-install-recommends`.
2. Package map (Alpine → Debian):

   | Use | Alpine | Debian |
   | --- | --- | --- |
   | shell base | `ca-certificates curl gcompat git jq less openssh-client-default python3 ripgrep tzdata zsh` | `ca-certificates curl git jq less openssh-client python3 ripgrep tzdata zsh` (no gcompat needed) |
   | gh | `github-cli` | `gh` |
   | Claude | `bash curl … libgcc libstdc++` | `bash curl ca-certificates git openssh-client zsh` |
   | Codex/OpenCode | `nodejs npm …` | `nodejs npm …` (trixie ships Node 20; confirm Codex/OpenCode minimums) |
   | ps/top | `procps-ng` | `procps` |
   | remote access | `dropbear openssh-client-default openssh-sftp-server rsync zsh` | `dropbear-bin openssh-client openssh-sftp-server rsync zsh` |

3. Path checks in the script that assume Alpine layouts need a Debian
   variant: `/lib/ld-linux-aarch64.so.1` (Alpine's gcompat shim; native on
   Debian), `/usr/lib/ssh/sftp-server` (Debian:
   `/usr/lib/openssh/sftp-server`), `/sbin/apk`.
4. Claude Code installer: pick `linux-arm64` (glibc) instead of
   `linux-arm64-musl` on Debian. `claude_download_kb` already reads the arch;
   add libc. The npm size helper already detects libc.
5. Mirror: rename the `alpineMirror` source to a per-distro mirror setting
   (`debianMirror` rewrites `/etc/apt/sources.list.d/debian.sources`). Keep
   the stored `alpineMirror` key so existing settings carry over.
6. Agent prompts that say "`apk add` will not work" become
   distro-neutral ("the package manager").
7. Wire per-distro tool state (`homes/debian` binds) from the risk section.

**Estimate: ~1.5–2 days.** The script is long and every branch needs a run.

Status (2026-10-12): done. The guest detects `apt-get` or `apk`, so one
script serves both. Debian has no BusyBox, so sessions and the SSH server
switch to the user with util-linux `setpriv` (no PAM, which PRoot's emulated
root cannot satisfy); Alpine keeps `busybox su`. Debian's zsh does not read
`/etc/zsh/zshrc.d`, so the SSH-only Claude messaging wrapper is Alpine-only
for now, and the remote agent installer cannot apt-install as the user (the
app's tiles install system packages as root).

`sudo` (2026-10-12): a real sudo cannot work, because our PRoot build leaves
out setuid emulation (`USERLAND` is not defined, so `fake_id0/exec.c` is not
compiled). Instead, the profile user's own sessions (app terminal and SSH,
never a harness) start a root helper (`/usr/local/lib/horus/sudo-helper`)
before dropping to the user, and `/usr/local/bin/sudo` asks it to run
commands, without a password. Sources live in
`android/app/src/main/resources/.../terminal/guest/`. Each helper is reachable
only inside its own PRoot process (long socket paths become per-process
bindings), and it checks the caller's emulated uid as well. Tested on the
Redmi: `sudo apt-get install`, pipes, a pty with Ctrl-C, `sudo -i`, and
refusal from another session.

### Phase 5: Users and UI

Done as a user per distribution rather than a distro setting:

1. Users (`ProfileUsersCore`): at most one per distribution, each with a
   real Linux username, one of nine hieroglyph avatars (`assets/avatars`),
   and their own password. Alpine keeps the 1.0.x verifier keys; an install
   from before usernames reads as `horus` with the falcon. One lockout is
   shared by every user, and USB pairing accepts either password.
2. Onboarding: welcome, Alpine or Debian (Alpine selected), the user
   (hieroglyph, username, password), permissions, install, keys. The download
   starts when the distribution is chosen.
3. Login: a greeter showing every user; signing in activates that user's
   rootfs (`activateRootfs`, no reinstall). Running sessions keep going.
4. Settings: a USERS section lists users and adds the missing one, which
   installs that distribution beside the active one (`makeActive: false`).
   The new user's tools install the first time they open a terminal.
5. Not done yet: removing a distribution, renaming a user, and a Debian
   mirror setting.

### Phase 6: Testing

- Unit: catalog, v1/v2 records, xz extractor, package map, script generation
  for both package managers (`ProotSessionLauncherTest`).
- Device (`TerminalRuntimeDeviceTest`, `TerminalToolchainDeviceTest`,
  `TerminalCliCompatibilityDeviceTest`): parametrise over distro.
- Manual matrix on the test phone, for each distro: fresh install, Bare
  terminal, Claude Code, Codex, OpenCode, gh login + clone, remote access over
  USB, file browser, reset (rootfs only / with home).
- Upgrade: 1.0.4 with Alpine data → new build, nothing changes, sessions
  start without reinstall.
- Switch: Alpine → Debian → Alpine, harnesses work on both without
  reinstalling each time.
- Downgrade: new build on Debian → 1.0.4. Expected: 1.0.4 sees no valid
  install and offers to install Alpine; home and workspaces intact.
  Document this in the changelog.

**Estimate: ~1–1.5 days.**

## Rollout

1. Phases 1–2 can ship in a patch release alone (no visible change), which
   de-risks the record format change before Debian exists.
2. Phases 3–6 ship together with Debian marked experimental.
3. F-Droid: no new prebuilt binaries in the APK; the only new dependency is
   `org.tukaani:xz` from Maven Central. Update the store description and
   README to mention the choice.

**Total: roughly 6–7 working days**, most of it phases 3, 4 and 6.

## Open questions

- Debian release: trixie (13, current stable) is the obvious pick. Pin a
  dated debuerreotype build and bump it on a schedule like Alpine.
- Shared vs separate home: per-distro tool state with shared user config is
  the recommendation; confirm after trying the selective binds on device.
- Ubuntu: the same work covers it (apt, glibc) with one more catalog entry.
  Leave it out of the first release.
