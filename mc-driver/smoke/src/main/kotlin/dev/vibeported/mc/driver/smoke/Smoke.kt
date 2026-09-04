package dev.vibeported.mc.driver.smoke

import dev.vibeported.mc.driver.cluster
import dev.vibeported.mc.driver.screenshot
import dev.vibeported.mc.driver.waitForPlayer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.minutes

/**
 * The driver reached from a plain `main`, rather than from a test.
 *
 * The checks moved to `src/test` and are ordinary `@Test` methods now, which is where anything
 * asserting anything belongs. What is left here is the other entry point: a `main` started through
 * [dev.vibeported.mc.driver.launcher.Launch], which is how anything that is *not* a test gets a
 * prepared NeoForge environment -- a one-off script, a demo, a recording somebody wants made.
 *
 * It stays because that path installs itself the same way everything else here does, silently, and
 * would otherwise rot unnoticed the moment the tests stopped using it.
 *
 * `gradlew :mc-driver:smoke:runDriver`.
 */
public object Smoke {

    private const val ALEX = "alex"

    @JvmStatic
    public fun main(args: Array<String>) {
        val failure = runBlocking {
            runCatching {
                cluster {
                    startServer()
                    startClient(ALEX)

                    withTimeout(2.minutes) {
                        waitForPlayer(ALEX)
                        println("mcdriver: wrote " + screenshot(ALEX, "launcher"))
                    }
                }
            }.exceptionOrNull()
        }

        if (failure == null) {
            println("smoke: the launcher path works")
            exitProcess(0)
        }

        // The whole chain, not the top of it: an `ExceptionInInitializerError` carries no message at
        // all and its cause is the only thing that says what went wrong.
        println(
            generateSequence(failure) { it.cause }
                .joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message ?: "(no message)"}" }
        )
        exitProcess(1)
    }
}
