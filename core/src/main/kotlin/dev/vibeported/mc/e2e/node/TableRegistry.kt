package dev.vibeported.mc.e2e.node

import dev.vibeported.mc.e2e.ProcedureTable
import dev.vibeported.mc.e2e.protocol.ProcedureId
import dev.vibeported.mc.e2e.protocol.ProcedureIndex
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/**
 * Which generated table owns which block, read from the `META-INF/e2e/procedures.json` resources the
 * compiler plugin emitted.
 *
 * Merged across the whole classpath, so blocks can live in as many modules as they like: the
 * gameplay DSL contributes its own, a suite contributes another, and a node handed a bare id finds
 * the one class that knows how to run it without caring which jar it came from.
 */
public class TableRegistry(
    public val index: ProcedureIndex,
    private val loader: ClassLoader,
) {
    private val tables = ConcurrentHashMap<String, ProcedureTable>()

    private val entries: Map<ProcedureId, ProcedureIndex.BlockEntry> =
        index.files.flatMap { it.blocks }.associateBy { it.id }

    public fun entryFor(block: ProcedureId): ProcedureIndex.BlockEntry =
        entries[block] ?: error("No index entry for block `$block`. Was it compiled with the e2e plugin?")

    public fun tableFor(block: ProcedureId): ProcedureTable =
        tables.computeIfAbsent(entryFor(block).table) { instantiate(it) }

    /** Every client any module on this classpath names in a way the compiler could resolve. */
    public fun clients(): Set<String> =
        index.files.flatMap { it.clients }.toSortedSet()

    private fun instantiate(className: String): ProcedureTable {
        val type = Class.forName(className, true, loader)
        // The plugin emits the table as a Kotlin `object`, so it is reachable as a static INSTANCE.
        val instance = type.getField("INSTANCE").get(null)
        return instance as ProcedureTable
    }

    public companion object {
        /** Merges every index on the classpath, so blocks can be split across modules. */
        public fun load(loader: ClassLoader = TableRegistry::class.java.classLoader): TableRegistry {
            val json = Json { ignoreUnknownKeys = true }
            val merged = loader.getResources(ProcedureIndex.RESOURCE_PATH).toList().flatMap { url ->
                json.decodeFromString(ProcedureIndex.serializer(), url.readText()).files
            }
            return TableRegistry(ProcedureIndex(merged), loader)
        }
    }
}
