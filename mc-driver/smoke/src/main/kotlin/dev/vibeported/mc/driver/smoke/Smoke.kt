package dev.vibeported.mc.driver.smoke

import dev.vibeported.mc.driver.InventorySlot
import dev.vibeported.mc.driver.Key
import dev.vibeported.mc.driver.UiLayer
import dev.vibeported.mc.driver.awaitScreen
import dev.vibeported.mc.driver.captureDirectory
import dev.vibeported.mc.driver.client
import dev.vibeported.mc.driver.cluster
import dev.vibeported.mc.driver.connectedClients
import dev.vibeported.mc.driver.currentScreen
import dev.vibeported.mc.driver.giveItem
import dev.vibeported.mc.driver.isAlive
import dev.vibeported.mc.driver.lookAt
import dev.vibeported.mc.driver.moveMouseBy
import dev.vibeported.mc.driver.positionOf
import dev.vibeported.mc.driver.press
import dev.vibeported.mc.driver.record
import dev.vibeported.mc.driver.screenshot
import dev.vibeported.mc.driver.server
import dev.vibeported.mc.driver.setUiLayer
import dev.vibeported.mc.driver.teleport
import dev.vibeported.mc.driver.waitForPlayer
import dev.vibeported.mc.driver.waitForScreen
import dev.vibeported.mc.driver.worldBuild
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.minecraft.core.BlockPos
import java.io.File
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds

/**
 * Drives a real server and a real client, once, and says whether each verb worked.
 *
 * Not a test framework and not a test: no assertions library, no report, no retries. It is the one
 * thing unit tests cannot do, which is prove that the mod loads, the mixins apply, the input gate
 * installs, and a value survives the trip -- all of which happen by side effect at startup and all
 * of which fail silently.
 *
 * Run it with `gradlew :mc-driver:smoke:runDriver`.
 */
public object Smoke {

    /** Somewhere on the flat world's surface with room around it. */
    private val GROUND = BlockPos(8, 65, 8)
    private val PERCH = BlockPos(8, 70, 8)

    @JvmStatic
    public fun main(args: Array<String>) {
        val failures = runBlocking { drive() }

        if (failures.isEmpty()) {
            println("smoke: everything worked")
            exitProcess(0)
        }
        println("smoke: ${failures.size} step(s) failed")
        failures.forEach { (step, failure) -> println("  $step: $failure") }
        exitProcess(1)
    }

    private suspend fun drive(): List<Pair<String, String>> {
        val failures = mutableListOf<Pair<String, String>>()

        // Each step is attempted even when an earlier one failed. A smoke run exists to tell you
        // *which* parts of a driver work, and stopping at the first problem answers that question
        // for one verb and leaves the rest unknown.
        suspend fun step(name: String, body: suspend () -> Unit) {
            print("smoke: $name ... ")
            try {
                withTimeout(60.seconds) { body() }
                println("ok")
            } catch (failure: Throwable) {
                println("FAILED")
                failures += name to describe(failure)
            }
        }

        cluster {
            startServer()
            startClient(ALEX)

            step("the roster knows the client") {
                val clients = connectedClients()
                check(ALEX in clients) { "connectedClients() said $clients" }
            }

            step("a server body runs") {
                val name = server { minecraftServer.serverVersion }
                check(name.isNotBlank()) { "the server reported no version" }
            }

            step("a client body runs") {
                val level = client(ALEX) { clientLevel?.dimension()?.toString() }
                check(level != null) { "the client is in no level" }
            }

            step("the player is up and about") { waitForPlayer(ALEX) }

            step("a BlockPos crosses both ways") {
                // The half of the design that fails silently: a serializer that encodes the wrong
                // thing is only visible as a position that is not the one that was sent.
                val seen = positionOf(ALEX)
                check(seen != null) { "positionOf returned null for a player who is right there" }
            }

            step("blocks are built from text") {
                worldBuild {
                    at(GROUND) { "minecraft:stone" }
                    at(GROUND.above()) { "minecraft:oak_stairs[facing=north]" }
                    fill(6..10, 64..64, 6..10) { "minecraft:polished_andesite" }
                }
                val built = server { serverLevel.getBlockState(GROUND.above()).block.descriptionId }
                check("stairs" in built) { "the stairs came out as $built" }
            }

            step("a bad block name fails where it was written") {
                val complained = runCatching {
                    worldBuild { at(GROUND.below()) { "minecraft:not_a_real_block" } }
                }.exceptionOrNull()
                check(complained != null) { "a nonsense block was accepted" }
            }

            step("teleport lands, and the client agrees") {
                teleport(ALEX, PERCH, flying = true)
                check(positionOf(ALEX) == PERCH) { "the server has them at ${positionOf(ALEX)}" }
            }

            step("lookAt turns the player") { lookAt(ALEX, GROUND) }

            step("the player is alive") {
                check(isAlive(ALEX)) { "isAlive said no" }
            }

            step("an item is given from text") {
                giveItem(ALEX, InventorySlot.HOTBAR_1, "minecraft:diamond_sword")
                val held = server(ALEX) { name -> playerNamed(name).inventory.getItem(0).count }
                check(held == 1) { "the slot holds $held items" }
            }

            step("input reaches the client") {
                client(ALEX) { press(Key.E) }
            }

            step("a screen opens and can be driven") {
                client(ALEX) { awaitScreen("InventoryScreen") }
                try {
                    waitForScreen(ALEX, "InventoryScreen") {
                        val carried = stackAt(InventorySlot.HOTBAR_1).count
                        check(carried == 1) { "the screen sees $carried in the hotbar" }
                    }
                } finally {
                    // Closed even when the check failed, so one bad step does not decide the next.
                    client(ALEX) { press(Key.ESCAPE) }
                }
            }

            step("the current screen is readable") {
                val open = client(ALEX) { currentScreen() }
                check(open == null) { "a screen is still open: $open" }
            }

            step("the interface can be hidden") {
                setUiLayer(ALEX, UiLayer.GUI, false)
                setUiLayer(ALEX, UiLayer.GUI, true)
            }

            step("a screenshot lands on disk") {
                val path = screenshot(ALEX, "smoke")
                check(File(path).isFile) { "$path was not written" }
            }

            step("a recording lands on disk") {
                // Something worth watching for a second or two, because a recording of a still
                // frame proves the encoder started and nothing else. The frames are taken on the
                // client's render thread and never reach the CPU, so this is also the only step
                // that exercises the mixin on `GameRenderer`.
                record(ALEX, "smoke.mp4") {
                    teleport(ALEX, PERCH.above(6), flying = true)
                    client(ALEX) { moveMouseBy(240.0, 0.0, over = 1.seconds) }
                    teleport(ALEX, PERCH, flying = true)
                    lookAt(ALEX, GROUND)
                }

                // Where `ScreenRecorder` files one: `<capture dir>/recordings/<client>/<name>`.
                val file = File(File(File(captureDirectory(), "recordings"), ALEX), "smoke.mp4")
                check(file.isFile) { "$file was not written" }
                check(file.length() > 0) { "$file is empty, so nothing was encoded into it" }
                println("smoke:   recorded ${file.length() / 1024} KiB to $file")
            }
        }

        return failures
    }

    /**
     * The whole chain, not the top of it.
     *
     * An `ExceptionInInitializerError` carries no message at all and its cause is the only thing
     * that says what went wrong -- which cost a run to learn, so it is written down here.
     */
    private fun describe(failure: Throwable): String = generateSequence(failure) { it.cause }
        .joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message ?: "(no message)"}" }

    private const val ALEX = "alex"
}
