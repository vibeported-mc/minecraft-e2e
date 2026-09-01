package dev.vibeported.mc.e2e.node

import dev.vibeported.mc.e2e.protocol.BlockId
import dev.vibeported.mc.e2e.BlockScope
import dev.vibeported.mc.e2e.DEFAULT_CLIENT
import dev.vibeported.mc.e2e.Shared
import dev.vibeported.mc.e2e.mc.TickClock
import dev.vibeported.mc.e2e.mc.applyPlayerAction
import dev.vibeported.mc.e2e.mc.awaitPlayerState
import dev.vibeported.mc.e2e.protocol.E2eAssertionError
import dev.vibeported.mc.e2e.protocol.NodeId
import dev.vibeported.mc.e2e.protocol.SharedId
import dev.vibeported.mc.e2e.rpc.AwaitPlayer
import dev.vibeported.mc.e2e.rpc.ControlPlayer
import dev.vibeported.mc.e2e.rpc.Event
import dev.vibeported.mc.e2e.rpc.PlayerAction
import dev.vibeported.mc.e2e.rpc.PlayerExpectation
import dev.vibeported.mc.e2e.rpc.InvokeBlock
import dev.vibeported.mc.e2e.rpc.Payload
import dev.vibeported.mc.e2e.rpc.SharedGet
import dev.vibeported.mc.e2e.rpc.SharedSet
import dev.vibeported.mc.e2e.rpc.ValueCodec
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import kotlin.reflect.KClass
import kotlin.time.Duration

/**
 * What a lifted block sees.
 *
 * Every member either crosses a process boundary or reaches into this node's game -- there is
 * nothing else, because the compiler plugin has already guaranteed the block references nothing
 * else. The game accessors simply read through to the live objects: the block body runs on the game
 * thread, so no marshalling is needed to make them safe.
 *
 * One object serves both roles, and asking a server node for a client accessor throws. That is not
 * a hole in the type separation: which accessors are even nameable was settled at compile time by
 * the receiver of the source lambda, so reaching one of these errors means the plugin handed a
 * block to the wrong node.
 */
internal class NodeBlockScope(
    override val self: NodeId,
    override val runId: String,
    private val currentBlock: BlockId,
    override val testName: String,
    private val server: MinecraftServer?,
    private val client: Minecraft?,
    private val codec: ValueCodec,
    private val tickClock: TickClock,
    private val emitLog: (Event) -> Unit,
    /** Sends a payload to the orchestrator, which relays and answers. */
    private val toOrchestrator: suspend (Payload) -> JsonElement?,
) : BlockScope {

    override val minecraftServer: MinecraftServer
        get() = server ?: error("`$currentBlock` asked for the server, but it is running on $self")

    override val serverLevel: ServerLevel get() = minecraftServer.overworld()

    override val serverPlayers: List<ServerPlayer> get() = minecraftServer.playerList.players

    override val serverPlayer: ServerPlayer? get() = serverPlayers.firstOrNull()

    override val clientName: String get() = self.name

    override val minecraft: Minecraft
        get() = client ?: error("`$currentBlock` asked for the client, but it is running on $self")

    override val clientLevel: ClientLevel? get() = minecraft.level

    override val clientPlayer: LocalPlayer? get() = minecraft.player

    override fun log(message: String) {
        emitLog(
            Event(
                from = self,
                to = NodeId.ORCHESTRATOR,
                runId = runId,
                block = currentBlock,
                message = message,
                atMillis = System.currentTimeMillis(),
            )
        )
    }

    override suspend fun dispatch(block: BlockId, target: NodeId) {
        toOrchestrator(InvokeBlock(runId, block, target))
    }

    override val level: Level
        get() = if (server != null) serverLevel else clientLevel
            ?: error("`$currentBlock` asked for the level, but this client has not joined one")

    override val currentTick: Long get() = tickClock.current

    override suspend fun awaitTicks(count: Int): Unit = tickClock.awaitTicks(count)

    override suspend fun teleport(client: String, pos: BlockPos, flying: Boolean) {
        val centre = Vec3.atBottomCenterOf(pos)
        control(client, PlayerAction.Teleport(centre.x, centre.y, centre.z, flying))
        confirm(client, PlayerExpectation.AtBlock(pos.x, pos.y, pos.z)) { seen ->
            "teleport to $pos never took effect for client `$client`; $seen"
        }
    }

    override val thisClient: String get() = if (client != null) self.name else DEFAULT_CLIENT

    override suspend fun lookAtPlayer(client: String, target: String) {
        control(client, PlayerAction.LookAtPlayer(target))
        confirm(client, PlayerExpectation.FacingPlayer(target)) { seen ->
            "lookAtPlayer(\"$target\") never took effect for client `$client`; $seen"
        }
    }

    override suspend fun lookAt(client: String, pos: BlockPos) {
        val centre = Vec3.atCenterOf(pos)
        control(client, PlayerAction.LookAt(centre.x, centre.y, centre.z))
        confirm(client, PlayerExpectation.Facing(centre.x, centre.y, centre.z)) { seen ->
            "lookAt $pos never took effect for client `$client`; $seen"
        }
    }

    /** Applies here if this is the server, and otherwise asks the orchestrator to relay it there. */
    private suspend fun control(client: String, action: PlayerAction) {
        val local = server
        if (local != null) local.applyPlayerAction(client, action)
        else toOrchestrator(ControlPlayer(runId, client, action))
    }

    /**
     * Asks the named client whether it has caught up, answering locally when that client is us.
     *
     * Always the client and never the server: the server changed its own copy the moment it acted,
     * so it would agree to anything.
     */
    private suspend fun confirm(
        client: String,
        expect: PlayerExpectation,
        describe: (String) -> String,
    ) {
        val here = this.client
        val seen = if (here != null) {
            awaitPlayerState(here, tickClock, expect, ACTION_TIMEOUT_TICKS)
        } else {
            (toOrchestrator(AwaitPlayer(runId, client, expect, ACTION_TIMEOUT_TICKS)) as? JsonPrimitive)
                ?.contentOrNull
        }
        if (seen != null) throw E2eAssertionError(describe(seen))
    }

    override fun <T : Any> sharedHandle(id: SharedId, type: KClass<T>): Shared<T> =
        RemoteShared(id, type)

    /**
     * One shared value, seen from this node.
     *
     * Constructing it costs nothing and touches nothing, which is what lets the plugin emit it
     * wherever a shared value is mentioned. Everything that actually crosses the wire is suspending,
     * and the orchestrator is the only party holding a value.
     */
    private inner class RemoteShared<T : Any>(
        override val id: SharedId,
        private val type: KClass<T>,
    ) : Shared<T> {

        override suspend fun get(): T = read(waitForIt = true)
            ?: error("e2e: `$id` resolved to null, which a shared value is never allowed to be")

        override suspend fun getOrNull(): T? = read(waitForIt = false)

        override suspend fun waitForSet(timeout: Duration?): T =
            read(waitForIt = true, timeout = timeout)
                ?: error("e2e: `$id` resolved to null, which a shared value is never allowed to be")

        override suspend fun set(value: T) {
            toOrchestrator(SharedSet(runId, id, type.java.name, codec.encode(type, value)))
        }

        private suspend fun read(waitForIt: Boolean, timeout: Duration? = null): T? {
            val encoded = toOrchestrator(
                SharedGet(
                    runId = runId,
                    id = id,
                    valueType = type.java.name,
                    await = waitForIt,
                    timeoutMillis = timeout?.inWholeMilliseconds,
                )
            )
            if (encoded == null || encoded is JsonNull) return null
            // javaObjectType, not java: for Int::class the latter is primitive `int`, which
            // Class.cast rejects outright.
            return type.javaObjectType.cast(codec.decode(type, encoded))
        }

        override fun toString(): String = "Shared($id)"
    }

    private companion object {
        /**
         * How many ticks a teleport or a turn gets to show up, from `mcE2E.actionTimeoutSeconds`.
         *
         * Counted in ticks rather than seconds because that is the unit the waiting happens in: a
         * server too busy to tick is exactly the case where a wall clock would give up early.
         */
        val ACTION_TIMEOUT_TICKS: Int =
            (System.getProperty("e2e.action.timeout.seconds")?.toIntOrNull() ?: 10) * 20
    }
}
