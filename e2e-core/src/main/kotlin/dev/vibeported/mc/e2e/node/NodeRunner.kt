package dev.vibeported.mc.e2e.node

import dev.vibeported.mc.e2e.protocol.NodeId
import dev.vibeported.mc.e2e.mc.McValueCodec
import dev.vibeported.mc.e2e.mc.TickClock
import dev.vibeported.mc.e2e.mc.applyPlayerAction
import dev.vibeported.mc.e2e.mc.Screenshots
import dev.vibeported.mc.e2e.mc.awaitPlayerState
import dev.vibeported.mc.e2e.rpc.Event
import dev.vibeported.mc.e2e.rpc.AwaitPlayer
import dev.vibeported.mc.e2e.rpc.ControlPlayer
import dev.vibeported.mc.e2e.rpc.InvokeBlock
import dev.vibeported.mc.e2e.rpc.RemoteInvocationException
import dev.vibeported.mc.e2e.rpc.Request
import dev.vibeported.mc.e2e.rpc.RpcPeer
import dev.vibeported.mc.e2e.rpc.ValueCodec
import dev.vibeported.mc.e2e.rpc.toRemoteFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
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

        // Only the server can move a player, so this arrives here when a client block asked for it.
        is ControlPlayer -> withContext(blockDispatcher) {
            val server = server ?: error("Node $id was asked to move a player but is not the server")
            server.applyPlayerAction(payload.client, payload.action)
            null
        }

        // And only a client can say whether it has caught up.
        is AwaitPlayer -> withContext(blockDispatcher) {
            val client = client ?: error("Node $id was asked about its player but is not a client")
            awaitPlayerState(client, tickClock, payload.expect, payload.timeoutTicks)
                ?.let { JsonPrimitive(it) }
        }

        else -> error("Node $id has no handler for $payload")
    }

    /**
     * A picture of what the client was looking at when it gave up.
     *
     * Only a client has anything to photograph, and a capture that itself fails must not replace the
     * failure being reported -- so anything going wrong here is swallowed and the original stands.
     */
    private suspend fun screenshotOfFailure(payload: InvokeBlock): String? {
        val minecraft = client ?: return null
        return try {
            withContext(blockDispatcher) {
                Screenshots.capture(
                    minecraft = minecraft,
                    client = id.name,
                    test = payload.test,
                    name = "failed - " + payload.block.value.substringAfterLast('/'),
                ).absolutePath
            }
        } catch (ignored: Throwable) {
            null
        }
    }

    private suspend fun runBlock(payload: InvokeBlock) {
        val table = registry.tableFor(payload.block)
        val scope = NodeBlockScope(
            self = id,
            runId = payload.runId,
            currentBlock = payload.block,
            testName = payload.test,
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
        try {
            withContext(blockDispatcher) {
                table.invoke(payload.block.value, scope)
            }
        } catch (failure: Throwable) {
            val shot = screenshotOfFailure(payload) ?: throw failure
            throw RemoteInvocationException(failure.toRemoteFailure(id).copy(screenshot = shot))
        }
    }
}
