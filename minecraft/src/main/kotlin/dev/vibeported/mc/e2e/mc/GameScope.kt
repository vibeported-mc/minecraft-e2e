package dev.vibeported.mc.e2e.mc

import dev.vibeported.mc.e2e.ClientScope
import dev.vibeported.mc.e2e.CurrentTest
import dev.vibeported.mc.e2e.DEFAULT_CLIENT
import dev.vibeported.mc.e2e.LogLine
import dev.vibeported.mc.e2e.ServerScope
import dev.vibeported.mc.e2e.reportLog
import dev.vibeported.rpc.NodeInfo
import dev.vibeported.rpc.Services
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
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
 * body declares `ServerScope` as its receiver, and the node it lands on is asked for one.
 */
internal class GameScope(
    override val node: NodeInfo,
    override val services: Services,
    private val server: MinecraftServer?,
    private val client: Minecraft?,
    private val tickClock: TickClock,
    /** Where a log line is sent from, so writing one never blocks the body that wrote it. */
    private val telemetry: CoroutineScope,
) : ServerScope, ClientScope {

    private val test get() = services.resolve(CurrentTest::class).value

    override val testName: String get() = test.testName
    override val runId: String get() = test.runId

    override fun log(message: String) {
        val line = LogLine(
            node = node.id.value,
            test = test.testName,
            atMillis = System.currentTimeMillis(),
            message = message,
        )
        // Launched, not awaited. Reaching the orchestrator is a round trip now that there is no
        // fire-and-forget frame, and a body should not wait on its own logging to continue.
        telemetry.launch { runCatching { reportLog(line) } }
    }

    override val minecraftServer: MinecraftServer
        get() = server ?: error("A server body was routed to ${node.id.value}, which runs no server")

    override val serverLevel: ServerLevel get() = minecraftServer.overworld()
    override val serverPlayers: List<ServerPlayer> get() = minecraftServer.playerList.players
    override val serverPlayer: ServerPlayer? get() = serverPlayers.firstOrNull()

    override val clientName: String get() = node.id.value

    override val minecraft: Minecraft
        get() = client ?: error("A client body was routed to ${node.id.value}, which runs no client")

    override val clientLevel: ClientLevel? get() = minecraft.level
    override val clientPlayer: LocalPlayer? get() = minecraft.player

    override val thisClient: String get() = if (client != null) node.id.value else DEFAULT_CLIENT

    override val level: Level
        get() = if (server != null) serverLevel else clientLevel
            ?: error("`${node.id.value}` asked for the level, but this client has not joined one")

    override val currentTick: Long get() = tickClock.current

    override suspend fun awaitTicks(count: Int): Unit = tickClock.awaitTicks(count)
}
