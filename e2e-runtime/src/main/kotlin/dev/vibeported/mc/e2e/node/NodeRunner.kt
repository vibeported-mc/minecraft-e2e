package dev.vibeported.mc.e2e.node

import dev.vibeported.mc.e2e.NodeId
import dev.vibeported.mc.e2e.rpc.Event
import dev.vibeported.mc.e2e.rpc.InvokeBlock
import dev.vibeported.mc.e2e.rpc.JsonValueCodec
import dev.vibeported.mc.e2e.rpc.Request
import dev.vibeported.mc.e2e.rpc.RpcPeer
import dev.vibeported.mc.e2e.rpc.ValueCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

/**
 * A server or client node: it waits to be told which block to run, looks it up in the generated
 * table, and runs it.
 *
 * It holds no idea of what the test is. That lives in the driver on the orchestrator, which is
 * precisely what lets the same node serve any test in the suite.
 */
public class NodeRunner(
    public val id: NodeId,
    private val peer: RpcPeer,
    private val registry: TableRegistry,
    private val facilities: Facilities,
    private val codec: ValueCodec = JsonValueCodec(),
) {
    // Unbounded, and written with trySend, so log() can stay non-suspending for its callers.
    private val logs = Channel<Event>(Channel.UNLIMITED)

    /**
     * Returns one job covering everything this node runs.
     *
     * Both the log pump and the receive loop are launched as children of it, so cancelling the
     * returned job stops the node completely. Launching the pump directly into [scope] instead
     * would leave it parked on [logs] forever, and any caller waiting on that scope -- a
     * `runBlocking` in a test runner, say -- would never be released.
     */
    public fun start(scope: CoroutineScope): Job {
        peer.onRequest = ::handle
        return scope.launch {
            launch {
                for (event in logs) peer.emit(event)
            }
            peer.start(this)
        }
    }

    private suspend fun handle(request: Request): JsonElement? = when (val payload = request.payload) {
        is InvokeBlock -> {
            runBlock(payload)
            null
        }

        else -> error("Node $id has no handler for $payload")
    }

    private suspend fun runBlock(payload: InvokeBlock) {
        val table = registry.tableFor(payload.block)
        val scope = NodeBlockScope(
            self = id,
            runId = payload.runId,
            currentBlock = payload.block,
            facilities = facilities,
            codec = codec,
            emitLog = { logs.trySend(it) },
            // Everything a block asks for goes to the orchestrator, including a nested client block
            // that this node raised: it routes onward and hands back the result.
            toOrchestrator = { peer.call(NodeId.ORCHESTRATOR, it) },
        )
        table.invoke(payload.block.value, scope)
    }
}
