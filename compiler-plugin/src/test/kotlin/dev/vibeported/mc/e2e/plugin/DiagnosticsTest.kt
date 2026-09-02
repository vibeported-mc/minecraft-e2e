package dev.vibeported.mc.e2e.plugin

import com.tschuchort.compiletesting.KotlinCompilation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The frontend checkers, which are what a test author actually meets.
 *
 * Each case asserts on the message a person reads, not on a diagnostic name, because a rule that
 * fires with an unhelpful explanation has not really done its job.
 */
class DiagnosticsTest {

    @TempDir
    lateinit var workingDir: File

    private fun compile(body: String) = E2eCompilation(workingDir).compile(
        "Suite.kt" to """
            package sample

            import dev.vibeported.mc.e2e.assertThat
            import dev.vibeported.mc.e2e.client
            import dev.vibeported.mc.e2e.server
            import dev.vibeported.mc.e2e.shared
            import dev.vibeported.mc.e2e.parallel
            import dev.vibeported.mc.e2e.suite
            import dev.vibeported.mc.e2e.waitForPlayer
            import dev.vibeported.mc.e2e.ServerScope
            import kotlinx.serialization.Serializable

            @Serializable
            data class BlockPos(val x: Int, val y: Int, val z: Int)

            $body
        """.trimIndent()
    )

    @Test
    fun `a client name computed at runtime is rejected`() {
        val result = compile(
            """
            val who = "steve"

            val s = suite("s") {
                e2e("t") {
                    client(who) { }
                }
            }
            """.trimIndent()
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.messages.contains("must be written out as a string literal"), result.messages)
    }

    /** The rule follows the annotation, so it reaches any function that takes a client name. */
    @Test
    fun `the literal rule applies to every function that names a client`() {
        val result = compile(
            """
            val who = "steve"

            val s = suite("s") {
                e2e("t") {
                    server {
                        waitForPlayer(who)
                    }
                }
            }
            """.trimIndent()
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.messages.contains("must be written out as a string literal"), result.messages)
    }

    @Test
    fun `a parallel group may hold only blocks`() {
        val result = compile(
            """
            val s = suite("s") {
                e2e("t") {
                    parallel {
                        client("steve") { }
                        println("hello")
                    }
                }
            }
            """.trimIndent()
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.messages.contains("parallel group holds blocks alone"), result.messages)
    }

    @Test
    fun `a block capturing a local is rejected by name`() {
        val result = compile(
            """
            val s = suite("s") {
                e2e("t") {
                    val placed = BlockPos(1, 2, 3)
                    server {
                        log(placed.toString())
                    }
                }
            }
            """.trimIndent()
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.messages.contains("cannot capture 'placed'"), result.messages)
        assertTrue(result.messages.contains("shared<T>()"), result.messages)
    }

    @Test
    fun `writing to a captured local is rejected too`() {
        val result = compile(
            """
            val s = suite("s") {
                e2e("t") {
                    var count = 0
                    server {
                        count = 1
                    }
                }
            }
            """.trimIndent()
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.messages.contains("cannot capture 'count'"), result.messages)
    }

    @Test
    fun `a shared value may be used across blocks`() {
        val result = compile(
            """
            val s = suite("s") {
                e2e("t") {
                    val pos = shared<BlockPos>()
                    server {
                        pos.set(BlockPos(1, 2, 3))
                    }
                    client {
                        assertThat { pos.get().x == 1 }
                    }
                }
            }
            """.trimIndent()
        )

        assertTrue(result.succeeded, result.messages)
    }

    @Test
    fun `declarations inside a block are not captures`() {
        val result = compile(
            """
            val s = suite("s") {
                e2e("t") {
                    server {
                        val mine = BlockPos(1, 2, 3)
                        listOf(1, 2).forEach { each ->
                            log("${'$'}mine ${'$'}each")
                        }
                    }
                }
            }
            """.trimIndent()
        )

        assertTrue(result.succeeded, result.messages)
    }

    @Test
    fun `top level declarations are not captures`() {
        val result = E2eCompilation(workingDir).compile(
            "Suite.kt" to """
                package sample

                import dev.vibeported.mc.e2e.server
                import dev.vibeported.mc.e2e.suite

                const val GREETING = "hello"

                val s = suite("s") {
                    e2e("t") {
                        server {
                            log(GREETING)
                        }
                    }
                }
            """.trimIndent()
        )

        assertTrue(result.succeeded, result.messages)
    }

    @Test
    fun `shared must initialise a local in the test body`() {
        val result = compile(
            """
            val s = suite("s") {
                e2e("t") {
                    shared<BlockPos>()
                    server { log("x") }
                }
            }
            """.trimIndent()
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.messages.contains("may only initialise a local"), result.messages)
    }

    @Test
    fun `a test name must be constant`() {
        val result = compile(
            """
            val computed = "not constant"

            val s = suite("s") {
                e2e(computed) {
                    server { log("x") }
                }
            }
            """.trimIndent()
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.messages.contains("must be a compile-time constant"), result.messages)
    }

    @Test
    fun `a block body must be written in place`() {
        val result = compile(
            """
            val s = suite("s") {
                e2e("t") {
                    val body: suspend ServerScope.() -> Unit = { log("x") }
                    server(body = body)
                }
            }
            """.trimIndent()
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.messages.contains("must be a lambda written in place"), result.messages)
    }

    @Test
    fun `a block hidden inside another lambda is rejected`() {
        val result = compile(
            """
            val s = suite("s") {
                e2e("t") {
                    listOf(1, 2).forEach {
                        server { log("which one am I?") }
                    }
                }
            }
            """.trimIndent()
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.messages.contains("cannot be declared inside another lambda"), result.messages)
    }

    @Test
    fun `a client block nested straight inside a server block is fine`() {
        val result = compile(
            """
            val s = suite("s") {
                e2e("t") {
                    server {
                        client { log("routed onward") }
                    }
                }
            }
            """.trimIndent()
        )

        assertTrue(result.succeeded, result.messages)
    }

    @Test
    fun `two tests in one suite may not share a name`() {
        val result = compile(
            """
            val s = suite("s") {
                e2e("same") { server { log("a") } }
                e2e("same") { server { log("b") } }
            }
            """.trimIndent()
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.messages.contains("'same' is declared twice"), result.messages)
    }

    /**
     * The whole reason shared values are handles: mentioning one is an ordinary expression, so it
     * goes wherever an expression goes, including a lambda that will never be inlined.
     */
    @Test
    fun `a shared handle may be captured by a lambda that is not inlined`() {
        val result = compile(
            """
            fun runLater(action: () -> String): String = action()

            val s = suite("s") {
                e2e("t") {
                    val pos = shared<BlockPos>()
                    server {
                        pos.set(BlockPos(1, 2, 3))
                        log(runLater { pos.id.value })
                    }
                }
            }
            """.trimIndent()
        )

        assertTrue(result.succeeded, result.messages)
    }

    @Test
    fun `a shared read inside an inline lambda is fine`() {
        val result = compile(
            """
            val s = suite("s") {
                e2e("t") {
                    val pos = shared<BlockPos>()
                    server {
                        pos.set(BlockPos(1, 2, 3))
                        assertThat { pos.get().x == 1 }
                    }
                }
            }
            """.trimIndent()
        )

        assertTrue(result.succeeded, result.messages)
    }

    @Test
    fun `a statement in a test body is rejected`() {
        val result = compile(
            """
            val s = suite("s") {
                e2e("t") {
                    println("this would never run")
                    server { log("x") }
                }
            }
            """.trimIndent()
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.messages.contains("may only declare shared values"), result.messages)
    }

    @Test
    fun `shared declarations and blocks are the whole of a legal test body`() {
        val result = compile(
            """
            val s = suite("s") {
                e2e("t") {
                    val pos = shared<BlockPos>()
                    server { pos.set(BlockPos(1, 2, 3)) }
                    client { assertThat { pos.get().x == 1 } }
                }
            }
            """.trimIndent()
        )

        assertTrue(result.succeeded, result.messages)
    }
}
