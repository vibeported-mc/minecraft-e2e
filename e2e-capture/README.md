# e2e-capture

Video capture for the e2e harness: record what a Minecraft client actually drew,
straight off the GPU, and hand back an MP4 a browser can play.

This directory is a **standalone Gradle build**; nothing in the project above it
references it yet, and it has no dependency on Minecraft. It holds a reproducible
cross-build of the FFmpeg libraries for Windows cut down to what capture needs,
Panama bindings generated from those exact headers, an object-oriented layer over
them, and an example that records itself.

```
e2e-capture/
  build.gradle.kts             the Gradle build; buildNatives drives the container
  docker/Dockerfile            the cross-build and the binding generation
  docker/scripts/00-deps.sh      nv-codec-headers, Vulkan-Headers, zlib, libopus
  docker/scripts/10-ffmpeg.sh    the component wishlists and the configure line
  docker/scripts/15-bindings.sh  jextract, cross-targeted at Windows
  docker/scripts/20-verify.sh    the gate: no missing library, no stray import
  docker/bindings/               umbrella headers for the bindings
  libav-gen/                   generated Panama bindings (sources in build/generated)
  libav/                       the object-oriented layer, and the natives it ships
  example/                     rotating triangle, recorded through NVENC
  build/natives/               the DLLs                    (gitignored)
  build/generated/             the jextract output         (gitignored)
  EXTENDING.md                 how to wrap more of libav
```

## Building

```powershell
.\gradlew build                 # everything, natives included
.\gradlew :example:run          # renders a triangle, writes example\out.mp4
```

There is no separate script to run first. The `buildNatives` task drives
`docker buildx` and unpacks its output into `build/natives` and
`build/generated`, and every other task depends on it.

It also stays out of the way: `docker/` is declared as the task's input and
those two directories as its outputs, so Gradle skips it entirely unless a
Dockerfile, a build script, a binding header or a pinned ref actually changed.
The first build takes a few minutes; every one after that skips straight past it
in well under a second. Only `gradlew clean`, or an edit under `docker/`, brings
docker back into the loop.

Two properties, both optional:

```powershell
.\gradlew build -Plibav.ffmpegRef=n9.0.1        # a different FFmpeg tag
.\gradlew build -Plibav.withPrograms=true       # also build ffmpeg.exe/ffprobe.exe
```

`ffmpeg.exe` and `ffprobe.exe` land in `build/natives` when asked for -- useful
for inspecting a recording by hand -- and are deliberately left out of the jar.

What the container produces, and all it produces:

```
build/natives/*.dll             avutil, avcodec, avformat, avfilter, swscale, swresample
build/natives/BUILD-MANIFEST.txt  exact revisions, configure line, every enabled component
build/natives/licenses/
build/generated/                the Panama bindings, ~350 classes
```

The headers, import libraries and upstream API examples stay inside the builder
image; nothing downstream reads them. `docker buildx build --target build` gets
a shell with the lot if you ever need it.


## What is in it

Built `--disable-everything` and switched back on component by component. LGPL:
no `--enable-gpl`, no `--enable-nonfree`, no x264/x265.

| | |
|---|---|
| **NVIDIA** | `h264_nvenc` `hevc_nvenc` `av1_nvenc`, nvdec + cuvid decode, CUDA hwcontext, `scale_cuda` `overlay_cuda` `hwupload_cuda` |
| **Vulkan** | Vulkan hwcontext, `h264_vulkan` `hevc_vulkan` `av1_vulkan` `ffv1_vulkan` video encode, `*_vulkan` hwaccel decode, `scale_vulkan` `overlay_vulkan` |
| **D3D11** | `d3d11va` + `dxva2` hwcontexts, for the WGL_NV_DX_interop2 fallback |
| **Muxing** | `mp4` `mov`, plus `matroska`/`webm`, `image2` (PNG frame grabs), `wav`, `ogg` |
| **Video** | H.264/HEVC/AV1/VP9 decode; `ffv1` + `rawvideo` + `png` for lossless intermediates |
| **Audio** | `aac` (native encoder, plays everywhere including Safari), `libopus`, `flac`, `pcm_s16le` |
| **Not in it** | network protocols, devices, subtitles, every software video encoder, docs |

Component names are checked against `configure --list-*` before they are passed
on, so a codec this release renamed or does not have drops out with a warning
instead of failing the build. Grep `BUILD-MANIFEST.txt` to see what actually
landed.

Nothing NVIDIA or Khronos is linked: `nvcuda.dll`, `nvEncodeAPI64.dll` and
`vulkan-1.dll` are all resolved at runtime by FFmpeg itself, and the verify stage
enforces that. The DLLs load fine on a machine with no NVIDIA GPU and no Vulkan
runtime -- probe for a device and fall back.

Two build-time details worth knowing, both new in FFmpeg 9:

* Vulkan is detected header-only (`check_pkg_config_header_only`), which is why
  `00-deps.sh` writes a `vulkan.pc` with an empty `Libs:` rather than building a
  loader. `--enable-vulkan-static`, which *would* link one, stays off.
* The Vulkan shaders are compiled to SPIR-V at build time by a host `glslc`
  (`--glslc=`), not by a linked-in libshaderc as in FFmpeg 7/8. That is a Debian
  package in the image and costs nothing -- there is no reason to turn Vulkan
  shader support off, so there is no knob for it.

## How a frame gets from LWJGL to the encoder

FFmpeg has no OpenGL hardware context and is not going to grow one; the `opengl`
output device is an SDL window, not interop, and is deliberately off. Interop is
the application's job, and this build exists to give it every route Windows
offers:

1. **CUDA-GL** (NVIDIA, simplest). Register the GL texture with
   `cuGraphicsGLRegisterImage`, map it, `cuMemcpy2D` into an `AVFrame` backed by
   an `AV_HWDEVICE_TYPE_CUDA` frames context, encode with `h264_nvenc`. Share the
   context by handing FFmpeg your `CUcontext` (or taking its
   `AVCUDADeviceContext.cuda_ctx`) so nothing crosses the PCIe bus. LWJGL's
   `org.lwjgl.cuda` covers the driver API.
2. **Vulkan external memory** (vendor-neutral, no copy at all). FFmpeg allocates
   Vulkan frames with exportable memory; import the Win32 handle into GL with
   `GL_EXT_memory_object_win32` and synchronise with `GL_EXT_semaphore_win32`,
   then encode with `h264_vulkan`. Also the path to take if the renderer itself
   is Vulkan, where the frame never leaves the device.
3. **D3D11 shared handle** (widest driver support). Blit into a shared D3D11
   texture, import into GL via `WGL_NV_DX_interop2`, wrap as an
   `AV_HWDEVICE_TYPE_D3D11VA` frame, `hwmap` to CUDA or Vulkan for encode.
4. **Read-back** (works everywhere, costs a stall). `glReadPixels` into a
   persistently-mapped PBO, then `hwupload_cuda` and encode. Worth having as the
   reference path to check the fast ones against.

`hwupload`, `hwdownload` and `hwmap` are all enabled, so any of these can be
wired as a filter graph rather than by hand.

For browser playback, mux MP4 with `movflags=faststart` (or `frag_keyframe+
empty_moov` if the recording has to survive a crashed client) and pick `aac` for
maximum compatibility, `libopus` for quality per bit.

## Pins

`docker/Dockerfile` build args, all overridable. `FFMPEG_REF` and
`WITH_PROGRAMS` have Gradle properties in front of them (`-Plibav.ffmpegRef`,
`-Plibav.withPrograms`); for the rest, edit the Dockerfile or pass
`--build-arg` to a manual `docker buildx build`:

| arg | default | |
|---|---|---|
| `FFMPEG_REF` | `n9.0.1` | |
| `NVCODEC_REF` | `master` | nv-codec-headers; must be >= what this FFmpeg wants |
| `VULKAN_HEADERS_REF` | `main` | must satisfy `vulkan >= 1.3.277` |
| `OPUS_REF` | `v1.5.2` | |
| `ZLIB_REF` | `v1.3.1` | needed by the PNG codec |
| `WITH_PROGRAMS` | `0` | build ffmpeg.exe/ffprobe.exe too |
| `DEBIAN_REF` | `trixie-slim` | supplies gcc-mingw-w64 14, clang 19, glslc |
| `JEXTRACT_URL` | jextract 25 | must match the JDK the bindings are compiled with |

A pinned ref that has since been retagged falls back to the default branch with
a warning rather than breaking the build. `BUILD-MANIFEST.txt` records the commit
hash actually used for every source, so a build stays identifiable after the fact.

## The Java layer

Three modules, all built by `gradlew build`:

* **`:libav-gen`** -- jextract output, the complete public surface of all six
  libraries (~350 classes). Generated into `build/generated`, never edited, never
  checked in. Its own module so it compiles once and stays cached.
* **`:libav`** -- the hand-written object-oriented layer. Small on purpose:
  `CudaDevice`, `CudaGlImage`, `HwFramePool`, `Frame`, `Packet`, `VideoEncoder`,
  `AudioEncoder`, `Muxer`. Each class owns one native resource and is
  `AutoCloseable`. Anything not wrapped is still reachable through `:libav-gen`
  -- see [EXTENDING.md](EXTENDING.md).
* **`:example`** -- `RotatingTriangleCapture`.

The `:libav` jar **carries the DLLs inside it** as resources and unpacks them on
first use into `%LOCALAPPDATA%\vibeported\libav\<id>`, keyed by content hash, so
a consumer needs nothing but the jar. `-Dlibav.home=<dir>` skips that and loads
out of a directory instead, which is what `:example:run` does against
`build/natives`. `nvcuda.dll` is never bundled -- it belongs to the driver -- and is
loaded from the system by name.

### What the example demonstrates

It reproduces Minecraft's rendering setup rather than inventing its own: the GLFW
hints are copied from `GlBackend.setWindowHints` (OpenGL 3.3 core,
forward-compatible, native context API), and it draws into an offscreen
`GL_RGBA8` colour texture with a depth attachment at 854x480 -- the shape and
format of `MainTarget`.

The capture path is then four steps, none of which involve the CPU:

```
GL_RGBA8 texture  --cuGraphicsGLRegisterImage-->  CUDA array
                  --cuMemcpy2D, device to device-->  AVFrame(CUDA, rgb0)
                  --avcodec_send_frame-->  h264_nvenc
                  --av_interleaved_write_frame-->  out.mp4
```

No `glReadPixels`, no `hwdownload`, no `swscale`. The CUDA device is chosen with
`cuGLGetDevices` rather than assumed to be device 0, which matters on any machine
with more than one GPU.

**Timing.** Frame timestamps are a pure function of the frame index, so the file
comes out identical on any machine -- what an e2e harness wants. The render loop
is then paced to that same 60 fps clock, because otherwise a fast GPU finishes
300 frames in well under a second while the file still says five, and the preview
window appears to spin ten times faster than the recording ever will. To record
wall-clock time instead -- dropping frames when the game stutters, which is
usually what recording a real session wants -- timestamp from a monotonic clock
and drop the pacing.

## Adding a component

Edit the wishlists at the top of `docker/scripts/10-ffmpeg.sh` -- they are plain
arrays, one per component class. (`buffer`/`buffersink` are not among them:
libavfilter always builds its own API entry points.) Adding a codec that needs a
new external library means adding it to `00-deps.sh` as a static build in
`/opt/win64` plus the matching `--enable-` flag; keep it LGPL-compatible unless
the licensing question gets answered first.
