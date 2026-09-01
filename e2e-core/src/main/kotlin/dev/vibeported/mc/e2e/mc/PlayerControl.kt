package dev.vibeported.mc.e2e.mc

import dev.vibeported.mc.e2e.rpc.PlayerAction
import dev.vibeported.mc.e2e.rpc.PlayerExpectation
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.commands.arguments.EntityAnchorArgument
import net.minecraft.server.MinecraftServer
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Applies a player action on the server, which is the only side that can.
 *
 * Both of these send the client a packet rather than changing anything the client owns, so neither
 * has taken effect when this returns. Confirming that is [awaitPlayerState], asked of the client.
 */
internal fun MinecraftServer.applyPlayerAction(client: String, action: PlayerAction) {
    val player = playerList.getPlayerByName(client)
        ?: error(
            "e2e: no player named `$client` is on the server; " +
                "connected: ${playerList.players.map { it.name.string }}"
        )

    when (action) {
        is PlayerAction.Teleport -> {
            if (action.flying) {
                // mayfly as well as flying: without the permission the client refuses the state and
                // the player drops out of the sky a tick later.
                player.abilities.mayfly = true
                player.abilities.flying = true
                player.onUpdateAbilities()
            }
            player.teleportTo(action.x, action.y, action.z)
        }

        is PlayerAction.LookAt ->
            player.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3(action.x, action.y, action.z))

        is PlayerAction.LookAtPlayer -> {
            val target = playerList.getPlayerByName(action.target)
                ?: error("e2e: no player named `${action.target}` to look at")
            player.lookAt(EntityAnchorArgument.Anchor.EYES, target, EntityAnchorArgument.Anchor.EYES)
        }
    }
}

/**
 * Waits on a client until its own player has caught up, and describes what it saw if it never did.
 *
 * Ticks rather than wall clock: the client applies a move on a tick, so counting ticks gives it the
 * same number of chances whatever the frame rate.
 */
internal suspend fun awaitPlayerState(
    minecraft: Minecraft,
    tickClock: TickClock,
    expect: PlayerExpectation,
    timeoutTicks: Int,
): String? {
    val startedTick = tickClock.current

    while (true) {
        val player = minecraft.player
        if (player != null && expect.isMetBy(player, minecraft)) return null

        if (tickClock.current - startedTick >= timeoutTicks) {
            return when (expect) {
                is PlayerExpectation.AtBlock -> "the client was at ${player?.blockPosition()}"
                is PlayerExpectation.Facing, is PlayerExpectation.FacingPlayer ->
                    "the client was facing yaw=${player?.yRot?.toInt()} pitch=${player?.xRot?.toInt()}"
            }
        }
        tickClock.awaitTicks(1)
    }
}

private fun PlayerExpectation.isMetBy(player: LocalPlayer, minecraft: Minecraft): Boolean {
    val at = player.blockPosition()
    return when (this) {
        is PlayerExpectation.AtBlock -> at.x == x && at.y == y && at.z == z

        is PlayerExpectation.Facing ->
            offBy(player.eyePosition, Vec3(x, y, z), player.yRot, player.xRot) <= FACING_TOLERANCE_DEGREES

        is PlayerExpectation.FacingPlayer -> {
            // The other player as this client currently sees them, so a target that is still
            // settling into position does not make the check pass against where it used to be.
            val other = minecraft.level?.players()?.firstOrNull { it.name.string == target }
            other != null &&
                offBy(player.eyePosition, other.eyePosition, player.yRot, player.xRot) <= FACING_TOLERANCE_DEGREES
        }
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
