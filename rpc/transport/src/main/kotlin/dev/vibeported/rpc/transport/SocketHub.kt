package dev.vibeported.rpc.transport

import dev.vibeported.rpc.NodeId
import dev.vibeported.rpc.NodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

/**
 * The middle of the star.
 *
 * It keeps the roster, pushes it out when it changes, and relays everything else to whoever it is
 * addressed to. It runs no procedures and holds no state about them -- a hub that understood calls
 * would be a bottleneck with opinions.
 *
 * The roster is the reason the star is worth having. Every node gets the same view of who is out
 * there, pushed rather than polled, which is what lets a fan-out predicate run on the node that
 * wrote it instead of having to cross a wire to be answered.
 */
public class SocketHub(requestedPort: Int = 0) {

    private val server = ServerSocket(requestedPort)

    /** The port actually bound, which matters when zero was asked for. */
    public val port: Int = server.localPort

    private val connections = ConcurrentHashMap<NodeId, Connection>()
    private val roster = ConcurrentHashMap<NodeId, NodeInfo>()
    private val lastSeen = ConcurrentHashMap<NodeId, Long>()

    public fun start(scope: CoroutineScope): Job = scope.launch(Dispatchers.IO) {
        while (isActive) {
            val socket = runCatching { server.accept() }.getOrNull() ?: break
            launch { serve(socket) }
        }
    }

    /**
     * Drops a node because something outside knows it is gone.
     *
     * The supervisor that spawned these processes reaps them, and knows before any socket does.
     * This is how that knowledge gets in.
     */
    public suspend fun evict(id: NodeId, why: String) {
        connections.remove(id)?.close()
        lastSeen.remove(id)
        if (roster.remove(id) != null) publishRoster()
    }

    /** Drops nodes that have not been heard from within [within]. @see Heartbeat */
    public suspend fun evictSilent(within: Duration) {
        val deadline = System.nanoTime() - within.inWholeNanoseconds
        roster.keys.toList()
            .filter { (lastSeen[it] ?: 0L) < deadline }
            .forEach { evict(it, "it stopped answering") }
    }

    public suspend fun stop() {
        withContext(Dispatchers.IO) {
            runCatching { server.close() }
            connections.values.forEach { it.close() }
            connections.clear()
            roster.clear()
        }
    }

    private suspend fun serve(socket: Socket) {
        val connection = Connection(socket)
        var speaker: NodeId? = null
        try {
            while (true) {
                val frame = withContext(Dispatchers.IO) { Framing.read(connection.input) } ?: break
                val envelope = EnvelopeCodec.decode(frame)

                if (envelope is Hello) {
                    speaker = envelope.info.id
                    connections[envelope.info.id] = connection
                    roster[envelope.info.id] = envelope.info
                    lastSeen[envelope.info.id] = System.nanoTime()
                    publishRoster()
                    continue
                }
                route(envelope)
            }
        } catch (ignored: Throwable) {
            // A broken connection is not an error here; it is the news that a node has gone, and it
            // is handled the same way as an orderly departure.
        } finally {
            // Whatever ended it -- goodbye, crash, or a cable -- the roster has to say so, because
            // somebody is waiting on a call to this node.
            speaker?.let { evict(it, "its connection closed") }
            connection.close()
        }
    }

    private suspend fun route(envelope: Envelope) {
        when {
            envelope.to != HUB -> connections[envelope.to]?.send(envelope)
            envelope is Goodbye -> evict(envelope.from, "it said goodbye")
            envelope is Heartbeat -> lastSeen[envelope.from] = System.nanoTime()
            else -> Unit
        }
    }

    private suspend fun publishRoster() {
        val nodes = roster.values.toSet()
        connections.forEach { (id, connection) ->
            runCatching { connection.send(Roster(nodes, id)) }
        }
    }

    private class Connection(private val socket: Socket) {
        val input = socket.getInputStream()
        private val output = socket.getOutputStream()
        private val writing = Mutex()

        suspend fun send(envelope: Envelope) {
            val bytes = EnvelopeCodec.encode(envelope)
            withContext(Dispatchers.IO) { writing.withLock { Framing.write(output, bytes) } }
        }

        fun close() {
            runCatching { socket.close() }
        }
    }

}
