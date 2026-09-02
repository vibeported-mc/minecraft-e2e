package dev.vibeported.mc.e2e.dsl.mc

import net.minecraft.commands.arguments.EntityAnchorArgument
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Mth
import net.minecraft.world.entity.PositionMoveRotation
import net.minecraft.world.phys.Vec3
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One point of a circular flight around another player, aimed at them.
 *
 * Position and rotation together, because they are: a camera that arrives a tick after the body it
 * is attached to wobbles, and the whole point of the orbit is that it does not.
 */
internal fun ServerPlayer.orbitStep(around: ServerPlayer, angle: Double, radius: Double, height: Double) {
    val centre = around.position()
    val position = Vec3(
        centre.x + cos(angle) * radius,
        centre.y + height,
        centre.z + sin(angle) * radius,
    )

    // Aim from where the eyes will be once moved, not from where they are now. Aiming from the old
    // position leaves the camera one tick behind the body, which is exactly the wobble to avoid.
    val eye = position.add(0.0, eyeHeight.toDouble(), 0.0)
    val target = around.getEyePosition(1.0f)
    val (yaw, pitch) = anglesToward(eye, target)

    // One packet carrying position, velocity and rotation. Zero velocity because the orbit is
    // written out in full every tick: any drift the client added would fight it.
    connection.teleport(PositionMoveRotation(position, Vec3.ZERO, yaw, pitch), emptySet())
    setYHeadRot(yaw)
}

/** Where this player sits on a circle around [around] right now, so an orbit can start there. */
internal fun ServerPlayer.angleAround(around: ServerPlayer): Double {
    val centre = around.position()
    return atan2(position().z - centre.z, position().x - centre.x)
}

/** Distance in the horizontal plane, which is what a circle around a player is measured in. */
internal fun ServerPlayer.horizontalDistanceTo(other: ServerPlayer): Double {
    val dx = position().x - other.position().x
    val dz = position().z - other.position().z
    return sqrt(dx * dx + dz * dz)
}

/**
 * The yaw and pitch that look from one point at another.
 *
 * Minecraft's own arithmetic, from `Entity.lookAt`, rather than a re-derivation of it: yaw is
 * measured from south and grows toward west, and getting either convention wrong points the camera
 * somewhere plausible but wrong.
 */
private fun anglesToward(from: Vec3, to: Vec3): Pair<Float, Float> {
    val dx = to.x - from.x
    val dy = to.y - from.y
    val dz = to.z - from.z
    val horizontal = sqrt(dx * dx + dz * dz)
    val pitch = Mth.wrapDegrees((-(Mth.atan2(dy, horizontal) * 180.0f / Math.PI)).toFloat())
    val yaw = Mth.wrapDegrees((Mth.atan2(dz, dx) * 180.0f / Math.PI).toFloat() - 90.0f)
    return yaw to pitch
}

/** Lets a player hang in the air, which an orbit needs before it moves them into it. */
internal fun ServerPlayer.allowFlight() {
    abilities.mayfly = true
    abilities.flying = true
    onUpdateAbilities()
}

/** Aims a player at another player's eyes without moving them. */
internal fun ServerPlayer.faceEyesOf(other: ServerPlayer) {
    lookAt(EntityAnchorArgument.Anchor.EYES, other.getEyePosition(1.0f))
}
