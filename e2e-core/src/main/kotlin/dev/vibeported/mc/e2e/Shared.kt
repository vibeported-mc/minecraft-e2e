package dev.vibeported.mc.e2e

import dev.vibeported.mc.e2e.protocol.SharedId
import kotlin.time.Duration

/**
 * A value that outlives any one node, held authoritatively by the orchestrator.
 *
 * A handle, not the value itself. That is what lets it be captured by any lambda, passed to a helper
 * or stored: obtaining one costs nothing and suspends nothing. Only reading and writing cross the
 * wire, and those are ordinary suspending calls, so the compiler decides where they are legal
 * without the framework having to police it.
 */
public interface Shared<T : Any> {

    /** Stable id, of the form `<test id>#<name>`. Worth having in a failure message. */
    public val id: SharedId

    /**
     * The current value, waiting for it to be set if it is not yet.
     *
     * Bounded by the test timeout rather than a timeout of its own, so a value nobody ever writes
     * fails the test with a report naming this id rather than hanging silently.
     */
    public suspend fun get(): T

    /** The current value, or null if nothing has written one yet. Never waits. */
    public suspend fun getOrNull(): T?

    public suspend fun set(value: T)

    /** @see get. Passing a [timeout] gives up sooner than the test would. */
    public suspend fun waitForSet(timeout: Duration? = null): T
}
