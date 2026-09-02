#!/usr/bin/env bash
# Settings shared by every stage of the cross-build. Sourced, never run.
set -euo pipefail

TARGET=x86_64-w64-mingw32
CROSS_PREFIX="${TARGET}-"
PREFIX=/opt/win64          # sysroot for the static dependencies we build ourselves
SRC=/build/src
OUT=/out                   # what ends up in dist/ on the host
JOBS="${JOBS:-$(nproc)}"
TOOLCHAIN=/build/scripts/mingw-w64-x86_64.cmake

# Only ever look at our own sysroot: a host .pc file leaking in would make
# configure believe in a library that cannot possibly link into a Windows DLL.
export PKG_CONFIG_LIBDIR="$PREFIX/lib/pkgconfig:$PREFIX/share/pkgconfig"
unset PKG_CONFIG_PATH

mkdir -p "$SRC" "$PREFIX" "$OUT"

# Shallow clone at a ref. A pin that has since been retagged or dropped falls
# back to the default branch with a warning rather than killing the build.
fetch() {
  local name=$1 url=$2 ref=$3
  local dir="$SRC/$name"   # separate: bash binds every `local` word before assigning
  if [ -d "$dir" ]; then echo "== $name: already present"; return; fi
  echo "== $name: cloning $url @ $ref"
  git clone --depth 1 --branch "$ref" --recurse-submodules "$url" "$dir" 2>/dev/null ||
    { echo "!! $name: ref '$ref' not found, using the default branch instead";
      git clone --depth 1 --recurse-submodules "$url" "$dir"; }
  git -C "$dir" --no-pager log -1 --format='   %H %s'
}

cmake_win() {
  local src=$1 build=$2; shift 2
  cmake -S "$src" -B "$build" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
    -DCMAKE_INSTALL_PREFIX="$PREFIX" \
    -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_SHARED_LIBS=OFF \
    -DCMAKE_INSTALL_LIBDIR=lib \
    "$@"
  cmake --build "$build" --parallel "$JOBS"
  cmake --install "$build"
}
