package dev.vibeported.mc.e2e

import dev.vibeported.mc.e2e.node.TableRegistry
import dev.vibeported.mc.e2e.protocol.ProcedureId
import dev.vibeported.mc.e2e.protocol.NodeId
import dev.vibeported.mc.e2e.rpc.Payload
import dev.vibeported.mc.e2e.rpc.ValueCodec
import kotlinx.serialization.json.JsonElement
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * Which process this code is running in, and how it reaches the others.
 *
 * Carried in the coroutine context rather than passed as a receiver, and that is the whole point:
 * `server { }` and `client { }` are top-level suspend functions, so they can be called from a test
 * method, a helper, a JUnit body -- anywhere at all -- without the caller having to be handed some
 * framework object first. Whoever is running installs this once and everything underneath finds it.
 */
public class Node(
    public val self: NodeId,
    internal val tables: TableRegistry,
    internal val codec: ValueCodec,
    /** Sends a payload to the orchestrator, which routes it onward and hands back the answer. */
    internal val relay: suspend (Payload) -> JsonElement?,
    /** Builds the receiver a block body runs against, when this node is the one running it. */
    internal val scopes: ScopeFactory,
) : AbstractCoroutineContextElement(Node) {

    public companion object Key : CoroutineContext.Key<Node> {

        @Volatile
        private var process: Node? = null

        /**
         * Makes this the node for the whole process, not just for one coroutine context.
         *
         * The context is the right answer and is looked at first. The fallback exists because a
         * main this framework calls is free to start its own `runBlocking` -- a JUnit test method
         * does exactly that -- and a fresh root coroutine inherits nothing. Without this, calling a
         * procedure from a test would fail for a reason nobody could be expected to guess.
         */
        public fun install(node: Node) {
            process = node
        }

        internal fun installed(): Node? = process
    }
}

/**
 * What a block body sees as its receiver on the node that runs it.
 *
 * Untyped here because the answer differs by node -- a `ServerScope` on the server, a `ClientScope`
 * on a client -- and this module has to name both without a dedicated server ever loading the
 * client one.
 */
public fun interface ScopeFactory {
    public fun create(run: RunContext, block: ProcedureId): Any
}

/**
 * The test a call belongs to.
 *
 * Separate from [Node] because a node outlives any one test: identity and transport are set up
 * once when the process starts, while this changes with every test and travels with every call.
 */
public class RunContext(
    public val runId: String,
    public val testName: String,
) : AbstractCoroutineContextElement(RunContext) {

    public companion object Key : CoroutineContext.Key<RunContext>
}

/** The node this code is running in, or a complaint that says how to get one. */
public suspend fun currentNode(): Node =
    coroutineContext[Node] ?: Node.installed() ?: error(
        "There is no e2e node in this coroutine context, so `server` and `client` have nowhere to " +
            "run. A test body reaches one by being started from the orchestrator, or from inside " +
            "another block."
    )

/** The test this code belongs to, or an unnamed one when nobody said. */
public suspend fun currentRun(): RunContext = coroutineContext[RunContext] ?: ANONYMOUS

private val ANONYMOUS = RunContext(runId = "anonymous", testName = "")
