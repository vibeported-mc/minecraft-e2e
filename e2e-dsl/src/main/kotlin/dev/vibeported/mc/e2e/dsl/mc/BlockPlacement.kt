package dev.vibeported.mc.e2e.dsl.mc

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState

/**
 * Puts one block of a fixture in the world.
 *
 * `UPDATE_KNOWN_SHAPE` is the whole reason this is not `setBlockAndUpdate`. A neighbour update lets a
 * block recompute the very properties the test just named -- a stair asked for `shape = straight`
 * turns into an inner corner because of what was placed beside it, a fence relinks, a chute
 * reorients -- so a fixture built with updates on is not the fixture that was written. Telling the
 * game the shape is already known is what structure placement does, and for the same reason.
 *
 * `UPDATE_CLIENTS` stays, because a client that never hears about the block cannot assert on it.
 */
internal fun ServerLevel.placeFixtureBlock(pos: BlockPos, state: BlockState) {
    setBlock(pos, state, Block.UPDATE_CLIENTS or Block.UPDATE_KNOWN_SHAPE)
}
