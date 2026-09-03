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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.serializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The same behaviour as the in-memory fabric, over real sockets.
 *
 * `runBlocking` rather than `runTest`: this is real IO on real threads, and virtual time would only
 * be pretending. It is slower and it is the point -- the framing, the hub and the connection
 * lifecycle are exactly the parts an in-process fabric cannot exercise.
 */
@OptIn(PluginGenerated::class)
class SocketClusterTest {

    class EchoTable : ProcedureTable {
        override val procedures = setOf("echo")

        override suspend fun invoke(procedure: String, services: Services, args: List<Any?>): Any? =
            "echo:" + args.single()

        override fun decodeArgs(procedure: String, args: List<ByteArray>, format: WireFormat): List<Any?> =
            listOf(format.decode(String.serializer(), args.single()))

        override fun encodeResult(procedure: String, value: Any?, format: WireFormat): ByteArray =
            format.encode(String.serializer(), value as String)
    }

    @Test
    fun `a call crosses a real socket`() = runBlocking {
        val hub = SocketHub()
        val scope = CoroutineScope(Job() + Dispatchers.IO)
        hub.start(scope)
        try {
            val caller = join(hub.port, "caller", scope)
            val worker = join(hub.port, "worker", scope)
            awaitRoster(caller, worker)

            val answer = withTimeout(10_000) {
                withContext(caller.node) {
                    dispatchTo(
                        target = worker.node.id,
                        procedure = "echo",
                        role = null,
                        args = listOf("down the wire"),
                        argSerializers = listOf(String.serializer()),
                        resultSerializer = String.serializer(),
                    )
                }
            }

            assertEquals("echo:down the wire", answer)
        } finally {
            scope.cancel()
            hub.stop()
        }
    }

    @Test
    fun `a socket dropping fails the calls waiting on it`() = runBlocking {
        val hub = SocketHub()
        val scope = CoroutineScope(Job() + Dispatchers.IO)
        hub.start(scope)
        try {
            val caller = join(hub.port, "caller", scope)
            val worker = join(hub.port, "worker", scope)
            awaitRoster(caller, worker)

            val call = scope.async {
                runCatching {
                    withContext(caller.node) {
                        dispatchTo(
                            target = worker.node.id,
                            procedure = "never",
                            role = null,
                            args = listOf("x"),
                            argSerializers = listOf(String.serializer()),
                            resultSerializer = String.serializer(),
                        )
                    }
                }
            }
            delay(100)

            // The process died: no goodbye, just a socket that is suddenly not there. The hub reads
            // end-of-stream and everyone finds out from the roster.
            worker.transport.close()

            val failure = withTimeout(10_000) { call.await() }.exceptionOrNull()
            assertTrue(
                failure is NodeGoneException || failure is RemoteCallException,
                "expected the call to be failed, got $failure",
            )
        } finally {
            scope.cancel()
            hub.stop()
        }
    }

    private class Joined(val node: RpcNode, val transport: SocketTransport)

    private suspend fun join(port: Int, id: String, scope: CoroutineScope): Joined {
        val transport = SocketTransport.connect(NodeId(id), "127.0.0.1", port)
        val peer = RpcPeer(transport, HUB)
        val membership = LiveMembership()
        val node = RpcNode(
            info = NodeInfo(NodeId(id)),
            tables = TableRegistry.of(listOf(EchoTable())),
            membership = membership,
            outbound = peer,
        )
        peer.onRequest = ProcedureServer(node)::handle
        peer.onRoster = { membership.update(it) }
        peer.start(scope)
        peer.announce(node.info)
        return Joined(node, transport)
    }

    private suspend fun awaitRoster(vararg nodes: Joined) {
        withTimeout(10_000) {
            while (nodes.any { it.node.membership.snapshot().size < nodes.size }) delay(10)
        }
    }
}
