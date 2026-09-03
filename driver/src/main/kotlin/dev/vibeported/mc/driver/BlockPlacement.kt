package dev.vibeported.mc.driver

import com.mojang.brigadier.exceptions.CommandSyntaxException
import net.minecraft.commands.arguments.blocks.BlockStateParser
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState

/**
 * A block written the way the `/setblock` command writes one.
 *
 * `"minecraft:stairs[facing=north]"`, parsed by the game's own parser -- so every form that command
 * accepts works here, a modded block needs nothing added, and a name or a property that does not
 * exist fails with Mojang's message at the line that wrote it. The alternative was a generated
 * builder per block, which is a great deal of code to say the same thing less portably.
 */
internal fun parseBlockState(text: String): BlockState = try {
    BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, text, false).blockState()
} catch (invalid: CommandSyntaxException) {
    throw IllegalArgumentException("mcdriver: `$text` is not a block: ${invalid.message}", invalid)
}

/**
 * Puts one block of a fixture in the world.
 *
 * `UPDATE_KNOWN_SHAPE` is the whole reason this is not `setBlockAndUpdate`. A neighbour update lets a
 * block recompute the very properties that were just named -- a stair asked for `shape=straight`
 * turns into an inner corner because of what was placed beside it, a fence relinks, a chute
 * reorients -- so a fixture built with updates on is not the fixture that was written. Telling the
 * game the shape is already known is what structure placement does, and for the same reason.
 *
 * `UPDATE_CLIENTS` stays, because a client that never hears about the block cannot see it.
 */
internal fun ServerLevel.placeFixtureBlock(pos: BlockPos, state: BlockState) {
    setBlock(pos, state, Block.UPDATE_CLIENTS or Block.UPDATE_KNOWN_SHAPE)
}
