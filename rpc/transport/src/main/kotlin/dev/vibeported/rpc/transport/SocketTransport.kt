package dev.vibeported.rpc.transport

import dev.vibeported.rpc.NodeId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.Socket

/**
 * A node's connection to the hub, over TCP.
 *
 * Blocking IO on the IO dispatcher rather than NIO: there is exactly one socket per node here, the
 * traffic is a handful of frames per call, and a selector would be machinery bought for a load that
 * does not exist.
 *
 * The stream ending is not an error. It is how this node learns the hub has gone, and it is what
 * makes a crashed process detectable without anybody polling anything.
 */
public class SocketTransport(
    override val self: NodeId,
    private val socket: Socket,
) : Transport {

    private val output = socket.getOutputStream()
    private val input = socket.getInputStream()

    // One socket, many coroutines: two frames interleaved would be one frame nobody can read.
    private val writing = Mutex()

    override suspend fun send(envelope: Envelope) {
        val bytes = EnvelopeCodec.encode(envelope)
        withContext(Dispatchers.IO) {
            writing.withLock { Framing.write(output, bytes) }
        }
    }

    override val incoming: Flow<Envelope> = flow {
        while (true) {
            val frame = Framing.read(input) ?: break
            emit(EnvelopeCodec.decode(frame))
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun close() {
        withContext(Dispatchers.IO) { runCatching { socket.close() } }
    }

    public companion object {
        public suspend fun connect(self: NodeId, host: String, port: Int): SocketTransport =
            withContext(Dispatchers.IO) { SocketTransport(self, Socket(host, port)) }
    }
}
