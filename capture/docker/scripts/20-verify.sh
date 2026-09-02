#!/usr/bin/env bash
# Gate the artifact before it leaves the image. Two things are worth failing the
# build over: a missing library, and an accidental runtime dependency -- a
# libstdc++/libwinpthread/libgcc import means the DLLs only load next to the
# compiler that built them, which is the failure mode that shows up on someone
# else's machine and nowhere else.
. "$(dirname "$0")/common.sh"

fail=0

echo "== libraries"
for lib in avutil avcodec avformat avfilter swscale swresample; do
  dll=$(ls "$OUT"/bin/"$lib"-*.dll 2>/dev/null | head -1 || true)
  if [ -z "$dll" ]; then echo "!! missing: $lib"; fail=1; continue; fi
  printf '   %-24s %8s KiB\n' "$(basename "$dll")" "$(( $(stat -c%s "$dll") / 1024 ))"
done

echo "== imports"
# vulkan-1.dll is on the list too: it has to stay a runtime dlopen, or the DLLs
# refuse to load on a machine with no Vulkan runtime installed.
banned='libstdc\+\+-6\.dll|libgcc_s_[a-z]+-1\.dll|libwinpthread-1\.dll|vulkan-1\.dll|nvcuda\.dll|nvEncodeAPI64\.dll'
for dll in "$OUT"/bin/*.dll; do
  imports=$("${CROSS_PREFIX}objdump" -p "$dll" | sed -n 's/^\s*DLL Name: //p' | sort -u)
  printf '   %-24s %s\n' "$(basename "$dll"):" "$(echo "$imports" | tr '\n' ' ')"
  if echo "$imports" | grep -qE "$banned"; then
    echo "!! $(basename "$dll") links something it was supposed to load at runtime"
    fail=1
  fi
done

echo "== requested features"
# config.mak names components with a class suffix; the manifest already has the
# CONFIG_ prefix and the =yes stripped off.
enabled=$(sed -n '/^== enabled components$/,$p' "$OUT/BUILD-MANIFEST.txt" | tr -s '[:space:]' '\n')
missing=()
for want in NVENC NVDEC CUVID CUDA_LLVM FFNVCODEC VULKAN D3D11VA LIBOPUS ZLIB \
            MP4_MUXER MOV_MUXER AAC_ENCODER LIBOPUS_ENCODER H264_NVENC_ENCODER \
            H264_VULKAN_ENCODER HEVC_VULKAN_ENCODER \
            HWUPLOAD_CUDA_FILTER HWMAP_FILTER SCALE_FILTER SCALE_VULKAN_FILTER; do
  grep -qxF "$want" <<< "$enabled" || missing+=("$want")
done
if [ ${#missing[@]} -ne 0 ]; then echo "!! not enabled: ${missing[*]}"; fail=1; else echo "   all present"; fi

echo "== bindings"
gen=$OUT/generated-java/$(echo "${BINDINGS_PACKAGE:-dev.vibeported.capture.libav.gen}" | tr . /)
if [ ! -d "$gen" ]; then
  echo "!! no generated bindings at $gen"; fail=1
else
  echo "   $(find "$gen" -name '*.java' | wc -l) java files"
  # The capture path needs these specific ones; a silently truncated
  # generation would otherwise only surface as a compile error much later.
  for c in libav AVFrame AVPacket AVCodecContext AVCUDADeviceContext AVHWFramesContext CUDA_MEMCPY2D; do
    [ -f "$gen/$c.java" ] || { echo "!! bindings missing $c"; fail=1; }
  done
  for f in cuInit cuGraphicsGLRegisterImage cuMemcpy2D_v2 avcodec_send_frame av_hwframe_get_buffer; do
    grep -qhE "(int|MemorySegment) $f\(" "$gen"/libav*.java || { echo "!! bindings missing $f"; fail=1; }
  done
fi

[ "$fail" -eq 0 ] || { echo "!! verification failed"; exit 1; }
echo "== ok"
