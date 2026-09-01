package dev.vibeported.mc.e2e.tests

import dev.vibeported.mc.e2e.InventorySlot
import dev.vibeported.mc.e2e.MouseButton
import dev.vibeported.mc.e2e.assertBlock
import dev.vibeported.mc.e2e.assertPlayerDead
import dev.vibeported.mc.e2e.attack
import dev.vibeported.mc.e2e.chat
import dev.vibeported.mc.e2e.assertThat
import dev.vibeported.mc.e2e.breakBlock
import dev.vibeported.mc.e2e.client
import dev.vibeported.mc.e2e.giveItem
import dev.vibeported.mc.e2e.isAlive
import dev.vibeported.mc.e2e.lookAt
import dev.vibeported.mc.e2e.lookAtPlayer
import dev.vibeported.mc.e2e.makeScreenshot
import dev.vibeported.mc.e2e.mouseDown
import dev.vibeported.mc.e2e.mouseUp
import dev.vibeported.mc.e2e.parallel
import dev.vibeported.mc.e2e.pixelsPerSecond
import dev.vibeported.mc.e2e.playerInventory
import dev.vibeported.mc.e2e.positionOf
import dev.vibeported.mc.e2e.scroll
import dev.vibeported.mc.e2e.server
import dev.vibeported.mc.e2e.shared
import dev.vibeported.mc.e2e.suite
import dev.vibeported.mc.e2e.teleport
import dev.vibeported.mc.e2e.ui
import dev.vibeported.mc.e2e.timeoutSec
import dev.vibeported.mc.e2e.waitForPlayer
import kotlinx.coroutines.delay
import net.minecraft.core.BlockPos
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Far from spawn and high up, so nothing is there by accident and nothing holds a player up. */
private val FAR_AWAY = BlockPos(100, 200, 200)

/** Long enough that a person watching the two clients can see what the test is doing. */
private val HOLD = 5.seconds

/** Slow on purpose: a drag that finishes in three frames is a drag nobody can check. */
private val DRAG = pixelsPerSecond(150.0)

/** A diamond sword recharges in about 12 ticks, and an uncharged hit barely counts. */
private const val SWING_TICKS = 14
private const val SWINGS = 12

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
                // Just the world for steve: no hotbar, no hearts, no chat across the middle of a
                // screenshot. Alex keeps the interface, so one run shows both.
                ui = false

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
                makeScreenshot("steve looking at alex")
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
                makeScreenshot("alex looking at steve")
            }
        }

        server {
            assertBlock("the server should still have the block it placed", target.get()) {
                it.block == Blocks.GOLD_BLOCK
            }
        }
    }

    e2e("both players equip themselves by dragging, and one mines the block") {
        val target = shared<BlockPos>()

        // Each client says what it ended up holding once its inventory is shut again. Reading the
        // other one is the handshake: neither turns to admire the other mid-drag.
        val steveHolds = shared<String>()
        val alexHolds = shared<String>()

        server {
            waitForPlayer("steve")
            waitForPlayer("alex")

            serverLevel.setBlockAndUpdate(FAR_AWAY, Blocks.GOLD_BLOCK.defaultBlockState())
            target.set(FAR_AWAY)

            // Opposite corners of the inventory on purpose: the two windows are then visibly doing
            // different work, and neither client can pass on the other one's state.
            giveItem("steve", InventorySlot.INV_1_1, ItemStack(Items.DIAMOND_SWORD))
            giveItem("steve", InventorySlot.INV_1_2, ItemStack(Items.SHIELD))
            giveItem("alex", InventorySlot.INV_3_9, ItemStack(Items.DIAMOND_PICKAXE))
            giveItem("alex", InventorySlot.INV_3_8, ItemStack(Items.TORCH, 16))
        }

        parallel {

            client("steve") {
                ui = false
                teleport(target.get().offset(-3, 4, -3), flying = true)

                // The interface comes back on its own the moment the inventory opens: nothing can
                // be dragged across a screen that is not drawn.
                playerInventory {
                    // What the server put there has to have reached this client before a drag can
                    // move it, and saying so means a sync problem cannot masquerade as a bad click.
                    assertSlot("steve should have been given a sword", InventorySlot.INV_1_1, timeoutSec(10)) {
                        it.item == Items.DIAMOND_SWORD
                    }
                    // The long way round, because this is the sequence the API is for: aim, take,
                    // carry, put down. swapSlot is these four calls plus the wait.
                    moveToSlot(InventorySlot.INV_1_1, DRAG)
                    click(MouseButton.LEFT)
                    assertThat("the sword should be on the cursor") { carried.item == Items.DIAMOND_SWORD }

                    // Halfway through the gesture, with the sword riding the cursor: the frame the
                    // overlay exists to make worth taking.
                    moveToSlot(selectedHotbar, DRAG)
                    makeScreenshot("carrying the sword to the main hand")

                    mouseDown(MouseButton.LEFT)
                    makeScreenshot("the drop, with the button down")
                    mouseUp(MouseButton.LEFT)

                    // Two buttons at once over an empty slot, which moves nothing and is the case
                    // that decides whether the indicators really stack. Then a scroll, which leaves
                    // no state behind for anything but the overlay to remember.
                    moveToSlot(InventorySlot.INV_2_5, DRAG)
                    mouseDown(MouseButton.LEFT)
                    mouseDown(MouseButton.RIGHT)
                    makeScreenshot("both buttons held")
                    mouseUp(MouseButton.RIGHT)
                    mouseUp(MouseButton.LEFT)

                    scroll(1.0)
                    makeScreenshot("scrolled up")

                    // Three unhurried seconds across the whole inventory. Nothing is being tested
                    // by it; it is there to be watched, which is the only way to judge whether the
                    // pointer tracks smoothly and lands where it was sent.
                    moveToSlot(InventorySlot.INV_1_1, over = 3.seconds)
                    makeScreenshot("after a slow sweep")

                    assertSlot("the sword should be in the main hand", selectedHotbar) {
                        it.item == Items.DIAMOND_SWORD
                    }

                    swapSlot(InventorySlot.INV_1_2, InventorySlot.OFFHAND)
                    assertSlot("the shield should be in the offhand", InventorySlot.OFFHAND) {
                        it.item == Items.SHIELD
                    }
                }
                // The inventory has closed by here, so what each player is holding is on show.
                steveHolds.set("a sword and a shield")

                log("alex is holding ${alexHolds.get()}")
                lookAtPlayer("alex")
                delay(HOLD)
                makeScreenshot("steve armed, looking at alex")
            }

            client("alex") {
                teleport(target.get().offset(3, 4, 3), flying = true)

                playerInventory {
                    assertSlot("alex should have been given a pickaxe", InventorySlot.INV_3_9, timeoutSec(10)) {
                        it.item == Items.DIAMOND_PICKAXE
                    }

                    swapSlot(InventorySlot.INV_3_9, selectedHotbar, over = 600.milliseconds)
                    assertSlot("the pickaxe should be in the main hand", selectedHotbar) {
                        it.item == Items.DIAMOND_PICKAXE
                    }

                    swapSlot(InventorySlot.INV_3_8, InventorySlot.OFFHAND)
                    assertSlot("the torch should be in the offhand", InventorySlot.OFFHAND) {
                        it.item == Items.TORCH
                    }
                }
                alexHolds.set("a pickaxe and a torch")

                log("steve is holding ${steveHolds.get()}")
                lookAtPlayer("steve")
                delay(HOLD)
                makeScreenshot("alex armed, looking at steve")
            }
        }

        // A client screen alone could be satisfied by a purely local menu, so the drags only count
        // if the server agrees about what each player ended up holding.
        server {
            val steve = waitForPlayer("steve")
            val alex = waitForPlayer("alex")

            assertThat("steve should be holding the sword and the shield") {
                steve.mainHandItem.item == Items.DIAMOND_SWORD &&
                    steve.offhandItem.item == Items.SHIELD
            }
            assertThat("alex should be holding the pickaxe and the torch") {
                alex.mainHandItem.item == Items.DIAMOND_PICKAXE &&
                    alex.offhandItem.item == Items.TORCH
            }
        }

        // Nothing but a keypress that went through handleKeybinds and out as a packet can make the
        // next assertion true, which is what makes this a test of the input path rather than of an
        // API call that happens to remove a block.
        parallel {
            client("alex") {
                // Within arm's length first: the diagonal perch the last step left alex on is nearly
                // six blocks from the gold, and no amount of holding attack reaches that far.
                teleport(target.get().above(2), flying = true)

                // Mining by hand for a moment before handing over to breakBlock, because a button
                // held in the world is the case the overlay has to get right: no screen, no pointer,
                // and attack down for as long as it takes.
                lookAt(target.get())
                mouseDown(MouseButton.LEFT)
                awaitTicks(6)
                makeScreenshot("mining, attack held")
                mouseUp(MouseButton.LEFT)

                breakBlock(target.get(), timeoutSec(20))
            }

            // Steve only watches, and that is the point: a second client saw it go, so the removal
            // was broadcast rather than being one client's local idea of the world.
            client("steve") {
                lookAtPlayer("alex")
                assertBlock("steve should see the gold go too", target.get(), timeoutSec(20)) {
                    it.isAir
                }
            }
        }

        // Steve objects. Both halves of the objection go through the client's own machinery: the
        // words through the chat keybind and screen, the rest through the attack button.
        client("steve") {
            chat("Alex! Leave the gold alone.")

            // A sword hit sends a flying player sailing, so one swing lands and every swing after
            // it finds empty air. Each round therefore closes the distance again once the knockback
            // has played out, and the spacing is the sword's cooldown, since an uncharged hit does
            // a fraction of the damage.
            var swings = 0
            while (swings < SWINGS && isAlive("alex")) {
                val there = positionOf("alex") ?: break
                teleport(there.east(), flying = true)
                lookAtPlayer("alex")
                attack()
                awaitTicks(SWING_TICKS)
                swings++
            }
            log("it took $swings swings")
        }

        server {
            assertBlock("the server should see the block alex mined go away", target.get()) {
                it.isAir
            }
            assertPlayerDead("alex")
        }

        // Long enough to watch the aftermath rather than have the window vanish on the last tick,
        // and one shot each: the same moment from the two ends of a sword.
        parallel {
            client("steve") {
                delay(HOLD)
                makeScreenshot("me stronk")
            }

            client("alex") {
                delay(HOLD)
                makeScreenshot("I was wrong")
            }
        }
    }
}
