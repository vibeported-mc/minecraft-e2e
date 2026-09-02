package dev.vibeported.mc.e2e.orchestrator

import dev.vibeported.mc.e2e.Logs
import dev.vibeported.mc.e2e.protocol.NodeId
import dev.vibeported.mc.e2e.protocol.NodeRole
import dev.vibeported.mc.e2e.rpc.AwaitPlayer
import dev.vibeported.mc.e2e.rpc.Cancel
import dev.vibeported.mc.e2e.rpc.ControlPlayer
import dev.vibeported.mc.e2e.rpc.Event
import dev.vibeported.mc.e2e.rpc.InvokeProcedure
import dev.vibeported.mc.e2e.rpc.Payload
import dev.vibeported.mc.e2e.rpc.Request
import dev.vibeported.mc.e2e.rpc.RpcPeer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.serialization.json.JsonElement

/**
 * The switchboard, and nothing else.
 *
 * Every payload from any node arrives here and is sent on to the node that can answer it. That is
 * the whole job: it runs no tests, keeps no report and knows nothing about what a test is. Whatever
 * is driving the run does all of that, calling `server { }` and `client { }` like any other code and
 * letting this route the results.
 *
 * It is also the only party that can start a client, so a call addressed to one nobody has started
 * yet waits here while that happens rather than failing.
 */
public class Orchestrator(
    private val peer: RpcPeer,
    /** Which nodes have dialled in, so a call can tell whether it has to start one first. */
    private val connected: () -> Set<NodeId> = ::emptySet,
    /** Starts a client and returns when it has joined and is ready to be called. */
    private val startClient: suspend (String) -> Unit = { name ->
        error("A procedure is addressed to client `" + name + "`, which is not running and cannot be started")
    },
) {
    public fun start(scope: CoroutineScope): Job {
        peer.onRequest = { request: Request -> route(request.payload) }
        // Forwarded, not kept: what a log line is for is somebody else's decision.
        peer.onEvent = { event -> Logs.emit(event) }
        return peer.start(scope)
    }

    /**
     * Single entry point for every payload, whether it arrived over the wire or from code running
     * in this process.
     *
     * Nothing is acted on here. This process has no game in it: moving a player is the server's
     * job, only a client can say whether it has caught up, and a procedure belongs to whichever
     * node was named when it was written.
     */
    public suspend fun route(payload: Payload): JsonElement? = when (payload) {
        is InvokeProcedure -> callNode(payload.target, payload)
        is ControlPlayer -> callNode(NodeId.SERVER, payload)
        is AwaitPlayer -> callNode(NodeId.client(payload.client), payload)
        is Cancel -> null
    }

    /**
     * Sends a payload to a node, starting that node first if it is a client nobody has started.
     *
     * A name the compiler could work out is already running by the time a test asks for it; one it
     * could not -- a name built at runtime, or read from a parameter -- lands here and costs a
     * launch. That is the price of letting a client be named by an expression.
     */
    private suspend fun callNode(target: NodeId, payload: Payload): JsonElement? {
        if (target.role == NodeRole.CLIENT && target !in connected()) {
            startClient(target.name)
        }
        return peer.call(target, payload)
    }
}
