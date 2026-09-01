package dev.vibeported.mc.e2e.tests

import dev.vibeported.mc.e2e.assertBlock
import dev.vibeported.mc.e2e.client
import dev.vibeported.mc.e2e.lookAt
import dev.vibeported.mc.e2e.lookAtPlayer
import dev.vibeported.mc.e2e.parallel
import dev.vibeported.mc.e2e.server
import dev.vibeported.mc.e2e.shared
import dev.vibeported.mc.e2e.suite
import dev.vibeported.mc.e2e.teleport
import dev.vibeported.mc.e2e.timeoutSec
import dev.vibeported.mc.e2e.waitForPlayer
import kotlinx.coroutines.delay
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Blocks
import kotlin.time.Duration.Companion.seconds

/** Far from spawn and high up, so nothing is there by accident and nothing holds a player up. */
private val FAR_AWAY = BlockPos(100, 200, 200)

/** Long enough that a person watching the two clients can see what the test is doing. */
private val HOLD = 5.seconds

val blocks = suite("blocks") {

    e2e("two players fly to a block, watch it, then watch each other") {
        val target = shared<BlockPos>()

        // Each client publishes where it ended up. Reading the other one is what makes the two
        // halves of a parallel step meet: neither turns to look until both have stopped moving.
        val steveAt = shared<BlockPos>()
        val alexAt = shared<BlockPos>()

        server {
            // Nobody is addressed by index here: these are the names the clients run under, and
            // naming them is also what tells the orchestrator to start them.
            waitForPlayer("steve")
            waitForPlayer("alex")

            // This body runs on the server thread, so the level is safe to touch directly.
            serverLevel.setBlockAndUpdate(FAR_AWAY, Blocks.GOLD_BLOCK.defaultBlockState())
            target.set(FAR_AWAY)
            log("placed a gold block at $FAR_AWAY")
        }

        // Both clients at once, which is the only way to have them look at each other while each
        // is somewhere worth looking at.
        parallel {

            client("steve") {
                val block = target.get()
                // Diagonally above rather than straight overhead, so facing the block is a real
                // rotation on both axes and a wrong one would be obvious.
                val stand = block.offset(-3, 4, -3)

                // Both are server operations, called from a client block on purpose: each is
                // relayed client -> orchestrator -> server, and neither returns until this client
                // has actually arrived and turned. Flying, because up here there is no floor.
                teleport(stand, flying = true)
                steveAt.set(stand)

                assertBlock("steve should see the gold block", block, timeoutSec(10)) {
                    it.block == Blocks.GOLD_BLOCK
                }

                lookAt(block)
                delay(HOLD)

                // Not a guess at how long alex takes: the read finishes the moment alex has landed.
                log("alex is at ${alexAt.get()}")
                lookAtPlayer("alex")
                delay(HOLD)
            }

            client("alex") {
                val block = target.get()
                val stand = block.offset(3, 4, 3)

                teleport(stand, flying = true)
                alexAt.set(stand)

                assertBlock("alex should see the gold block", block, timeoutSec(10)) {
                    it.block == Blocks.GOLD_BLOCK
                }

                lookAt(block)
                delay(HOLD)

                log("steve is at ${steveAt.get()}")
                lookAtPlayer("steve")
                delay(HOLD)
            }
        }

        server {
            assertBlock("the server should still have the block it placed", target.get()) {
                it.block == Blocks.GOLD_BLOCK
            }
        }
    }
}
