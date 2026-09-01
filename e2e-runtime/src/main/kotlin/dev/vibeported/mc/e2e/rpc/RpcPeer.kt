package dev.vibeported.mc.e2e.rpc

import dev.vibeported.mc.e2e.E2eAssertionError
import dev.vibeported.mc.e2e.NodeId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** A failure that happened on another node, rethrown locally with the remote detail preserved. */
public class RemoteInvocationException(
    public val failure: RemoteFailure,
) : RuntimeException(
    buildString {
        append(failure.type)
        failure.node?.let { append(" on ").append(it) }
        failure.message?.let { append(": ").append(it) }
        append('\n').append(failure.stack)
    }
)

/**
 * Request/response over a [Transport].
 *
 * Handlers run in their own coroutine rather than on the receive loop, which is what makes nested
 * dispatch work: a server node handling `InvokeBlock` can itself call out for a `client { }` block
 * and still process the reply on the same peer.
 */
public class RpcPeer(
    private val transport: Transport,
    private val callTimeout: Duration = 60.seconds,
) {
    public val self: NodeId get() = transport.self

    /** Answers an incoming request. Returning normally sends a result; throwing sends a failure. */
    public var onRequest: (suspend (Request) -> JsonElement?)? = null

    /** Receives fire-and-forget log events. */
    public var onEvent: (suspend (Event) -> Unit)? = null

    private val nextCallId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<Response>>()
    private val inFlight = ConcurrentHashMap<Long, Job>()

    public fun start(scope: CoroutineScope): Job = scope.launch {
        transport.incoming.collect { envelope ->
            when (envelope) {
                is Request -> handleRequest(this, envelope)
                is Response -> pending.remove(envelope.callId)?.complete(envelope)
                is Event -> onEvent?.invoke(envelope)
                // Identity is settled by the hub as the socket is accepted; nothing to do here.
                is Hello -> Unit
            }
        }
    }

    /** Sends [payload] to [to] and suspends until that node answers. */
    public suspend fun call(to: NodeId, payload: Payload): JsonElement? {
        val callId = nextCallId.getAndIncrement()
        val reply = CompletableDeferred<Response>()
        pending[callId] = reply
        try {
            transport.send(Request(callId, self, to, payload))
            val response = withTimeout(callTimeout) { reply.await() }
            response.failure?.let { throw RemoteInvocationException(it) }
            return response.result
        } catch (cancellation: CancellationException) {
            // Tell the far side to stop; otherwise a cancelled test leaves a block running forever.
            withContext(NonCancellable) {
                runCatching { transport.send(Request(nextCallId.getAndIncrement(), self, to, Cancel(callId))) }
            }
            throw cancellation
        } finally {
            pending.remove(callId)
        }
    }

    /** Fire-and-forget; used for log lines. */
    public suspend fun emit(event: Event) {
        transport.send(event)
    }

    private fun handleRequest(scope: CoroutineScope, request: Request) {
        val payload = request.payload
        if (payload is Cancel) {
            inFlight.remove(payload.callId)?.cancel()
            return
        }
        val handler = onRequest ?: error("$self received $payload with no request handler installed")
        val job = scope.launch {
            val response = try {
                Response(request.callId, self, request.from, result = handler(request))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                Response(request.callId, self, request.from, failure = failure.toRemoteFailure(self))
            }
            withContext(NonCancellable) { transport.send(response) }
        }
        inFlight[request.callId] = job
        job.invokeOnCompletion { inFlight.remove(request.callId) }
    }
}

/** Flattens a throwable into something a report can print and a caller can rethrow. */
public fun Throwable.toRemoteFailure(node: NodeId? = null): RemoteFailure {
    // A failure relayed through an intermediate node keeps the node and block where it started,
    // so a client assertion raised via the server still reports as the client's.
    if (this is RemoteInvocationException) return failure
    val stack = StringWriter().also { printStackTrace(PrintWriter(it)) }.toString()
    return RemoteFailure(
        type = this::class.qualifiedName ?: "Throwable",
        message = message,
        stack = stack,
        assertion = this is E2eAssertionError,
        node = node,
    )
}
