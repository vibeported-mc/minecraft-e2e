package dev.vibeported.capture.cuda;

import dev.vibeported.capture.libav.HwFramePool;
import dev.vibeported.capture.libav.Libav;
import dev.vibeported.capture.libav.LibavException;
import dev.vibeported.capture.libav.PixelFormat;
import dev.vibeported.capture.libav.gen.AVBufferRef;
import dev.vibeported.capture.libav.gen.AVCUDADeviceContext;
import dev.vibeported.capture.libav.gen.AVHWDeviceContext;
import dev.vibeported.capture.libav.gen.libav;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/**
 * libav's CUDA device, and the bridge from OpenGL into it.
 *
 * <p>The CUDA context belongs to libav. Taking its context rather than creating
 * our own is what lets a texture registered here be copied straight into a
 * frame the encoder reads, with no context switching and no host memory.
 */
public final class CudaDevice implements AutoCloseable {

    private final Arena arena = Arena.ofConfined();
    private final int ordinal;
    private MemorySegment deviceRef;

    private CudaDevice(int ordinal) {
        this.ordinal = ordinal;
        MemorySegment holder = arena.allocate(libav.C_POINTER);
        LibavException.check("av_hwdevice_ctx_create(cuda," + ordinal + ")",
                libav.av_hwdevice_ctx_create(holder, libav.AV_HWDEVICE_TYPE_CUDA(),
                        Libav.str(arena, Integer.toString(ordinal)), MemorySegment.NULL, 0));
        this.deviceRef = holder.get(libav.C_POINTER, 0).reinterpret(AVBufferRef.sizeof());
    }

    /**
     * Opens the CUDA device driving the current OpenGL context.
     *
     * <p>Requires a current GL context. On a multi-GPU machine this is the only
     * correct choice; see {@link CudaRuntime#deviceForCurrentGlContext()}.
     */
    public static CudaDevice forCurrentGlContext() {
        Libav.init();
        CudaRuntime.init();
        return new CudaDevice(CudaRuntime.deviceForCurrentGlContext());
    }

    /** The CUDA ordinal this device was opened on. */
    public int ordinal() {
        return ordinal;
    }

    public String name() {
        return CudaRuntime.deviceName(ordinal);
    }

    /** The libav CUcontext. Borrowed; this class still owns it. */
    MemorySegment context() {
        return AVCUDADeviceContext.cuda_ctx(cudaContext());
    }

    /** The stream libav issues its own work on; ours must be ordered with it. */
    MemorySegment stream() {
        return AVCUDADeviceContext.stream(cudaContext());
    }

    private MemorySegment cudaContext() {
        MemorySegment hw = Libav.at(AVBufferRef.data(deviceRef), AVHWDeviceContext.sizeof());
        return Libav.at(AVHWDeviceContext.hwctx(hw), AVCUDADeviceContext.sizeof());
    }

    /** A pool of frames on this device for an encoder to consume. */
    public HwFramePool framePool(int width, int height, PixelFormat softwareFormat, int size) {
        return HwFramePool.on(deviceRef, width, height, softwareFormat, size);
    }

    /**
     * Registers an OpenGL texture for reading by CUDA.
     *
     * <p>Register once and reuse it: registration is expensive, copying is not.
     */
    public CudaGlImage registerGlTexture(int textureId) {
        return new CudaGlImage(this, textureId);
    }

    @Override
    public void close() {
        if (deviceRef != null) {
            MemorySegment holder = arena.allocate(libav.C_POINTER);
            holder.set(libav.C_POINTER, 0, deviceRef);
            libav.av_buffer_unref(holder);
            deviceRef = null;
        }
        arena.close();
    }
}
