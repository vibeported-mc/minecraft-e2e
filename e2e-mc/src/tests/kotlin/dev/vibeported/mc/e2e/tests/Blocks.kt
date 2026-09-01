package dev.vibeported.mc.e2e.tests

import dev.vibeported.mc.e2e.assertThat
import dev.vibeported.mc.e2e.client
import dev.vibeported.mc.e2e.mc.firstPlayer
import dev.vibeported.mc.e2e.mc.onClient
import dev.vibeported.mc.e2e.mc.onServer
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
            val placed = onServer {
                val player = playerList.players.firstOrNull()
                    ?: error("nobody had joined the server, so there was no player to stand in front of")

                // Two blocks along the way the player is looking, at their feet.
                val front = player.blockPosition().relative(player.direction, 2)
                overworld().setBlockAndUpdate(front, Blocks.GOLD_BLOCK.defaultBlockState())
                front
            }

            target = placed
            log("placed a gold block at $placed")
        }

        client {
            // The read has to land in a local first: inside onClient the lambda is not suspending,
            // and a shared read is a call to the orchestrator.
            val expected = target

            // Give the server time to send the block change down to this client.
            delay(3.seconds)

            val seen = onClient {
                level?.getBlockState(expected)?.block
            }
            log("the client sees $seen at $expected")

            assertThat("the client should see the gold block the server placed at $expected") {
                seen == Blocks.GOLD_BLOCK
            }

            // Leave it on screen long enough to be watched.
            delay(5.seconds)
        }

        server {
            val placed = target
            val stillThere = onServer {
                overworld().getBlockState(placed).block == Blocks.GOLD_BLOCK
            }
            assertThat("the server should still have the block it placed") { stillThere }
        }
    }
}
