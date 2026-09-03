package dev.vibeported.rpc.testkit

import dev.vibeported.rpc.NodeId
import dev.vibeported.rpc.PluginGenerated
import dev.vibeported.rpc.ProcedureTable
import dev.vibeported.rpc.RpcTarget
import dev.vibeported.rpc.Services
import dev.vibeported.rpc.WireFormat
import dev.vibeported.rpc.dispatchEach
import dev.vibeported.rpc.dispatchEachCatching
import dev.vibeported.rpc.transport.RemoteCallException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.serializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(PluginGenerated::class)
class FanOutTest {

    /** What a node calls itself, resolved from that node's own services. */
    class Label(val text: String)

    class ReportTable(private val unwell: Boolean = false) : ProcedureTable {
        override val procedures = setOf("report")

        override suspend fun invoke(procedure: String, services: Services, args: List<Any?>): Any? {
            if (unwell) error("this node is unwell")
            // Resolved on the node that runs it, which is the whole point of node-local services:
            // the same procedure gives a different answer depending on where it lands.
            return services.resolve<Label>().text
        }

        override fun decodeArgs(procedure: String, args: List<ByteArray>, format: WireFormat) = emptyList<Any?>()

        override fun encodeResult(procedure: String, value: Any?, format: WireFormat): ByteArray =
            format.encode(String.serializer(), value as String)
    }

    @Test
    fun `every worker answers, and says who it is`() = runTest {
        val cluster = RpcCluster(backgroundScope)
        val caller = cluster.join("caller", roles = setOf("driver"))
        listOf("one", "two", "three").forEach { name ->
            cluster.join(name, roles = setOf("worker"), tables = listOf(ReportTable()), services = labelled(name))
        }
        cluster.awaitEveryoneSeesEveryone()

        val answers = withContext(caller) {
            dispatchEach(
                target = RpcTarget.Where { true },
                procedure = "report",
                role = "worker",
                args = emptyList(),
                argSerializers = emptyList(),
                resultSerializer = String.serializer(),
            )
        }

        // The caller matched the predicate too, and is not a worker. Narrowing by the body's role
        // is what keeps it out -- it could not have loaded the table anyway.
        assertEquals(setOf(NodeId("one"), NodeId("two"), NodeId("three")), answers.keys)
        assertEquals(listOf("one", "three", "two"), answers.values.sorted())
    }

    @Test
    fun `one bad node fails the whole fan-out`() = runTest {
        val cluster = clusterWithOneBadNode(backgroundScope)
        val caller = cluster.first
        cluster.second.awaitEveryoneSeesEveryone()

        val failure = runCatching {
            withContext(caller) {
                dispatchEach(
                    target = RpcTarget.Where { true },
                    procedure = "report",
                    role = "worker",
                    args = emptyList(),
                    argSerializers = emptyList(),
                    resultSerializer = String.serializer(),
                )
            }
        }.exceptionOrNull()

        assertTrue(failure is RemoteCallException, "got $failure")
        val message = (failure as RemoteCallException).message!!
        assertTrue("unwell" in message, message)
    }

    @Test
    fun `catching lets the healthy nodes still report`() = runTest {
        val cluster = clusterWithOneBadNode(backgroundScope)
        val caller = cluster.first
        cluster.second.awaitEveryoneSeesEveryone()

        val answers = withContext(caller) {
            dispatchEachCatching(
                target = RpcTarget.Where { true },
                procedure = "report",
                role = "worker",
                args = emptyList(),
                argSerializers = emptyList(),
                resultSerializer = String.serializer(),
            )
        }

        assertEquals(3, answers.size)
        assertEquals(1, answers.values.count { it.isFailure })
        // The distinction that earns the second variant: a node being unreachable is information,
        // not necessarily the end of the matter.
        assertEquals(listOf("one", "three"), answers.values.mapNotNull { it.getOrNull() }.sorted())
    }

    private suspend fun clusterWithOneBadNode(scope: kotlinx.coroutines.CoroutineScope) =
        RpcCluster(scope).let { cluster ->
            val caller = cluster.join("caller", roles = setOf("driver"))
            cluster.join("one", setOf("worker"), listOf(ReportTable()), labelled("one"))
            cluster.join("two", setOf("worker"), listOf(ReportTable(unwell = true)), labelled("two"))
            cluster.join("three", setOf("worker"), listOf(ReportTable()), labelled("three"))
            caller to cluster
        }

    private fun labelled(name: String) = Services().apply { provide(Label(name)) }
}
