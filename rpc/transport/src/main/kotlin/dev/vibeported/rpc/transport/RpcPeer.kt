package dev.vibeported.rpc.transport

import dev.vibeported.rpc.NodeId
import dev.vibeported.rpc.Outbound
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * One node's half of the conversation.
 *
 * Turns a stream of envelopes into request/response pairs: outgoing calls wait on a deferred keyed
 * by call id, incoming ones are handed to whatever this node can run and answered in place. It is
 * the only thing that knows a call has two halves, which is why the transport below can stay a pipe
 * and the dispatcher above can stay a function.
 */
public class RpcPeer(
    private val transport: Transport,
    private val hub: NodeId,
) : Outbound {

    /**
     * What to do with a request that arrives.
     *
     * Assigned after construction on purpose: a node needs an [Outbound] to be built, and the thing
     * that serves requests needs the node. Something has to be late, and this is the smaller half.
     */
    public var onRequest: (suspend (procedure: String, args: List<ByteArray>) -> ByteArray?)? = null

    /** Told when the hub sends a new roster. */
    public var onRoster: (suspend (Set<dev.vibeported.rpc.NodeInfo>) -> Unit)? = null

    private val pending = ConcurrentHashMap<Long, CompletableDeferred<Response>>()
    private val callIds = AtomicLong()

    public fun start(scope: CoroutineScope): Job = scope.launch {
        transport.incoming.collect { envelope -> receive(envelope, this) }
    }

    override suspend fun call(target: NodeId, procedure: String, args: List<ByteArray>): ByteArray? {
        val callId = callIds.incrementAndGet()
        val answer = CompletableDeferred<Response>()
        pending[callId] = answer

        try {
            transport.send(
                Request(
                    callId = callId,
                    from = transport.self,
                    to = target,
                    procedure = procedure,
                    args = args,
                )
            )
            val response = answer.await()
            response.failure?.let { throw RemoteCallException(target, procedure, it) }
            return response.result
        } finally {
            pending.remove(callId)
        }
    }

    private suspend fun receive(envelope: Envelope, scope: CoroutineScope) {
        when (envelope) {
            is Response -> pending.remove(envelope.callId)?.complete(envelope)

            is Roster -> onRoster?.invoke(envelope.nodes)

            // Each request on its own coroutine: a procedure that suspends -- awaiting a tick, or
            // calling onward to a third node -- must not stop this node hearing anything else.
            is Request -> scope.launch { answer(envelope) }

            is Hello -> Unit
        }
    }

    private suspend fun answer(request: Request) {
        val reply = try {
            val handler = onRequest ?: error("This node serves no procedures")
            val result = handler(request.procedure, request.args)
            Response(request.callId, transport.self, request.from, result = result)
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
    public suspend fun announce(info: dev.vibeported.rpc.NodeInfo) {
        transport.send(Hello(info, hub))
    }
}
