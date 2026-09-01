package dev.vibeported.mc.e2e.mc

import dev.vibeported.mc.e2e.protocol.NodeId
import dev.vibeported.mc.e2e.node.NodeRunner
import dev.vibeported.mc.e2e.node.TableRegistry
import dev.vibeported.mc.e2e.rpc.RpcPeer
import dev.vibeported.mc.e2e.rpc.SocketNodeTransport
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import net.minecraft.client.Minecraft
import net.minecraft.server.MinecraftServer
import net.minecraft.util.thread.BlockableEventLoop
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.server.ServerStoppingEvent
import net.neoforged.neoforge.event.server.ServerStartedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.minutes

/**
 * Brings a node up once the game is far enough along to be worth testing, and dials the orchestrator.
 *
 * Waiting matters as much as connecting. Reporting readiness the moment the mod loads would let the
 * orchestrator dispatch a block into a world that does not exist yet, so each side has its own idea
 * of ready: a started server, and a client that is actually in a level.
 *
 * The scope is `Default`, not the game thread: the RPC machinery belongs off the loop. Only block
 * bodies are dispatched onto it, by [NodeRunner].
 */
internal object Nodes {

    private var scope: CoroutineScope? = null

    fun connect(self: NodeId, host: String, port: Int, server: MinecraftServer?, client: Minecraft?) {
        val nodeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("e2e-$self"))
        scope = nodeScope

        val transport = SocketNodeTransport.connect(self, host, port)
        transport.start(nodeScope)

        val loop: BlockableEventLoop<*> = server ?: client
            ?: error("A node needs either a server or a client to run blocks on")

        // One listener for the whole node. Everything that waits on a tick shares this counter,
        // rather than registering and unregistering a listener per wait.
        val tickClock = TickClock()
        if (server != null) {
            NeoForge.EVENT_BUS.addListener<ServerTickEvent.Post> { tickClock.onTick() }
        } else {
            NeoForge.EVENT_BUS.addListener<ClientTickEvent.Post> { tickClock.onTick() }
        }

        NodeRunner(
            id = self,
            peer = RpcPeer(transport, callTimeout = 10.minutes),
            registry = TableRegistry.load(E2eMod::class.java.classLoader),
            server = server,
            client = client,
            blockDispatcher = GameThreadDispatcher(loop),
            tickClock = tickClock,
        ).start(nodeScope)

        E2eMod.LOG.info("e2e: {} connected to the orchestrator at {}:{}", self, host, port)
    }

    /**
     * Stops the node when the game does.
     *
     * A block suspended mid-await would otherwise wait on a loop that has stopped accepting work,
     * and never come back.
     */
    fun shutdown() {
        scope?.cancel("the game is shutting down")
        scope = null
    }
}

internal object ServerNode {

    fun install(host: String, port: Int) {
        NeoForge.EVENT_BUS.addListener<ServerStartedEvent> { event ->
            Nodes.connect(NodeId.SERVER, host, port, server = event.server, client = null)
        }
        NeoForge.EVENT_BUS.addListener<ServerStoppingEvent> { Nodes.shutdown() }
    }
}

internal object ClientNode {

    fun install(host: String, port: Int, self: NodeId) {
        val connected = AtomicBoolean(false)

        NeoForge.EVENT_BUS.addListener<ClientTickEvent.Post> {
            val minecraft = Minecraft.getInstance()
            // --quickPlayMultiplayer walks the connect screens on its own; ready means the world and
            // the player have actually arrived, not merely that the client booted.
            if (minecraft.level == null || minecraft.player == null) return@addListener
            if (!connected.compareAndSet(false, true)) return@addListener

            Nodes.connect(self, host, port, server = null, client = minecraft)
        }
    }
}
