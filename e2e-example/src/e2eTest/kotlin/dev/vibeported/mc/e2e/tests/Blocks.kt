package dev.vibeported.mc.e2e.tests

import dev.vibeported.mc.e2e.assertBlock
import dev.vibeported.mc.e2e.client
import dev.vibeported.mc.e2e.lookAt
import dev.vibeported.mc.e2e.server
import dev.vibeported.mc.e2e.shared
import dev.vibeported.mc.e2e.suite
import dev.vibeported.mc.e2e.teleport
import dev.vibeported.mc.e2e.timeoutSec
import dev.vibeported.mc.e2e.waitForPlayer
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Blocks

/** Far from spawn and high up, so nothing is there by accident and nothing holds the player up. */
private val FAR_AWAY = BlockPos(100, 200, 200)

val blocks = suite("blocks") {

    e2e("a block placed far away shows up once the player flies to it") {
        val target = shared<BlockPos>()

        server {
            // Says out loud what the harness only happens to guarantee: a player is here and ready.
            waitForPlayer()

            // This body runs on the server thread, so the level is safe to touch directly.
            serverLevel.setBlockAndUpdate(FAR_AWAY, Blocks.GOLD_BLOCK.defaultBlockState())
            target.set(FAR_AWAY)
            log("placed a gold block at $FAR_AWAY")
        }

        client {
            val block = target.get()

            // Both are server operations, called from a client block on purpose: each is relayed
            // client -> orchestrator -> server, and neither returns until this client has actually
            // arrived and turned. Flying, because 200 blocks up there is nothing to stand on.
            teleport(block.above(2), flying = true)
            lookAt(block)

            // No sleep: the wait is the assertion. Replication is the only race left here.
            assertBlock("the client should see the gold block", block, timeoutSec(10)) {
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
