@file:OptIn(ExperimentalSerializationApi::class)

package dev.vibeported.rpc

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.json.Json

/**
 * How a value becomes bytes.
 *
 * Bytes rather than a format-specific tree, so a deployment can swap JSON for something compact
 * without every layer above learning a new element type. The serializers are handed in rather than
 * looked up: the compiler plugin resolved them at the call site, where the static types were still
 * visible, which is what turns an unserializable argument into a compile error instead of a puzzle
 * at run time.
 */
public interface WireFormat {
    public fun <T> encode(serializer: SerializationStrategy<T>, value: T): ByteArray
    public fun <T> decode(deserializer: DeserializationStrategy<T>, bytes: ByteArray): T
}

/**
 * The default: CBOR.
 *
 * Binary, and native to `ByteArray`, which is what keeps bytes as bytes the whole way down instead
 * of being Base64'd to survive a text format. Readability on the wire is not worth paying for --
 * what is worth reading is logged by name, long before anything is encoded.
 */
public class CborWireFormat(private val cbor: Cbor = Cbor) : WireFormat {

    override fun <T> encode(serializer: SerializationStrategy<T>, value: T): ByteArray =
        cbor.encodeToByteArray(serializer, value)

    override fun <T> decode(deserializer: DeserializationStrategy<T>, bytes: ByteArray): T =
        cbor.decodeFromByteArray(deserializer, bytes)

    override fun toString(): String = "cbor"
}

/**
 * JSON, for when a human has to read the payload after all.
 *
 * Kept because it is occasionally exactly what is wanted while debugging, not because anything
 * defaults to it.
 */
public class JsonWireFormat(private val json: Json = Json) : WireFormat {

    override fun <T> encode(serializer: SerializationStrategy<T>, value: T): ByteArray =
        json.encodeToString(serializer, value).encodeToByteArray()

    override fun <T> decode(deserializer: DeserializationStrategy<T>, bytes: ByteArray): T =
        json.decodeFromString(deserializer, bytes.decodeToString())

    override fun toString(): String = "json"
}
