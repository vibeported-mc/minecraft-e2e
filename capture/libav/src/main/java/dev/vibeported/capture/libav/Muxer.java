package dev.vibeported.capture.libav;

import dev.vibeported.capture.libav.gen.AVCodecContext;
import dev.vibeported.capture.libav.gen.AVFormatContext;
import dev.vibeported.capture.libav.gen.AVOutputFormat;
import dev.vibeported.capture.libav.gen.AVRational;
import dev.vibeported.capture.libav.gen.AVStream;
import dev.vibeported.capture.libav.gen.libav;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Writes encoded packets into a container. MP4, unless the name says otherwise. */
public final class Muxer implements AutoCloseable {

    private final Arena arena = Arena.ofConfined();
    private final List<Stream> streams = new ArrayList<>();
    private final Path file;
    private MemorySegment ctx;
    private boolean headerWritten;
    private boolean trailerWritten;

    /**
     * How the MP4 is laid out. faststart rewrites the file at the end so the index sits in front,
     * which is what a browser wants -- but it means a file whose writer never got to finish is not
     * playable at all.
     */
    private String movflags = "faststart";

    private Muxer(Path file) {
        Libav.init();
        this.file = file;

        MemorySegment holder = arena.allocate(libav.C_POINTER);
        LibavException.check("avformat_alloc_output_context2",
                libav.avformat_alloc_output_context2(holder, MemorySegment.NULL,
                        MemorySegment.NULL, Libav.str(arena, file.toString())));
        this.ctx = holder.get(libav.C_POINTER, 0).reinterpret(AVFormatContext.sizeof());
    }

    public static Muxer create(Path file) {
        return new Muxer(file);
    }

    /**
     * Writes a fragmented MP4 instead, which stays playable if the writer is killed.
     *
     * Worth it whenever the process might not get to close the file cleanly -- a test client that a
     * harness terminates, say. Each fragment is self-describing, so whatever reached the disk plays,
     * where a faststart file with no trailer is just bytes.
     */
    public Muxer fragmented() {
        movflags = "frag_keyframe+empty_moov+default_base_moof";
        return this;
    }

    /**
     * Adds a stream carrying this encoder's output.
     *
     * <p>Must happen before {@link #open}. Containers that keep codec setup in
     * the header -- MP4 does -- need the encoder told so before it is opened,
     * which is why {@link #globalHeaderRequired()} exists.
     */
    public Stream add(Encoder encoder) {
        // Catches the mistake that is otherwise invisible until playback: an MP4 whose codec
        // configuration lives nowhere. A fragmented file writes its header before any packet, so
        // there is no later opportunity to recover the extradata from the stream.
        if (globalHeaderRequired() && !encoder.hasGlobalHeader()) {
            throw new LibavException(
                    "This container keeps codec configuration in the header, but the encoder was "
                            + "opened without AV_CODEC_FLAG_GLOBAL_HEADER. Build it with "
                            + ".globalHeader(muxer.globalHeaderRequired()) before opening it.");
        }
        MemorySegment st = LibavException.checkNotNull("avformat_new_stream",
                libav.avformat_new_stream(ctx, MemorySegment.NULL)).reinterpret(AVStream.sizeof());

        LibavException.check("avcodec_parameters_from_context",
                libav.avcodec_parameters_from_context(AVStream.codecpar(st), encoder.raw()));

        MemorySegment tb = AVStream.time_base(st);
        AVRational.num(tb, encoder.timeBaseNum());
        AVRational.den(tb, encoder.timeBaseDen());

        Stream stream = new Stream(st, encoder.timeBaseNum(), encoder.timeBaseDen());
        streams.add(stream);
        return stream;
    }

    /**
     * True when this container wants codec extradata in the file header rather
     * than in the stream. Encoders must set AV_CODEC_FLAG_GLOBAL_HEADER before
     * being opened, or the MP4 is written without a usable decoder config.
     */
    public boolean globalHeaderRequired() {
        MemorySegment oformat = Libav.at(AVFormatContext.oformat(ctx), AVOutputFormat.sizeof());
        return (AVOutputFormat.flags(oformat) & libav.AVFMT_GLOBALHEADER()) != 0;
    }

    /**
     * Applies that flag to an encoder that has not been opened yet.
     *
     * Prefer {@code globalHeader(muxer.globalHeaderRequired())} on the encoder builder. This throws
     * if the encoder is already open, because at that point the flag would do nothing.
     */
    public static void requireGlobalHeader(Encoder encoder) {
        encoder.enableGlobalHeader();
    }

    /** Opens the file and writes the header. */
    public Muxer open() {
        MemorySegment pb = arena.allocate(libav.C_POINTER);
        LibavException.check("avio_open",
                libav.avio_open(pb, Libav.str(arena, file.toString()), libav.AVIO_FLAG_WRITE()));
        AVFormatContext.pb(ctx, pb.get(libav.C_POINTER, 0));

        MemorySegment opts = arena.allocate(libav.C_POINTER);
        opts.set(libav.C_POINTER, 0, MemorySegment.NULL);
        libav.av_dict_set(opts, Libav.str(arena, "movflags"), Libav.str(arena, movflags), 0);

        int rc = libav.avformat_write_header(ctx, opts);
        libav.av_dict_free(opts);
        LibavException.check("avformat_write_header", rc);
        headerWritten = true;
        return this;
    }

    /** Writes one packet, restamped from the encoder's clock to the stream's. */
    public void write(Packet packet, Stream stream) {
        packet.streamIndex(stream.index());
        packet.pts(stream.toStreamTime(packet.pts()));
        packet.dts(stream.toStreamTime(packet.dts()));
        packet.duration(stream.toStreamTime(packet.duration()));
        LibavException.check("av_interleaved_write_frame",
                libav.av_interleaved_write_frame(ctx, packet.raw()));
    }

    @Override
    public void close() {
        if (ctx != null) {
            if (headerWritten && !trailerWritten) {
                trailerWritten = true;
                libav.av_write_trailer(ctx);
            }
            MemorySegment pb = AVFormatContext.pb(ctx);
            if (!pb.equals(MemorySegment.NULL)) {
                MemorySegment holder = arena.allocate(libav.C_POINTER);
                holder.set(libav.C_POINTER, 0, pb);
                libav.avio_closep(holder);
                AVFormatContext.pb(ctx, MemorySegment.NULL);
            }
            libav.avformat_free_context(ctx);
            ctx = null;
        }
        arena.close();
    }

    /** One output stream, and the clock conversion that goes with it. */
    public static final class Stream {
        private final MemorySegment stream;
        private final int srcNum;
        private final int srcDen;

        private Stream(MemorySegment stream, int srcNum, int srcDen) {
            this.stream = stream;
            this.srcNum = srcNum;
            this.srcDen = srcDen;
        }

        public int index() {
            return AVStream.index(stream);
        }

        /**
         * Converts a timestamp from the encoder's time base to the stream's.
         *
         * <p>Done here rather than through {@code av_packet_rescale_ts} because
         * that takes two AVRationals by value, and keeping structs out of the
         * calling convention keeps the FFI surface pointer-only.
         */
        long toStreamTime(long value) {
            if (value == Long.MIN_VALUE) return value; // AV_NOPTS_VALUE
            MemorySegment tb = AVStream.time_base(stream);
            long dstNum = AVRational.num(tb);
            long dstDen = AVRational.den(tb);
            // value * (srcNum/srcDen) / (dstNum/dstDen). The factors are tiny
            // (frame rates and container ticks), so exact math fails loudly
            // rather than silently wrapping.
            long numerator = Math.multiplyExact(Math.multiplyExact(value, srcNum), dstDen);
            long denominator = Math.multiplyExact((long) srcDen, dstNum);
            // Round half away from zero, matching AV_ROUND_NEAR_INF.
            long half = denominator / 2;
            return numerator >= 0
                    ? (numerator + half) / denominator
                    : -((-numerator + half) / denominator);
        }
    }
}
