package dev.vibeported.rpc.transport

import dev.vibeported.rpc.NodeId
import dev.vibeported.rpc.NodeInfo
import dev.vibeported.rpc.PluginGenerated
import dev.vibeported.rpc.ProcedureServer
import dev.vibeported.rpc.ProcedureTable
import dev.vibeported.rpc.Role
import dev.vibeported.rpc.RpcNode
import dev.vibeported.rpc.Services
import dev.vibeported.rpc.TableRegistry
import dev.vibeported.rpc.WireFormat
import dev.vibeported.rpc.dispatchTo
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.builtins.serializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@OptIn(PluginGenerated::class)
class ClusterTest {

    /** Stands in for what the compiler plugin will emit, so the wiring can be tested without it. */
    object EchoTable : ProcedureTable {

        /** Set when anything is serialized, which is how the local path proves it took a shortcut. */
        var serialized = false

        override val procedures = setOf("echo")

        override suspend fun invoke(procedure: String, services: Services, args: List<Any?>): Any? =
            "echo:" + args.single()

        override fun decodeArgs(procedure: String, args: List<ByteArray>, format: WireFormat): List<Any?> {
            serialized = true
            return listOf(format.decode(String.serializer(), args.single()))
        }

        override fun encodeResult(procedure: String, value: Any?, format: WireFormat): ByteArray {
            serialized = true
            return format.encode(String.serializer(), value as String)
        }
    }

    @Test
    fun `a call reaches the node that owns it`() = runTest {
        val fabric = InMemoryFabric()
        val caller = join(fabric, "caller", "driver")
        val worker = join(fabric, "worker", "worker")
        awaitBoth(caller, worker)

        val answer = withContext(caller) {
            dispatchTo(
                target = worker.id,
                procedure = "echo",
                role = null,
                args = listOf("over there"),
                argSerializers = listOf(String.serializer()),
                resultSerializer = String.serializer(),
            )
        }

        assertEquals("echo:over there", answer)
        assertEquals(true, EchoTable.serialized, "a remote call has to cross the wire")
    }

    @Test
    fun `a call to this node never serializes`() = runTest {
        val fabric = InMemoryFabric()
        val only = join(fabric, "alone", "worker")
        EchoTable.serialized = false

        val answer = withContext(only) {
            dispatchTo(
                target = only.id,
                procedure = "echo",
                role = null,
                args = listOf("right here"),
                argSerializers = listOf(String.serializer()),
                resultSerializer = String.serializer(),
            )
        }

        assertEquals("echo:right here", answer)
        // The property that makes it affordable to build an ordinary API out of these calls: a
        // helper reaching for a procedure on the node it is already running on pays nothing.
        assertEquals(false, EchoTable.serialized, "a local call must not encode anything")
    }

    private suspend fun TestScope.join(fabric: InMemoryFabric, id: String, role: String): RpcNode {
        val transport = fabric.connect(NodeId(id))
        val peer = RpcPeer(transport, InMemoryFabric.HUB)
        val membership = LiveMembership()
        val node = RpcNode(
            info = NodeInfo(NodeId(id), setOf(Role(role))),
            tables = TableRegistry.of(listOf(EchoTable)),
            membership = membership,
            outbound = peer,
        )
        peer.onRequest = ProcedureServer(node)::handle
        peer.onRoster = { membership.update(it) }
        peer.start(backgroundScope)
        peer.announce(node.info)
        return node
    }

    private suspend fun awaitBoth(vararg nodes: RpcNode) {
        withTimeout(5_000) {
            while (nodes.any { it.membership.snapshot().size < nodes.size }) yield()
        }
    }
}
