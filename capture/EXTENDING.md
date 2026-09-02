# Wrapping more of libav

The bindings under `dev.vibeported.capture.libav.gen` cover **the whole public
surface** of the six FFmpeg libraries this project builds — every function,
struct and constant. The hand-written layer next to it wraps only what capture
needs.

So the usual answer to "how do I use X from libav" is: **X is already bound.**
Write a wrapper class; do not rebuild anything.

## The normal case: wrap something already bound

1. **Find it.** The generated code is in `build/generated/`. Functions live
   on the `libav` class (split across `libav.java`, `libav_1.java`, …); each
   struct is its own class with an accessor per field.

   ```
   grep -rl "avfilter_graph_alloc" build/generated/
   ```

2. **Write the wrapper** in `libav/src/main/java/dev/vibeported/capture/libav/`,
   following the rules below.

3. **Compile.** `gradlew :capture:libav:compileJava`. Nothing else to run.

### Worked example: a filter graph

Say you want `scale_cuda` in front of the encoder. Everything needed —
`avfilter_graph_alloc`, `avfilter_graph_create_filter`, `AVFilterContext`,
`av_buffersrc_add_frame`, `av_buffersink_get_frame` — is already generated,
because `libavfilter` is one of the libraries built. A wrapper looks like every
other class here:

```java
public final class FilterGraph implements AutoCloseable {
    private final Arena arena = Arena.ofConfined();
    private MemorySegment graph;

    public FilterGraph() {
        Libav.init();
        graph = LibavException.checkNotNull("avfilter_graph_alloc",
                libav.avfilter_graph_alloc()).reinterpret(AVFilterGraph.sizeof());
    }

    @Override public void close() {
        if (graph != null) {
            MemorySegment holder = arena.allocate(libav.C_POINTER);
            holder.set(libav.C_POINTER, 0, graph);
            libav.avfilter_graph_free(holder);
            graph = null;
        }
        arena.close();
    }
}
```

Note the pattern: allocate through libav, wrap the pointer, `reinterpret` it to
the struct's size so the generated accessors can read through it, and free in
`close()`.

## Rules

- **Never edit generated code.** The `buildNatives` task overwrites
  `build/generated/` wholesale, and the directory is gitignored.
- **One native resource per wrapper**, and always `AutoCloseable`. If a class
  owns two things that need freeing, it is two classes.
- **`reinterpret` every pointer before reading through it.** jextract hands back
  zero-length segments for pointers on purpose — they are unusable until you say
  how much is really there. `Libav.at(pointer, Struct.sizeof())` does this.
- **Check every return code.** `LibavException.check(what, rc)` for `int`
  returns, `LibavException.checkNotNull(what, p)` for pointers. A silently
  ignored AVERROR turns into a confusing failure three calls later.
- **Keep structs out of the calling convention.** Functions that take or return
  a struct *by value* — `av_rescale_q`, `av_packet_rescale_ts`, anything taking
  an `AVRational` — are avoided in favour of doing the arithmetic in Java. See
  `Muxer.Stream.toStreamTime`. This keeps the FFI surface pointer-only.
- **No hand-copied constants.** If you need one, it is almost certainly already
  generated: `libav.AV_PIX_FMT_0BGR32()`, `libav.AVERROR_EOF()`. See below for
  the exception.

## The rarer cases, which do need a rebuild

Each of these means editing something under `docker/`. Nothing else is needed:
`docker/` is the `buildNatives` task input, so the next `gradlew build` notices
the change and regenerates by itself.

**A function-like macro.** jextract cannot evaluate `AVERROR(EAGAIN)`, so it
binds nothing for it. Restate it as an object macro in
`docker/bindings/libav_extra.h`:

```c
#define E2E_AVERROR_EAGAIN AVERROR(EAGAIN)
```

It then arrives as `libav.E2E_AVERROR_EAGAIN()`, still computed from FFmpeg's
own headers rather than typed into Java from memory.

**More of the CUDA driver API.** nv-codec-headers only supplies function
*typedefs*, which jextract cannot turn into downcalls, so each entry point is
declared for real in `docker/bindings/cuda_min.h`. Add the prototype there.

**A component FFmpeg was not built with.** If the codec, muxer or filter is not
in `build/natives/BUILD-MANIFEST.txt`, no amount of binding will help — it is not in the
DLLs. Add it to the wishlists at the top of `docker/scripts/10-ffmpeg.sh`.

**A header the build skipped.** `15-bindings.sh` derives its header list from
FFmpeg's own `config.h`: a header for a feature this build disabled is left out,
because its SDK is not present to parse it. Turn the feature on in
`10-ffmpeg.sh` and the header follows automatically.

**Vulkan.** `libavutil/hwcontext_vulkan.h` is excluded by name, because
`AVVulkanDeviceContext` embeds a `VkPhysicalDeviceFeatures2` *by value* — so
binding it means binding the entire Vulkan API, thousands of classes for
something the capture path does not call. The DLLs have full Vulkan support
regardless; only the Java view is omitted. To bind it, drop the header from
`BINDINGS_EXCLUDE_HEADERS` in `15-bindings.sh` and add the Vulkan include
directory to the keep filter in the same file.

## How generation works, in one paragraph

`docker/scripts/15-bindings.sh` builds an umbrella header from every public
header this build supports (asking FFmpeg's `config.h` which those are), takes
the `-D` and `-I` flags out of FFmpeg's own `config.mak` so the headers are
parsed exactly as they were compiled, and runs jextract over it. Cross-targeting
is done with a `compile_flags.txt` holding `--target=x86_64-w64-windows-gnu` and
`--sysroot` — jextract 25 has no `-C` option, and without this the headers would
be parsed with Linux's data model and produce silently wrong struct layouts.
Symbols reachable only through the mingw C runtime are filtered out by which
header they came from, which is why the output is ~350 files rather than ~4000.
