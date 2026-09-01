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
 * and therefore exactly one stable id. The orchestrator is not a game process, so nothing from
 * either side of the game is reachable from a driver.
 */
public interface E2eScope : E2eBlockScope

/**
 * Receiver of a block that runs inside a game process.
 *
 * Deliberately carries no game accessors of its own: those hang off [ServerScope] and [ClientScope]
 * separately, so what a block can reach is decided by which kind of block it is.
 */
public interface NodeScope : E2eBlockScope {
    /**
     * Looks up something this node offers -- a `MinecraftServer` on the server, a `Minecraft` on a
     * client. The accessors that name those types live in the `e2e-mc` module, which is what keeps
     * this one free of any dependency on the game and its tests fast.
     */
    public fun <T : Any> facility(type: KClass<T>): T
}

/**
 * Receiver of a `server { }` block, which runs in the dedicated server process.
 *
 * Separate from [ClientScope] deliberately. A server block cannot so much as name a client-side
 * value, because no accessor for one exists on this type: the split is enforced by the type system
 * rather than by convention or a lint, and `@E2eDsl` stops a nested block reaching the outer
 * receiver implicitly. Values cross between the two sides only as `shared`.
 */
public interface ServerScope : NodeScope

/** Receiver of a `client { }` block, which runs in a client process. @see ServerScope */
public interface ClientScope : NodeScope {
    /** Which client this is, when a test runs more than one. */
    public val clientIndex: Int
}

/** @see NodeScope.facility */
public inline fun <reified T : Any> NodeScope.facility(): T = facility(T::class)

/**
 * The concrete scope a node hands to a lifted block.
 *
 * It satisfies every scope interface so that one generated `invoke` can serve driver, server and
 * client blocks alike. Which members are legal in a given block is still settled at compile time by
 * the receiver its source lambda declared: a `server { }` body is typed
 * `suspend ServerScope.() -> Unit` and can never see a client accessor, whatever the runtime object
 * handed to it happens to also implement.
 */
public interface BlockScope : E2eScope, ServerScope, ClientScope
