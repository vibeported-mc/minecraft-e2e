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
            import dev.vibeported.mc.e2e.suite
            import dev.vibeported.mc.e2e.NodeScope
            import dev.vibeported.mc.e2e.world.BlockPos

            $body
        """.trimIndent()
    )

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
                    var pos by shared<BlockPos>()
                    server {
                        pos = BlockPos(1, 2, 3)
                    }
                    client {
                        assertThat { pos.x == 1 }
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
    fun `shared must be a delegate`() {
        val result = compile(
            """
            val s = suite("s") {
                e2e("t") {
                    val pos = shared<BlockPos>()
                    server { log("x") }
                }
            }
            """.trimIndent()
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.messages.contains("may only be used as"), result.messages)
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
                    val body: suspend NodeScope.() -> Unit = { log("x") }
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

    @Test
    fun `two shared values in one test may not share a name`() {
        val result = compile(
            """
            val s = suite("s") {
                e2e("t") {
                    var pos by shared<BlockPos>()
                    var pos2 by shared<BlockPos>()
                    server { pos = BlockPos(1, 1, 1) }
                }
            }
            """.trimIndent().replace("pos2", "pos")
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.messages.contains("declared twice"), result.messages)
    }
}
