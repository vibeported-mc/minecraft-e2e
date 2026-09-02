package dev.vibeported.capture.libav;

import dev.vibeported.capture.libav.gen.AVPacket;
import dev.vibeported.capture.libav.gen.libav;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/**
 * One {@code AVPacket} of encoded output.
 *
 * <p>An encoder reuses a single packet across its whole run and hands the same
 * instance to the sink each time, so a sink must finish with it before
 * returning -- do not stash it.
 */
public final class Packet implements AutoCloseable {

    private final Arena arena = Arena.ofConfined();
    private MemorySegment packet;

    Packet() {
        this.packet = LibavException.checkNotNull("av_packet_alloc", libav.av_packet_alloc())
                .reinterpret(AVPacket.sizeof());
    }

    MemorySegment raw() {
        return packet;
    }

    public long pts() {
        return AVPacket.pts(packet);
    }

    public long dts() {
        return AVPacket.dts(packet);
    }

    public long duration() {
        return AVPacket.duration(packet);
    }

    public int size() {
        return AVPacket.size(packet);
    }

    void pts(long v) {
        AVPacket.pts(packet, v);
    }

    void dts(long v) {
        AVPacket.dts(packet, v);
    }

    void duration(long v) {
        AVPacket.duration(packet, v);
    }

    void streamIndex(int v) {
        AVPacket.stream_index(packet, v);
    }

    void unref() {
        libav.av_packet_unref(packet);
    }

    @Override
    public void close() {
        if (packet != null) {
            MemorySegment ref = arena.allocate(libav.C_POINTER);
            ref.set(libav.C_POINTER, 0, packet);
            libav.av_packet_free(ref);
            packet = null;
        }
        arena.close();
    }
}
