package dev.vibeported.capture.libav;

import dev.vibeported.capture.libav.gen.AVFrame;
import dev.vibeported.capture.libav.gen.libav;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/**
 * One {@code AVFrame}. Either a hardware frame handed out by {@link HwFramePool}
 * (whose planes are CUDA device pointers) or a plain audio frame.
 */
public final class Frame implements AutoCloseable {

    private MemorySegment frame;

    private Frame(MemorySegment frame) {
        this.frame = frame;
    }

    static Frame alloc() {
        return new Frame(LibavException.checkNotNull("av_frame_alloc",
                libav.av_frame_alloc()).reinterpret(AVFrame.sizeof()));
    }

    MemorySegment raw() {
        return frame;
    }

    /**
     * Plane {@code i}. For a CUDA frame this is a device pointer, not memory
     * this process may read -- pass it to CUDA, never dereference it.
     */
    public MemorySegment plane(int i) {
        return AVFrame.data(frame, i);
    }

    /** Bytes between the starts of two rows of plane {@code i}. */
    public int linesize(int i) {
        return AVFrame.linesize(frame, i);
    }

    public int width() {
        return AVFrame.width(frame);
    }

    public int height() {
        return AVFrame.height(frame);
    }

    public int format() {
        return AVFrame.format(frame);
    }

    public long pts() {
        return AVFrame.pts(frame);
    }

    public Frame pts(long pts) {
        AVFrame.pts(frame, pts);
        return this;
    }

    /** Overwrites a plane with zeroes -- silence, for audio. */
    public void zeroPlane(int i, long bytes) {
        AVFrame.data(frame, i).reinterpret(bytes).fill((byte) 0);
    }

    /**
     * Returns the frame, and with it the buffer it holds.
     *
     * The scratch arena is created here rather than held as a field on purpose: a capture pipeline
     * takes frames on the render thread and hands them to an encoder thread, and a confined arena
     * belonging to the wrong thread would refuse to free them. Everything else this class touches
     * is native memory with no thread of its own.
     */
    @Override
    public void close() {
        if (frame == null) return;
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment ref = scratch.allocate(libav.C_POINTER);
            ref.set(libav.C_POINTER, 0, frame);
            libav.av_frame_free(ref);
        } finally {
            frame = null;
        }
    }
}
