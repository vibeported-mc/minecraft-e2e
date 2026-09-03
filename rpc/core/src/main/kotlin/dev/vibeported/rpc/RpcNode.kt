@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package dev.vibeported.rpc

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * Which process this code is running in, and how it reaches the others.
 *
 * Carried in the coroutine context rather than passed as a receiver, and that is the point: a call
 * is an ordinary top-level suspend function, so it can be made from a test method, a helper, a
 * framework nobody here has heard of -- without the caller being handed some object first.
 */
public class RpcNode(
    public val info: NodeInfo,
    public val tables: TableRegistry,
    public val membership: Membership,
    public val services: Services = Services(),
    public val format: WireFormat = CborWireFormat(),
    public val outbound: Outbound = Outbound.Isolated,
) : AbstractCoroutineContextElement(RpcNode) {

    init {
        // The receiver a body written against the plain entry points expects. Provided here so that
        // the simplest possible call works with no wiring at all; a layer wanting something richer
        // provides its own type and its bodies ask for that instead.
        if (services.resolveOrNull(RpcScope::class) == null) {
            services.provide(RpcScope::class) { NodeScope(info, services) }
        }
    }

    public val id: NodeId get() = info.id
    public val roles: Set<Role> get() = info.roles

    override fun toString(): String = "RpcNode($info)"

    public companion object Key : CoroutineContext.Key<RpcNode> {

        @Volatile
        private var process: RpcNode? = null

        /**
         * Makes this the node for the whole process, not just for one coroutine context.
         *
         * The context is the right answer and is looked at first. This fallback exists because code
         * the framework calls into is free to start its own `runBlocking` -- a JUnit method does
         * exactly that -- and a fresh root coroutine inherits nothing. Without it, a call from such
         * a place would fail for a reason nobody could be expected to guess.
         */
        public fun install(node: RpcNode) {
            process = node
        }

        public fun uninstall() {
            process = null
        }

        internal fun installed(): RpcNode? = process
    }
}

/** The node this code is running in, or a complaint that says how to get one. */
public suspend fun currentNode(): RpcNode =
    coroutineContext[RpcNode] ?: RpcNode.installed() ?: error(
        "There is no RPC node in this coroutine context, so a call has nowhere to run. A node is " +
            "installed by whoever starts this process, or inherited from the call you are inside."
    )

/**
 * Serves procedures that arrived from somewhere else.
 *
 * The mirror of the outbound path, and the reason both halves of serialization live on the table:
 * the bytes arriving here carry no type information, and only the generated code knows what they
 * were.
 */
public class ProcedureServer(private val node: RpcNode) {

    public suspend fun handle(procedure: String, args: List<ByteArray>): ByteArray? {
        val table = node.tables.tableFor(procedure)
        val decoded = table.decodeArgs(procedure, args, node.format)
        val result = table.invoke(procedure, node.services, decoded)
        return table.encodeResult(procedure, result, node.format)
    }
}
