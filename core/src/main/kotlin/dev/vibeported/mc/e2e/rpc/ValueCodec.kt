package dev.vibeported.mc.e2e.rpc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.serializer
import kotlin.reflect.KClass
import kotlin.reflect.full.starProjectedType

/**
 * Turns `shared` values into something that can travel between nodes.
 *
 * Deliberately an interface: today every shared value is a plain `@Serializable` class, but once the
 * nodes are real Minecraft processes the interesting values will be `BlockPos`, `ItemStack` and
 * friends, which want their vanilla codecs instead.
 */
public interface ValueCodec {
    public fun encode(type: KClass<*>, value: Any?): JsonElement
    public fun decode(type: KClass<*>, element: JsonElement): Any?
}

/** Resolves a `kotlinx.serialization` serializer from the runtime class. */
public class JsonValueCodec(private val json: Json = DefaultJson) : ValueCodec {

    override fun encode(type: KClass<*>, value: Any?): JsonElement {
        if (value == null) return JsonNull
        return json.encodeToJsonElement(json.serializersModule.serializer(type.starProjectedType), value)
    }

    override fun decode(type: KClass<*>, element: JsonElement): Any? {
        if (element is JsonNull) return null
        return json.decodeFromJsonElement(json.serializersModule.serializer(type.starProjectedType), element)
    }

    public companion object {
        public val DefaultJson: Json = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
            // Payloads legitimately carry a property called `type`; keeping the polymorphic
            // discriminator out of that namespace stops the two colliding.
            classDiscriminator = "@kind"
        }
    }
}

/** Resolves the [KClass] a [SharedGet]/[SharedSet] names, using the caller's class loader. */
public fun resolveType(name: String, loader: ClassLoader): KClass<*> =
    when (name) {
        "int" -> Int::class
        "long" -> Long::class
        "boolean" -> Boolean::class
        "double" -> Double::class
        else -> Class.forName(name, false, loader).kotlin
    }
