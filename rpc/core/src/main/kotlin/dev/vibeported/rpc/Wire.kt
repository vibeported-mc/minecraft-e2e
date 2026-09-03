package dev.vibeported.rpc

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
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
 * The default: JSON, as UTF-8.
 *
 * Chosen for being readable in a log rather than for being small. A framework whose failures happen
 * across process boundaries is worth being able to read on the wire, and anything that outgrows it
 * can supply its own [WireFormat].
 */
public class JsonWireFormat(private val json: Json = Json) : WireFormat {

    override fun <T> encode(serializer: SerializationStrategy<T>, value: T): ByteArray =
        json.encodeToString(serializer, value).encodeToByteArray()

    override fun <T> decode(deserializer: DeserializationStrategy<T>, bytes: ByteArray): T =
        json.decodeFromString(deserializer, bytes.decodeToString())

    override fun toString(): String = "json"
}
