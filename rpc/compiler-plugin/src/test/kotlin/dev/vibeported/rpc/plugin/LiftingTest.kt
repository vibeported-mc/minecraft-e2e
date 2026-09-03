package dev.vibeported.rpc.plugin

import dev.vibeported.rpc.NodeId
import dev.vibeported.rpc.NodeInfo
import dev.vibeported.rpc.ProcedureTable
import dev.vibeported.rpc.RpcScope
import dev.vibeported.rpc.CborWireFormat
import dev.vibeported.rpc.Services
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.serializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * That a body really was lifted into a class, and that the class really runs it.
 *
 * Loading the generated table and calling it is the only way to know. Everything short of that --
 * the manifest, an IR dump -- says what the plugin intended, not what the backend produced and the
 * JVM accepted.
 */
class LiftingTest {

    @TempDir
    lateinit var workingDir: File

    @Test
    fun `the generated table owns the body and can run it`() {
        val result = RpcCompilation(workingDir).compile(
            "Sample.kt" to """
                import dev.vibeported.rpc.node
                import dev.vibeported.rpc.rpcCall

                suspend fun caller() {
                    rpcCall(node("a"), 20) { given -> given + 2 }
                }
            """.trimIndent()
        )

        assertTrue(result.succeeded, result.messages)

        // A Kotlin `object`, so the JVM knows it by its INSTANCE field -- exactly how TableRegistry
        // will reach it at run time.
        val table = result.classLoader
            .loadClass("SampleKt_Rpc")
            .getField("INSTANCE")
            .get(null) as ProcedureTable

        assertEquals(setOf("SampleKt.caller/0"), table.procedures())

        // The receiver is resolved from the node's own services, which is what makes an injected
        // client reachable from every body routed there.
        val answer = runBlocking {
            table.invoke("SampleKt.caller/0", servicesOfferingAScope(), listOf(20))
        }
        assertEquals(22, answer)
    }

    /**
     * A node normally provides this for itself; here it is provided by hand.
     *
     * That the generated `invoke` asks for it at all is the point: the receiver comes from the node
     * the body landed on, which is what makes an injected client reachable from every body routed
     * there.
     */
    private fun servicesOfferingAScope(): Services {
        val services = Services()
        services.provide<RpcScope>(object : RpcScope {
            override val node = NodeInfo(NodeId("under-test"))
            override val services get() = services
        })
        return services
    }

    @Test
    fun `arguments and results survive a round trip through the table`() {
        val result = RpcCompilation(workingDir).compile(
            "Wire.kt" to """
                import dev.vibeported.rpc.node
                import dev.vibeported.rpc.rpcCall

                suspend fun caller() {
                    rpcCall(node("a"), "hello", 3) { text, times -> text.repeat(times) }
                }
            """.trimIndent()
        )
        assertTrue(result.succeeded, result.messages)

        val table = result.classLoader
            .loadClass("WireKt_Rpc")
            .getField("INSTANCE")
            .get(null) as ProcedureTable

        val id = "WireKt.caller/0"
        val format = CborWireFormat()

        // Exactly the path a call from another node takes: bytes in, objects out, run, bytes back.
        val encodedArgs = listOf(
            format.encode(String.serializer(), "hello"),
            format.encode(Int.serializer(), 3),
        )
        val decoded = table.decodeArgs(id, encodedArgs, format)
        assertEquals(listOf("hello", 3), decoded)

        val answer = runBlocking { table.invoke(id, servicesOfferingAScope(), decoded) }
        val encoded = table.encodeResult(id, answer, format)!!
        assertEquals("hellohellohello", format.decode(String.serializer(), encoded))
    }

    @Test
    fun `an unknown id is refused by name`() {
        val result = RpcCompilation(workingDir).compile(
            "Other.kt" to """
                import dev.vibeported.rpc.node
                import dev.vibeported.rpc.rpcCall

                suspend fun caller() {
                    rpcCall(node("a")) { 1 }
                }
            """.trimIndent()
        )
        assertTrue(result.succeeded, result.messages)

        val table = result.classLoader
            .loadClass("OtherKt_Rpc")
            .getField("INSTANCE")
            .get(null) as ProcedureTable

        val failure = runCatching {
            runBlocking { table.invoke("nothing.like.it/0", servicesOfferingAScope(), emptyList()) }
        }.exceptionOrNull()

        assertTrue(failure != null, "an unknown id must not be silently ignored")
        assertTrue("nothing.like.it/0" in failure!!.message.orEmpty(), failure.toString())
    }
}
