#!/usr/bin/env bash
# The FFmpeg cross-build itself: shared av* DLLs for x86_64 Windows, LGPL, with
# everything switched off except the components the capture pipeline names.
. "$(dirname "$0")/common.sh"

: "${FFMPEG_REF:=n9.0.1}"
: "${WITH_PROGRAMS:=0}"

fetch ffmpeg https://github.com/FFmpeg/FFmpeg.git "$FFMPEG_REF"
cd "$SRC/ffmpeg"

# --- component wishlists ----------------------------------------------------
# Names are checked against `configure --list-*` before being passed on, so a
# component this FFmpeg release does not have (or has renamed) drops out with a
# warning instead of failing configure. Grep the manifest afterwards to see what
# actually made it in.

ENCODERS=(
  # NVENC: the primary path. Takes CUDA frames straight out of GL interop.
  h264_nvenc hevc_nvenc av1_nvenc
  # Vulkan video encode: the driver-agnostic path, no CUDA round trip.
  h264_vulkan hevc_vulkan av1_vulkan ffv1_vulkan
  # CPU fallbacks and lossless intermediates.
  ffv1 rawvideo mjpeg png
  # Browser-playable audio.
  aac libopus flac pcm_s16le
)
DECODERS=(
  h264 hevc av1 vp9
  h264_cuvid hevc_cuvid av1_cuvid vp9_cuvid
  ffv1 rawvideo mjpeg png
  aac opus libopus flac vorbis pcm_s16le
)
HWACCELS=(
  h264_nvdec hevc_nvdec av1_nvdec vp9_nvdec
  h264_vulkan hevc_vulkan av1_vulkan
  # Both spellings of each D3D11 hwaccel: the _d3d11va2 entry points live in
  # an object gated on the non-2 config symbol, so enabling only the 2 leaves
  # a dangling ff_*_d3d11va2_hwaccel reference at link time.
  h264_d3d11va h264_d3d11va2 hevc_d3d11va hevc_d3d11va2 av1_d3d11va av1_d3d11va2
)
MUXERS=(mp4 mov matroska webm image2 wav ogg rawvideo null)
DEMUXERS=(mov matroska image2 wav ogg rawvideo h264 hevc aac flac concat)
PARSERS=(h264 hevc av1 vp9 aac opus vorbis flac png mjpeg)
BSFS=(h264_mp4toannexb hevc_mp4toannexb aac_adtstoasc extract_extradata null)
PROTOCOLS=(file pipe fd)
FILTERS=(
  # buffer/buffersink are not on the list: libavfilter always builds its own
  # API entry points, they are not optional components.
  null anull copy format aformat setpts asetpts settb fps trim atrim
  scale crop pad vflip hflip transpose overlay concat
  aresample amix volume
  # Hardware frame plumbing: hwupload_cuda is the GL->NVENC hop, hwmap is the
  # zero-copy one where the driver allows it.
  hwupload hwdownload hwmap hwupload_cuda
  scale_cuda overlay_cuda scale_vulkan overlay_vulkan
  # Sources, so the build can be smoke-tested without a Minecraft window.
  color testsrc testsrc2 anullsrc sine
)

# --- wishlist -> configure flags -------------------------------------------
SELECTED=()
select_class() {
  local singular=$1 plural=$2; shift 2
  # --list-* prints three padded columns, so tokenise on whitespace.
  local available; available=$(./configure --list-"$plural" | tr -s '[:space:]' '\n' | sed '/^$/d')
  local keep=() drop=()
  for c in "$@"; do
    if grep -qxF -- "$c" <<< "$available"; then keep+=("$c"); else drop+=("$c"); fi
  done
  if [ ${#drop[@]} -ne 0 ]; then echo "!! $plural not in this release, skipped: ${drop[*]}"; fi
  if [ ${#keep[@]} -eq 0 ]; then return 0; fi
  SELECTED+=("--enable-$singular=$(IFS=,; echo "${keep[*]}")")
}

select_class encoder  encoders  "${ENCODERS[@]}"
select_class decoder  decoders  "${DECODERS[@]}"
select_class hwaccel  hwaccels  "${HWACCELS[@]}"
select_class muxer    muxers    "${MUXERS[@]}"
select_class demuxer  demuxers  "${DEMUXERS[@]}"
select_class parser   parsers   "${PARSERS[@]}"
select_class bsf      bsfs      "${BSFS[@]}"
select_class protocol protocols "${PROTOCOLS[@]}"
select_class filter   filters   "${FILTERS[@]}"

# --- configure --------------------------------------------------------------
FLAGS=(
  --prefix="$OUT"
  --enable-cross-compile --cross-prefix="$CROSS_PREFIX"
  --target-os=mingw32 --arch=x86_64
  --pkg-config=pkg-config --pkg-config-flags=--static

  --enable-shared --disable-static
  --disable-autodetect --disable-everything
  --disable-doc --disable-debug --disable-avdevice
  --disable-network --disable-iconv --disable-schannel

  # LGPL only: no --enable-gpl, no --enable-nonfree. NVENC and NVDEC have been
  # redistributable under the LGPL since they moved to nv-codec-headers.
  --enable-zlib

  # CUDA/NVENC/NVDEC. --enable-cuda-llvm compiles the CUDA kernels with clang
  # against FFmpeg's own compat/cuda headers, so no CUDA toolkit is needed.
  --enable-ffnvcodec --enable-nvenc --enable-nvdec --enable-cuvid
  --enable-cuda-llvm --nvcc=clang

  # Vulkan. Header-only detection: libavutil dlopens vulkan-1.dll itself, and
  # --enable-vulkan-static (which would link a loader) stays off deliberately.
  # The Vulkan shaders are compiled to SPIR-V at build time by the host glslc,
  # so nothing here links a GLSL compiler either.
  --enable-vulkan --glslc=glslc

  # D3D11 is the other half of the Windows interop story: GL and Vulkan can both
  # import a D3D11 shared handle when the direct path is unavailable.
  --enable-d3d11va --enable-dxva2

  --enable-libopus

  --extra-cflags="-I$PREFIX/include -O2"
  # -static-libgcc so the DLLs carry no libgcc_s dependency to ship alongside;
  # 20-verify.sh fails the build if one ever creeps back in.
  --extra-ldflags="-L$PREFIX/lib -static-libgcc"
)
if [ "$WITH_PROGRAMS" = "1" ]; then
  # ffmpeg.exe/ffprobe.exe: handy when bisecting a capture bug by hand, dead
  # weight in the shipped artifact, so off unless asked for.
  FLAGS+=(--enable-ffmpeg --enable-ffprobe)
else
  FLAGS+=(--disable-programs)
fi

printf '%s\n' "== configure:" "${FLAGS[@]}" "${SELECTED[@]}"
./configure "${FLAGS[@]}" "${SELECTED[@]}" || { echo "!! configure failed, tail of config.log:";
                                                tail -n 60 ffbuild/config.log 2>/dev/null; exit 1; }
make -j"$JOBS"
make install

# --- tidy the import libraries ----------------------------------------------
# FFmpeg's mingw build emits both flavours already: lib*.dll.a for mingw, and
# dlltool-generated *.lib next to the DLLs for link.exe. Move the MSVC ones out
# of bin/ so that directory holds nothing but the DLLs you ship next to the jar,
# and park the .def files with them. JNI/Panama loading needs none of it.
mkdir -p "$OUT/lib/msvc"
mv "$OUT"/bin/*.lib "$OUT/lib/msvc/" 2>/dev/null || true
mv "$OUT"/lib/*.def "$OUT/lib/msvc/" 2>/dev/null || true
echo "== MSVC import libraries: $(ls "$OUT"/lib/msvc/*.lib 2>/dev/null | wc -l)"

# --- manifest ---------------------------------------------------------------
{
  echo "FFmpeg $FFMPEG_REF -- Windows x86_64 (mingw-w64), LGPL, shared"
  echo "built $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo
  echo "== source revisions"
  for d in "$SRC"/*/; do
    printf '%-20s %s\n' "$(basename "$d")" "$(git -C "$d" rev-parse HEAD 2>/dev/null || echo n/a)"
  done
  echo
  echo "== configure"
  printf '%s\n' "${FLAGS[@]}" "${SELECTED[@]}"
  echo
  echo "== enabled components"
  grep -E '^CONFIG_[A-Z0-9_]+=yes' ffbuild/config.mak | sed 's/^CONFIG_//; s/=yes//' | sort | pr -3 -t -w 100
} > "$OUT/BUILD-MANIFEST.txt"

install -Dm644 COPYING.LGPLv2.1 "$OUT/licenses/FFmpeg-COPYING.LGPLv2.1"
install -Dm644 LICENSE.md "$OUT/licenses/FFmpeg-LICENSE.md"
install -Dm644 "$SRC/opus/COPYING" "$OUT/licenses/opus-COPYING"
