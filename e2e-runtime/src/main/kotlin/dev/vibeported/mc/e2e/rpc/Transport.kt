package dev.vibeported.mc.e2e.rpc

import dev.vibeported.mc.e2e.NodeId
import kotlinx.coroutines.flow.Flow

/**
 * One node's connection to the others.
 *
 * The only thing above this line that knows how nodes are separated. Today the implementation is
 * [InMemoryHub]; a socket or stdio transport for real Minecraft processes has to satisfy exactly
 * this and nothing more.
 */
public interface Transport {
    public val self: NodeId

    /** Hands an envelope to whoever [Envelope.to] names. Must not block on a reply. */
    public suspend fun send(envelope: Envelope)

    /** Everything addressed to [self]. Cold until collected, and collected exactly once. */
    public val incoming: Flow<Envelope>

    public suspend fun close() {}
}
