package dev.vibeported.mc.driver.smoke

import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.InventorySlot
import dev.vibeported.mc.driver.Key
import dev.vibeported.mc.driver.awaitNoScreen
import dev.vibeported.mc.driver.awaitScreen
import dev.vibeported.mc.driver.client
import dev.vibeported.mc.driver.currentScreen
import dev.vibeported.mc.driver.giveItem
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.press
import dev.vibeported.mc.driver.waitForPlayer
import dev.vibeported.mc.driver.waitForScreen
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Synthetic input reaching a client, and an open screen being driven.
 *
 * The class that shows what sharing a cluster costs. An open screen is state the next test inherits,
 * and the inventory key *toggles* -- so a test that pressed it against an already-open screen would
 * close the inventory and then wait for it forever. Which is exactly what happened the first time
 * these were written. Every test here therefore starts from a known screen and leaves none behind.
 */
@DrivesMinecraft
class ScreenTest {

    @AfterEach
    fun leaveNoScreenOpen(cluster: ClusterScope): Unit = runBlocking {
        closeAnyScreen()
    }

    @Test
    fun `input reaches the client`(cluster: ClusterScope) = cluster.driving {
        waitForPlayer(ALEX)
        openInventory()
    }

    @Test
    @DisplayName("an open screen can be read and driven")
    fun `a screen is driven`(cluster: ClusterScope) = cluster.driving {
        waitForPlayer(ALEX)
        giveItem(ALEX, InventorySlot.HOTBAR_1, "minecraft:diamond_sword")
        openInventory()

        waitForScreen(ALEX, "InventoryScreen") {
            assertEquals(1, stackAt(InventorySlot.HOTBAR_1).count, "the screen cannot see the sword")
        }
    }

    @Test
    fun `closing a screen leaves none open`(cluster: ClusterScope) = cluster.driving {
        waitForPlayer(ALEX)
        openInventory()
        closeAnyScreen()

        assertNull(client(ALEX) { currentScreen() }, "a screen is still open")
    }
}

/**
 * Opens the player inventory, whatever was on screen before.
 *
 * By pressing the key rather than by handing the client a new screen: the keybind, the screen stack
 * and every hook along the way are the part worth exercising. Which is also why it has to start from
 * nothing open -- the key toggles.
 */
private suspend fun openInventory() {
    closeAnyScreen()
    client(ALEX) {
        press(Key.E)
        awaitScreen("InventoryScreen")
    }
}

/** Leaves the client showing the world, and returns once it really is. */
private suspend fun closeAnyScreen() {
    client(ALEX) {
        if (currentScreen() != null) {
            press(Key.ESCAPE)
            awaitNoScreen()
        }
    }
}
