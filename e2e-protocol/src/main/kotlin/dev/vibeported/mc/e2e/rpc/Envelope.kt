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

/**
 * Read of a `shared` value against the orchestrator's authoritative store.
 *
 * [await] is what separates `get()` from `getOrNull()`: a waiting read parks on the orchestrator
 * until something writes the value, so a test does not have to guess at when that will be.
 */
@Serializable
public data class SharedGet(
    public val runId: String,
    public val id: SharedId,
    /** Fully qualified class name, used to pick the serializer on both ends. */
    public val valueType: String,
    public val await: Boolean = true,
    /** Null means bounded only by the test timeout. */
    public val timeoutMillis: Long? = null,
) : Payload

/** Write of a `shared` value. */
@Serializable
public data class SharedSet(
    public val runId: String,
    public val id: SharedId,
    public val valueType: String,
    public val value: JsonElement,
) : Payload

/**
 * Drives a test player. Always handled by the server, which is the only side that can actually move
 * one -- a client that moved itself would be corrected on the next tick.
 */
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
