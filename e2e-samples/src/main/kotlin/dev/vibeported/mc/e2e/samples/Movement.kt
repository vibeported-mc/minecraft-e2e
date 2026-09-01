package dev.vibeported.mc.e2e.samples

import dev.vibeported.mc.e2e.assertThat
import dev.vibeported.mc.e2e.client
import dev.vibeported.mc.e2e.server
import dev.vibeported.mc.e2e.shared
import dev.vibeported.mc.e2e.suite
import dev.vibeported.mc.e2e.world.Block
import dev.vibeported.mc.e2e.world.BlockPos
import dev.vibeported.mc.e2e.world.clientWorld
import dev.vibeported.mc.e2e.world.serverWorld
import dev.vibeported.mc.e2e.world.world

val movement = suite("movement") {

    e2e("block moved") {
        var pos by shared<BlockPos>()

        server {
            pos = BlockPos(1, 2, 3)
            serverWorld.setBlock(pos, Block.STONE)
            serverWorld.sync()
            log("placed stone at $pos")
        }

        client {
            clientWorld.awaitBlock(pos, Block.STONE)
            assertThat("the client should see the stone the server placed") {
                world.getBlock(pos) == Block.STONE
            }
        }
    }

    e2e("server hands work to a client mid-step") {
        var pos by shared<BlockPos>()

        server {
            pos = BlockPos(4, 5, 6)
            serverWorld.setBlock(pos, Block.GLASS)
            serverWorld.sync()

            client {
                // Routed server -> orchestrator -> client and awaited, all while the server block
                // above is still suspended part way through.
                clientWorld.awaitBlock(pos, Block.GLASS)
                assertThat("the client should have the glass by now") {
                    world.getBlock(pos) == Block.GLASS
                }
            }

            log("the client confirmed the glass before this block finished")
        }
    }
}
