package dev.vibeported.rpc.transport

import dev.vibeported.rpc.NodeId
import dev.vibeported.rpc.NodeInfo
import dev.vibeported.rpc.Outbound
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration

/**
 * One node's half of the conversation.
 *
 * Turns a stream of envelopes into request/response pairs: outgoing calls wait on a deferred keyed
 * by call id, incoming ones are handed to whatever this node can run and answered in place. It is
 * the only thing that knows a call has two halves, which is why the transport below can stay a pipe
 * and the dispatcher above can stay a function.
 *
 * It is also where a call stops waiting for an answer nobody is bringing. A crashed node must fail
 * the calls sent to it rather than leave them outstanding, because a hung test says far less than a
 * failed one.
 */
public class RpcPeer(
    private val transport: Transport,
    private val hub: NodeId,
    /** How often to say we are still here. Zero to say nothing. */
    private val heartbeat: Duration = Duration.ZERO,
) : Outbound {

    /**
     * What to do with a request that arrives.
     *
     * Assigned after construction on purpose: a node needs an [Outbound] to be built, and the thing
     * that serves requests needs the node. Something has to be late, and this is the smaller half.
     */
    public var onRequest: (suspend (procedure: String, args: List<ByteArray>) -> ByteArray?)? = null

    /** Told when the hub sends a new roster. */
    public var onRoster: (suspend (Set<NodeInfo>) -> Unit)? = null

    private val pending = ConcurrentHashMap<Long, Waiting>()
    private val serving = ConcurrentHashMap<String, Job>()
    private val callIds = AtomicLong()
    private val known = java.util.concurrent.atomic.AtomicReference<Set<NodeId>>(emptySet())

    private class Waiting(val target: NodeId, val procedure: String, val answer: CompletableDeferred<Response>)

    public fun start(scope: CoroutineScope): Job = scope.launch {
        if (heartbeat > Duration.ZERO) {
            scope.launch {
                while (true) {
                    delay(heartbeat)
                    transport.send(Heartbeat(transport.self, hub))
                }
            }
        }

        try {
            transport.incoming.collect { envelope -> receive(envelope, this) }
        } finally {
            // The stream ending means the connection did. Anything still waiting is waiting on a
            // node this process can no longer reach.
            abandonAll("the connection to the cluster closed")
        }
    }

    override suspend fun call(target: NodeId, procedure: String, args: List<ByteArray>): ByteArray? {
        val callId = callIds.incrementAndGet()
        val answer = CompletableDeferred<Response>()
        pending[callId] = Waiting(target, procedure, answer)

        try {
            transport.send(Request(callId, transport.self, target, procedure, args))
            val response = answer.await()
            response.failure?.let { throw RemoteCallException(target, procedure, it) }
            return response.result
        } catch (cancelled: CancellationException) {
            // Tell the other end to stop. Sent outside cancellation, because a cancelled coroutine
            // cannot suspend -- and without it the body would run on, doing whatever it does to the
            // world, long after anyone stopped waiting for the answer.
            withContext(NonCancellable) {
                runCatching { transport.send(Cancel(callId, transport.self, target)) }
            }
            throw cancelled
        } finally {
            pending.remove(callId)
        }
    }

    /** Fails every call outstanding to [node]. Called when the roster says it has gone. */
    public fun abandon(node: NodeId, why: String) {
        pending.entries.filter { it.value.target == node }.forEach { (callId, waiting) ->
            pending.remove(callId)
            waiting.answer.completeExceptionally(NodeGoneException(node, waiting.procedure, why))
        }
    }

    private fun abandonAll(why: String) {
        pending.entries.toList().forEach { (callId, waiting) ->
            pending.remove(callId)
            waiting.answer.completeExceptionally(NodeGoneException(waiting.target, waiting.procedure, why))
        }
    }

    /** Says goodbye, so the other side knows this was meant. */
    public suspend fun leave() {
        runCatching { transport.send(Goodbye(transport.self, hub)) }
    }

    private suspend fun receive(envelope: Envelope, scope: CoroutineScope) {
        when (envelope) {
            // A response for a call nobody is waiting on any more is simply dropped: the caller was
            // cancelled, and the id will never be issued again.
            is Response -> pending.remove(envelope.callId)?.answer?.complete(envelope)

            is Roster -> {
                // Done here rather than left to whoever wired the peer up: forgetting it turns a
                // crashed node into a call that waits forever, which is not a mistake worth making
                // available.
                abandonDeparted(envelope.nodes)
                onRoster?.invoke(envelope.nodes)
            }

            // Each request on its own coroutine: a procedure that suspends -- awaiting a tick, or
            // calling onward to a third node -- must not stop this node hearing anything else.
            is Request -> {
                val key = keyOf(envelope.from, envelope.callId)
                serving[key] = scope.launch { try { answer(envelope) } finally { serving.remove(key) } }
            }

            is Cancel -> serving.remove(keyOf(envelope.from, envelope.callId))?.cancel()

            is Hello, is Goodbye, is Heartbeat -> Unit
        }
    }

    /**
     * Fails calls to nodes that were in the roster and no longer are.
     *
     * Only nodes that *left* -- a target missing from the very first roster is far more likely to be
     * one that has not announced itself yet than one that has died.
     */
    private fun abandonDeparted(roster: Set<NodeInfo>) {
        val present = roster.map { it.id }.toSet()
        val previous = known.getAndSet(present)
        (previous - present).forEach { gone -> abandon(gone, "it left the cluster") }
    }

    private fun keyOf(from: NodeId, callId: Long): String = "${from.value}#$callId"

    private suspend fun answer(request: Request) {
        val reply = try {
            val handler = onRequest ?: error("This node serves no procedures")
            val result = handler(request.procedure, request.args)
            Response(request.callId, transport.self, request.from, result = result)
        } catch (cancelled: CancellationException) {
            // Asked to stop, so say nothing: the caller has already given up on the answer.
            throw cancelled
        } catch (failure: Throwable) {
            Response(
                callId = request.callId,
                from = transport.self,
                to = request.from,
                failure = RemoteFailure(
                    type = failure::class.java.name,
                    message = failure.message,
                    stack = failure.stackTraceToString(),
                ),
            )
        }
        transport.send(reply)
    }

    /** Announces this node to the hub, which is how the roster learns about it. */
    public suspend fun announce(info: NodeInfo) {
        transport.send(Hello(info, hub))
    }
}
