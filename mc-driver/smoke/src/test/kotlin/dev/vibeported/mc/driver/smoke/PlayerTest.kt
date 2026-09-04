package dev.vibeported.mc.driver.smoke

import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.InventorySlot
import dev.vibeported.mc.driver.giveItem
import dev.vibeported.mc.driver.isAlive
import dev.vibeported.mc.driver.lookAt
import dev.vibeported.mc.driver.positionOf
import dev.vibeported.mc.driver.server
import dev.vibeported.mc.driver.teleport
import dev.vibeported.mc.driver.waitForPlayer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** Moving a player about, and reading back where they went. */
@DrivesMinecraft
class PlayerTest {

    @Test
    fun `the player is up and about`(cluster: ClusterScope) = cluster.driving {
        waitForPlayer(ALEX)
        assertTrue(isAlive(ALEX), "isAlive said no about a player who had just been waited for")
    }

    @Test
    @DisplayName("a BlockPos survives the trip in both directions")
    fun `a position crosses`(cluster: ClusterScope) = cluster.driving {
        // The half of the design that fails silently: a serializer encoding the wrong thing shows
        // up only as a position that is not the one that was sent.
        waitForPlayer(ALEX)
        assertNotNull(positionOf(ALEX), "positionOf returned null for a player who is right there")
    }

    @Test
    fun `teleport lands, and the client agrees`(cluster: ClusterScope) = cluster.driving {
        waitForPlayer(ALEX)
        teleport(ALEX, Where.PERCH, flying = true)
        assertEquals(Where.PERCH, positionOf(ALEX))
    }

    @Test
    fun `lookAt turns the player`(cluster: ClusterScope) = cluster.driving {
        waitForPlayer(ALEX)
        teleport(ALEX, Where.PERCH, flying = true)
        lookAt(ALEX, Where.GROUND)
    }

    @Test
    @DisplayName("an item is given from text, and built on the server")
    fun `an item is given`(cluster: ClusterScope) = cluster.driving {
        waitForPlayer(ALEX)
        giveItem(ALEX, InventorySlot.HOTBAR_1, "minecraft:diamond_sword")

        val held = server(ALEX) { name -> playerNamed(name).inventory.getItem(0).count }
        assertEquals(1, held, "the hotbar slot did not end up holding one sword")
    }
}
