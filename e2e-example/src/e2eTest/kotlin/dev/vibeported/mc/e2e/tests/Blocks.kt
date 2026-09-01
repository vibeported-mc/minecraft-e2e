package dev.vibeported.mc.e2e.tests

import dev.vibeported.mc.e2e.assertBlock
import dev.vibeported.mc.e2e.client
import dev.vibeported.mc.e2e.server
import dev.vibeported.mc.e2e.shared
import dev.vibeported.mc.e2e.suite
import dev.vibeported.mc.e2e.timeoutSec
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Blocks

val blocks = suite("blocks") {

    e2e("a block placed in front of the player shows up on the client") {
        val target = shared<BlockPos>()

        server {
            // This body runs on the server thread, so the level is safe to touch directly.
            val player = serverPlayer
                ?: error("nobody had joined the server, so there was no player to stand in front of")

            val front = player.blockPosition().relative(player.direction, 2)
            serverLevel.setBlockAndUpdate(front, Blocks.GOLD_BLOCK.defaultBlockState())

            target.set(front)
            log("placed a gold block at $front")
        }

        client {
            // No sleep: the wait is the assertion. Replication is the only race here, so waiting for
            // it is what the test should say, rather than guessing at how long it takes.
            assertBlock("the client should see the gold block", target.get(), timeoutSec(5)) {
                it.block == Blocks.GOLD_BLOCK
            }
        }

        server {
            assertBlock("the server should still have the block it placed", target.get()) {
                it.block == Blocks.GOLD_BLOCK
            }
        }
    }
}
