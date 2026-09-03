package dev.vibeported.rpc.transport

import dev.vibeported.rpc.NodeId
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EnvelopeCodecTest {

    @Test
    fun `arguments survive as bytes`() {
        // The reason for choosing CBOR: this is a byte array on both sides, not a Base64 string and
        // not a list of numbers.
        val payload = byteArrayOf(0, 1, 2, -1, 127, -128)
        val sent = Request(7, NodeId("a"), NodeId("b"), "echo", listOf(payload))

        val back = EnvelopeCodec.decode(EnvelopeCodec.encode(sent)) as Request

        assertEquals(sent.callId, back.callId)
        assertEquals(sent.procedure, back.procedure)
        assertArrayEquals(payload, back.args.single())
    }

    @Test
    fun `a failure round trips`() {
        val sent = Response(
            callId = 1,
            from = NodeId("b"),
            to = NodeId("a"),
            failure = RemoteFailure("java.lang.IllegalStateException", "no", "stack here"),
        )

        val back = EnvelopeCodec.decode(EnvelopeCodec.encode(sent)) as Response

        assertEquals(sent.failure, back.failure)
        assertEquals(null, back.result)
    }
}
