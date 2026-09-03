package dev.vibeported.rpc

/**
 * The one thing core needs from a transport: get these bytes to that node and bring back the answer.
 *
 * Everything about connections, routing and framing lives on the other side of this interface, which
 * is what lets a node that never leaves its process depend on none of it.
 */
public interface Outbound {

    public suspend fun call(target: NodeId, procedure: String, args: List<ByteArray>): ByteArray?

    public companion object {
        /** For a node that is alone. Any attempt to leave it says why rather than what. */
        public val Isolated: Outbound = object : Outbound {
            override suspend fun call(target: NodeId, procedure: String, args: List<ByteArray>): ByteArray? =
                error(
                    "This node has no transport, so `$procedure` cannot be sent to $target. " +
                        "It was built for a cluster of one."
                )
        }
    }
}
