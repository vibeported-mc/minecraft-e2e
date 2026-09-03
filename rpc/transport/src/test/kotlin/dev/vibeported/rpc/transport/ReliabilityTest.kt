package dev.vibeported.rpc.transport

import dev.vibeported.rpc.NodeId
import dev.vibeported.rpc.NodeInfo
import dev.vibeported.rpc.PluginGenerated
import dev.vibeported.rpc.ProcedureServer
import dev.vibeported.rpc.ProcedureTable
import dev.vibeported.rpc.RpcNode
import dev.vibeported.rpc.Services
import dev.vibeported.rpc.TableRegistry
import dev.vibeported.rpc.WireFormat
import dev.vibeported.rpc.dispatchTo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.builtins.serializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(PluginGenerated::class)
class ReliabilityTest {

    /** A procedure that never returns, so the interesting cases are the ones that end it. */
    class HangTable : ProcedureTable {
        val started = CompletableDeferred<Unit>()

        @Volatile
        var cancelledRemotely = false

        override fun procedures() = setOf("hang")

        override suspend fun invoke(procedure: String, services: Services, args: List<Any?>): Any? {
            started.complete(Unit)
            try {
                awaitCancellation()
            } catch (stopped: CancellationException) {
                cancelledRemotely = true
                throw stopped
            }
        }

        override fun decodeArgs(procedure: String, args: List<ByteArray>, format: WireFormat) = emptyList<Any?>()
        override fun encodeResult(procedure: String, value: Any?, format: WireFormat): ByteArray? = null
    }

    @Test
    fun `a node that dies fails the calls sent to it, rather than hanging them`() = runTest {
        val fabric = InMemoryFabric()
        val caller = join(fabric, "caller", HangTable())
        val worker = join(fabric, "worker", HangTable())
        awaitRoster(caller, worker)

        // Caught inside the coroutine rather than at the await: an `async` that fails propagates
        // to its parent, which would take the test down with it before anything could be asserted.
        val call = async {
            runCatching {
                withContext(caller.node) {
                    dispatchTo(worker.node.id, "hang", null, emptyList(), emptyList(), String.serializer())
                }
            }
        }
        worker.tables.started.await()

        // The client crashed. Nothing said goodbye; the connection simply went.
        fabric.disconnect(worker.node.id)

        val failure = call.await().exceptionOrNull()
        assertTrue(failure is NodeGoneException, "got ${failure?.let { it::class.simpleName }}")
        assertEquals(worker.node.id, (failure as NodeGoneException).node)
        assertTrue("hang" in failure.message!!, failure.message!!)
    }

    @Test
    fun `cancelling the caller stops the body on the other node`() = runTest {
        val fabric = InMemoryFabric()
        val caller = join(fabric, "caller", HangTable())
        val worker = join(fabric, "worker", HangTable())
        awaitRoster(caller, worker)

        val call = async {
            withContext(caller.node) {
                dispatchTo(worker.node.id, "hang", null, emptyList(), emptyList(), String.serializer())
            }
        }
        worker.tables.started.await()

        call.cancel()

        // Without a Cancel frame the body would run on, still doing whatever it does to the world,
        // long after anyone stopped waiting for its answer.
        withTimeout(5_000) {
            while (!worker.tables.cancelledRemotely) yield()
        }
        assertTrue(worker.tables.cancelledRemotely)
    }

    private class Joined(val node: RpcNode, val peer: RpcPeer, val tables: HangTable)

    private suspend fun TestScope.join(fabric: InMemoryFabric, id: String, tables: HangTable): Joined {
        val transport = fabric.connect(NodeId(id))
        val peer = RpcPeer(transport, HUB)
        val membership = LiveMembership()
        val node = RpcNode(
            info = NodeInfo(NodeId(id)),
            tables = TableRegistry.of(listOf(tables)),
            membership = membership,
            outbound = peer,
        )
        peer.onRequest = ProcedureServer(node)::handle
        peer.onRoster = { membership.update(it) }
        peer.start(backgroundScope)
        peer.announce(node.info)
        return Joined(node, peer, tables)
    }

    private suspend fun awaitRoster(vararg nodes: Joined) {
        withTimeout(5_000) {
            while (nodes.any { it.node.membership.snapshot().size < nodes.size }) yield()
        }
    }
}
