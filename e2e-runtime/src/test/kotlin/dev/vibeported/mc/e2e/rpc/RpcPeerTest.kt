package dev.vibeported.mc.e2e.rpc

import dev.vibeported.mc.e2e.BlockId
import dev.vibeported.mc.e2e.E2eAssertionError
import dev.vibeported.mc.e2e.NodeId
import dev.vibeported.mc.e2e.SharedId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration.Companion.milliseconds

class RpcPeerTest {

    private val hub = InMemoryHub()

    @Test
    fun `a call is answered by the far side`() = runBlocking {
        coroutineScope {
            val caller = RpcPeer(hub.connect(NodeId.ORCHESTRATOR))
            val callee = RpcPeer(hub.connect(NodeId.SERVER))
            callee.onRequest = { JsonPrimitive("answered") }
            val jobs = listOf(caller.start(this), callee.start(this))

            val result = caller.call(NodeId.SERVER, InvokeBlock("run", BlockId("b"), NodeId.SERVER))
            assertEquals("answered", result?.jsonPrimitive?.content)

            jobs.forEach { it.cancel() }
        }
    }

    @Test
    fun `a failure on the far side arrives with its type and message intact`() = runBlocking {
        coroutineScope {
            val caller = RpcPeer(hub.connect(NodeId.ORCHESTRATOR))
            val callee = RpcPeer(hub.connect(NodeId.SERVER))
            callee.onRequest = { throw E2eAssertionError("the block was not there") }
            val jobs = listOf(caller.start(this), callee.start(this))

            val thrown = assertThrows<RemoteInvocationException> {
                caller.call(NodeId.SERVER, InvokeBlock("run", BlockId("b"), NodeId.SERVER))
            }

            assertEquals("the block was not there", thrown.failure.message)
            assertEquals(NodeId.SERVER, thrown.failure.node)
            // An assertion is a test result, not a harness error, and the distinction survives.
            assertTrue(thrown.failure.assertion)

            jobs.forEach { it.cancel() }
        }
    }

    @Test
    fun `a call that is never answered times out rather than hanging`() = runBlocking {
        coroutineScope {
            val caller = RpcPeer(hub.connect(NodeId.ORCHESTRATOR), callTimeout = 150.milliseconds)
            val callee = RpcPeer(hub.connect(NodeId.SERVER))
            callee.onRequest = { CompletableDeferred<Nothing>().await() }
            val jobs = listOf(caller.start(this), callee.start(this))

            assertThrows<TimeoutCancellationException> {
                caller.call(NodeId.SERVER, InvokeBlock("run", BlockId("b"), NodeId.SERVER))
            }

            jobs.forEach { it.cancel() }
        }
    }

    /**
     * The shape nested blocks rely on: while the orchestrator is waiting on the server, the server
     * calls back and must be served. That only works because handlers run off the receive loop.
     */
    @Test
    fun `a node can call back into the caller while the caller is still waiting`() = runBlocking {
        coroutineScope {
            val orchestrator = RpcPeer(hub.connect(NodeId.ORCHESTRATOR))
            val server = RpcPeer(hub.connect(NodeId.SERVER))

            orchestrator.onRequest = { JsonPrimitive("from the orchestrator") }
            server.onRequest = {
                // Mid-request, ask the caller something and wait for it.
                orchestrator.call(NodeId.ORCHESTRATOR, SharedGet("run", SharedId("x"), "int"))
            }
            val jobs = listOf(orchestrator.start(this), server.start(this))

            val result = orchestrator.call(NodeId.SERVER, InvokeBlock("run", BlockId("b"), NodeId.SERVER))
            assertEquals("from the orchestrator", result?.jsonPrimitive?.content)

            jobs.forEach { it.cancel() }
        }
    }

    @Test
    fun `envelopes survive being encoded and decoded`() = runBlocking {
        coroutineScope {
            val caller = RpcPeer(hub.connect(NodeId.ORCHESTRATOR))
            val callee = RpcPeer(hub.connect(NodeId.SERVER))

            val seen = CompletableDeferred<Payload>()
            callee.onRequest = { request ->
                seen.complete(request.payload)
                null
            }
            val jobs = listOf(caller.start(this), callee.start(this))

            val sent = SharedSet("run", SharedId("pos"), "java.lang.Integer", JsonPrimitive(3))
            caller.call(NodeId.SERVER, sent)

            // The in-memory hub still round-trips every envelope through text, so this is a real
            // check that the payload can cross a wire, not just a reference comparison.
            assertEquals(sent, seen.await())

            jobs.forEach { it.cancel() }
        }
    }
}
