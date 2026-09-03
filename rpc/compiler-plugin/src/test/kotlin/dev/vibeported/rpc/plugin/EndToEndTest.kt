package dev.vibeported.rpc.plugin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.lang.reflect.InvocationTargetException

/**
 * A call written as one would write it, run for real.
 *
 * Everything before this proved a piece: that a body is lifted, that a table serializes, that a
 * cluster routes. This is the first test where none of it is hand-written -- the snippet says
 * `rpcCall`, the plugin rewrites it, and the answer comes back from another node.
 */
class EndToEndTest {

    @TempDir
    lateinit var workingDir: File

    @Test
    fun `a call written as rpcCall reaches the node that runs it`() {
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
                    // The table arrives as a parameter because the snippet cannot name it: it is
                    // generated in the backend, long after the frontend resolved this file.
                    @JvmStatic
                    fun run(table: ProcedureTable): String = runBlocking {
                        val scope = CoroutineScope(Job())
                        val cluster = RpcCluster(scope)
                        val here = cluster.join("here", tables = TableRegistry.of(listOf(table)))
                        cluster.join("there", tables = TableRegistry.of(listOf(table)))
                        cluster.awaitEveryoneSeesEveryone()

                        val answer = withContext(here) { greet("there", "world") }
                        scope.cancel()
                        answer
                    }
                }

                suspend fun greet(who: String, what: String): String =
                    rpcCall(node(who), what) { subject -> "hello " + subject }
            """.trimIndent()
        )

        assertTrue(result.succeeded, result.messages)

        val table = result.classLoader
            .loadClass("SuiteKt_Rpc")
            .getField("INSTANCE")
            .get(null)

        val runner = result.classLoader.loadClass("Runner")
        val answer = try {
            runner.getMethod("run", result.classLoader.loadClass("dev.vibeported.rpc.ProcedureTable"))
                .invoke(null, table)
        } catch (wrapped: InvocationTargetException) {
            throw wrapped.targetException
        }

        // Encoded on one node, decoded on another, run there, and the result encoded back.
        assertEquals("hello world", answer)
    }
}
