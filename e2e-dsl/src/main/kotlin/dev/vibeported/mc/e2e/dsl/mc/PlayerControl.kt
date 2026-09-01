package dev.vibeported.mc.e2e.dsl.mc

import dev.vibeported.mc.e2e.ClientScope
import net.minecraft.client.player.LocalPlayer
import net.minecraft.commands.arguments.EntityAnchorArgument
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sqrt

/*
 * The two halves of moving a player: doing it, on the server, and confirming it, on the client.
 *
 * They used to be joined by payloads of their own -- a ControlPlayer message and an AwaitPlayer
 * message that the transport knew about by name. They are ordinary functions now, called from
 * ordinary `server { }` and `client { }` procedures, and the transport knows about neither.
 */

/** Finds a player on the server, or says who is actually connected. */
internal fun MinecraftServer.playerNamed(client: String) =
    playerList.getPlayerByName(client)
        ?: error(
            "e2e: no player named `$client` is on the server; " +
                "connected: ${playerList.players.map { it.name.string }}"
        )

/**
 * Moves a player. Only the server can.
 *
 * Sends the client a packet rather than changing anything the client owns, so nothing about this
 * has taken effect when it returns -- which is why every caller follows it with [awaitArrival].
 */
internal fun MinecraftServer.movePlayer(client: String, pos: BlockPos, flying: Boolean) {
    val player = playerNamed(client)
    if (flying) {
        // mayfly as well as flying: without the permission the client refuses the state and the
        // player drops out of the sky a tick later.
        player.abilities.mayfly = true
        player.abilities.flying = true
        player.onUpdateAbilities()
    }
    val centre = Vec3.atBottomCenterOf(pos)
    player.teleportTo(centre.x, centre.y, centre.z)
}

internal fun MinecraftServer.turnPlayerToward(client: String, pos: BlockPos) {
    playerNamed(client).lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(pos))
}

internal fun MinecraftServer.turnPlayerTowardPlayer(client: String, target: String) {
    playerNamed(client)
        .lookAt(EntityAnchorArgument.Anchor.EYES, playerNamed(target), EntityAnchorArgument.Anchor.EYES)
}

/**
 * Waits until this client own player is standing where it was sent.
 *
 * Ticks rather than wall clock: a client applies a move on a tick, so counting ticks gives it the
 * same number of chances whatever the frame rate. Returns a description of what it saw instead, or
 * null once it arrived.
 */
internal suspend fun ClientScope.awaitArrival(
    pos: BlockPos,
    timeoutTicks: Int,
): String? = awaitPlayer(
    timeoutTicks,
    met = { it.blockPosition() == pos },
    describe = { "it was at ${it?.blockPosition()}" },
)

/** And until it is facing a block. @see awaitArrival */
internal suspend fun ClientScope.awaitFacing(
    pos: BlockPos,
    timeoutTicks: Int,
): String? = awaitPlayer(
    timeoutTicks,
    met = { offBy(it.eyePosition, Vec3.atCenterOf(pos), it.yRot, it.xRot) <= FACING_TOLERANCE_DEGREES },
    describe = { "it was facing yaw=${it?.yRot?.toInt()} pitch=${it?.xRot?.toInt()}" },
)

/**
 * And until it is facing another player.
 *
 * The other player as this client currently sees them, so a target still settling into position
 * cannot make the check pass against where it used to be.
 */
internal suspend fun ClientScope.awaitFacingPlayer(
    target: String,
    timeoutTicks: Int,
): String? = awaitPlayer(
    timeoutTicks,
    met = { player ->
        val other = clientLevel?.players()?.firstOrNull { it.name.string == target }
        other != null &&
            offBy(player.eyePosition, other.eyePosition, player.yRot, player.xRot) <= FACING_TOLERANCE_DEGREES
    },
    describe = { "it was facing yaw=${it?.yRot?.toInt()} pitch=${it?.xRot?.toInt()}" },
)

private suspend fun ClientScope.awaitPlayer(
    timeoutTicks: Int,
    met: (LocalPlayer) -> Boolean,
    describe: (LocalPlayer?) -> String,
): String? {
    val startedTick = currentTick
    while (true) {
        val current = clientPlayer
        if (current != null && met(current)) return null
        if (currentTick - startedTick >= timeoutTicks) return describe(clientPlayer)
        awaitTicks(1)
    }
}

/**
 * How far the player is from facing [target], in degrees.
 *
 * Worked out on the client from its own eye position rather than handed across, so it cannot be
 * checked against a position the player has since left.
 */
private fun offBy(eye: Vec3, target: Vec3, yaw: Float, pitch: Float): Float {
    val delta = target.subtract(eye)
    val horizontal = sqrt(delta.x * delta.x + delta.z * delta.z)

    val wantedYaw = Math.toDegrees(atan2(delta.z, delta.x)).toFloat() - 90f
    val wantedPitch = (-Math.toDegrees(atan2(delta.y, horizontal))).toFloat()

    return max(
        abs(Mth.wrapDegrees(yaw - wantedYaw)),
        abs(Mth.wrapDegrees(pitch - wantedPitch)),
    )
}

/** A degree is far tighter than a player can aim, and far looser than float noise. */
private const val FACING_TOLERANCE_DEGREES = 1.0f
