package dev.vibeported.rpc.plugin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.lang.reflect.InvocationTargetException

/**
 * What may cross a wire, beyond the obvious.
 *
 * Three cases that a scheme resting on `@Serializable` gets wrong if nobody checks: a value that may
 * be absent, an enum nobody annotated, and a type from a library that will never carry the
 * annotation at all. Each of these is a signature that exists in the harness this framework was
 * extracted from, so getting them wrong means the framework cannot host the thing it came from.
 */
class WireTypesTest {

    @TempDir
    lateinit var workingDir: File

    private val preamble = """
        import dev.vibeported.rpc.RpcScope
        import dev.vibeported.rpc.node
        import dev.vibeported.rpc.rpcCall
    """.trimIndent()

    @Test
    fun `a nullable result round trips as null`() {
        val result = RpcCompilation(workingDir).compile(
            "Suite.kt" to """
                import dev.vibeported.rpc.ProcedureTable
                import dev.vibeported.rpc.TableRegistry
                import dev.vibeported.rpc.node
                import dev.vibeported.rpc.rpcCall
                import dev.vibeported.rpc.testkit.RpcCluster
                import kotlinx.coroutines.CoroutineScope
                import kotlinx.coroutines.Job
                import kotlinx.coroutines.cancel
                import kotlinx.coroutines.runBlocking
                import kotlinx.coroutines.withContext

                object Runner {
                    @JvmStatic
                    fun run(table: ProcedureTable): String = runBlocking {
                        val scope = CoroutineScope(Job())
                        val cluster = RpcCluster(scope)
                        val here = cluster.join("here", tables = TableRegistry.of(listOf(table)))
                        cluster.join("there", tables = TableRegistry.of(listOf(table)))
                        cluster.awaitEveryoneSeesEveryone()

                        // The absent one first: a serializer that refuses null fails here.
                        val nothing = withContext(here) { maybe("there", false) }
                        val something = withContext(here) { maybe("there", true) }
                        scope.cancel()
                        "" + nothing + "/" + something
                    }
                }

                suspend fun maybe(who: String, give: Boolean): String? =
                    rpcCall(node(who), give) { yes -> if (yes) "here" else null }
            """.trimIndent()
        )

        assertTrue(result.succeeded, result.messages)

        val table = result.classLoader.loadClass("SuiteKt_Rpc").getField("INSTANCE").get(null)
        val answer = try {
            result.classLoader.loadClass("Runner")
                .getMethod("run", result.classLoader.loadClass("dev.vibeported.rpc.ProcedureTable"))
                .invoke(null, table)
        } catch (wrapped: InvocationTargetException) {
            throw wrapped.targetException
        }

        assertEquals("null/here", answer)
    }

    @Test
    fun `an enum needs no annotation`() {
        // kotlinx synthesizes a serializer for one from its entry names, so refusing it would be
        // refusing something that works -- and would push every enum on a wire into a wrapper.
        val result = RpcCompilation(workingDir).compile(
            "Sample.kt" to (
                preamble + "\n\n" + """
                enum class Slot { MAIN_HAND, OFF_HAND }

                suspend fun pick(who: String, slot: Slot): Slot =
                    rpcCall(node(who), slot) { chosen -> chosen }
                """.trimIndent()
                )
        )

        assertTrue(result.succeeded, result.messages)
    }

    @Test
    fun `a type the build supplies a serializer for is allowed`() {
        // The case a game needs: `java.io.File` stands in for a type from a library that will never
        // be @Serializable, and that the build promises a serializer for instead.
        val compilation = RpcCompilation(workingDir).apply { contextual = listOf("java.io.File") }
        val result = compilation.compile(
            "Sample.kt" to (
                preamble + "\n\n" + """
                import java.io.File

                suspend fun where(who: String, file: File): File? =
                    rpcCall(node(who), file) { it }
                """.trimIndent()
                )
        )

        assertTrue(result.succeeded, result.messages)
    }

    @Test
    fun `more than one promised type is allowed, which a comma-joined list would not be`() {
        // The option is passed once per type. A comma inside a `-P` value is how the Kotlin CLI
        // separates one plugin option from the next, so a joined list arrives as a second option
        // with no `plugin:` prefix -- and the compiler says only "Wrong plugin option format",
        // naming neither the option nor the plugin. One type never showed it; two do.
        val compilation = RpcCompilation(workingDir).apply {
            contextual = listOf("java.io.File", "java.net.URI")
        }
        val result = compilation.compile(
            "Sample.kt" to (
                preamble + "\n\n" + """
                import java.io.File
                import java.net.URI

                suspend fun both(who: String, file: File, uri: URI): File =
                    rpcCall(node(who), file, uri) { f, _ -> f }
                """.trimIndent()
                )
        )

        assertTrue(result.succeeded, result.messages)
    }

    @Test
    fun `the same type is still refused when the build did not promise one`() {
        // The other half: naming a type in the option is what allows it, so a typo in that list is
        // a compile error rather than a serializer that quietly is not there.
        val result = RpcCompilation(workingDir).compile(
            "Sample.kt" to (
                preamble + "\n\n" + """
                import java.io.File

                suspend fun where(who: String, file: File): File =
                    rpcCall(node(who), file) { it }
                """.trimIndent()
                )
        )

        assertFalse(result.succeeded, "an unpromised library type must not compile")
        assertTrue("'File'" in result.messages, result.messages)
    }
}
