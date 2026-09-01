package dev.vibeported.mc.e2e

import kotlinx.serialization.Serializable

/** One `suite("...") { }`, built by running its (side-effect free) builder body. */
@Serializable
public data class SuiteDescriptor(
    public val id: String,
    public val name: String,
    public val tests: List<TestDescriptor>,
)

/** One `e2e("...") { }`. Its body lives in the dispatch table under [driver]. */
@Serializable
public data class TestDescriptor(
    public val id: String,
    public val name: String,
    public val driver: BlockId,
)

/**
 * What the compiler plugin writes to `META-INF/e2e/index.json`.
 *
 * The orchestrator reads this to build a run plan without loading a single test body, and each node
 * uses it to find the table class that owns a block it has been asked to run.
 */
@Serializable
public data class E2eIndex(
    public val files: List<FileEntry> = emptyList(),
) {
    @Serializable
    public data class FileEntry(
        public val sourceFile: String,
        /** JVM name of the file facade class, e.g. `dev.example.MovementKt`. */
        public val facadeClass: String,
        /** JVM name of the generated table object, e.g. `dev.example.MovementKt$E2eBlocks`. */
        public val tableClass: String,
        public val suites: List<SuiteEntry> = emptyList(),
        public val blocks: List<BlockEntry> = emptyList(),
    )

    /**
     * A top-level suite property, reachable as a static getter on the facade class.
     *
     * The tests are listed here rather than recovered by running the builder, because the
     * orchestrator is a plain JVM with no Minecraft on its classpath and could not load a test mod
     * class if it wanted to. Planning a run therefore needs nothing but this file.
     */
    @Serializable
    public data class SuiteEntry(
        public val id: String,
        public val name: String,
        /** JVM getter name on the facade class, e.g. `getMovement`. */
        public val accessor: String,
        public val tests: List<TestEntry> = emptyList(),
    )

    @Serializable
    public data class TestEntry(
        public val id: String,
        public val name: String,
        /** The block that drives this test. It runs on the server node. */
        public val driver: BlockId,
    )

    @Serializable
    public data class BlockEntry(
        public val id: BlockId,
        public val role: NodeRole,
        public val clientIndex: Int = 0,
        /** Id of the enclosing block; `null` for a test driver. */
        public val parent: BlockId? = null,
        /** Id of the test this block belongs to. */
        public val test: String,
    )

    public companion object {
        public const val RESOURCE_PATH: String = "META-INF/e2e/index.json"
    }
}
