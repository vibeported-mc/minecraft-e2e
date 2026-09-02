package dev.vibeported.capture.libav;

import dev.vibeported.capture.libav.gen.AVBufferRef;
import dev.vibeported.capture.libav.gen.AVHWFramesContext;
import dev.vibeported.capture.libav.gen.libav;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/**
 * A pool of hardware frames -- an {@code AVHWFramesContext} over a CUDA device.
 *
 * <p>Frames come out of a fixed pool rather than being allocated per frame,
 * because device allocation is expensive and the encoder holds onto frames for
 * a few ticks after you hand them over.
 */
public final class HwFramePool implements AutoCloseable {

    private final Arena arena = Arena.ofConfined();
    private final PixelFormat softwareFormat;
    private final int width;
    private final int height;
    private MemorySegment framesRef;

    /**
     * Internal seam: builds a pool on a libav hardware device.
     * Prefer {@code CudaDevice.framePool(...)}.
     */
    public static HwFramePool on(MemorySegment deviceRef, int width, int height,
                                PixelFormat softwareFormat, int size) {
        return new HwFramePool(deviceRef, width, height, softwareFormat, size);
    }

    private HwFramePool(MemorySegment deviceRef, int width, int height, PixelFormat softwareFormat, int size) {
        this.width = width;
        this.height = height;
        this.softwareFormat = softwareFormat;

        MemorySegment ref = LibavException.checkNotNull("av_hwframe_ctx_alloc",
                libav.av_hwframe_ctx_alloc(deviceRef)).reinterpret(AVBufferRef.sizeof());

        MemorySegment ctx = Libav.at(AVBufferRef.data(ref), AVHWFramesContext.sizeof());
        AVHWFramesContext.format(ctx, PixelFormat.CUDA.value());
        AVHWFramesContext.sw_format(ctx, softwareFormat.value());
        AVHWFramesContext.width(ctx, width);
        AVHWFramesContext.height(ctx, height);
        AVHWFramesContext.initial_pool_size(ctx, size);

        int rc = libav.av_hwframe_ctx_init(ref);
        if (rc < 0) {
            unref(ref);
            throw new LibavException("av_hwframe_ctx_init", rc);
        }
        this.framesRef = ref;
    }

    /** The {@code AVBufferRef*} an encoder needs; still owned by this pool. */
    MemorySegment raw() {
        return framesRef;
    }

    public PixelFormat softwareFormat() {
        return softwareFormat;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /**
     * Takes a frame from the pool. Closing it returns the buffer to the pool,
     * so close every frame or the pool runs dry.
     */
    public Frame acquire() {
        Frame frame = Frame.alloc();
        try {
            LibavException.check("av_hwframe_get_buffer",
                    libav.av_hwframe_get_buffer(framesRef, frame.raw(), 0));
            return frame;
        } catch (RuntimeException e) {
            frame.close();
            throw e;
        }
    }

    @Override
    public void close() {
        if (framesRef != null) {
            unref(framesRef);
            framesRef = null;
        }
        arena.close();
    }

    private void unref(MemorySegment ref) {
        MemorySegment holder = arena.allocate(libav.C_POINTER);
        holder.set(libav.C_POINTER, 0, ref);
        libav.av_buffer_unref(holder);
    }
}
