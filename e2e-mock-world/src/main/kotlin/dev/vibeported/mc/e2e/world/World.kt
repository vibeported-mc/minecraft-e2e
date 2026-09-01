package dev.vibeported.mc.e2e.world

import kotlinx.serialization.Serializable

/** Stand-in for `net.minecraft.core.BlockPos`. Serializable, because it crosses nodes as a `shared`. */
@Serializable
public data class BlockPos(public val x: Int, public val y: Int, public val z: Int) {
    override fun toString(): String = "($x, $y, $z)"
}

/** Stand-in for a block state. An enum for now; the shape of the API is what is being probed here. */
@Serializable
public enum class Block {
    AIR,
    STONE,
    DIRT,
    GLASS,
}

/** What every node can do with its own view of the world: read it. */
public interface World {
    public fun getBlock(pos: BlockPos): Block

    /** Every non-air block, for assertions and reports. */
    public fun blocks(): Map<BlockPos, Block>
}
