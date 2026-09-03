package dev.vibeported.mc.e2e.dsl

import dev.vibeported.mc.e2e.dsl.mc.allowFlight
import dev.vibeported.mc.e2e.dsl.mc.angleAround
import dev.vibeported.mc.e2e.dsl.mc.horizontalDistanceTo
import dev.vibeported.mc.e2e.dsl.mc.orbitStep
import dev.vibeported.mc.e2e.dsl.mc.playerNamed
import dev.vibeported.mc.e2e.server
import kotlinx.serialization.Serializable
import kotlin.math.PI

/**
 * Flies one player in a circle around another, watching them the whole way.
 *
 * ```kotlin
 * record("alex", "circling.mp4") {
 *     orbitPlayer("alex", around = "steve", overTicks = 200)
 * }
 * ```
 *
 * Driven entirely from the server, a step per tick: each tick computes where on the circle the
 * player should be and which way to look, and sends both in one movement. Nothing is left to the
 * client, so the path is the same on every machine however fast it renders -- which is what makes
 * the resulting video worth comparing against another run of it.
 *
 * The circle starts wherever the orbiting player already stands, so there is no jump into it, and it
 * ends exactly one turn later on the same spot. By default it also keeps whatever distance the two
 * players were already at.
 *
 * Motion is at tick rate, because that is what "server driven" means: twenty new camera positions a
 * second, interpolated by nothing. Spread the turn over enough ticks and each step is small enough
 * not to read as steps -- [overTicks] of 200 turns about 1.8 degrees a tick, which is smooth.
 *
 * @param orbiter the player who flies the circle
 * @param around the player at the centre, who is watched throughout
 * @param overTicks how long one full turn takes; 20 ticks is a second
 * @param radius how far out to fly, or null to keep the distance the two are already at
 * @param height how far above the watched player's feet to fly
 * @param turns how many times around, so 0.5 is a half circle
 */
public suspend fun orbitPlayer(
    orbiter: String,
    around: String,
    overTicks: Int = 200,
    radius: Double? = null,
    height: Double = 2.0,
    turns: Double = 1.0,
) {
    // The shape of the flight, in one value. A body takes up to five arguments, and this wanted six
    // -- but the object is the better reading anyway: `overTicks`, `radius`, `height` and `turns`
    // describe one thing between them, and four bare numbers in a row is a row of four bare numbers.
    val path = OrbitPath(overTicks = overTicks, radius = radius, height = height, turns = turns)

    server(orbiter, around, path) { flier, centre, along ->
        val ticks = along.overTicks
        val distance = along.radius
        val up = along.height
        val laps = along.turns
        val moving = minecraftServer.playerNamed(flier)
        val watched = minecraftServer.playerNamed(centre)

        moving.allowFlight()

        // Start from where they already are, so the first step is a step and not a jump.
        val startAngle = moving.angleAround(watched)
        val sweep = 2.0 * PI * laps
        val orbitRadius = distance ?: moving.horizontalDistanceTo(watched).coerceAtLeast(1.0)

        var tick = 0
        serverTickLoop(maxTicks = ticks + 40) {
            // Recomputed against the watched player every tick rather than against a remembered
            // point: if they move, the circle follows them and the camera stays on them.
            val progress = tick.toDouble() / ticks
            moving.orbitStep(watched, startAngle + sweep * progress, orbitRadius, up)
            tick++ < ticks
        }
    }
}

/**
 * The shape of one orbit, as a value a body can be handed.
 *
 * `@Serializable` because it crosses to the server, which is the rule for everything that does:
 * there is no escape hatch, and a type that cannot be encoded is a compile error rather than a
 * surprise part-way through a test.
 */
@Serializable
public data class OrbitPath(
    /** How long one full turn takes; 20 ticks is a second. */
    public val overTicks: Int = 200,
    /** How far out to fly, or null to keep the distance the two are already at. */
    public val radius: Double? = null,
    /** How far above the watched player's feet to fly. */
    public val height: Double = 2.0,
    /** How many times around, so 0.5 is a half circle. */
    public val turns: Double = 1.0,
)
