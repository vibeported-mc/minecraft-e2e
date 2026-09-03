package dev.vibeported.rpc.plugin

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The other half of what moving the decision into the compiler bought.
 *
 * The framework this replaces resolved a codec by class at run time, so a body returning something
 * nothing could encode compiled cleanly and failed in the middle of a test -- once for a
 * `java.io.File`, with nothing in the failure pointing back at the signature that caused it. Here
 * the types are still in view, so the same mistake is a message on the declaration.
 */
class SerializationTest {

    @TempDir
    lateinit var workingDir: File

    private val preamble = """
        import dev.vibeported.rpc.RpcScope
        import dev.vibeported.rpc.node
        import dev.vibeported.rpc.rpcCall
        import kotlinx.serialization.Serializable

        class Opaque(val handle: Long)

        @Serializable
        class Sendable(val handle: Long)
    """.trimIndent()

    @Test
    fun `a result nothing can encode is refused, naming the type`() {
        val result = compile(
            """
            suspend fun caller(): Opaque = rpcCall(node("a")) { Opaque(1) }
            """
        )

        assertFalse(result.succeeded, "an unencodable result must not compile")
        assertTrue("A procedure result cannot be 'Opaque'" in result.messages, result.messages)
        assertTrue("@Serializable" in result.messages, result.messages)
    }

    @Test
    fun `an argument nothing can encode is refused, naming the parameter`() {
        val result = compile(
            """
            suspend fun caller(what: Opaque): Int =
                rpcCall(node("a"), what) { subject -> subject.handle.toInt() }
            """
        )

        assertFalse(result.succeeded, "an unencodable argument must not compile")
        assertTrue("A procedure argument 'subject' cannot be 'Opaque'" in result.messages, result.messages)
    }

    @Test
    fun `a generic type is refused, because only its class survives the lookup`() {
        val result = compile(
            """
            suspend fun caller(): List<Int> = rpcCall(node("a")) { listOf(1, 2) }
            """
        )

        assertFalse(result.succeeded, "a generic result must not compile")
        assertTrue("it has type arguments" in result.messages, result.messages)
    }

    @Test
    fun `primitives and Serializable classes are fine`() {
        val result = compile(
            """
            suspend fun caller(what: Sendable): Sendable =
                rpcCall(node("a"), what, 2) { subject, times -> Sendable(subject.handle * times) }
            """
        )

        assertTrue(result.succeeded, result.messages)
    }

    private fun compile(body: String) =
        RpcCompilation(workingDir).compile("Sample.kt" to (preamble + "\n\n" + body.trimIndent()))
}
