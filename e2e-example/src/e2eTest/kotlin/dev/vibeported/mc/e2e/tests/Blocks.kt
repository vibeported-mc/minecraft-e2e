package dev.vibeported.mc.e2e.tests

import dev.vibeported.mc.e2e.blocks.minecraft
import dev.vibeported.mc.e2e.client
import dev.vibeported.mc.e2e.dsl.InventorySlot
import dev.vibeported.mc.e2e.dsl.MouseButton
import dev.vibeported.mc.e2e.dsl.assertBlock
import dev.vibeported.mc.e2e.dsl.assertPlayerDead
import dev.vibeported.mc.e2e.dsl.assertThat
import dev.vibeported.mc.e2e.dsl.attack
import dev.vibeported.mc.e2e.dsl.breakBlock
import dev.vibeported.mc.e2e.dsl.build
import dev.vibeported.mc.e2e.dsl.chat
import dev.vibeported.mc.e2e.dsl.giveItem
import dev.vibeported.mc.e2e.dsl.isAlive
import dev.vibeported.mc.e2e.dsl.lookAt
import dev.vibeported.mc.e2e.dsl.lookAtPlayer
import dev.vibeported.mc.e2e.dsl.makeScreenshot
import dev.vibeported.mc.e2e.dsl.pixelsPerSecond
import dev.vibeported.mc.e2e.dsl.playerInventory
import dev.vibeported.mc.e2e.dsl.positionOf
import dev.vibeported.mc.e2e.dsl.orbitPlayer
import dev.vibeported.mc.e2e.dsl.record
import dev.vibeported.mc.e2e.dsl.RecordingOptions
import dev.vibeported.mc.e2e.dsl.teleport
import dev.vibeported.mc.e2e.dsl.timeoutSec
import dev.vibeported.mc.e2e.dsl.ui
import dev.vibeported.mc.e2e.dsl.waitForPlayer
import dev.vibeported.mc.e2e.ServerScope
import dev.vibeported.mc.e2e.server
import dev.vibeported.mc.e2e.suite.suite
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.minecraft.core.BlockPos
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.StairBlock
import net.minecraft.world.level.block.state.properties.StairsShape
import kotlin.math.hypot
import kotlin.time.Duration.Companion.seconds

/** Far from spawn and high up, so nothing is there by accident and nothing holds a player up. */
private val FAR_AWAY = BlockPos(100, 200, 200)

/** Long enough that a person watching the two clients can see what the test is doing. */
private val HOLD = 5.seconds

/** Slow on purpose: a drag that finishes in three frames is a drag nobody can check. */
private val DRAG = pixelsPerSecond(150.0)

/** Its own corner of the world, so the ring cannot touch anything the other tests placed. */
private val RING = BlockPos(140, 200, 200)

/** The ring is [RING_SIDE] blocks on a side, so its far edge is at this offset. */
private const val RING_SIDE = 5
private const val RING_LAST = RING_SIDE - 1

val blocks = suite("blocks") {

    e2e("alex flies a circle around steve, filming him the whole way") {
        val target = server {
            waitForPlayer("steve")
            waitForPlayer("alex")
            build { at(FAR_AWAY) { minecraft.gold_block } }
            FAR_AWAY
        }

        // Steve hangs above the block and stays put; alex starts one side of him, which is where
        // the circle will begin and end.
        teleport("steve", target.offset(0, 3, 0), flying = true)
        teleport("alex", target.offset(-6, 3, 0), flying = true)
        // No lookAt here on purpose: the orbit aims at steve on its very first tick, and asserting
        // the aim beforehand races alex client copy of where steve just teleported to.

        // Everything inside is ordinary test DSL, and exactly it is what ends up in the file. The
        // recording is closed before this returns, so the next line could read the video back.
        record("alex", "circling.mp4", RecordingOptions(fps = 30)) {
            // One full turn over ten seconds, driven a step per server tick. Alex keeps steve
            // centred throughout, so the video is a slow pan around a stationary subject.
            orbitPlayer("alex", around = "steve", overTicks = 200)
        }

        // The circle ended where it began, which is the check that it was a circle and not a drift.
        server {
            val alex = serverPlayers.first { it.name.string == "alex" }
            val steve = serverPlayers.first { it.name.string == "steve" }
            val apart = hypot(alex.x - steve.x, alex.z - steve.z)
            assertThat("alex should have come back to the radius he set out at") { apart in 5.0..7.0 }
        }
    }

    e2e("two players fly to a block, watch it, then watch each other") {
        // The block comes back as a return value. It used to be a `shared` handle written on one
        // node and read on another, with a store and a parking read behind it; a procedure that
        // returns something needs none of that.
        val target = server {
            waitForPlayer("steve")
            waitForPlayer("alex")

            build { at(FAR_AWAY) { minecraft.gold_block } }
            log("placed a gold block at $FAR_AWAY")
            FAR_AWAY
        }

        // Both clients at once. Ordinary structured concurrency, because a test body is ordinary
        // code now -- there is no `parallel { }` because there is nothing left for it to do.
        coroutineScope {
            launch { watchTheBlock("steve", "alex", target, target.offset(-3, 4, -3)) }
            launch { watchTheBlock("alex", "steve", target, target.offset(3, 4, 3)) }
        }

        server(target) { pos ->
            assertBlock("the server should still have the block it placed", pos) {
                it.block == Blocks.GOLD_BLOCK
            }
        }
    }

    e2e("both players equip themselves by dragging, and one mines the block") {
        val target = server {
            waitForPlayer("steve")
            waitForPlayer("alex")
            build { at(FAR_AWAY) { minecraft.gold_block } }

            // Opposite corners of the inventory on purpose: the two windows are then visibly doing
            // different work, and neither client can pass on the other one's state.
            giveItem("steve", InventorySlot.INV_1_1, ItemStack(Items.DIAMOND_SWORD))
            giveItem("steve", InventorySlot.INV_1_2, ItemStack(Items.SHIELD))
            giveItem("alex", InventorySlot.INV_3_9, ItemStack(Items.DIAMOND_PICKAXE))
            giveItem("alex", InventorySlot.INV_3_8, ItemStack(Items.TORCH, 16))
            FAR_AWAY
        }

        coroutineScope {
            launch {
                equip("steve", target.offset(-3, 4, -3), InventorySlot.INV_1_1, InventorySlot.INV_1_2)
            }
            launch {
                equip("alex", target.offset(3, 4, 3), InventorySlot.INV_3_9, InventorySlot.INV_3_8)
            }
        }

        // A client screen alone could be satisfied by a purely local menu, so the drags only count
        // if the server agrees about what each player ended up holding.
        server {
            val steve = waitForPlayer("steve")
            val alex = waitForPlayer("alex")

            assertThat("steve should be holding the sword and the shield") {
                steve.mainHandItem.item == Items.DIAMOND_SWORD && steve.offhandItem.item == Items.SHIELD
            }
            assertThat("alex should be holding the pickaxe and the torch") {
                alex.mainHandItem.item == Items.DIAMOND_PICKAXE && alex.offhandItem.item == Items.TORCH
            }
        }

        // Nothing but a keypress that went through handleKeybinds and out as a packet can make the
        // next assertion true, which is what makes this a test of the input path rather than of an
        // API call that happens to remove a block.
        teleport("alex", target.above(2), flying = true)
        client("alex", target) { pos -> breakBlock(pos, timeoutSec(20)) }

        server(target) { pos ->
            assertBlock("the server should see the block alex mined go away", pos) { it.isAir }
        }

        objectAndSettleIt("steve", "alex")
    }

    e2e("a ring of stairs keeps the corners it was given, wrong way round and all") {
        // Four straight edges facing outwards, and four corners deliberately mirrored: each corner
        // is given the corner shape belonging to the *other* hand, so instead of closing the ring it
        // turns away from both edges it should meet.
        //
        // That is the interesting part. A stair works out its own shape from its neighbours, so a
        // block placed with updates on would be quietly straightened into the corner the game thinks
        // it should be -- and the test would pass while testing nothing. `build` places with
        // UPDATE_KNOWN_SHAPE for exactly this reason, and the assertions below are what pins that
        // down: they fail if anything recomputed a shape behind our back.
        server {
            waitForPlayer("steve")

            build(RING) {
                fill(1..<RING_LAST, 0..0, 0..0) { minecraft.oak_stairs { facing = north } }
                fill(1..<RING_LAST, 0..0, RING_LAST..RING_LAST) {
                    minecraft.oak_stairs { facing = south }
                }
                fill(0..0, 0..0, 1..<RING_LAST) { minecraft.oak_stairs { facing = west } }
                fill(RING_LAST..RING_LAST, 0..0, 1..<RING_LAST) {
                    minecraft.oak_stairs { facing = east }
                }

                at(0, 0, 0) { minecraft.oak_stairs { facing = north; shape = outer_right } }
                at(RING_LAST, 0, 0) { minecraft.oak_stairs { facing = north; shape = outer_left } }
                at(0, 0, RING_LAST) { minecraft.oak_stairs { facing = south; shape = outer_left } }
                at(RING_LAST, 0, RING_LAST) {
                    minecraft.oak_stairs { facing = south; shape = outer_right }
                }
            }

            log("placed a ${RING_SIDE}x$RING_SIDE stair ring at $RING with its corners mirrored")
        }

        // Somewhere the whole ring is in frame, held long enough for a person watching to see which
        // way the corners point. Before the assertions on purpose: if one of them fails, the picture
        // of what was actually built is the first thing worth having.
        teleport("steve", RING.offset(RING_LAST / 2, 6, -6), flying = true)
        lookAt("steve", RING.offset(RING_LAST / 2, 0, RING_LAST / 2))
        delay(HOLD)

        client("steve") { makeScreenshot("the stair ring, corners the wrong way round") }

        // Read back on the server, which is the only opinion that settles it: a client draws what it
        // was sent, so a corner that the server had already straightened would look correct there.
        server {
            assertCorner("north-west", RING, StairsShape.OUTER_RIGHT)
            assertCorner("north-east", RING.offset(RING_LAST, 0, 0), StairsShape.OUTER_LEFT)
            assertCorner("south-west", RING.offset(0, 0, RING_LAST), StairsShape.OUTER_LEFT)
            assertCorner("south-east", RING.offset(RING_LAST, 0, RING_LAST), StairsShape.OUTER_RIGHT)

            // And one edge, so a run where every shape came out `straight` cannot pass by accident.
            val middleOfNorthEdge = RING.offset(RING_LAST / 2, 0, 0)
            assertBlock("the middle of the north edge should be a straight stair", middleOfNorthEdge) {
                it.getValue(StairBlock.SHAPE) == StairsShape.STRAIGHT
            }
        }
    }

}

/** Fails unless a corner still holds the mirrored shape it was built with. */
private suspend fun ServerScope.assertCorner(corner: String, pos: BlockPos, shape: StairsShape) {
    assertBlock("the $corner corner should still be $shape", pos) {
        it.getValue(StairBlock.SHAPE) == shape
    }
}

/**
 * One client flies to a perch, checks the block arrived, and turns to the other player.
 *
 * An ordinary function taking ordinary arguments. It holds `client { }` calls, which used to be
 * illegal anywhere but inside a declared test: a procedure id is lexical now, so a helper is as
 * good a home for one as a test body.
 */
private suspend fun watchTheBlock(who: String, other: String, target: BlockPos, perch: BlockPos) {
    // Just the world for steve: no hotbar, no hearts, no chat across a screenshot. Alex keeps the
    // interface, so one run produces both pictures.
    if (who == "steve") client(who) { ui = false }

    teleport(who, perch, flying = true)

    client(who, who, target) { name, pos ->
        assertBlock("$name should see the gold block", pos, timeoutSec(10)) {
            it.block == Blocks.GOLD_BLOCK
        }
    }

    lookAt(who, target)
    delay(HOLD)

    lookAtPlayer(who, other)
    delay(HOLD)

    client(who, who, other) { name, them -> makeScreenshot("$name looking at $them") }
}

/** Drags two things into the hands of one player, the long way round. */
private suspend fun equip(
    who: String,
    perch: BlockPos,
    weapon: InventorySlot,
    offhand: InventorySlot,
) {
    if (who == "steve") client(who) { ui = false }
    teleport(who, perch, flying = true)

    client(who, who, weapon, offhand) { name, weaponSlot, offhandSlot ->
        // The interface comes back on its own the moment the inventory opens: nothing can be
        // dragged across a screen that is not drawn.
        playerInventory {
            assertSlot("$name should have been given something to hold", weaponSlot, timeoutSec(10)) {
                !it.isEmpty
            }

            // The long way round, because this is the sequence the API is for: aim, take, carry,
            // put down. swapSlot is these four calls plus the wait.
            moveToSlot(weaponSlot, DRAG)
            click(MouseButton.LEFT)
            makeScreenshot("carrying the weapon")

            moveToSlot(selectedHotbar, DRAG)
            click(MouseButton.LEFT)

            swapSlot(offhandSlot, InventorySlot.OFFHAND)
            makeScreenshot("armed")
        }
    }
}

/** Steve objects to the mining, in chat and then with a sword. */
private suspend fun objectAndSettleIt(who: String, target: String) {
    client(who, target) { other -> chat("$other! Leave the gold alone.") }

    // A sword hit sends a flying player sailing, so one swing lands and every swing after it finds
    // empty air. Each round closes the distance again once the knockback has played out.
    repeat(SWINGS) {
        val there = client(who, target) { other -> positionOf(other) } ?: return@repeat
        teleport(who, there.east(), flying = true)
        lookAtPlayer(who, target)

        val stillUp = client(who, target) { other ->
            attack()
            awaitTicks(SWING_TICKS)
            isAlive(other)
        }
        if (!stillUp) return@repeat
    }

    server(target) { other -> assertPlayerDead(other) }

    // Long enough to watch the aftermath rather than have the window vanish on the last tick.
    client(who) {
        delay(HOLD)
        makeScreenshot("me stronk")
    }
    client(target) {
        delay(HOLD)
        makeScreenshot("I was wrong")
    }
}

/** A diamond sword recharges in about 12 ticks, and an uncharged hit barely counts. */
private const val SWING_TICKS = 14
private const val SWINGS = 12
