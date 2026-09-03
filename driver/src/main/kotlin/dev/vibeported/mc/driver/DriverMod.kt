package dev.vibeported.mc.driver

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
 * Nothing here is about procedures, transports or tables -- all of that is [RpcHost]. What a game
 * supplies that nothing else can is the three things assembled here: an event loop to run bodies on,
 * the scope a body sees when it lands, and the hooks a client needs to be driven at all.
 *
 * One `@Mod` rather than two. The keyboard and the procedure layer used to be separate mods and
 * could have stayed so, but two `@Mod` classes cannot share an id, and there is no reason left for a
 * game to load half a driver.
 *
 * A process with no `rpc.node` set is not part of a cluster and this does nothing at all, so the
 * jar can sit in an ordinary development client without changing it.
 */
@Mod(DriverMod.ID)
public class DriverMod {

    init {
        val name = startedNodeName()
        val hub = hubAddress()
        val roles = startedRoles()

        when {
            name == null || hub == null ->
                LOG.info("mcdriver: no {} and {}, so this game is not part of a cluster", NODE_PROPERTY, HUB_PROPERTY)

            startedAsServer() -> installServer(NodeId(name), roles, hub)
            startedAsClient() -> installClient(NodeId(name), roles, hub)

            else -> error(
                "mcdriver: $ROLES_PROPERTY is `${roles.joinToString(",")}`, which names neither " +
                    "`${SERVER_ROLE.value}` nor `${CLIENT_ROLE.value}`, so there is no game to drive."
            )
        }
    }

    private fun installServer(id: NodeId, roles: Set<String>, hub: HubAddress) {
        NeoForge.EVENT_BUS.addListener<ServerStartedEvent> { event ->
            connect(id, roles, hub, server = event.server, client = null)
        }
        NeoForge.EVENT_BUS.addListener<ServerStoppingEvent> { shutdown() }
    }

    private fun installClient(id: NodeId, roles: Set<String>, hub: HubAddress) {
        // The keyboard, the window and the frame hook, all of which are client-only classes. Behind
        // a call rather than inline, so a dedicated server loading this class never resolves one.
        ClientHooks.install()

        val connected = AtomicBoolean(false)

        NeoForge.EVENT_BUS.addListener<ClientTickEvent.Post> {
            val minecraft = Minecraft.getInstance()
            // Ready means the world and the player have actually arrived, not merely that the game
            // booted -- `--quickPlayMultiplayer` walks the connect screens on its own. Joining the
            // roster is how this node says so, and whatever is driving waits for exactly that.
            if (minecraft.level == null || minecraft.player == null) return@addListener
            if (!connected.compareAndSet(false, true)) return@addListener

            connect(id, roles, hub, server = null, client = minecraft)
        }
    }

    private fun connect(
        id: NodeId,
        roles: Set<String>,
        hub: HubAddress,
        server: MinecraftServer?,
        client: Minecraft?,
    ) {
        // Default, not the game thread: the RPC machinery belongs off the loop, so a slow tick
        // cannot delay the thing driving it. Only bodies are dispatched onto the loop.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("mcdriver-$id"))
        this.scope = scope

        val loop: BlockableEventLoop<*> = server ?: client
            ?: error("mcdriver: a node needs either a server or a client to run bodies on")

        // One listener for the whole node. Everything that waits on a tick shares this counter,
        // rather than registering and unregistering a listener per wait.
        val tickClock = TickClock()
        if (server != null) {
            NeoForge.EVENT_BUS.addListener<ServerTickEvent.Post> { tickClock.onTick() }
        } else {
            NeoForge.EVENT_BUS.addListener<ClientTickEvent.Post> { tickClock.onTick() }
        }

        val services = Services()

        scope.launch {
            val host = RpcHost(
                id = id,
                roles = roles.map(::Role).toSet(),
                services = services,
                dispatcher = GameThreadDispatcher(loop),
                // The loader this mod was loaded by, so the tables and the serializers resolve to
                // the same classes the rest of this node is built out of. Under a mod loader that
                // is emphatically not this class's own.
                loader = DriverMod::class.java.classLoader,
            )
            val connection = host.connect(scope, hub)

            // Registered after connecting because the scope needs the node's own identity, which is
            // settled by the join. One instance, shared by every body that lands here.
            val forBodies = GameScope(
                node = connection.node.info,
                services = services,
                server = server,
                client = client,
                tickClock = tickClock,
            )
            services.provide(ServerScope::class, forBodies)
            services.provide(ClientScope::class, forBodies)
            services.provide(WorldBuilderScope::class, forBodies)
            services.provide(ScreenScope::class, forBodies)
            services.provide(NodeScope::class, forBodies)
            services.provide(RpcScope::class, forBodies)

            LOG.info("mcdriver: {} joined the cluster at {} as {}", id.value, hub, roles)
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

    public companion object {
        public const val ID: String = "mcdriver"

        internal val LOG = LoggerFactory.getLogger(ID)
    }
}
