package dev.vibeported.rpc.plugin

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * What marking the *parameter* rather than the function bought.
 *
 * The plugin knows no function by name. Anyone can write a call of their own -- one that shuffles
 * its targets, or one that fixes the scope to something a layer defines -- take a body at an
 * `@RpcLift` parameter, and hand it on. These tests are that promise: a body written at the far end
 * of a chain is still lifted, and the one thing a link may not do is run it.
 */
class ForwardingTest {

    @TempDir
    lateinit var workingDir: File

    private val preamble = """
        import dev.vibeported.rpc.NodeInfo
        import dev.vibeported.rpc.RpcBody0
        import dev.vibeported.rpc.RpcBody1
        import dev.vibeported.rpc.RpcLift
        import dev.vibeported.rpc.RpcScope
        import dev.vibeported.rpc.Services
        import dev.vibeported.rpc.node
        import dev.vibeported.rpc.rpcCall
        import dev.vibeported.rpc.rpcCallIn
    """.trimIndent()

    @Test
    fun `a call of one's own, with a scope of one's own, needs no compiler support`() {
        val result = compile(
            """
            class ClientScope(
                override val node: NodeInfo,
                override val services: Services,
                val label: String,
            ) : RpcScope

            suspend fun <R> client(name: String, @RpcLift body: RpcBody0<ClientScope, R>): R =
                rpcCallIn(node(name), body)

            suspend fun caller(): String = client("alex") { label }
            """
        )

        assertTrue(result.succeeded, result.messages)
    }

    @Test
    fun `a body handed through two links is still lifted at the end`() {
        val result = compile(
            """
            suspend fun <A1, R> middle(name: String, a1: A1, @RpcLift body: RpcBody1<RpcScope, A1, R>): R =
                inner(name, a1, body)

            suspend fun <A1, R> inner(name: String, a1: A1, @RpcLift body: RpcBody1<RpcScope, A1, R>): R =
                rpcCall(node(name), a1, body)

            suspend fun caller(): String = middle("alex", "world") { subject -> "hello " + subject }
            """
        )

        assertTrue(result.succeeded, result.messages)
    }

    @Test
    fun `a link may not run the body itself`() {
        val result = compile(
            """
            suspend fun <R> middle(scope: RpcScope, @RpcLift body: RpcBody0<RpcScope, R>): R =
                with(body) { scope.run() }
            """
        )

        assertFalse(result.succeeded, "running a lifted body must not compile")
        assertTrue("cannot be run here" in result.messages, result.messages)
    }

    @Test
    fun `a body that was never lifted cannot be forwarded`() {
        val result = compile(
            """
            suspend fun caller(): Int {
                val body = RpcBody0<RpcScope, Int> { 1 }
                return rpcCall(node("a"), body)
            }
            """
        )

        assertFalse(result.succeeded, "a body in a variable must not be accepted")
        assertTrue("must be a lambda written here" in result.messages, result.messages)
    }

    private fun compile(body: String) =
        RpcCompilation(workingDir).compile("Sample.kt" to (preamble + "\n\n" + body.trimIndent()))
}
