package dev.vibeported.mc.e2e.tests

import dev.vibeported.mc.e2e.assertThat
import dev.vibeported.mc.e2e.client
import dev.vibeported.mc.e2e.server
import dev.vibeported.mc.e2e.shared
import dev.vibeported.mc.e2e.suite
import kotlinx.coroutines.delay
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Blocks
import kotlin.time.Duration.Companion.seconds

val blocks = suite("blocks") {

    e2e("a block placed in front of the player shows up on the client") {
        var target by shared<BlockPos>()

        server {
            // This body runs on the server thread, so the level is safe to touch directly.
            val player = serverPlayer
                ?: error("nobody had joined the server, so there was no player to stand in front of")

            val front = player.blockPosition().relative(player.direction, 2)
            serverLevel.setBlockAndUpdate(front, Blocks.GOLD_BLOCK.defaultBlockState())

            // Awaiting hands the server thread back, so the game keeps ticking here, and the rest of
            // this block resumes on it.
            target = front
            log("placed a gold block at $front")
        }

        client {
            val expected = target

            // Give the server time to send the block change down to this client.
            delay(3.seconds)

            val seen = clientLevel?.getBlockState(expected)?.block
            log("the client sees $seen at $expected")

            assertThat("the client should see the gold block the server placed at $expected") {
                seen == Blocks.GOLD_BLOCK
            }

            // Leave it on screen long enough to be watched.
            delay(5.seconds)
        }

        server {
            val placed = target
            assertThat("the server should still have the block it placed") {
                serverLevel.getBlockState(placed).block == Blocks.GOLD_BLOCK
            }
        }
    }
}
