package dev.vibeported.capture.libav;

import dev.vibeported.capture.libav.gen.libav;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;

/**
 * Entry point. Loads the native libraries and exposes the few global knobs
 * worth having.
 *
 * <p>This package is a deliberately small object-oriented face on
 * {@code dev.vibeported.capture.libav.gen}, which binds the whole of libav.
 * Anything not wrapped here is still reachable there -- see EXTENDING.md.
 */
public final class Libav {

    /** av_log levels, only the ones worth choosing between. */
    public enum LogLevel {
        QUIET(-8), ERROR(16), WARNING(24), INFO(32), VERBOSE(40), DEBUG(48);

        final int value;

        LogLevel(int value) {
            this.value = value;
        }
    }

    private Libav() {}

    /** Loads the DLLs if they are not loaded yet. Safe to call repeatedly. */
    public static Path init() {
        return NativeBootstrap.ensureLoaded();
    }

    public static void logLevel(LogLevel level) {
        init();
        libav.av_log_set_level(level.value);
    }

    /** e.g. {@code "62.3.100"} -- the libavcodec the DLLs actually are. */
    public static String codecVersion() {
        init();
        int v = libav.avcodec_version();
        return (v >> 16) + "." + ((v >> 8) & 0xff) + "." + (v & 0xff);
    }

    /** The name libav gives a pixel format, for logging what was negotiated. */
    public static String pixelFormatName(int pixFmt) {
        init();
        MemorySegment name = libav.av_get_pix_fmt_name(pixFmt);
        return name.equals(MemorySegment.NULL) ? "?" : name.reinterpret(Long.MAX_VALUE).getString(0);
    }

    /** Allocates a NUL-terminated C string in {@code arena}. */
    public static MemorySegment str(Arena arena, String s) {
        return arena.allocateFrom(s);
    }

    /**
     * Gives a bare pointer a length so the generated struct accessors can read
     * through it. jextract hands back zero-length segments for pointers, which
     * are deliberately unusable until you say how much is really there.
     */
    public static MemorySegment at(MemorySegment pointer, long size) {
        return pointer.reinterpret(size);
    }
}
