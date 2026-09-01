package dev.vibeported.mc.e2e

import dev.vibeported.mc.e2e.protocol.E2eAssertionError
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer

/**
 * Waits until a player has joined and is actually somewhere, and returns them.
 *
 * "Joined" is not enough on its own: a player exists on the server for a while before their chunk is
 * ticking, and a test that grabs them in that window sees a position it is about to move away from.
 * So this waits for all three -- present, alive, and standing in a chunk the server is ticking.
 *
 * Worth calling even where the harness happens to guarantee a player already. The guarantee today is
 * a chain of coincidences: a client only dials the orchestrator once it has a level and a player, and
 * the orchestrator only starts tests once every client has dialled in. Saying it out loud costs one
 * line and survives that chain changing.
 */
public suspend fun ServerScope.waitForPlayer(
    @MinecraftClientName client: String = DEFAULT_CLIENT,
    mode: AssertMode = timeoutSec(30),
): ServerPlayer {
    var found: ServerPlayer? = null

    val arrived = awaitCondition(mode) {
        found = minecraftServer.playerList.getPlayerByName(client)?.takeIf { it.isReady() }
        found != null
    }

    if (!arrived) {
        throw E2eAssertionError(
            "no player named `$client` was ready on the server within $mode; " +
                "connected: ${serverPlayers.map { it.name.string }}"
        )
    }
    return found!!
}

/** Present, alive, and in a chunk the server is ticking, which is when a position means something. */
private fun ServerPlayer.isReady(): Boolean =
    isAlive && (level() as? ServerLevel)?.isPositionEntityTicking(blockPosition()) == true
