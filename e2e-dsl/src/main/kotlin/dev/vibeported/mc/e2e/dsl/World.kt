package dev.vibeported.mc.e2e.dsl

import dev.vibeported.mc.e2e.ProcedureDsl
import dev.vibeported.mc.e2e.ServerScope
import dev.vibeported.mc.e2e.dsl.mc.placeFixtureBlock
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel

/**
 * Builds a fixture: a handful of blocks, placed where the test wants them.
 *
 * Not a suspending call and not a procedure, and that is the point. It is an ordinary extension on
 * [ServerScope], so it runs inside whatever `server { }` already had the game thread, places every
 * block in that one visit and returns. A `build` that suspended per block would cost a tick each --
 * the server ticks between suspensions, which is what makes a hundred-block fixture take five
 * seconds instead of none -- and a `build` that marshalled its blocks would need a codec for a list
 * of positions and states that the transport does not have.
 *
 * ```
 * server {
 *     build(FAR_AWAY) {
 *         at(0, 0, 0) { minecraft.hopper { facing = down } }
 *         at(0, 1, 0) { minecraft.stone }
 *     }
 * }
 * ```
 *
 * [origin] is where the fixture goes, and coordinates inside are offsets from it. Left out, the
 * origin is the world origin and the coordinates read as absolute positions -- so a one-off block
 * needs no ceremony, and a fixture that should move only names a different origin.
 */
public fun ServerScope.build(origin: BlockPos = BlockPos.ZERO, body: BuildScope.() -> Unit) {
    BuildScope(serverLevel, origin).body()
}

/** @see build */
public fun ServerScope.build(x: Int, y: Int, z: Int, body: BuildScope.() -> Unit): Unit =
    build(BlockPos(x, y, z), body)

/**
 * Where a fixture is described.
 *
 * Blocks are placed as they are named rather than collected and placed at the end. The two are the
 * same to a test -- nothing observes the world between one line of a fixture and the next, since
 * this never suspends -- and placing as we go means a failure names the block that failed instead of
 * a batch containing it.
 */
@ProcedureDsl
public class BuildScope internal constructor(
    private val level: ServerLevel,
    private val origin: BlockPos,
) {

    /** Places one block, at [origin] plus these offsets. */
    public fun at(x: Int, y: Int, z: Int, block: () -> BlockSpec) {
        level.placeFixtureBlock(origin.offset(x, y, z), block().state)
    }

    /** @see at */
    public fun at(pos: BlockPos, block: () -> BlockSpec): Unit = at(pos.x, pos.y, pos.z, block)

    /**
     * Places the same block across a box, ends included.
     *
     * The lambda runs once per position rather than once for the box, so a fixture can vary what it
     * places without the DSL needing a second form for the case.
     */
    public fun fill(xs: IntRange, ys: IntRange, zs: IntRange, block: () -> BlockSpec) {
        for (x in xs) for (y in ys) for (z in zs) at(x, y, z, block)
    }
}
