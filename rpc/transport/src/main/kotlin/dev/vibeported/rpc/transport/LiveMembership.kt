package dev.vibeported.rpc.transport

import dev.vibeported.rpc.Membership
import dev.vibeported.rpc.MembershipEvent
import dev.vibeported.rpc.NodeInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicReference

/**
 * A node's replica of who is out there, kept current by whatever feeds it a roster.
 *
 * The diffing happens here rather than at the source so that the wire frame can stay a plain set:
 * a roster is a fact about the world, and "who joined" is a question about two facts in a row. A
 * transport that reconnects and resends the whole roster therefore produces no spurious events.
 */
public class LiveMembership(initial: Set<NodeInfo> = emptySet()) : Membership {

    private val state = AtomicReference(initial)
    private val events = MutableSharedFlow<MembershipEvent>(extraBufferCapacity = 64)

    override fun snapshot(): Set<NodeInfo> = state.get()

    override val changes: Flow<MembershipEvent> = events.asSharedFlow()

    /** Replaces the roster, emitting only what actually changed. */
    public suspend fun update(nodes: Set<NodeInfo>) {
        val previous = state.getAndSet(nodes)
        val before = previous.associateBy { it.id }
        val after = nodes.associateBy { it.id }

        after.forEach { (id, info) -> if (id !in before) events.emit(MembershipEvent.Joined(info)) }
        before.keys.forEach { id -> if (id !in after) events.emit(MembershipEvent.Left(id)) }
    }
}
