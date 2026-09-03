package dev.vibeported.rpc.transport

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * Where one envelope ends and the next begins.
 *
 * A stream has no idea, so every frame is a four-byte big-endian length followed by that many bytes.
 * Unglamorous, and the alternative -- a delimiter -- would mean escaping the payload, which is
 * exactly what choosing a binary format was meant to avoid.
 */
internal object Framing {

    /** Bigger than any envelope should be, and small enough that a bad length cannot exhaust memory. */
    private const val LIMIT = 64 * 1024 * 1024

    fun write(out: OutputStream, payload: ByteArray) {
        val size = payload.size
        out.write((size ushr 24) and 0xFF)
        out.write((size ushr 16) and 0xFF)
        out.write((size ushr 8) and 0xFF)
        out.write(size and 0xFF)
        out.write(payload)
        out.flush()
    }

    /** The next frame, or null at a clean end of stream -- which is what a departed node looks like. */
    fun read(input: InputStream): ByteArray? {
        val header = ByteArray(4)
        if (!readFully(input, header)) return null

        val size = ((header[0].toInt() and 0xFF) shl 24) or
            ((header[1].toInt() and 0xFF) shl 16) or
            ((header[2].toInt() and 0xFF) shl 8) or
            (header[3].toInt() and 0xFF)

        require(size in 0..LIMIT) { "Frame claims to be $size bytes, which is not credible" }

        val payload = ByteArray(size)
        if (!readFully(input, payload)) throw EOFException("Stream ended $size bytes into a frame")
        return payload
    }

    /** False only at a clean end before anything was read; a partial frame is a broken one. */
    private fun readFully(input: InputStream, into: ByteArray): Boolean {
        var offset = 0
        while (offset < into.size) {
            val read = input.read(into, offset, into.size - offset)
            if (read < 0) return offset == 0
            offset += read
        }
        return true
    }
}
