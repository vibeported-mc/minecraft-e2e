package dev.vibeported.mc.e2e.node

import dev.vibeported.mc.e2e.ClientScope
import dev.vibeported.mc.e2e.ServerScope
import dev.vibeported.mc.e2e.protocol.ProcedureId
import dev.vibeported.mc.e2e.DEFAULT_CLIENT
import dev.vibeported.mc.e2e.mc.TickClock
import dev.vibeported.mc.e2e.protocol.NodeId
import dev.vibeported.mc.e2e.rpc.Event
import dev.vibeported.mc.e2e.rpc.Payload
import dev.vibeported.mc.e2e.rpc.ValueCodec
import kotlinx.serialization.json.JsonElement
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level

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
internal class NodeProcedureScope(
    override val self: NodeId,
    override val runId: String,
    private val currentProcedure: ProcedureId,
    override val testName: String,
    private val server: MinecraftServer?,
    private val client: Minecraft?,
    private val codec: ValueCodec,
    private val tickClock: TickClock,
    private val emitLog: (Event) -> Unit,
    /** Sends a payload to the orchestrator, which relays and answers. */
    private val toOrchestrator: suspend (Payload) -> JsonElement?,
) : ServerScope, ClientScope {

    override val minecraftServer: MinecraftServer
        get() = server ?: error("`$currentProcedure` asked for the server, but it is running on $self")

    override val serverLevel: ServerLevel get() = minecraftServer.overworld()

    override val serverPlayers: List<ServerPlayer> get() = minecraftServer.playerList.players

    override val serverPlayer: ServerPlayer? get() = serverPlayers.firstOrNull()

    override val clientName: String get() = self.name

    override val minecraft: Minecraft
        get() = client ?: error("`$currentProcedure` asked for the client, but it is running on $self")

    override val clientLevel: ClientLevel? get() = minecraft.level

    override val clientPlayer: LocalPlayer? get() = minecraft.player

    override val thisClient: String get() = if (client != null) self.name else DEFAULT_CLIENT

    override fun log(message: String) {
        emitLog(
            Event(
                from = self,
                to = NodeId.ORCHESTRATOR,
                runId = runId,
                procedure = currentProcedure,
                message = message,
                atMillis = System.currentTimeMillis(),
            )
        )
    }

    override val level: Level
        get() = if (server != null) serverLevel else clientLevel
            ?: error("`$currentProcedure` asked for the level, but this client has not joined one")

    override val currentTick: Long get() = tickClock.current

    override suspend fun awaitTicks(count: Int): Unit = tickClock.awaitTicks(count)
}
