@file:OptIn(E2eGenerated::class)

package dev.vibeported.mc.e2e

import dev.vibeported.mc.e2e.protocol.E2eAssertionError

/**
 * Declares a group of tests.
 *
 * The builder body is *not* lifted: it runs locally on whichever node is enumerating tests, and by
 * the time it runs the compiler plugin has already replaced each `e2e { }` body with a block id, so
 * running it is cheap and free of side effects.
 */
public fun suite(name: String, body: SuiteBuilder.() -> Unit): SuiteDescriptor =
    suite(name, name, body)

/** Plugin-rewritten form of [suite], carrying the id derived from the declaring file. */
@E2eGenerated
public fun suite(name: String, id: String, body: SuiteBuilder.() -> Unit): SuiteDescriptor =
    SuiteBuilder(name, id).apply(body).build()

@E2eDsl
public class SuiteBuilder internal constructor(
    public val name: String,
    public val id: String,
) {
    private val tests = mutableListOf<TestDescriptor>()

    /**
     * Declares one end-to-end test.
     *
     * [body] is declarative, not code: it may hold `shared` declarations and `server`/`client` calls
     * and nothing else, which is checked at compile time. The compiler plugin reads the blocks out
     * of it as an ordered list of steps, so by the time this runs there is no body left to execute.
     */
    public fun e2e(name: String, body: suspend E2eScope.() -> Unit): Unit =
        throw E2ePluginNotAppliedException("e2e(\"$name\")")

    /** Plugin-rewritten form of [e2e]. The steps live in the generated index, keyed by [id]. */
    @E2eGenerated
    public fun e2e(name: String, id: String) {
        tests += TestDescriptor(id, name)
    }

    internal fun build(): SuiteDescriptor = SuiteDescriptor(id, name, tests.toList())
}

/**
 * Runs the blocks inside it at the same time, and returns when the last of them finishes.
 *
 * Everything else in a test happens one step after another, which is what makes a test readable.
 * This is for the cases where the point *is* simultaneity -- two clients looking at each other,
 * two players racing for the same block -- and it is deliberately the only way to get it, so a
 * reader can tell at a glance which parts of a test overlap.
 *
 * Like a test body, this may contain only `server`/`client` blocks.
 */
public suspend fun E2eScope.parallel(body: suspend E2eScope.() -> Unit): Unit =
    throw E2ePluginNotAppliedException("parallel { }")

/**
 * Runs [body] on the server and suspends until it finishes.
 *
 * [body] is lifted out of its enclosing closure at compile time, so it may not reference anything
 * from around it except `shared` values -- a captured local would not exist on the server.
 * Its receiver is [ServerScope], so the client side of the game is not merely discouraged here, it
 * is unnameable. Pass [id] to pin this block's stable id when renaming the test would churn it.
 */
public suspend fun E2eBlockScope.server(
    id: String? = null,
    body: suspend ServerScope.() -> Unit,
): Unit = throw E2ePluginNotAppliedException("server { }")

/**
 * Runs [body] on the client called [name] and suspends until it finishes.
 *
 * The name is the client's username as well as its address, and it must be a string literal: the
 * compiler collects every name a suite mentions so the run starts exactly those clients.
 *
 * Legal inside a `server { }` block too: the server asks the orchestrator to route it onward and
 * awaits the result, which is how one test step can straddle both processes.
 *
 * @see server for the capture rules, which are identical.
 */
public suspend fun E2eBlockScope.client(
    @MinecraftClientName name: String = DEFAULT_CLIENT,
    id: String? = null,
    body: suspend ClientScope.() -> Unit,
): Unit = throw E2ePluginNotAppliedException("client { }")

/**
 * Declares a value that outlives any one node: `val pos = shared<BlockPos>()`.
 *
 * The declaration only names the value and fixes its id; the test body it sits in never runs. Every
 * mention of `target` inside a block is rewritten by the compiler plugin into a handle bound to that
 * node, which is what lets a value written on the server be read on a client in another process.
 */
public fun <T : Any> E2eScope.shared(): Shared<T> =
    throw E2ePluginNotAppliedException("shared<T>()")
