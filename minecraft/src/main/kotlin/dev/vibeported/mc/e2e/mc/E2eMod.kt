package dev.vibeported.mc.e2e.mc

import dev.vibeported.mc.e2e.CLIENT_ROLE
import dev.vibeported.mc.e2e.ClientScope
import dev.vibeported.mc.e2e.Artifact
import dev.vibeported.mc.e2e.CurrentTest
import dev.vibeported.mc.e2e.FailureArtifacts
import dev.vibeported.mc.e2e.DEFAULT_CLIENT
import dev.vibeported.mc.e2e.SERVER_NODE
import dev.vibeported.mc.e2e.ROLE_PROPERTY
import dev.vibeported.mc.e2e.SERVER_ROLE
import dev.vibeported.mc.e2e.startedNodeName
import dev.vibeported.mc.e2e.startedRole
import dev.vibeported.mc.e2e.ServerScope
import dev.vibeported.mc.e2e.reportArtifact
import dev.vibeported.rpc.CborWireFormat
import dev.vibeported.rpc.NodeId
import dev.vibeported.rpc.Role
import dev.vibeported.rpc.RpcScope
import dev.vibeported.rpc.Services
import dev.vibeported.rpc.host.HubAddress
import dev.vibeported.rpc.host.RpcHost
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.cbor.Cbor
import net.minecraft.client.Minecraft
import net.minecraft.server.MinecraftServer
import net.minecraft.util.thread.BlockableEventLoop
import net.neoforged.fml.common.Mod
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.server.ServerStartedEvent
import net.neoforged.neoforge.event.server.ServerStoppingEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Puts an RPC node inside a running game.
 *
 * Nothing here is about procedures, transports or tables -- all of that is [RpcHost]. What this
 * module supplies is the three things only a game can: an event loop to run bodies on, the scope a
 * body sees when it lands, and the serializers for values that are Mojang's rather than ours.
 */
@Mod("e2e")
public class E2eMod {

    init {
        val role = startedRole()
        if (role == null) {
            LOG.info("e2e: no {} set, so this process is not part of a test run", ROLE_PROPERTY)
        } else {
            val hub = HubAddress(
                host = System.getProperty("e2e.hub.host", "127.0.0.1"),
                port = System.getProperty("e2e.hub.port")?.toIntOrNull()
                    ?: error("e2e: $ROLE_PROPERTY is set but e2e.hub.port is not"),
            )

            when (role) {
                "server" -> installServer(hub)
                "client" -> installClient(hub, startedNodeName())
                else -> error("e2e: unknown $ROLE_PROPERTY '$role'")
            }
        }
    }

    private fun installServer(hub: HubAddress) {
        NeoForge.EVENT_BUS.addListener<ServerStartedEvent> { event ->
            connect(NodeId(SERVER_NODE), SERVER_ROLE, hub, server = event.server, client = null)
        }
        NeoForge.EVENT_BUS.addListener<ServerStoppingEvent> { shutdown() }
    }

    private fun installClient(hub: HubAddress, name: String) {
        val connected = AtomicBoolean(false)

        NeoForge.EVENT_BUS.addListener<ClientTickEvent.Post> {
            val minecraft = Minecraft.getInstance()
            // --quickPlayMultiplayer walks the connect screens on its own; ready means the world and
            // the player have actually arrived, not merely that the client booted. Joining the
            // roster is how this node says so, and the orchestrator waits for exactly that.
            if (minecraft.level == null || minecraft.player == null) return@addListener
            if (!connected.compareAndSet(false, true)) return@addListener

            connect(NodeId(name), CLIENT_ROLE, hub, server = null, client = minecraft)
        }
    }

    private fun connect(
        id: NodeId,
        role: Role,
        hub: HubAddress,
        server: MinecraftServer?,
        client: Minecraft?,
    ) {
        // Default, not the game thread: the RPC machinery belongs off the loop, so a slow tick
        // cannot delay the thing measuring it. Only bodies are dispatched onto the loop.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("e2e-$id"))
        this.scope = scope

        val loop: BlockableEventLoop<*> = server ?: client
            ?: error("e2e: a node needs either a server or a client to run bodies on")

        // One listener for the whole node. Everything that waits on a tick shares this counter,
        // rather than registering and unregistering a listener per wait.
        val tickClock = TickClock()
        if (server != null) {
            NeoForge.EVENT_BUS.addListener<ServerTickEvent.Post> { tickClock.onTick() }
        } else {
            NeoForge.EVENT_BUS.addListener<ClientTickEvent.Post> { tickClock.onTick() }
        }

        val services = Services()
        services.provide(CurrentTest::class, CurrentTest())

        scope.launch {
            val host = RpcHost(
                id = id,
                roles = setOf(role),
                services = services,
                format = CborWireFormat(Cbor { serializersModule = MinecraftSerializers.module }),
                dispatcher = GameThreadDispatcher(loop),
                // The loader this mod was loaded by, so the tables resolve to the same classes the
                // rest of this node is built out of. @see OrchestratorBootstrap for what the wrong
                // one looks like.
                loader = E2eMod::class.java.classLoader,
                onBodyFailure = { procedure, _ ->
                    // Nothing is attached to the failure, which travels as it always did. The
                    // picture goes separately, as an ordinary value through an ordinary call.
                    val test = services.resolve(CurrentTest::class).value
                    FailureArtifacts.capture(procedure, test.testName)?.let { path ->
                        reportArtifact(
                            Artifact(
                                node = id.value,
                                test = test.testName,
                                procedure = procedure,
                                path = path,
                            )
                        )
                    }
                },
            )
            val connection = host.connect(scope, hub)

            // Registered after connecting because the scope needs the node's own identity, which is
            // settled by the join. One instance, shared by every body that lands here.
            val scopeForBodies = GameScope(
                node = connection.node.info,
                services = services,
                server = server,
                client = client,
                tickClock = tickClock,
                telemetry = scope,
            )
            services.provide(ServerScope::class, scopeForBodies)
            services.provide(ClientScope::class, scopeForBodies)
            services.provide(RpcScope::class, scopeForBodies)

            LOG.info("e2e: {} joined the run at {}", id.value, hub)
        }
    }

    /**
     * Stops the node when the game does.
     *
     * A body suspended mid-await would otherwise wait on a loop that has stopped accepting work, and
     * never come back.
     */
    private fun shutdown() {
        scope?.cancel("the game is shutting down")
        scope = null
    }

    private var scope: CoroutineScope? = null

    internal companion object {
        val LOG = LoggerFactory.getLogger("e2e")
    }
}
