package dev.vibeported.capture.libav;

import dev.vibeported.capture.libav.gen.libav;

/**
 * The handful of pixel formats this capture path deals in. Values come from the
 * generated bindings, never from a hand-copied number.
 */
public enum PixelFormat {

    /**
     * A frame living in CUDA device memory. What an NVENC encoder is fed; the
     * real layout is the pool's {@link HwFramePool#softwareFormat()}.
     */
    CUDA(libav.AV_PIX_FMT_CUDA()),

    /**
     * 32-bit packed RGB, bytes R,G,B,X.
     *
     * <p>This is the one that matters: it is byte-for-byte what an OpenGL
     * {@code GL_RGBA8} texture holds, which is what Minecraft's main render
     * target is ({@code GpuFormat.RGBA8_UNORM}). NVENC accepts it directly and
     * does the RGB-to-YUV conversion in hardware, so a capture needs no
     * conversion pass and no CPU involvement at all.
     *
     * <p>On a little-endian machine libav spells this {@code AV_PIX_FMT_0BGR32},
     * which resolves to {@code AV_PIX_FMT_RGB0}. The naming is confusing; the
     * memory order is not.
     */
    RGB0(libav.AV_PIX_FMT_0BGR32()),

    /** Planar 4:2:0, NVENC's other native input. Here for completeness. */
    NV12(libav.AV_PIX_FMT_NV12());

    private final int value;

    PixelFormat(int value) {
        this.value = value;
    }

    public int value() {
        return value;
    }

    /** What libav calls it -- worth logging, to prove what was negotiated. */
    public String libavName() {
        return Libav.pixelFormatName(value);
    }
}
