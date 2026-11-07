#!/usr/bin/env bash
# Builds the standalone libghostty-vt library from source using Zig.
#
# Usage:
#   scripts/native/build-libghostty-vt.sh <target> <out-dir>
#
# Targets:
#   arm64-v8a | aarch64-linux-android : Android ARM64
#   x86_64    | x86_64-linux-android  : Android x86_64
#   host      | linux-x86_64          : Host system (for local JVM unit tests)
#   all                               : Build all supported targets
#
# Follows the official libghostty-vt packaging specifications:
#   https://github.com/ghostty-org/ghostty/blob/main/PACKAGING-LIBGHOSTTY.md
set -euo pipefail

REPO_ROOT=$(cd "$(dirname "$0")/../.." && pwd)
CACHE_DIR="$REPO_ROOT/native/cache"
GHOSTTY_VT_VERSION="1.0.0"
SRC_TARBALL="libghostty-vt-${GHOSTTY_VT_VERSION}.tar.gz"
DEPS_TARBALL="libghostty-vt-${GHOSTTY_VT_VERSION}-deps.tar.gz"
SRC_URL="https://release.files.ghostty.org/libghostty-vt/${GHOSTTY_VT_VERSION}/${SRC_TARBALL}"
DEPS_URL="https://release.files.ghostty.org/libghostty-vt/${GHOSTTY_VT_VERSION}/${DEPS_TARBALL}"
SRC_SHA256="4a6ef758be721923e1969e7b81b443dcc92d8dd3fb5a39600c03a8817a753676"
DEPS_SHA256="4d5ea5e448e4c7ea8fb55ccbaa8668f2a97d44f438cee831e2b7ecd162d85c29"

TARGET="${1:-all}"
if [ -n "${2:-}" ]; then
  mkdir -p "$2"
  OUT_BASE_DIR="$(cd "$2" && pwd)"
else
  OUT_BASE_DIR="$REPO_ROOT/android/app/build/generated/libghostty-vt"
fi

# Verify Zig compiler is present
if ! command -v zig >/dev/null 2>&1; then
  echo "Error: zig compiler not found in PATH" >&2
  exit 1
fi

mkdir -p "$CACHE_DIR"

# Helper to verify sha256
verify_sha256() {
  local file="$1"
  local expected="$2"
  if [ ! -f "$file" ]; then
    return 1
  fi
  local actual
  actual=$(sha256sum "$file" | awk '{print $1}')
  [ "$actual" = "$expected" ]
}

# Fetch tarballs if missing or invalid
if ! verify_sha256 "$CACHE_DIR/$SRC_TARBALL" "$SRC_SHA256"; then
  echo "Downloading $SRC_TARBALL..."
  curl -sSL "$SRC_URL" -o "$CACHE_DIR/$SRC_TARBALL"
  if ! verify_sha256 "$CACHE_DIR/$SRC_TARBALL" "$SRC_SHA256"; then
    echo "Error: Checksum mismatch for $SRC_TARBALL" >&2
    exit 1
  fi
fi

if ! verify_sha256 "$CACHE_DIR/$DEPS_TARBALL" "$DEPS_SHA256"; then
  echo "Downloading $DEPS_TARBALL..."
  curl -sSL "$DEPS_URL" -o "$CACHE_DIR/$DEPS_TARBALL"
  if ! verify_sha256 "$CACHE_DIR/$DEPS_TARBALL" "$DEPS_SHA256"; then
    echo "Error: Checksum mismatch for $DEPS_TARBALL" >&2
    exit 1
  fi
fi

# Build for a single target triple
build_target() {
  local arch="$1"
  local zig_target="$2"
  local target_out_dir="$3"

  echo "=== Building libghostty-vt for $arch ($zig_target) ==="
  local work_dir
  work_dir=$(mktemp -d -t "ghostty-build-${arch}-XXXXXX")
  local cache_dir
  cache_dir=$(mktemp -d -t "ghostty-cache-${arch}-XXXXXX")

  trap 'rm -rf "$work_dir" "$cache_dir"' EXIT

  # Unpack source and dependencies
  tar -xzf "$CACHE_DIR/$SRC_TARBALL" -C "$work_dir"
  tar -xzf "$CACHE_DIR/$DEPS_TARBALL" -C "$work_dir"

  local src_dir="$work_dir/libghostty-vt-${GHOSTTY_VT_VERSION}"
  local deps_dir="$work_dir/libghostty-vt-${GHOSTTY_VT_VERSION}-deps"

  mkdir -p "$target_out_dir"

  local zig_args=(
    build
    --system "$deps_dir"
    -Demit-lib-vt
    -Doptimize=ReleaseFast
    -Dcpu=baseline
    --prefix "$target_out_dir"
  )

  if [ -n "$zig_target" ]; then
    zig_args+=("-Dtarget=$zig_target")
  fi

  # Run zig build in an isolated clean global cache directory
  (
    cd "$src_dir"
    ZIG_GLOBAL_CACHE_DIR="$cache_dir" zig "${zig_args[@]}"
  )

  # Copy headers into native/libghostty-vt/include if not already present
  if [ -d "$target_out_dir/include/ghostty" ]; then
    mkdir -p "$REPO_ROOT/native/libghostty-vt/include"
    cp -r "$target_out_dir/include/"* "$REPO_ROOT/native/libghostty-vt/include/"
  fi

  rm -rf "$work_dir" "$cache_dir"
  trap - EXIT
  echo "=== Successfully built libghostty-vt for $arch -> $target_out_dir ==="
}

case "$TARGET" in
  arm64-v8a|aarch64-linux-android)
    build_target "arm64-v8a" "aarch64-linux-android" "$OUT_BASE_DIR"
    ;;
  x86_64|x86_64-linux-android)
    build_target "x86_64" "x86_64-linux-android" "$OUT_BASE_DIR"
    ;;
  host|linux-x86_64)
    build_target "host" "" "$OUT_BASE_DIR"
    ;;
  all)
    build_target "arm64-v8a" "aarch64-linux-android" "$OUT_BASE_DIR/arm64-v8a"
    build_target "x86_64" "x86_64-linux-android" "$OUT_BASE_DIR/x86_64"
    build_target "host" "" "$OUT_BASE_DIR/host"
    ;;
  *)
    echo "Unknown target: $TARGET" >&2
    echo "Expected: arm64-v8a, x86_64, host, or all" >&2
    exit 1
    ;;
esac
