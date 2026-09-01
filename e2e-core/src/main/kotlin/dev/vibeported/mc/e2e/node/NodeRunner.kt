package dev.vibeported.mc.e2e.node

import dev.vibeported.mc.e2e.protocol.NodeId
import dev.vibeported.mc.e2e.mc.McValueCodec
import dev.vibeported.mc.e2e.mc.TickClock
import dev.vibeported.mc.e2e.rpc.Event
import dev.vibeported.mc.e2e.rpc.InvokeBlock
import dev.vibeported.mc.e2e.rpc.Request
import dev.vibeported.mc.e2e.rpc.RpcPeer
import dev.vibeported.mc.e2e.rpc.ValueCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import net.minecraft.client.Minecraft
import net.minecraft.server.MinecraftServer

/**
 * A server or client node: it waits to be told which block to run, looks it up in the generated
 * table, and runs it on the game thread.
 *
 * It holds no idea of what the test *is*. That is the orchestrator's list of steps, which is
 * precisely what lets one node serve any test in the suite.
 */
public class NodeRunner(
    public val id: NodeId,
    private val peer: RpcPeer,
    private val registry: TableRegistry,
    private val server: MinecraftServer?,
    private val client: Minecraft?,
    /**
     * Where block bodies run. In a game process this is the event loop, which is what makes
     * Minecraft safe to touch anywhere in a block; a test with no game passes something simpler.
     */
    private val blockDispatcher: CoroutineContext = EmptyCoroutineContext,
    private val codec: ValueCodec = McValueCodec(),
    /** Ticked by the game, so a block can wait for one. Idle in a test with no game attached. */
    public val tickClock: TickClock = TickClock(),
) {
    // Unbounded, and written with trySend, so log() can stay non-suspending for its callers.
    private val logs = Channel<Event>(Channel.UNLIMITED)

    /**
     * Returns one job covering everything this node runs.
     *
     * Both the log pump and the receive loop are children of it, so cancelling the returned job
     * stops the node completely rather than leaving a coroutine parked on a channel forever.
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
            server = server,
            client = client,
            codec = codec,
            tickClock = tickClock,
            emitLog = { logs.trySend(it) },
            // Everything a block asks for goes to the orchestrator, including a nested client block
            // this node raised: it routes onward and hands back the result.
            toOrchestrator = { peer.call(NodeId.ORCHESTRATOR, it) },
        )

        // The whole body runs on the game thread, which is what makes every Minecraft call in it
        // safe. Suspending inside it releases the thread, so the game keeps ticking meanwhile.
        withContext(blockDispatcher) {
            table.invoke(payload.block.value, scope)
        }
    }
}
