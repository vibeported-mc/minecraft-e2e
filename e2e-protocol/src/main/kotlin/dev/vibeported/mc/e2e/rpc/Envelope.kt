package dev.vibeported.mc.e2e.rpc

import dev.vibeported.mc.e2e.protocol.BlockId
import dev.vibeported.mc.e2e.protocol.NodeId
import dev.vibeported.mc.e2e.protocol.NodeRole
import dev.vibeported.mc.e2e.protocol.SharedId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Body of a [Request]. Every one of these has to survive a wire, so they are all serializable. */
@Serializable
public sealed interface Payload

/**
 * "Run this lifted block and tell me how it went."
 *
 * Always addressed to the orchestrator first, even when the sender knows perfectly well which node
 * should run it. Everything funnelling through one place is what gives the report a single ordering
 * for blocks and log lines across all the nodes.
 */
@Serializable
public data class InvokeBlock(
    public val runId: String,
    public val block: BlockId,
    /** The node that should actually run it. */
    public val target: NodeId,
) : Payload

/** Read of a `shared` value against the orchestrator's authoritative store. */
@Serializable
public data class SharedGet(
    public val runId: String,
    public val id: SharedId,
    /** Fully qualified class name, used to pick the serializer on both ends. */
    public val valueType: String,
) : Payload

/** Write of a `shared` value. */
@Serializable
public data class SharedSet(
    public val runId: String,
    public val id: SharedId,
    public val valueType: String,
    public val value: JsonElement,
) : Payload

/** Asks the receiving peer to cancel an in-flight call it is running for us. */
@Serializable
public data class Cancel(public val callId: Long) : Payload

@Serializable
public sealed interface Envelope {
    public val to: NodeId
}

/**
 * The first frame a node sends, naming itself.
 *
 * The orchestrator accepts a socket before it can know which process dialled in, so identity has to
 * arrive in band.
 */
@Serializable
public data class Hello(
    public val from: NodeId,
    override val to: NodeId = NodeId(NodeRole.ORCHESTRATOR),
) : Envelope

@Serializable
public data class Request(
    public val callId: Long,
    public val from: NodeId,
    override val to: NodeId,
    public val payload: Payload,
) : Envelope

@Serializable
public data class Response(
    public val callId: Long,
    public val from: NodeId,
    override val to: NodeId,
    public val result: JsonElement? = null,
    public val failure: RemoteFailure? = null,
) : Envelope

/** A captured log line. Fire-and-forget: nothing ever responds to one. */
@Serializable
public data class Event(
    public val from: NodeId,
    override val to: NodeId,
    public val runId: String,
    public val block: BlockId?,
    public val message: String,
    public val atMillis: Long,
) : Envelope

/**
 * A failure that happened on another node.
 *
 * The exception itself cannot cross the wire, so the parts that matter for a report do:
 * its type, its message, and the stack as text. [assertion] is kept apart because a failed
 * `assertThat` is a test result, not a framework error.
 */
@Serializable
public data class RemoteFailure(
    public val type: String,
    public val message: String?,
    public val stack: String,
    public val assertion: Boolean = false,
    public val node: NodeId? = null,
    public val block: BlockId? = null,
)
