#!/usr/bin/env bash
set -euo pipefail
repo_root=$(cd "$(dirname "$0")/../.." && pwd)
test_dir=$(mktemp -d)
trap 'rm -rf "$test_dir"' EXIT
"${CC:-cc}" -std=c11 -Wall -Wextra -Werror -O2 \
  "$repo_root/android/app/src/main/cpp/provision_supervisor.c" -o "$test_dir/supervisor"
python3 "$repo_root/native/tests/provision_supervisor_test.py" "$test_dir/supervisor"
