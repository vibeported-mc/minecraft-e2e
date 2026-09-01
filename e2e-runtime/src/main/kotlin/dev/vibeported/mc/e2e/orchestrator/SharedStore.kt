package dev.vibeported.mc.e2e.orchestrator

import dev.vibeported.mc.e2e.SharedId
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.ConcurrentHashMap

/** A `shared` value was read before any node wrote it. A test failure, not a framework error. */
public class UnsetSharedException(public val id: SharedId) : IllegalStateException(
    "Shared value `$id` was read before anything wrote it"
)

/**
 * The one authoritative copy of every `shared` value, held by the orchestrator.
 *
 * Keyed by run as well as by id, so the same test can be re-run, or several run at once, without
 * one leaking values into another.
 */
public class SharedStore {
    private val values = ConcurrentHashMap<String, JsonElement>()

    public fun get(runId: String, id: SharedId): JsonElement =
        values[key(runId, id)] ?: throw UnsetSharedException(id)

    public fun set(runId: String, id: SharedId, value: JsonElement) {
        values[key(runId, id)] = value
    }

    public fun snapshot(runId: String): Map<String, JsonElement> {
        val prefix = "$runId\u0000"
        return values.filterKeys { it.startsWith(prefix) }
            .mapKeys { (k, _) -> k.removePrefix(prefix) }
    }

    public fun clear(runId: String) {
        val prefix = "$runId\u0000"
        values.keys.removeIf { it.startsWith(prefix) }
    }

    private fun key(runId: String, id: SharedId) = "$runId\u0000${id.value}"
}
