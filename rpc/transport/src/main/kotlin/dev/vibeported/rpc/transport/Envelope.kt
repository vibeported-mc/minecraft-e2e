package dev.vibeported.rpc.transport

import dev.vibeported.rpc.NodeId
import dev.vibeported.rpc.NodeInfo
import kotlinx.serialization.Serializable

/**
 * Everything that crosses a connection.
 *
 * Small on purpose. The frames here describe *delivery* -- who is speaking, to whom, and which
 * question an answer belongs to -- and say nothing whatever about what is being delivered. That is
 * what keeps a transport reusable: it never learns what a procedure is.
 */
@Serializable
public sealed interface Envelope {
    public val to: NodeId
}

/**
 * Where a node addresses the hub itself rather than a peer.
 *
 * Not a node: nothing runs procedures here, and no call is ever routed to it. It exists so that
 * announcing yourself and asking the cluster a question use the same envelope as everything else.
 */
public val HUB: NodeId = NodeId("\$hub")

/**
 * The first frame a node sends, naming itself.
 *
 * A hub accepts a connection before it can know who dialled in, so identity has to arrive in band.
 * Roles come with it, because the hub's roster is what every other node filters on.
 */
@Serializable
public data class Hello(
    public val info: NodeInfo,
    override val to: NodeId,
) : Envelope

/**
 * Who is currently connected, as the hub sees it.
 *
 * Pushed rather than polled, and pushed to everyone on every change. This is the only reason a
 * predicate can be evaluated on the node that wrote it instead of having to cross a wire.
 */
@Serializable
public data class Roster(
    public val nodes: Set<NodeInfo>,
    override val to: NodeId,
) : Envelope

/**
 * Run this, and tell me how it went.
 *
 * What the bytes mean is the node's business; the transport only carries them. They stay bytes the
 * whole way because envelopes are encoded with CBOR, where a byte array is a native type -- JSON
 * would have forced either Base64 or an array of numbers, and neither is worth having.
 */
@Serializable
public data class Request(
    public val callId: Long,
    public val from: NodeId,
    override val to: NodeId,
    public val procedure: String,
    public val args: List<ByteArray> = emptyList(),
) : Envelope

/**
 * Stop running that, I am no longer waiting for it.
 *
 * Without this, cancelling a caller only discards the answer: the body keeps running on the other
 * node, with whatever it was doing to the world still going on. A test abandoned halfway would leave
 * a client still dutifully carrying out its last instruction.
 */
@Serializable
public data class Cancel(
    public val callId: Long,
    public val from: NodeId,
    override val to: NodeId,
) : Envelope

/**
 * Leaving on purpose.
 *
 * Not a liveness mechanism -- a socket closing says that already, and faster. This says something a
 * dropped connection cannot: that the departure was intended, so a report can tell a client that
 * finished from one that died.
 */
@Serializable
public data class Goodbye(
    public val from: NodeId,
    override val to: NodeId,
) : Envelope

/** Still here. @see Goodbye for the difference between leaving and vanishing. */
@Serializable
public data class Heartbeat(
    public val from: NodeId,
    override val to: NodeId,
) : Envelope

@Serializable
public data class Response(
    public val callId: Long,
    public val from: NodeId,
    override val to: NodeId,
    public val result: ByteArray? = null,
    public val failure: RemoteFailure? = null,
) : Envelope

/**
 * A failure that happened somewhere else.
 *
 * The exception cannot cross, so the parts worth reading do. Kept deliberately free of anything
 * domain-specific: a framework that carried, say, a screenshot field would have stopped being one.
 */
@Serializable
public data class RemoteFailure(
    public val type: String,
    public val message: String?,
    public val stack: String,
)

/**
 * Raised when the node a call was sent to is no longer there.
 *
 * The alternative is waiting forever for an answer nobody is coming back with, which turns a crashed
 * client into a hung test rather than a failed one -- much the worse of the two.
 */
public class NodeGoneException(
    public val node: NodeId,
    public val procedure: String,
    public val why: String,
) : RuntimeException("`" + procedure + "` was sent to " + node + ", which is gone: " + why)

/** Raised locally when the other end reported a failure. @see RemoteFailure */
public class RemoteCallException(
    public val node: NodeId,
    public val procedure: String,
    public val remote: RemoteFailure,
) : RuntimeException(
    "`$procedure` failed on $node: ${remote.type}: ${remote.message}\n--- remote stack ---\n${remote.stack}"
)
