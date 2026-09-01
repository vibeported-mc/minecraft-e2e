package dev.vibeported.mc.e2e.rpc

import dev.vibeported.mc.e2e.NodeId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap

/**
 * Length-prefixed JSON frames.
 *
 * A newline-delimited protocol would be simpler, but a stack trace inside a failure payload is
 * exactly the sort of thing that carries newlines, so the length goes on the front instead.
 */
internal object Frames {
    fun write(out: DataOutputStream, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        synchronized(out) {
            out.writeInt(bytes.size)
            out.write(bytes)
            out.flush()
        }
    }

    /** Returns null at end of stream, which is how a node leaving is told from a node failing. */
    fun read(input: DataInputStream): String? = try {
        val size = input.readInt()
        require(size in 0..MAX_FRAME) { "Refusing a $size byte frame" }
        val bytes = ByteArray(size)
        input.readFully(bytes)
        String(bytes, Charsets.UTF_8)
    } catch (end: EOFException) {
        null
    } catch (closed: SocketException) {
        null
    }

    private const val MAX_FRAME = 64 * 1024 * 1024
}

/**
 * A game process's end of the wire.
 *
 * It announces itself with [Hello] before anything else, because the orchestrator accepts a socket
 * before it can know which node dialled in.
 */
public class SocketNodeTransport(
    override val self: NodeId,
    private val socket: Socket,
    private val json: Json = JsonValueCodec.DefaultJson,
) : Transport {

    private val out = DataOutputStream(socket.getOutputStream().buffered())
    private val input = DataInputStream(socket.getInputStream().buffered())
    private val inbox = Channel<Envelope>(Channel.UNLIMITED)

    init {
        socket.tcpNoDelay = true
        Frames.write(out, json.encodeToString(Envelope.serializer(), Hello(self)))
    }

    /** Pumps the socket into [incoming]. Blocking reads, so it belongs on the IO dispatcher. */
    public fun start(scope: CoroutineScope): Job = scope.launch(Dispatchers.IO) {
        try {
            while (isActive) {
                val frame = Frames.read(input) ?: break
                inbox.send(json.decodeFromString(Envelope.serializer(), frame))
            }
        } finally {
            inbox.close()
        }
    }

    override suspend fun send(envelope: Envelope) {
        withContext(Dispatchers.IO) {
            Frames.write(out, json.encodeToString(Envelope.serializer(), envelope))
        }
    }

    override val incoming: Flow<Envelope> get() = inbox.consumeAsFlow()

    override suspend fun close() {
        runCatching { socket.close() }
        inbox.close()
    }

    public companion object {
        /** Dials the orchestrator, retrying while the game process is still starting up. */
        public fun connect(
            self: NodeId,
            host: String,
            port: Int,
            attempts: Int = 60,
            json: Json = JsonValueCodec.DefaultJson,
        ): SocketNodeTransport {
            var lastFailure: Exception? = null
            repeat(attempts) {
                try {
                    return SocketNodeTransport(self, Socket(host, port), json)
                } catch (failure: Exception) {
                    lastFailure = failure
                    Thread.sleep(500)
                }
            }
            throw IllegalStateException("Could not reach the e2e orchestrator at $host:$port", lastFailure)
        }
    }
}

/**
 * The orchestrator's end: it accepts every node and relays between them.
 *
 * A server block that raises a `client { }` sends it here and this forwards it on, which is what
 * lets two game processes that have no connection of their own hand work to each other, and gives
 * the report one place that saw every message.
 */
public class SocketHub(
    requestedPort: Int = 0,
    private val json: Json = JsonValueCodec.DefaultJson,
) : AutoCloseable {

    private val server = ServerSocket(requestedPort)
    private val connections = ConcurrentHashMap<NodeId, Connection>()
    private val orchestratorInbox = Channel<Envelope>(Channel.UNLIMITED)

    public val port: Int get() = server.localPort

    public fun start(scope: CoroutineScope): Job = scope.launch(Dispatchers.IO) {
        while (isActive && !server.isClosed) {
            val socket = try {
                server.accept()
            } catch (closed: SocketException) {
                break
            }
            socket.tcpNoDelay = true
            launch(Dispatchers.IO) { serve(socket, this) }
        }
    }

    /** Suspends until [node] has dialled in, or gives up after [timeout]. */
    public suspend fun awaitNode(node: NodeId, timeout: Duration = 5.minutes): Boolean =
        withTimeoutOrNull(timeout) {
            while (!connections.containsKey(node)) delay(200)
            true
        } ?: false

    public fun connected(): Set<NodeId> = connections.keys.toSet()

    /** The orchestrator's own [Transport]: sending routes to a node, receiving takes its own mail. */
    public fun transport(): Transport = object : Transport {
        override val self: NodeId = NodeId.ORCHESTRATOR
        override suspend fun send(envelope: Envelope): Unit = route(envelope)
        override val incoming: Flow<Envelope> get() = orchestratorInbox.consumeAsFlow()
    }

    private fun serve(socket: Socket, scope: CoroutineScope) {
        val input = DataInputStream(socket.getInputStream().buffered())
        val out = DataOutputStream(socket.getOutputStream().buffered())

        val hello = Frames.read(input)?.let { json.decodeFromString(Envelope.serializer(), it) } as? Hello
            ?: return
        val connection = Connection(hello.from, socket, out)
        connections[hello.from] = connection

        try {
            while (!socket.isClosed) {
                val frame = Frames.read(input) ?: break
                route(json.decodeFromString(Envelope.serializer(), frame))
            }
        } finally {
            connections.remove(hello.from)
            runCatching { socket.close() }
        }
    }

    private fun route(envelope: Envelope) {
        if (envelope.to == NodeId.ORCHESTRATOR) {
            orchestratorInbox.trySend(envelope)
            return
        }
        val target = connections[envelope.to]
        if (target == null) {
            // Answer rather than drop: a caller waiting on a node that never arrived should fail
            // with something it can print, not sit until its timeout.
            if (envelope is Request) {
                deliver(
                    Response(
                        callId = envelope.callId,
                        from = NodeId.ORCHESTRATOR,
                        to = envelope.from,
                        failure = RemoteFailure(
                            type = "dev.vibeported.mc.e2e.rpc.UnknownNodeException",
                            message = "No node connected as ${envelope.to}",
                            stack = "",
                        ),
                    )
                )
            }
            return
        }
        Frames.write(target.out, json.encodeToString(Envelope.serializer(), envelope))
    }

    private fun deliver(envelope: Envelope) {
        if (envelope.to == NodeId.ORCHESTRATOR) {
            orchestratorInbox.trySend(envelope)
        } else {
            connections[envelope.to]?.let {
                Frames.write(it.out, json.encodeToString(Envelope.serializer(), envelope))
            }
        }
    }

    override fun close() {
        connections.values.forEach { runCatching { it.socket.close() } }
        connections.clear()
        runCatching { server.close() }
        orchestratorInbox.close()
    }

    private class Connection(val node: NodeId, val socket: Socket, val out: DataOutputStream)
}
