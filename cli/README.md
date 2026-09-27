# horus CLI

Use Android phones running [Horus](https://github.com/scarif-labs/horus) like small Linux machines
from your computer, over USB. Every phone gets an SSH shell in its Alpine
workspace; you can copy files, forward ports, and run a command on all of them
at once.

Remote access is optional. Horus works fully on the phone without it.

## Install

Needs Node 18+, `adb` (Android platform-tools), and OpenSSH (`ssh`, `scp`;
`rsync` for `horus sync`). On macOS, `brew install android-platform-tools`
gives you `adb`; on Linux it's usually the `adb` or `android-tools` package.

```sh
npm install -g horus-cli
```

The CLI has no dependencies. Update with `npm update -g horus-cli`; remove it
with `npm uninstall -g horus-cli` (your keys and pairings stay in `~/.horus`).
From a clone of the repo, `npm install -g ./cli` links it instead.

On each phone: install Horus, finish setup, and turn on **USB debugging** in
Android's developer options. Then connect it and allow this computer.

## Pair

```sh
horus pair            # or: horus pair <serial> --name pixel
```

This makes a key for this computer in `~/.horus`, asks for the phone's Horus
password, and sends the public key to the app over adb. The first time, the
phone installs its SSH server (a minute or so). If Android won't start it in
the background, the CLI opens Horus on the phone.

The phone's host key is pinned on first connect, over USB, in
`~/.horus/known_hosts`.

## Use

```sh
horus devices                         # connected phones and their state
horus ssh                             # interactive zsh
horus ssh pixel -- make test          # one command
horus exec --all -- git -C /workspace/projects/app pull
horus cp -r ./app pixel:/workspace/projects/
horus cp pixel:/workspace/out.log .
horus sync ./site/ pixel:/workspace/site/ --delete
horus forward pixel 3000              # phone's port 3000 at localhost:3000
horus status --log                    # battery, temperature, server log
horus stop                            # turn remote access off on the phone
horus unpair                          # remove this computer's key
```

The first time you run `claude`, `codex` or `opencode` on a phone, it installs
that agent into your home there (needs internet, a minute or two) and then
starts it.

`horus cp` copies like `scp` and follows symlinks; `horus sync` (rsync `-a`)
keeps them.

When only one phone is connected, you can leave out its name. The adb serial
works anywhere a name does.

### Plain ssh and VS Code

```sh
horus config --install
ssh pixel
```

This writes `~/.horus/ssh_config` and includes it from `~/.ssh/config`. Each
phone's entry uses `horus proxy` as its ProxyCommand, which re-creates the adb
forward on demand. VS Code Remote-SSH picks the hosts up from there.

### Long-running work

Android 12+ kills background child processes beyond a small limit shared by
all apps, which can cut builds and agents short:

```sh
horus tune
```

This runs the documented `device_config` and `settings` commands over adb to
lift that limit for the whole phone. It lasts until a factory reset. Also allow
Horus unrestricted battery use; Horus asks for this during setup.

## How it works

- The phone runs `dropbear` inside Alpine, bound to `127.0.0.1:8022`. The CLI
  reaches it with `adb forward`, so nothing listens on Wi-Fi.
- Logins are key-only, never as root, and only as the Horus profile user.
- The CLI talks to the app with `adb shell content call`. The app answers that
  only for the adb shell, and pairing also needs the Horus password.
- Environment: `HORUS_HOME` (default `~/.horus`), `ADB` (path to adb),
  `HORUS_PASSWORD` (non-interactive pairing), `HORUS_APP_ID`.
