package dev.vibeported.mc.e2e.rpc

import dev.vibeported.mc.e2e.protocol.NodeId
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

public class UnknownNodeException(node: NodeId) : IllegalStateException("No node connected as $node")

/**
 * Routes envelopes between nodes living in one JVM.
 *
 * By default it still encodes every envelope to text and decodes it again on delivery. That costs
 * nothing at this size and buys the thing that matters: a payload which could not survive a real
 * wire fails here, in a fast unit test, rather than on the day the nodes become separate processes.
 */
public class InMemoryHub(
    private val json: Json = JsonValueCodec.DefaultJson,
    private val simulateWire: Boolean = true,
) {
    private val inboxes = ConcurrentHashMap<NodeId, Channel<Envelope>>()

    public fun connect(node: NodeId): Transport {
        val inbox = inboxes.computeIfAbsent(node) { Channel(Channel.UNLIMITED) }
        return HubTransport(node, inbox)
    }

    private fun route(envelope: Envelope) {
        val inbox = inboxes[envelope.to] ?: throw UnknownNodeException(envelope.to)
        val delivered = if (simulateWire) {
            json.decodeFromString(Envelope.serializer(), json.encodeToString(Envelope.serializer(), envelope))
        } else {
            envelope
        }
        val result = inbox.trySend(delivered)
        check(result.isSuccess) { "Inbox for ${envelope.to} rejected an envelope: $result" }
    }

    public fun shutdown() {
        inboxes.values.forEach { it.close() }
        inboxes.clear()
    }

    private inner class HubTransport(
        override val self: NodeId,
        private val inbox: Channel<Envelope>,
    ) : Transport {
        override suspend fun send(envelope: Envelope): Unit = route(envelope)
        override val incoming: Flow<Envelope> get() = inbox.consumeAsFlow()
        override suspend fun close() {
            inbox.close()
            inboxes.remove(self)
        }
    }
}
