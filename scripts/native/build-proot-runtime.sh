#!/usr/bin/env bash
# Builds the Alpine terminal PRoot runtime from source with the Android NDK.
#
# Usage: scripts/native/build-proot-runtime.sh <ndk-dir> <min-sdk> <out-dir>
#
# Inputs (all in-tree, no network access):
#   native/proot             termux/proot v5.1.107.92 (git submodule)
#   native/libandroid-shmem  termux/libandroid-shmem v0.7 (git submodule)
#   native/talloc            talloc 2.4.3 core sources + generated config.h
#   native/patches/proot-android.patch
#
# Outputs in <out-dir>:
#   libproot.so         PRoot executable, talloc and android-shmem linked statically
#   libproot_loader.so  PRoot's 64-bit loader (selected at runtime via PROOT_LOADER)
#
# The APK only exposes executables through the JNI library directory, which is
# why both binaries carry lib*.so names.
set -euo pipefail

if [ $# -ne 3 ]; then
  echo "usage: $0 <ndk-dir> <min-sdk> <out-dir>" >&2
  exit 2
fi

NDK_DIR=$1
MIN_SDK=$2
OUT_DIR=$(mkdir -p "$3" && cd "$3" && pwd)
REPO_ROOT=$(cd "$(dirname "$0")/../.." && pwd)
NATIVE_DIR="$REPO_ROOT/native"
PROOT_VERSION=5.1.107.92

case "$(uname -s)" in
  Darwin) HOST_TAG=darwin-x86_64 ;;
  Linux) HOST_TAG=linux-x86_64 ;;
  *) echo "unsupported build host $(uname -s)" >&2; exit 1 ;;
esac
TOOLCHAIN="$NDK_DIR/toolchains/llvm/prebuilt/$HOST_TAG/bin"
if [ ! -x "$TOOLCHAIN/clang" ]; then
  echo "NDK clang not found under $TOOLCHAIN" >&2
  exit 1
fi
for src in "$NATIVE_DIR/proot/src/GNUmakefile" "$NATIVE_DIR/libandroid-shmem/shmem.c"; do
  if [ ! -f "$src" ]; then
    echo "missing $src; run: git submodule update --init" >&2
    exit 1
  fi
done

TARGET="aarch64-linux-android$MIN_SDK"
CC="$TOOLCHAIN/clang --target=$TARGET"
AR="$TOOLCHAIN/llvm-ar"
# Deterministic output: no build paths or timestamps in the binaries.
REPRO_FLAGS="-ffile-prefix-map=$REPO_ROOT=."

WORK_DIR="$OUT_DIR/.work"
rm -rf "$WORK_DIR"
mkdir -p "$WORK_DIR/include" "$WORK_DIR/lib"

# talloc (LGPL-3.0-or-later) and libandroid-shmem (BSD-3-Clause) as static archives.
$CC -O2 -fPIC -fvisibility=hidden -D_GNU_SOURCE=1 -D__STDC_WANT_LIB_EXT1__=1 \
  -ffile-prefix-map="$REPO_ROOT"=. \
  -I"$NATIVE_DIR/talloc" -I"$NATIVE_DIR/talloc/lib/replace" \
  -c "$NATIVE_DIR/talloc/talloc.c" -o "$WORK_DIR/talloc.o"
# Termux's patched NDK headers supply _PATH_TMP and a transitive <fcntl.h>;
# the stock NDK does neither. Key symlinks live in the app's own cache dir.
$CC -O2 -fPIC -std=c11 -ffile-prefix-map="$REPO_ROOT"=. \
  -include fcntl.h -D_PATH_TMP="\"/data/data/com.scariflabs.horus/cache/\"" \
  -c "$NATIVE_DIR/libandroid-shmem/shmem.c" -o "$WORK_DIR/shmem.o"
"$AR" rcsD "$WORK_DIR/lib/libtalloc.a" "$WORK_DIR/talloc.o"
"$AR" rcsD "$WORK_DIR/lib/libandroid-shmem.a" "$WORK_DIR/shmem.o"
cp "$NATIVE_DIR/talloc/talloc.h" "$NATIVE_DIR/libandroid-shmem/shm.h" "$WORK_DIR/include/"

# PRoot, built from a patched copy so the submodule checkout stays pristine.
cp -R "$NATIVE_DIR/proot" "$WORK_DIR/proot"
rm -rf "$WORK_DIR/proot/.git"
git -C "$WORK_DIR/proot" init -q
git -C "$WORK_DIR/proot" apply --recount "$NATIVE_DIR/patches/proot-android.patch"
rm -rf "$WORK_DIR/proot/.git"

# Flags go through the environment: the GNUmakefile appends to them with +=,
# whereas command-line variables would replace its own include paths. Only
# static archives sit in the -L directory, so -ltalloc/-landroid-shmem link
# statically; liblog is the platform library shmem logs through. Segments are
# 16 KB aligned so the binaries load on 16 KB page-size devices.
CPPFLAGS="-I$WORK_DIR/include -DARG_MAX=131072 -DVERSION=\\\"$PROOT_VERSION\\\"" \
CFLAGS="-O2 $REPRO_FLAGS" \
LDFLAGS="-L$WORK_DIR/lib -llog -Wl,--build-id=none -Wl,-z,max-page-size=16384" \
LOADER_LDFLAGS="-Wl,-z,max-page-size=16384" \
make -C "$WORK_DIR/proot/src" -j"$(getconf _NPROCESSORS_ONLN)" proot loader/loader \
  CC="$CC" LD="$CC" \
  STRIP="$TOOLCHAIN/llvm-strip" OBJCOPY="$TOOLCHAIN/llvm-objcopy" OBJDUMP="$TOOLCHAIN/llvm-objdump" \
  GIT=false \
  PROOT_WITH_LIBANDROID_SHMEM=true \
  PROOT_UNBUNDLE_LOADER=/proot-loader-is-set-via-PROOT_LOADER

"$TOOLCHAIN/llvm-strip" -o "$OUT_DIR/libproot.so" "$WORK_DIR/proot/src/proot"
"$TOOLCHAIN/llvm-strip" -o "$OUT_DIR/libproot_loader.so" "$WORK_DIR/proot/src/loader/loader"
chmod 755 "$OUT_DIR/libproot.so" "$OUT_DIR/libproot_loader.so"
rm -rf "$WORK_DIR"
