package dev.vibeported.capture.libav;

import dev.vibeported.capture.libav.gen.libav;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/** A libav call that failed. Carries the negative AVERROR and its text. */
public class LibavException extends RuntimeException {

    private final int code;

    LibavException(String what, int code) {
        super(what + ": " + describe(code) + " (" + code + ")");
        this.code = code;
    }

    LibavException(String message) {
        super(message);
        this.code = 0;
    }

    public int code() {
        return code;
    }

    /** Throws unless {@code ret} is non-negative; returns it otherwise. */
    public static int check(String what, int ret) {
        if (ret < 0) throw new LibavException(what, ret);
        return ret;
    }

    /** Throws if the pointer is null; returns it otherwise. */
    public static MemorySegment checkNotNull(String what, MemorySegment p) {
        if (p == null || p.equals(MemorySegment.NULL)) {
            throw new LibavException(what + " returned null");
        }
        return p;
    }

    private static String describe(int code) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(256);
            if (libav.av_strerror(code, buf, buf.byteSize()) < 0) return "unknown error";
            return buf.getString(0);
        } catch (RuntimeException e) {
            return "unknown error";
        }
    }
}
