package dev.vibeported.mc.e2e.rpc

import dev.vibeported.mc.e2e.protocol.ProcedureId
import dev.vibeported.mc.e2e.protocol.NodeId
import dev.vibeported.mc.e2e.protocol.NodeRole
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
public data class InvokeProcedure(
    public val runId: String,
    public val procedure: ProcedureId,
    /** The node that should actually run it. */
    public val target: NodeId,
    /**
     * The name of the test this block belongs to.
     *
     * Carried rather than parsed back out of the block id: a node has things to file under the test
     * it is running -- screenshots, for one -- and the id is a path whose shape is not a promise.
     */
    public val test: String = "",
    /**
     * The block's arguments, already encoded.
     *
     * Encoded by the caller because only the compiled call site knows what each one was declared
     * as; decoded by the receiving table for exactly the same reason. Nothing in between has to
     * know what any of them mean.
     */
    public val args: List<JsonElement> = emptyList(),
) : Payload

@Serializable
public data class ControlPlayer(
    public val runId: String,
    public val client: String,
    public val action: PlayerAction,
) : Payload

@Serializable
public sealed interface PlayerAction {
    @Serializable
    public data class Teleport(
        public val x: Double,
        public val y: Double,
        public val z: Double,
        public val flying: Boolean,
    ) : PlayerAction

    @Serializable
    public data class LookAt(public val x: Double, public val y: Double, public val z: Double) : PlayerAction

    @Serializable
    public data class LookAtPlayer(public val target: String) : PlayerAction
}

/**
 * Asks a client whether its own player has caught up yet.
 *
 * The counterpart to [ControlPlayer], and the reason moving a player is not fire-and-forget. The
 * server sets its own copy of a position the instant it teleports, so asking the server proves
 * nothing; only the client can say whether it has applied the move.
 */
@Serializable
public data class AwaitPlayer(
    public val runId: String,
    public val client: String,
    public val expect: PlayerExpectation,
    public val timeoutTicks: Int,
) : Payload

@Serializable
public sealed interface PlayerExpectation {
    @Serializable
    public data class AtBlock(public val x: Int, public val y: Int, public val z: Int) : PlayerExpectation

    @Serializable
    public data class Facing(public val x: Double, public val y: Double, public val z: Double) : PlayerExpectation

    /**
     * Facing another player, worked out from this client's own view of them.
     *
     * By name rather than by position, because the other player may have moved since whoever asked
     * last saw them.
     */
    @Serializable
    public data class FacingPlayer(public val target: String) : PlayerExpectation
}

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
    public val procedure: ProcedureId?,
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
    public val block: ProcedureId? = null,
    /**
     * A picture of the client at the moment it failed, if one could be taken.
     *
     * The cheapest evidence there is for the failures that are hardest to read: a message saying a
     * slot was empty is a puzzle, and the same message beside a screenshot of an inventory usually
     * is not.
     */
    public val screenshot: String? = null,
)
