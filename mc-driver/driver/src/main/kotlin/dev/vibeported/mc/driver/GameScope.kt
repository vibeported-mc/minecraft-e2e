package dev.vibeported.mc.driver

import dev.vibeported.rpc.NodeInfo
import dev.vibeported.rpc.Services
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level

/**
 * What a body sees on the game node that runs it.
 *
 * One object serves both roles, and asking a server node for a client accessor throws. That is not a
 * hole in the type separation: which accessors are even nameable was settled at compile time by the
 * receiver of the source lambda, so reaching one of these errors means a body was routed somewhere
 * it was never written for.
 *
 * Made once per node and registered in its [Services], which is how the framework resolves it: a
 * body declares `ServerScope` as its receiver, and the node it lands on is asked for one. That is
 * also why nothing here is per-call -- there is one of these, for every body that ever lands.
 */
internal class GameScope(
    override val node: NodeInfo,
    override val services: Services,
    private val server: MinecraftServer?,
    private val client: Minecraft?,
    private val tickClock: TickClock,
) : ServerScope, ClientScope, WorldBuilderScope, ScreenAccess {

    // -- the server half -------------------------------------------------------------------------

    override val minecraftServer: MinecraftServer
        get() = server ?: error("A server body was routed to ${node.id.value}, which runs no server")

    override val serverLevel: ServerLevel get() = minecraftServer.overworld()

    override val serverPlayers: List<ServerPlayer> get() = minecraftServer.playerList.players

    override fun playerNamed(name: String): ServerPlayer = playerOrNull(name)
        ?: error(
            "No player called `$name` is connected. " +
                serverPlayers.joinToString(prefix = "Here: [", postfix = "]") { it.name.string }
        )

    override fun playerOrNull(name: String): ServerPlayer? =
        serverPlayers.firstOrNull { it.name.string == name }

    override fun worldBuild(body: WorldBuilderScope.() -> Unit) {
        body(this)
    }

    override fun at(x: Int, y: Int, z: Int, block: () -> String) {
        at(BlockPos(x, y, z), block)
    }

    override fun at(pos: BlockPos, block: () -> String) {
        serverLevel.placeFixtureBlock(pos, parseBlockState(block()))
    }

    override fun fill(xs: IntRange, ys: IntRange, zs: IntRange, block: () -> String) {
        // Parsed once, not once per block. A fill is the case where that difference is worth
        // having, and the lambda is a lambda so the caller can name the block once either way.
        val state = parseBlockState(block())
        val level = serverLevel
        for (x in xs) for (y in ys) for (z in zs) level.placeFixtureBlock(BlockPos(x, y, z), state)
    }

    // -- the client half -------------------------------------------------------------------------

    override val clientName: String get() = node.id.value

    override val minecraft: Minecraft
        get() = client ?: error("A client body was routed to ${node.id.value}, which runs no client")

    override val clientLevel: ClientLevel? get() = minecraft.level

    override val clientPlayer: LocalPlayer? get() = minecraft.player

    // -- what both halves have -------------------------------------------------------------------

    override val level: Level
        get() = if (server != null) serverLevel else clientLevel
            ?: error("`${node.id.value}` asked for the level, but this client has not joined one")

    override val currentTick: Long get() = tickClock.current

    override suspend fun awaitTicks(count: Int): Unit = tickClock.awaitTicks(count)
}
