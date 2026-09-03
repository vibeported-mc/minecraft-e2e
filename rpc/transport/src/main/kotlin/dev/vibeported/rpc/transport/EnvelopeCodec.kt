@file:OptIn(ExperimentalSerializationApi::class)

package dev.vibeported.rpc.transport

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor

/**
 * Envelopes, as bytes.
 *
 * Needed only by transports that actually have a wire -- the in-memory fabric hands the objects
 * straight over and encodes nothing at all, which is part of why it is worth testing against.
 *
 * CBOR rather than JSON because an envelope's payload is a byte array, and a text format has to
 * either Base64 it or write it out as a list of numbers. One inflates by a third, the other by
 * rather more, and neither buys anything a procedure name in a log does not.
 */
public object EnvelopeCodec {

    public fun encode(envelope: Envelope): ByteArray =
        Cbor.encodeToByteArray(Envelope.serializer(), envelope)

    public fun decode(bytes: ByteArray): Envelope =
        Cbor.decodeFromByteArray(Envelope.serializer(), bytes)
}
