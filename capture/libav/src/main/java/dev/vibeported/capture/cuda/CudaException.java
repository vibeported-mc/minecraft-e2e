package dev.vibeported.capture.cuda;

import dev.vibeported.capture.libav.gen.libav;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/** A CUDA driver call that returned something other than CUDA_SUCCESS. */
public class CudaException extends RuntimeException {

    private final int code;

    CudaException(String what, int code) {
        super(what + ": " + describe(code) + " (" + code + ")");
        this.code = code;
    }

    CudaException(String message) {
        super(message);
        this.code = 0;
    }

    public int code() {
        return code;
    }

    static int check(String what, int result) {
        if (result != 0) throw new CudaException(what, result);
        return result;
    }

    private static String describe(int code) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(libav.C_POINTER);
            String name = libav.cuGetErrorName(code, out) == 0
                    ? cstring(out.get(libav.C_POINTER, 0)) : "?";
            String text = libav.cuGetErrorString(code, out) == 0
                    ? cstring(out.get(libav.C_POINTER, 0)) : "";
            return text.isEmpty() ? name : name + " -- " + text;
        } catch (RuntimeException e) {
            return "unknown error";
        }
    }

    private static String cstring(MemorySegment p) {
        return p.equals(MemorySegment.NULL) ? "?" : p.reinterpret(Long.MAX_VALUE).getString(0);
    }
}
