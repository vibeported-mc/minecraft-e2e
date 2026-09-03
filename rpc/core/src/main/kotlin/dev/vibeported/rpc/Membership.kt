package dev.vibeported.rpc

import kotlinx.coroutines.flow.Flow

/**
 * Who else is out there, as this node last heard.
 *
 * Declared here and answered elsewhere: routing knows about connections and this module does not, so
 * core states the question and the transport layer supplies an implementation.
 *
 * A snapshot is a replica and can be a moment out of date. That is admitted rather than hidden --
 * a node can die between being listed by a fan-out and being called by it, and the honest response
 * is a failure attributed to that node, not a pretence that the list was authoritative.
 */
public interface Membership {

    /** Everyone believed alive, including this node. */
    public fun snapshot(): Set<NodeInfo>

    /** Joins and departures, from the moment it is collected. */
    public val changes: Flow<MembershipEvent>

    public companion object {
        /** A cluster of one, for a node that never talks to anybody. */
        public fun of(self: NodeInfo): Membership = FixedMembership(setOf(self))

        public fun of(nodes: Set<NodeInfo>): Membership = FixedMembership(nodes)
    }
}

public sealed interface MembershipEvent {
    public data class Joined(public val node: NodeInfo) : MembershipEvent
    public data class Left(public val id: NodeId) : MembershipEvent
}

private class FixedMembership(private val nodes: Set<NodeInfo>) : Membership {
    override fun snapshot(): Set<NodeInfo> = nodes
    override val changes: Flow<MembershipEvent> = kotlinx.coroutines.flow.emptyFlow()
}
