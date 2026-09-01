package dev.vibeported.mc.e2e.orchestrator

import dev.vibeported.mc.e2e.protocol.SharedId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

/** A waiting read gave up before anything wrote the value. */
public class UnsetSharedException(public val id: SharedId) : IllegalStateException(
    "Shared value `$id` was still unset when the read gave up waiting"
)

/**
 * The one authoritative copy of every `shared` value, held by the orchestrator.
 *
 * Keyed by run as well as by id, so the same test can be re-run, or several run at once, without one
 * leaking values into another.
 *
 * A read of a value nothing has written yet **parks** rather than failing. Where blocks execute in
 * sequence the value is always already there and the wait costs nothing, but parking is what makes a
 * read mean "when this exists" rather than "if it happens to exist yet", which is the difference
 * between a test that states its intent and one that races.
 */
public class SharedStore {

    private val values = ConcurrentHashMap<String, JsonElement>()
    private val waiting = ConcurrentHashMap<String, MutableList<CompletableDeferred<JsonElement>>>()

    /** The value if it is there, else null. Never waits. */
    public fun peek(runId: String, id: SharedId): JsonElement? = values[key(runId, id)]

    /**
     * The value, waiting for it if need be.
     *
     * A null [timeout] waits indefinitely, leaving the per-test timeout to bound it, which is what
     * lets the test report name the value it was still waiting on rather than merely say it ran out
     * of time.
     */
    public suspend fun await(runId: String, id: SharedId, timeout: Duration?): JsonElement {
        val key = key(runId, id)
        values[key]?.let { return it }

        val pending = CompletableDeferred<JsonElement>()
        waiting.compute(key) { _, existing -> (existing ?: mutableListOf()).also { it += pending } }

        // A set may have landed between the read above and registering just now.
        values[key]?.let {
            pending.complete(it)
            return it
        }

        return try {
            if (timeout == null) {
                pending.await()
            } else {
                withTimeoutOrNull(timeout) { pending.await() } ?: throw UnsetSharedException(id)
            }
        } finally {
            waiting[key]?.remove(pending)
        }
    }

    public fun set(runId: String, id: SharedId, value: JsonElement) {
        val key = key(runId, id)
        values[key] = value
        waiting.remove(key)?.forEach { it.complete(value) }
    }

    public fun snapshot(runId: String): Map<String, JsonElement> {
        val prefix = runId + SEPARATOR
        return values.filterKeys { it.startsWith(prefix) }
            .mapKeys { (k, _) -> k.removePrefix(prefix) }
    }

    /** Frees the run, failing anything still parked so no coroutine outlives the test. */
    public fun clear(runId: String) {
        val prefix = runId + SEPARATOR
        values.keys.removeIf { it.startsWith(prefix) }
        waiting.keys.filter { it.startsWith(prefix) }.forEach { key ->
            val id = SharedId(key.removePrefix(prefix))
            waiting.remove(key)?.forEach { it.completeExceptionally(UnsetSharedException(id)) }
        }
    }

    private fun key(runId: String, id: SharedId) = runId + SEPARATOR + id.value

    private companion object {
        /**
         * A run id is a test id plus a counter and a shared id is a test id plus a name, so neither
         * contains a newline. That makes it a separator the two can never be confused across.
         */
        const val SEPARATOR = "\n"
    }
}
