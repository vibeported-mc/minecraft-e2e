package dev.vibeported.capture.libav;

import dev.vibeported.capture.libav.gen.AVCodecContext;
import dev.vibeported.capture.libav.gen.AVFrame;
import dev.vibeported.capture.libav.gen.libav;

/**
 * An audio encoder, and the silent-track generator that goes with it.
 *
 * <p>A video-only MP4 is legal but awkward: some players and most browser
 * pipelines behave better with an audio track present, so a capture with no
 * sound still carries a silent one.
 */
public final class AudioEncoder extends Encoder {

    private final int channels;

    private AudioEncoder(String codecName, int channels) {
        super(codecName);
        this.channels = channels;
    }

    /** AAC -- the native encoder, which every browser can play. */
    public static Builder aac() {
        return new Builder("aac");
    }

    public static final class Builder {
        private final String codecName;
        private int sampleRate = 48_000;
        private int channels = 2;
        private long bitRate = 128_000;
        private boolean globalHeader;

        private Builder(String codecName) {
            this.codecName = codecName;
        }

        public Builder sampleRate(int rate) {
            this.sampleRate = rate;
            return this;
        }

        public Builder channels(int channels) {
            this.channels = channels;
            return this;
        }

        public Builder bitRate(long bitsPerSecond) {
            this.bitRate = bitsPerSecond;
            return this;
        }

        /** @see VideoEncoder.Builder#globalHeader(boolean) */
        public Builder globalHeader(boolean required) {
            this.globalHeader = required;
            return this;
        }

        public AudioEncoder open() {
            AudioEncoder enc = new AudioEncoder(codecName, channels);
            var ctx = enc.ctx;

            AVCodecContext.sample_fmt(ctx, libav.AV_SAMPLE_FMT_FLTP());
            AVCodecContext.sample_rate(ctx, sampleRate);
            AVCodecContext.bit_rate(ctx, bitRate);
            libav.av_channel_layout_default(AVCodecContext.ch_layout(ctx), channels);
            enc.timeBase(1, sampleRate);

            if (globalHeader) enc.enableGlobalHeader();
            enc.openCodec();
            return enc;
        }
    }

    /** Samples per frame, as the codec chose it -- 1024 for AAC. */
    public int frameSize() {
        return AVCodecContext.frame_size(ctx);
    }

    public int sampleRate() {
        return AVCodecContext.sample_rate(ctx);
    }

    /**
     * A frame of silence at {@code pts}, ready to encode.
     *
     * <p>{@code av_frame_get_buffer} hands back uninitialised memory, so the
     * planes are zeroed explicitly. Zero is silence for planar float.
     */
    public Frame silence(long pts) {
        Frame frame = Frame.alloc();
        try {
            var raw = frame.raw();
            int samples = frameSize();
            AVFrame.format(raw, libav.AV_SAMPLE_FMT_FLTP());
            AVFrame.nb_samples(raw, samples);
            AVFrame.sample_rate(raw, sampleRate());
            libav.av_channel_layout_default(AVFrame.ch_layout(raw), channels);

            LibavException.check("av_frame_get_buffer", libav.av_frame_get_buffer(raw, 0));
            for (int plane = 0; plane < channels; plane++) {
                frame.zeroPlane(plane, (long) samples * Float.BYTES);
            }
            return frame.pts(pts);
        } catch (RuntimeException e) {
            frame.close();
            throw e;
        }
    }
}
