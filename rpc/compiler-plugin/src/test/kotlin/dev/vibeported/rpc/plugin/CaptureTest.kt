package dev.vibeported.rpc.plugin

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The rule that makes lifting sound.
 *
 * A body is taken out of its closure and run somewhere else entirely, so a reference to a local of
 * the enclosing function is not a matter of taste: it is a value that will not exist when the body
 * runs. Catching it as a compile error is the whole reason this plugin has a frontend half.
 */
class CaptureTest {

    @TempDir
    lateinit var workingDir: File

    private val preamble = """
        import dev.vibeported.rpc.RpcScope
        import dev.vibeported.rpc.node
        import dev.vibeported.rpc.rpcCall

        val topLevel: String = "fine, because every node loads the same jars"
    """.trimIndent()

    @Test
    fun `capturing a local is an error that names it`() {
        val result = compile(
            """
            suspend fun caller() {
                val secret = 42
                rpcCall(node("a")) { secret + 1 }
            }
            """
        )

        assertFalse(result.succeeded, "capturing must not compile")
        assertTrue("cannot capture 'secret'" in result.messages, result.messages)
    }

    @Test
    fun `writing to a captured local is an error too`() {
        val result = compile(
            """
            suspend fun caller() {
                var counter = 0
                rpcCall(node("a")) { counter = 1 }
            }
            """
        )

        assertFalse(result.succeeded, "assigning to a capture must not compile")
        assertTrue("cannot capture 'counter'" in result.messages, result.messages)
    }

    @Test
    fun `an argument is how a value gets there`() {
        val result = compile(
            """
            suspend fun caller() {
                val secret = 42
                rpcCall(node("a"), secret) { given -> given + 1 }
            }
            """
        )

        assertTrue(result.succeeded, result.messages)
    }

    @Test
    fun `top level and locally declared values are fine`() {
        val result = compile(
            """
            suspend fun caller() {
                rpcCall(node("a")) {
                    val mine = 1
                    mine + topLevel.length
                }
            }
            """
        )

        assertTrue(result.succeeded, result.messages)
    }

    @Test
    fun `a function reference has no body to lift`() {
        val result = compile(
            """
            suspend fun RpcScope.elsewhere(): Int = 1

            suspend fun caller() {
                rpcCall(node("a"), RpcScope::elsewhere)
            }
            """
        )

        assertFalse(result.succeeded, "a reference must not be accepted as a body")
        assertTrue("must be a lambda written here" in result.messages, result.messages)
    }

    private fun compile(body: String) =
        RpcCompilation(workingDir).compile("Sample.kt" to (preamble + "\n\n" + body.trimIndent()))
}
