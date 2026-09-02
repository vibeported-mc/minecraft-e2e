package dev.vibeported.capture.libav;

import dev.vibeported.capture.libav.gen.AVCodecContext;
import dev.vibeported.capture.libav.gen.AVRational;
import dev.vibeported.capture.libav.gen.libav;

/**
 * A hardware video encoder fed hardware frames.
 *
 * <p>The frames it takes are CUDA frames from a {@link HwFramePool}. Nothing is
 * ever read back to the CPU: NVENC reads the same device memory the GL texture
 * was copied into, and converts RGB to YUV itself.
 */
public final class VideoEncoder extends Encoder {

    private VideoEncoder(String codecName) {
        super(codecName);
    }

    /** H.264 on NVENC. The default for capture. */
    public static Builder h264Nvenc() {
        return new Builder("h264_nvenc");
    }

    /** HEVC on NVENC -- better compression, narrower browser support. */
    public static Builder hevcNvenc() {
        return new Builder("hevc_nvenc");
    }

    /**
     * AV1 on NVENC -- the smallest of the three, and the fussiest about hardware.
     *
     * Needs Ada (RTX 40) or newer to encode at all; on anything older opening it fails rather than
     * falling back, which is the honest outcome.
     */
    public static Builder av1Nvenc() {
        return new Builder("av1_nvenc");
    }

    public static final class Builder {
        private final String codecName;
        private HwFramePool frames;
        private int fps = 60;
        private int gop = 60;
        private boolean globalHeader;
        private final java.util.LinkedHashMap<String, String> options = new java.util.LinkedHashMap<>();

        private Builder(String codecName) {
            this.codecName = codecName;
        }

        /** Where frames come from. Also fixes the encoder's size and format. */
        public Builder frames(HwFramePool frames) {
            this.frames = frames;
            return this;
        }

        public Builder fps(int fps) {
            this.fps = fps;
            return this;
        }

        /** Keyframe interval in frames. Shorter seeks better, compresses worse. */
        public Builder gopSize(int gop) {
            this.gop = gop;
            return this;
        }

        /**
         * Whether the container carries the codec configuration instead of the stream.
         *
         * Pass {@link Muxer#globalHeaderRequired()}. It has to be settled here rather than after
         * opening, because libavcodec reads the flag when the codec opens and ignores it later.
         */
        public Builder globalHeader(boolean required) {
            this.globalHeader = required;
            return this;
        }

        /** A codec-private option, e.g. {@code preset=p4} or {@code cq=23}. */
        public Builder option(String name, String value) {
            options.put(name, value);
            return this;
        }

        public VideoEncoder open() {
            if (frames == null) throw new IllegalStateException("frames(...) is required");

            VideoEncoder enc = new VideoEncoder(codecName);
            var ctx = enc.ctx;

            AVCodecContext.width(ctx, frames.width());
            AVCodecContext.height(ctx, frames.height());
            // The frames are CUDA frames; the pool says what is really in them.
            AVCodecContext.pix_fmt(ctx, PixelFormat.CUDA.value());
            AVCodecContext.sw_pix_fmt(ctx, frames.softwareFormat().value());
            AVCodecContext.gop_size(ctx, gop);
            // No B-frames: they reorder output and buy little on screen content.
            AVCodecContext.max_b_frames(ctx, 0);

            enc.timeBase(1, fps);
            var fr = AVCodecContext.framerate(ctx);
            AVRational.num(fr, fps);
            AVRational.den(fr, 1);

            // The encoder takes its own reference to the pool, so the pool
            // outliving or predeceasing the encoder is not a crash.
            AVCodecContext.hw_frames_ctx(ctx, LibavException.checkNotNull("av_buffer_ref",
                    libav.av_buffer_ref(frames.raw())));

            options.forEach(enc::option);
            if (globalHeader) enc.enableGlobalHeader();
            enc.openCodec();
            return enc;
        }
    }
}
