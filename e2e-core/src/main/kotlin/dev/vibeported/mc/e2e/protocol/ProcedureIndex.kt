package dev.vibeported.mc.e2e.protocol

import kotlinx.serialization.Serializable

/**
 * Every block a module can be asked to run, written by the compiler plugin.
 *
 * Two questions only: which table owns a given block, and which clients a module mentions. It knows
 * nothing about tests -- it used to describe them as ordered lists of steps, because a test was a
 * plan somebody walked, and a test is ordinary code now. What is left is a routing table for the
 * RPC layer, which is why it is named for blocks rather than for the framework.
 */
@Serializable
public data class ProcedureIndex(
    public val files: List<FileEntry> = emptyList(),
) {
    @Serializable
    public data class FileEntry(
        public val sourceFile: String,
        /** JVM name of the file facade class, e.g. `dev.example.MovementKt`. */
        public val facadeClass: String,
        public val blocks: List<BlockEntry> = emptyList(),
        /**
         * Every client this file names in a way the compiler could resolve.
         *
         * Merged across the classpath and with whatever the build declares, and that is the list a
         * run starts up front. A name it could not resolve is not a failure: the orchestrator
         * starts that client the first time something addresses it.
         */
        public val clients: List<String> = emptyList(),
    )

    @Serializable
    public data class BlockEntry(
        public val id: ProcedureId,
        public val role: NodeRole,
        /** Which client runs it, when the compiler could tell. Empty for a server block. */
        public val client: String = "",
        /**
         * JVM name of the generated table that owns it.
         *
         * Per block rather than per file, because the tables are split by role: a dedicated server
         * is dist-cleaned and could not verify a class holding client bodies.
         */
        public val table: String,
        /** Id of the enclosing block, or null when written straight into ordinary code. */
        public val parent: ProcedureId? = null,
    )

    public companion object {
        public const val RESOURCE_PATH: String = "META-INF/e2e/procedures.json"
    }
}
