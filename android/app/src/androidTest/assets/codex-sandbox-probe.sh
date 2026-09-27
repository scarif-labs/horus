#!/bin/sh
# Offline capability probe for the packaged PRoot runtime, in a test-owned home.
# argv: Codex executable, private log file. Never emit raw command output.
set -u
test "$#" -eq 2 || exit 2

# Pin the policy/backend so ambient configuration cannot turn this into an
# unsandboxed success. The PTY supervisor supplies the outer teardown deadline.
timeout -s KILL 20 "$1" -c features.use_legacy_landlock=false \
  -c 'sandbox_mode="read-only"' sandbox linux -- /bin/true >"$2" 2>&1
probe_exit=$?
case "$probe_exit" in
  0)
    # PRoot 5.1.107.92 emulates unshare/setns and strips clone namespace flags.
    # Executing true therefore cannot establish Bubblewrap's isolation boundary.
    status=unsupported; detail=proot_namespace_isolation_unavailable ;;
  182)
    # PRoot loader.c FATAL(), not a Bubblewrap "unsupported" exit convention.
    # The exit alone does not identify which loader syscall failed.
    status=fail; detail=proot_loader_exit_182 ;;
  137|124)
    status=fail; detail=sandbox_timeout_or_signal ;;
  *)
    status=fail; detail="sandbox_exit_$probe_exit" ;;
esac
printf 'P6_SANDBOX|codex-cli|%s|v=unknown|d=%s\n' "$status" "$detail"
