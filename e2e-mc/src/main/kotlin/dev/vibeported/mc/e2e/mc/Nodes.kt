package dev.vibeported.mc.e2e.mc

import dev.vibeported.mc.e2e.NodeId
import dev.vibeported.mc.e2e.node.Facilities
import dev.vibeported.mc.e2e.node.NodeRunner
import dev.vibeported.mc.e2e.node.TableRegistry
import dev.vibeported.mc.e2e.rpc.RpcPeer
import dev.vibeported.mc.e2e.rpc.SocketNodeTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import net.minecraft.client.Minecraft
import net.minecraft.server.MinecraftServer
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.server.ServerStartedEvent
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.minutes

/**
 * Brings a node up once the game is far enough along to be worth testing, and dials the orchestrator.
 *
 * Waiting matters as much as connecting. Reporting readiness the moment the mod loads would let the
 * orchestrator dispatch a block into a world that does not exist yet, so each side has its own idea
 * of ready: a started server, and a client that is actually in a level.
 */
private fun connect(self: NodeId, host: String, port: Int, facilities: Facilities) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val transport = SocketNodeTransport.connect(self, host, port)
    transport.start(scope)

    val runner = NodeRunner(
        id = self,
        peer = RpcPeer(transport, callTimeout = 10.minutes),
        registry = TableRegistry.load(E2eMod::class.java.classLoader),
        facilities = facilities,
        codec = McValueCodec(),
    )
    runner.start(scope)
    E2eMod.LOG.info("e2e: {} connected to the orchestrator at {}:{}", self, host, port)
}

internal object ServerNode {

    fun install(host: String, port: Int) {
        NeoForge.EVENT_BUS.addListener<ServerStartedEvent> { event ->
            val server: MinecraftServer = event.server
            connect(
                self = NodeId.SERVER,
                host = host,
                port = port,
                facilities = Facilities.of(
                    MinecraftServer::class to server,
                ),
            )
        }
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

            connect(
                self = self,
                host = host,
                port = port,
                facilities = Facilities.of(
                    Minecraft::class to minecraft,
                ),
            )
        }
    }
}
