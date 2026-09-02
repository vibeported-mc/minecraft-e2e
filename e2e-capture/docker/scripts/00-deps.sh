#!/usr/bin/env bash
# Header-only and small static dependencies. Nothing here ends up as a separate
# DLL: it is all linked into the av* libraries or resolved from the driver at
# runtime.
. "$(dirname "$0")/common.sh"

: "${NVCODEC_REF:=master}"
: "${VULKAN_HEADERS_REF:=main}"
: "${OPUS_REF:=v1.5.2}"
: "${ZLIB_REF:=v1.3.1}"

# --- nv-codec-headers -------------------------------------------------------
# The NVENC/NVDEC/CUDA entry points, headers only. nvEncodeAPI64.dll, nvcuda.dll
# and nvdec are loaded off the installed driver at runtime, so no part of the
# CUDA toolkit is needed to build and nothing NVIDIA has to be redistributed.
fetch nv-codec-headers https://github.com/FFmpeg/nv-codec-headers.git "$NVCODEC_REF"
make -C "$SRC/nv-codec-headers" install PREFIX="$PREFIX"

# --- Vulkan-Headers ---------------------------------------------------------
# libavutil dlopens vulkan-1.dll itself, so headers alone are enough -- there is
# deliberately no loader import in the shipped DLLs.
fetch Vulkan-Headers https://github.com/KhronosGroup/Vulkan-Headers.git "$VULKAN_HEADERS_REF"
cmake -S "$SRC/Vulkan-Headers" -B "$SRC/Vulkan-Headers/build" -G Ninja \
      -DCMAKE_INSTALL_PREFIX="$PREFIX" >/dev/null
cmake --install "$SRC/Vulkan-Headers/build" >/dev/null

# Vulkan-Headers ships no pkg-config file (the loader normally provides it), and
# FFmpeg's configure gates on `vulkan >= 1.3.277`. Write one with an empty Libs:
# so configure is satisfied without linking a loader we do not want linked.
vk_patch=$(grep -oP '^#define\s+VK_HEADER_VERSION\s+\K[0-9]+' \
           "$PREFIX/include/vulkan/vulkan_core.h" | head -1)
vk_minor=$(grep -oP '^#define\s+VK_API_VERSION_1_\K[0-9]+' \
           "$PREFIX/include/vulkan/vulkan_core.h" | sort -n | tail -1)
mkdir -p "$PREFIX/lib/pkgconfig"
cat > "$PREFIX/lib/pkgconfig/vulkan.pc" <<PC
prefix=$PREFIX
includedir=\${prefix}/include
Name: Vulkan
Description: Vulkan headers; the loader is resolved at runtime by libavutil
Version: 1.${vk_minor:-3}.${vk_patch:-0}
Cflags: -I\${includedir}
Libs:
PC
echo "== vulkan.pc: $(grep ^Version "$PREFIX/lib/pkgconfig/vulkan.pc")"

# --- zlib -------------------------------------------------------------------
# Needed by the PNG codec (frame grabs) and by mov/matroska metadata. The win32
# makefile is used rather than CMake because CMake names the static library
# zlibstatic on Windows, which -lz would then miss.
fetch zlib https://github.com/madler/zlib.git "$ZLIB_REF"
make -C "$SRC/zlib" -f win32/Makefile.gcc -j"$JOBS" \
     PREFIX="$CROSS_PREFIX" libz.a
make -C "$SRC/zlib" -f win32/Makefile.gcc install \
     PREFIX="$CROSS_PREFIX" SHARED_MODE=0 \
     INCLUDE_PATH="$PREFIX/include" LIBRARY_PATH="$PREFIX/lib" BINARY_PATH="$PREFIX/bin"

# --- libopus ----------------------------------------------------------------
# The native AAC encoder covers Safari; Opus covers everything else and is the
# better codec at the bitrates a screen capture wants.
fetch opus https://github.com/xiph/opus.git "$OPUS_REF"
cmake_win "$SRC/opus" "$SRC/opus/build" \
  -DOPUS_BUILD_PROGRAMS=OFF -DOPUS_BUILD_TESTING=OFF -DBUILD_TESTING=OFF

echo "== dependency sysroot:"; find "$PREFIX" -name '*.pc' -printf '   %p\n'
