package dev.vibeported.capture.cuda;

import dev.vibeported.capture.libav.gen.libav;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/**
 * The CUDA driver API, as much of it as capture needs.
 *
 * <p>{@code nvcuda.dll} is part of the NVIDIA display driver, not something
 * that can be shipped, so it is loaded from the system by name. Its absence
 * means "no NVIDIA driver here", which is a normal thing to discover, not a
 * crash.
 */
public final class CudaRuntime {

    private static boolean initialised;

    private CudaRuntime() {}

    /** Loads and initialises the driver. Safe to call repeatedly. */
    public static synchronized void init() {
        if (initialised) return;
        try {
            System.loadLibrary("nvcuda");
        } catch (UnsatisfiedLinkError e) {
            throw new CudaException("nvcuda.dll is not present -- no NVIDIA driver on this machine");
        }
        CudaException.check("cuInit", libav.cuInit(0));
        initialised = true;
    }

    /**
     * The CUDA device driving the current OpenGL context.
     *
     * <p>Worth the trouble on any machine with more than one GPU: registering a
     * GL texture with a CUDA context on the wrong device fails, and "device 0"
     * is not reliably the one rendering. Requires a current GL context.
     */
    public static int deviceForCurrentGlContext() {
        init();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment count = arena.allocate(libav.C_INT);
            MemorySegment devices = arena.allocate(libav.C_INT, 8);
            CudaException.check("cuGLGetDevices",
                    libav.cuGLGetDevices(count, devices, 8, libav.CU_GL_DEVICE_LIST_ALL()));
            if (count.get(libav.C_INT, 0) < 1) {
                throw new CudaException("No CUDA device is driving the current GL context");
            }
            return devices.get(libav.C_INT, 0);
        }
    }

    /** Human-readable name of a CUDA device, for logging what was picked. */
    public static String deviceName(int device) {
        init();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment name = arena.allocate(256);
            CudaException.check("cuDeviceGetName", libav.cuDeviceGetName(name, 256, device));
            return name.getString(0);
        }
    }

    static void pushContext(MemorySegment context) {
        CudaException.check("cuCtxPushCurrent", libav.cuCtxPushCurrent_v2(context));
    }

    static void popContext() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(libav.C_POINTER);
            CudaException.check("cuCtxPopCurrent", libav.cuCtxPopCurrent_v2(out));
        }
    }
}
