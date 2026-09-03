package dev.vibeported.rpc

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.SerializersModuleBuilder
import kotlin.reflect.KClass

/**
 * Marks a hand-written serializer for a type that cannot carry its own.
 *
 * Some values have to cross a wire without ever being `@Serializable`: a type from a library nobody
 * here owns cannot be annotated, given a companion, or changed at all. The answer is to write a
 * `KSerializer` for it and put this on the object:
 *
 * ```
 * @RpcSerializer(BlockPos::class)
 * public object BlockPosSerializer : MojangCodecSerializer<BlockPos>(BlockPos.CODEC)
 * ```
 *
 * One declaration does everything. The compiler finds it and stops refusing that type; it records
 * the pair in a manifest beside the procedures; and every node on that classpath assembles it into
 * the module its wire format consults. Nothing to add to a build script, nothing to register at
 * startup, and no way to have the compiler believe a serializer exists that the runtime then lacks.
 *
 * The type is named rather than read off the object's `KSerializer<T>` supertype. Reading it would
 * mean walking a substituted supertype closure in both halves of the compiler -- `BlockPosSerializer`
 * extends `MojangCodecSerializer<BlockPos>`, which is what implements `KSerializer` -- and getting
 * that subtly wrong is a serializer silently registered against the wrong type. One token is cheaper
 * than that class of bug.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
public annotation class RpcSerializer(public val forType: KClass<*>)

/**
 * What the compiler plugin recorded about the serializers in one module.
 *
 * Names only, like the procedure manifest beside it -- but for the opposite reason. That one avoids
 * loading classes a node may not have; this one is loaded eagerly, because a serializer a node
 * cannot resolve is a node that will fail at the first call that sends one, and finding that out
 * while starting beats finding it out mid-call.
 */
@Serializable
public data class SerializerManifest(
    public val entries: List<Entry> = emptyList(),
) {
    @Serializable
    public data class Entry(
        /** The type this serializes, as the compiler saw it. */
        public val type: String,
        /** The `object` implementing `KSerializer` for it. */
        public val serializer: String,
        /** The module that compiled it, so a duplicate can name both culprits. */
        public val module: String = "",
    )

    public companion object {
        public const val RESOURCE: String = "META-INF/rpc/serializers.json"

        private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

        public fun parse(text: String): SerializerManifest = json.decodeFromString(serializer(), text)

        public fun render(manifest: SerializerManifest): String =
            json.encodeToString(serializer(), manifest)

        /** Every manifest on the classpath, merged. @see ProcedureManifest.load */
        public fun load(loader: ClassLoader): SerializerManifest {
            val merged = loader.getResources(RESOURCE).toList().flatMap { url ->
                parse(url.readText()).entries
            }

            // Two serializers for one type is a build that cannot be run, not a race for the
            // classpath to settle: which one wins would decide what goes on the wire.
            merged.groupBy { it.type }.forEach { (type, claims) ->
                if (claims.distinctBy { it.serializer }.size > 1) {
                    val where = claims.joinToString(", ") { "${it.serializer} in ${it.module.ifEmpty { "?" }}" }
                    error("More than one serializer is registered for `$type`: $where")
                }
            }
            return SerializerManifest(merged.distinctBy { it.type })
        }
    }
}

/**
 * The serializers a classpath offers, assembled into one module.
 *
 * Built the way a table registry is built -- read the manifest, resolve each name, take the
 * `INSTANCE` the compiler emitted for an `object`. A node does this once as it starts, so a
 * serializer naming a class this process lacks fails here rather than in the middle of a call.
 */
public object SerializerRegistry {

    /** The module every node hands to its wire format. Empty when nothing declared one. */
    public fun load(loader: ClassLoader = SerializerRegistry::class.java.classLoader): SerializersModule {
        val manifest = SerializerManifest.load(loader)
        if (manifest.entries.isEmpty()) return SerializersModule { }

        return SerializersModule {
            manifest.entries.forEach { entry -> register(entry, loader) }
        }
    }

    private fun SerializersModuleBuilder.register(entry: SerializerManifest.Entry, loader: ClassLoader) {
        val type = resolve(entry.type, loader, entry)
        val serializer = instantiate(entry, loader)

        @Suppress("UNCHECKED_CAST")
        contextual(type as KClass<Any>, serializer as KSerializer<Any>)
    }

    private fun resolve(name: String, loader: ClassLoader, entry: SerializerManifest.Entry): KClass<*> =
        try {
            Class.forName(name, false, loader).kotlin
        } catch (missing: ClassNotFoundException) {
            error(
                "`${entry.serializer}` serializes `$name`, which is not on this classpath. It was " +
                    "compiled into ${entry.module.ifEmpty { "some module" }}; a node that cannot " +
                    "resolve the type cannot be sent one either."
            )
        }

    private fun instantiate(entry: SerializerManifest.Entry, loader: ClassLoader): KSerializer<*> =
        try {
            val type = Class.forName(entry.serializer, true, loader)
            type.getField("INSTANCE").get(null) as KSerializer<*>
        } catch (failure: ReflectiveOperationException) {
            error(
                "`${entry.serializer}` is registered as a serializer for `${entry.type}` but could " +
                    "not be read. It has to be an `object` implementing KSerializer: ${failure.message}"
            )
        }
}
