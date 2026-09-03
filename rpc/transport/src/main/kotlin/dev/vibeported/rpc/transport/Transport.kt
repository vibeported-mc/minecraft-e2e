package dev.vibeported.rpc.transport

import dev.vibeported.rpc.NodeId
import kotlinx.coroutines.flow.Flow

/**
 * One node's connection to the rest.
 *
 * The only thing in the framework that knows how nodes are separated. An in-memory implementation
 * and a socket one satisfy exactly this and nothing more, which is what lets everything above be
 * tested in a single JVM and then deployed across processes unchanged.
 */
public interface Transport {

    public val self: NodeId

    /** Hands an envelope onward. Must not block waiting for a reply. */
    public suspend fun send(envelope: Envelope)

    /** Everything addressed to [self]. */
    public val incoming: Flow<Envelope>

    public suspend fun close() {}
}
