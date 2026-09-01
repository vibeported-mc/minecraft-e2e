package dev.vibeported.mc.e2e.node

import dev.vibeported.mc.e2e.protocol.BlockId
import dev.vibeported.mc.e2e.BlockScope
import dev.vibeported.mc.e2e.protocol.NodeId
import dev.vibeported.mc.e2e.protocol.SharedId
import dev.vibeported.mc.e2e.rpc.Event
import dev.vibeported.mc.e2e.rpc.InvokeBlock
import dev.vibeported.mc.e2e.rpc.Payload
import dev.vibeported.mc.e2e.rpc.SharedGet
import dev.vibeported.mc.e2e.rpc.SharedSet
import dev.vibeported.mc.e2e.rpc.ValueCodec
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import kotlin.reflect.KClass

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
    private val server: MinecraftServer?,
    private val client: Minecraft?,
    private val codec: ValueCodec,
    private val emitLog: (Event) -> Unit,
    /** Sends a payload to the orchestrator, which relays and answers. */
    private val toOrchestrator: suspend (Payload) -> JsonElement?,
) : BlockScope {

    override val minecraftServer: MinecraftServer
        get() = server ?: error("`$currentBlock` asked for the server, but it is running on $self")

    override val serverLevel: ServerLevel get() = minecraftServer.overworld()

    override val serverPlayers: List<ServerPlayer> get() = minecraftServer.playerList.players

    override val serverPlayer: ServerPlayer? get() = serverPlayers.firstOrNull()

    override val clientIndex: Int get() = self.index

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

    override suspend fun sharedGet(id: SharedId, type: KClass<*>): Any? {
        val encoded = toOrchestrator(SharedGet(runId, id, type.java.name)) ?: JsonNull
        return codec.decode(type, encoded)
    }

    override suspend fun sharedSet(id: SharedId, type: KClass<*>, value: Any?) {
        toOrchestrator(SharedSet(runId, id, type.java.name, codec.encode(type, value)))
    }
}
