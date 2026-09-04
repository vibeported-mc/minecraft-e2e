package dev.vibeported.mc.driver.smoke

import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.server
import dev.vibeported.mc.driver.worldBuild
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Building the world out of text, and refusing text that is not a block. */
@DrivesMinecraft
class WorldTest {

    @Test
    @DisplayName("blocks are built from text, properties and all")
    fun `blocks are built`(cluster: ClusterScope) = cluster.driving {
        worldBuild {
            at(Where.BUILDING) { "minecraft:stone" }
            at(Where.BUILDING.above()) { "minecraft:oak_stairs[facing=north]" }
            fill(38..42, 64..64, 6..10) { "minecraft:polished_andesite" }
        }

        val built = server { serverLevel.getBlockState(Where.BUILDING.above()).block.descriptionId }
        assertTrue("stairs" in built, "the stairs came out as $built")
    }

    @Test
    @DisplayName("a bad block name fails where it was written")
    fun `a nonsense block is refused`(cluster: ClusterScope) = cluster.driving {
        // The other half of blocks being text: the parser is the game's own, so a name that does not
        // exist fails with Mojang's message rather than arriving as air.
        assertThrows<Exception> {
            worldBuild { at(Where.REJECT) { "minecraft:not_a_real_block" } }
        }
    }
}
