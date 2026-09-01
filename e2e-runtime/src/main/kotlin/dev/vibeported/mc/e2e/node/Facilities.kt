package dev.vibeported.mc.e2e.node

import kotlin.reflect.KClass

/**
 * What a particular node can offer a block running on it.
 *
 * The seam that keeps the runtime free of any world model: today the samples put a mock world in
 * here, and a real deployment would put a `MinecraftServer` or `Minecraft` instance in instead.
 */
public class Facilities(entries: Map<KClass<*>, Any>) {

    private val entries: Map<KClass<*>, Any> = entries.toMap()

    public fun <T : Any> get(type: KClass<T>): T {
        entries[type]?.let { return type.java.cast(it) }
        // Fall back to an assignable entry so a facility registered under its concrete type still
        // answers a request for the interface it implements.
        val assignable = entries.values.firstOrNull { type.java.isInstance(it) }
            ?: error(
                "No facility of type ${type.qualifiedName} on this node. " +
                    "Available: ${entries.keys.mapNotNull { it.qualifiedName }.sorted()}"
            )
        return type.java.cast(assignable)
    }

    public companion object {
        public val EMPTY: Facilities = Facilities(emptyMap())

        public fun of(vararg entries: Pair<KClass<*>, Any>): Facilities = Facilities(entries.toMap())
    }
}
