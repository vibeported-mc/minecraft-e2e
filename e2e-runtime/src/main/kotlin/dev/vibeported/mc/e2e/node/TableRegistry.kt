package dev.vibeported.mc.e2e.node

import dev.vibeported.mc.e2e.BlockId
import dev.vibeported.mc.e2e.E2eBlockTable
import dev.vibeported.mc.e2e.E2eIndex
import dev.vibeported.mc.e2e.SuiteDescriptor
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/**
 * Everything a node knows about the compiled tests, read from the `META-INF/e2e/index.json`
 * resources the compiler plugin emitted.
 *
 * The index exists so the orchestrator can plan a run without loading a single test body, and so a
 * node handed a bare block id can find the one class that knows how to run it.
 */
public class TableRegistry(
    public val index: E2eIndex,
    private val loader: ClassLoader,
) {
    private val tables = ConcurrentHashMap<String, E2eBlockTable>()

    private val blockOwner: Map<BlockId, E2eIndex.FileEntry> =
        index.files.flatMap { file -> file.blocks.map { it.id to file } }.toMap()

    private val blockEntries: Map<BlockId, E2eIndex.BlockEntry> =
        index.files.flatMap { it.blocks }.associateBy { it.id }

    public fun entryFor(block: BlockId): E2eIndex.BlockEntry =
        blockEntries[block] ?: error("No index entry for block `$block`. Was it compiled with the e2e plugin?")

    public fun tableFor(block: BlockId): E2eBlockTable {
        val file = blockOwner[block] ?: error("No table owns block `$block`")
        return tables.computeIfAbsent(file.tableClass) { instantiate(it) }
    }

    /** Runs each generated suite builder to recover the declared structure. Cheap: the bodies are gone. */
    public fun suites(): List<SuiteDescriptor> = index.files.flatMap { file ->
        val facade = Class.forName(file.facadeClass, true, loader)
        file.suites.map { suite ->
            facade.getMethod(suite.accessor).invoke(null) as SuiteDescriptor
        }
    }

    private fun instantiate(className: String): E2eBlockTable {
        val type = Class.forName(className, true, loader)
        // The plugin emits the table as a Kotlin `object`, so it is reachable as a static INSTANCE.
        val instance = type.getField("INSTANCE").get(null)
        return instance as E2eBlockTable
    }

    public companion object {
        /** Merges every index on the classpath, so tests can be split across modules. */
        public fun load(loader: ClassLoader = TableRegistry::class.java.classLoader): TableRegistry {
            val json = Json { ignoreUnknownKeys = true }
            val merged = loader.getResources(E2eIndex.RESOURCE_PATH).toList().flatMap { url ->
                json.decodeFromString(E2eIndex.serializer(), url.readText()).files
            }
            return TableRegistry(E2eIndex(merged), loader)
        }
    }
}
