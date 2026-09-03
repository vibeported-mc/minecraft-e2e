package dev.vibeported.rpc

import kotlin.reflect.KClass

/**
 * What a node has to offer the procedures that run on it.
 *
 * This is the answer to "how does a procedure reach the thing this process is wrapped around". A
 * node injected into a game client provides that client once, here, and every procedure routed to
 * that node receives it as its receiver. Nothing is shared between nodes -- the name is about
 * sharing across *calls*, not across the cluster, and there is deliberately no way to reach another
 * node's services except by calling a procedure on it.
 *
 * Instances are made once and kept. A factory is called on first use rather than at registration, so
 * a node can be assembled before the thing it wraps exists.
 */
public class Services {

    private val lock = Any()
    private val factories = LinkedHashMap<KClass<*>, () -> Any>()
    private val instances = LinkedHashMap<KClass<*>, Any>()

    /** Registers how to make [type], the first time somebody asks for it. */
    public fun <T : Any> provide(type: KClass<T>, factory: () -> T) {
        synchronized(lock) {
            factories[type] = factory
            instances.remove(type)
        }
    }

    /** Registers something that already exists. */
    public fun <T : Any> provide(type: KClass<T>, instance: T) {
        synchronized(lock) {
            factories.remove(type)
            instances[type] = instance
        }
    }

    /**
     * The instance of [type] this node offers.
     *
     * Throws rather than returning null, and says what it does have: a procedure asking for a
     * receiver the node cannot supply is a wiring mistake, and the list of what is registered is
     * almost always enough to see which one.
     */
    public fun <T : Any> resolve(type: KClass<T>): T =
        resolveOrNull(type) ?: error(
            "This node offers no `${type.java.name}`. It offers: " +
                (registered().map { it.java.simpleName }.sorted().takeIf { it.isNotEmpty() }
                    ?: listOf("nothing at all"))
        )

    @Suppress("UNCHECKED_CAST")
    public fun <T : Any> resolveOrNull(type: KClass<T>): T? = synchronized(lock) {
        instances[type]?.let { return@synchronized it as T }
        val factory = factories[type] ?: return@synchronized null
        // Built inside the lock so two callers cannot each make one; a receiver is meant to be the
        // same object for every procedure that asks.
        val made = factory()
        instances[type] = made
        made as T
    }

    /** Everything this node can supply, whether or not it has been made yet. */
    public fun registered(): Set<KClass<*>> = synchronized(lock) {
        LinkedHashSet<KClass<*>>(factories.keys) + instances.keys
    }

    public inline fun <reified T : Any> provide(noinline factory: () -> T): Unit = provide(T::class, factory)

    public inline fun <reified T : Any> provide(instance: T): Unit = provide(T::class, instance)

    public inline fun <reified T : Any> resolve(): T = resolve(T::class)
}
