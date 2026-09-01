package dev.vibeported.mc.e2e.node

import dev.vibeported.mc.e2e.Node
import dev.vibeported.mc.e2e.RunContext
import dev.vibeported.mc.e2e.ScopeFactory
import dev.vibeported.mc.e2e.protocol.ProcedureId
import dev.vibeported.mc.e2e.protocol.NodeId
import dev.vibeported.mc.e2e.mc.McValueCodec
import dev.vibeported.mc.e2e.mc.TickClock
import dev.vibeported.mc.e2e.rpc.Event
import dev.vibeported.mc.e2e.rpc.InvokeProcedure
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
    private val procedureDispatcher: CoroutineContext = EmptyCoroutineContext,
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

    /**
     * This node's identity and reach, for anything running inside a block body.
     *
     * Built once: a node outlives every test it runs, and a `server { }` nested inside a block finds
     * it by looking up the coroutine context rather than by being handed anything.
     */
    private val node: Node by lazy {
        Node(
            self = id,
            tables = registry,
            codec = codec,
            // Everything a block asks for goes to the orchestrator, including a block this node
            // raised for somewhere else: it routes onward and hands back the result.
            relay = { peer.call(NodeId.ORCHESTRATOR, it) },
            scopes = ScopeFactory { run, block -> scopeFor(run, block) },
        )
    }

    private suspend fun handle(request: Request): JsonElement? = when (val payload = request.payload) {
        is InvokeProcedure -> runProcedure(payload)

        else -> error("Node $id has no handler for $payload")
    }

    private fun scopeFor(run: RunContext, block: ProcedureId): Any = NodeProcedureScope(
        self = id,
        runId = run.runId,
        currentProcedure = block,
        testName = run.testName,
        server = server,
        client = client,
        codec = codec,
        tickClock = tickClock,
        emitLog = { logs.trySend(it) },
        toOrchestrator = { peer.call(NodeId.ORCHESTRATOR, it) },
    )

    private suspend fun runProcedure(payload: InvokeProcedure): JsonElement? {
        val table = registry.tableFor(payload.procedure)
        val id = payload.procedure.value
        val run = RunContext(payload.runId, payload.test)
        val scope = scopeFor(run, payload.procedure)

        // Decoded by the table rather than here: only the generated code knows what each parameter
        // was declared as, and this end has a list of values with no idea what any of them mean.
        val args = table.decodeArgs(id, payload.args, codec)

        // The whole body runs on the game thread, which is what makes every Minecraft call in it
        // safe. Suspending inside it releases the thread, so the game keeps ticking meanwhile. The
        // node and the run ride along, so a `server { }` written inside this body can find them.
        return try {
            val result = withContext(procedureDispatcher + node + run) {
                table.invoke(id, scope, args)
            }
            table.encodeResult(id, result, codec)
        } catch (failure: Throwable) {
            // Whatever can take a picture of this node registers itself; the transport has no
            // idea what a screenshot is and no way to take one.
            val shot = FailureArtifacts.capture(payload.procedure, payload.test) ?: throw failure
            throw RemoteInvocationException(failure.toRemoteFailure(this.id).copy(screenshot = shot))
        }
    }
}
