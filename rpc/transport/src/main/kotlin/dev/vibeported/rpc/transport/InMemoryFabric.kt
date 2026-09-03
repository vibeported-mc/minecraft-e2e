package dev.vibeported.rpc.transport

import dev.vibeported.rpc.NodeId
import dev.vibeported.rpc.NodeInfo
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.ConcurrentHashMap

/**
 * A whole cluster inside one process.
 *
 * Not a stub. It is the same star the socket transport forms -- nodes announce themselves to a hub,
 * the hub keeps the roster and pushes it out, requests are relayed to their destination -- with the
 * sockets removed. Everything above it therefore runs unchanged whether the nodes are threads or
 * machines, which is what makes the framework testable without processes.
 */
public class InMemoryFabric {

    private val mailboxes = ConcurrentHashMap<NodeId, Channel<Envelope>>()
    private val roster = ConcurrentHashMap<NodeId, NodeInfo>()

    /** Opens this node's connection. It is not in the roster until it announces itself. */
    public fun connect(id: NodeId): Transport {
        val mailbox = Channel<Envelope>(Channel.BUFFERED)
        mailboxes[id] = mailbox
        return FabricTransport(id, mailbox)
    }

    public suspend fun disconnect(id: NodeId) {
        mailboxes.remove(id)?.close()
        if (roster.remove(id) != null) publishRoster()
    }

    private suspend fun deliver(envelope: Envelope) {
        if (envelope.to == HUB) {
            // The hub's whole job: learn who is there, and tell everyone else.
            if (envelope is Hello) {
                roster[envelope.info.id] = envelope.info
                publishRoster()
            }
            return
        }
        // A message for a node nobody has connected is dropped rather than fatal: the caller is
        // already waiting on a reply that will not come, and its own timeout is the better report.
        mailboxes[envelope.to]?.send(envelope)
    }

    private suspend fun publishRoster() {
        val nodes = roster.values.toSet()
        mailboxes.forEach { (id, mailbox) -> mailbox.send(Roster(nodes, id)) }
    }

    private inner class FabricTransport(
        override val self: NodeId,
        private val mailbox: Channel<Envelope>,
    ) : Transport {

        override suspend fun send(envelope: Envelope): Unit = deliver(envelope)

        override val incoming: Flow<Envelope> = flow { for (envelope in mailbox) emit(envelope) }

        override suspend fun close() {
            disconnect(self)
        }
    }

    public companion object {
        /** Where a `Hello` goes. Not a real node, and never the target of a call. */
        public val HUB: NodeId = NodeId("\$hub")
    }
}
