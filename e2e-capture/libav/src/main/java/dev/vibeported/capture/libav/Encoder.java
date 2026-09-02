package dev.vibeported.capture.libav;

import dev.vibeported.capture.libav.gen.AVCodecContext;
import dev.vibeported.capture.libav.gen.AVRational;
import dev.vibeported.capture.libav.gen.libav;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.function.Consumer;

/**
 * Shared machinery for the encoders: the {@code AVCodecContext}, the
 * send-then-drain loop, and the codec-private options.
 */
public abstract class Encoder implements AutoCloseable {

    protected final Arena arena = Arena.ofConfined();
    protected final MemorySegment codec;
    protected MemorySegment ctx;

    private final Packet packet = new Packet();
    private boolean opened;

    protected Encoder(String codecName) {
        Libav.init();
        this.codec = LibavException.checkNotNull("avcodec_find_encoder_by_name(" + codecName + ")",
                libav.avcodec_find_encoder_by_name(Libav.str(arena, codecName)));
        this.ctx = LibavException.checkNotNull("avcodec_alloc_context3",
                libav.avcodec_alloc_context3(codec)).reinterpret(AVCodecContext.sizeof());
    }

    /**
     * Sets a codec-private option, e.g. NVENC's {@code preset} or {@code cq}.
     * Searches children so the option lands on the codec's private context.
     */
    public Encoder option(String name, String value) {
        LibavException.check("av_opt_set(" + name + ")",
                libav.av_opt_set(ctx, Libav.str(arena, name), Libav.str(arena, value),
                        libav.AV_OPT_SEARCH_CHILDREN()));
        return this;
    }

    protected void openCodec() {
        LibavException.check("avcodec_open2",
                libav.avcodec_open2(ctx, codec, MemorySegment.NULL));
        opened = true;
    }

    /**
     * Asks the encoder to keep its codec configuration out of the stream, because the container
     * carries it instead.
     *
     * Has to happen before the codec is opened. Setting the flag afterwards is silently ignored by
     * libavcodec, and the result is an MP4 with no SPS/PPS in it -- a file that looks fine until
     * something tries to decode it -- so this refuses rather than allowing that.
     */
    void enableGlobalHeader() {
        if (opened) {
            throw new LibavException(
                    "AV_CODEC_FLAG_GLOBAL_HEADER has to be set before the codec is opened; "
                            + "afterwards it does nothing and the container gets no extradata");
        }
        AVCodecContext.flags(ctx, AVCodecContext.flags(ctx) | libav.AV_CODEC_FLAG_GLOBAL_HEADER());
    }

    boolean hasGlobalHeader() {
        return (AVCodecContext.flags(ctx) & libav.AV_CODEC_FLAG_GLOBAL_HEADER()) != 0;
    }

    /** The context, for a muxer that needs to copy parameters off it. */
    MemorySegment raw() {
        return ctx;
    }

    public int timeBaseNum() {
        return AVRational.num(AVCodecContext.time_base(ctx));
    }

    public int timeBaseDen() {
        return AVRational.den(AVCodecContext.time_base(ctx));
    }

    protected void timeBase(int num, int den) {
        MemorySegment tb = AVCodecContext.time_base(ctx);
        AVRational.num(tb, num);
        AVRational.den(tb, den);
    }

    /** Encodes one frame, handing every packet it produces to {@code sink}. */
    public void encode(Frame frame, Consumer<Packet> sink) {
        send(frame.raw());
        receive(sink);
    }

    /**
     * Flushes the encoder. NVENC buffers several frames, so without this the
     * tail of the recording is simply missing.
     */
    public void drain(Consumer<Packet> sink) {
        send(MemorySegment.NULL);
        receive(sink);
    }

    private void send(MemorySegment frame) {
        LibavException.check("avcodec_send_frame", libav.avcodec_send_frame(ctx, frame));
    }

    private void receive(Consumer<Packet> sink) {
        while (true) {
            int rc = libav.avcodec_receive_packet(ctx, packet.raw());
            // Not an error: the encoder wants more input, or it is done.
            if (rc == libav.E2E_AVERROR_EAGAIN() || rc == libav.AVERROR_EOF()) return;
            LibavException.check("avcodec_receive_packet", rc);
            try {
                sink.accept(packet);
            } finally {
                packet.unref();
            }
        }
    }

    @Override
    public void close() {
        packet.close();
        if (ctx != null) {
            MemorySegment holder = arena.allocate(libav.C_POINTER);
            holder.set(libav.C_POINTER, 0, ctx);
            libav.avcodec_free_context(holder);
            ctx = null;
        }
        arena.close();
    }
}
