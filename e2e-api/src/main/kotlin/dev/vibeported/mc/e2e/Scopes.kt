package dev.vibeported.mc.e2e

import kotlin.reflect.KClass

/** Restricts implicit receivers so an inner block cannot silently call an outer scope's members. */
@DslMarker
public annotation class E2eDsl

/**
 * Common receiver of everything the compiler plugin lifts: the driver body of an `e2e` test and
 * every `server`/`client` block, however deeply nested.
 *
 * After the plugin has run, the single argument of every lifted function is a [BlockScope], which
 * the local node supplies. Nothing else is in scope, by construction -- that is the whole point.
 */
@E2eDsl
public interface E2eBlockScope {
    /** Where this block is currently executing. */
    public val self: NodeId

    /** Identifies the one test run this block belongs to; used to correlate logs and shared state. */
    public val runId: String

    /** Appends a line to this node's captured log, which the report interleaves by time. */
    public fun log(message: String)

    /** Sends a lifted block to [target] and suspends until that node has finished running it. */
    public suspend fun dispatch(block: BlockId, target: NodeId)

    /** Reads a `shared` value from the orchestrator's authoritative store. */
    public suspend fun sharedGet(id: SharedId, type: KClass<*>): Any?

    /** Writes a `shared` value into the orchestrator's authoritative store. */
    public suspend fun sharedSet(id: SharedId, type: KClass<*>, value: Any?)
}

/**
 * Receiver of an `e2e` test's driver body, which runs on the orchestrator.
 *
 * `shared` may only be declared here, so every distributed value has exactly one declaring scope
 * and therefore exactly one stable id.
 */
public interface E2eScope : E2eBlockScope

/** Receiver of a `server` or `client` block, which runs on that node. */
public interface NodeScope : E2eBlockScope {
    /**
     * Looks up something this node offers -- the mock world today, a `MinecraftServer` or
     * `Minecraft` instance once the nodes are real processes.
     */
    public fun <T : Any> facility(type: KClass<T>): T
}

/** @see NodeScope.facility */
public inline fun <reified T : Any> NodeScope.facility(): T = facility(T::class)

/**
 * The concrete scope a node hands to a lifted block.
 *
 * It satisfies both [E2eScope] and [NodeScope] so that one generated `invoke` can serve driver and
 * node blocks alike; which members are legal in a given block is settled at compile time by the
 * receiver the source lambda declared, not here.
 */
public interface BlockScope : E2eScope, NodeScope
