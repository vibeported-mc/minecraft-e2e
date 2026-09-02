# syntax=docker/dockerfile:1.7
#
# Cross-compiles the FFmpeg libraries for Windows x86_64 with mingw-w64. The
# host stays Linux because the toolchain, the dependency builds and the CUDA
# kernel compilation are all reproducible there; the output is ordinary Windows
# DLLs that LWJGL, Panama or a JNI shim loads like any other.
#
#   docker buildx build --target dist --output type=local,dest=dist docker
#
# ...or just run build-windows.ps1, which passes the build args for you.

ARG DEBIAN_REF=trixie-slim

FROM debian:${DEBIAN_REF} AS build

# clang is here for --enable-cuda-llvm: it compiles FFmpeg's .cu kernels to PTX
# against FFmpeg's own compat/cuda headers, which is what keeps the CUDA toolkit
# out of this image entirely. glslc compiles the Vulkan shaders to SPIR-V at
# build time -- as of FFmpeg 9 that is a host-side step, so no GLSL compiler has
# to be cross-built or linked in.
# Plain gcc is the *host* compiler: FFmpeg builds a couple of generator tools
# that have to run here, so the cross toolchain alone is not enough.
RUN --mount=type=cache,target=/var/cache/apt,sharing=locked \
    --mount=type=cache,target=/var/lib/apt/lists,sharing=locked \
    rm -f /etc/apt/apt.conf.d/docker-clean && \
    apt-get update && apt-get install -y --no-install-recommends \
      ca-certificates curl git make cmake ninja-build pkg-config \
      gcc \
      nasm clang glslc \
      gcc-mingw-w64-x86-64 g++-mingw-w64-x86-64 binutils-mingw-w64-x86-64 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /build
COPY scripts/ /build/scripts/
# Git on Windows does not always carry the exec bit across a clone.
RUN chmod +x /build/scripts/*.sh

# Build args are visible to RUN as environment variables, which is how the
# scripts pick them up. Each stage is its own layer so a change to the FFmpeg
# ref does not rebuild the dependency sysroot.
ARG NVCODEC_REF=master
ARG VULKAN_HEADERS_REF=main
ARG OPUS_REF=v1.5.2
ARG ZLIB_REF=v1.3.1
RUN scripts/00-deps.sh

ARG FFMPEG_REF=n9.0.1
ARG WITH_PROGRAMS=0
RUN scripts/10-ffmpeg.sh

# Panama bindings for the DLLs just built. Its own layer: regenerating bindings
# is common while the FFmpeg build below it is not.
ARG JEXTRACT_URL=
COPY bindings/ /build/bindings/
RUN scripts/15-bindings.sh
RUN scripts/20-verify.sh

# Only what the Gradle build consumes, laid out as the two directories it
# expects. The headers, import libraries and API examples stay in the builder
# image; nothing downstream reads them.
#
#   docker buildx build --target artifacts --output type=local,dest=build docker
#
# gives build/natives (the DLLs, plus the licence texts and the manifest that
# say what they are -- both go in the jar, the LGPL asks for the first) and
# build/generated (the jextract output).
FROM scratch AS artifacts
COPY --from=build /out/bin/               /natives/
COPY --from=build /out/licenses/          /natives/licenses/
COPY --from=build /out/BUILD-MANIFEST.txt /natives/
COPY --from=build /out/generated-java/    /generated/
