package dev.vibeported.rpc

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What the compiler plugin recorded about the procedures in one module.
 *
 * Names only. Reading a manifest never loads a class, which is the whole reason it exists in this
 * form: a node has to know that a procedure exists and whose role owns it in order to explain a
 * misrouted call, while never touching the class that would fail to resolve.
 *
 * This is also why `ServiceLoader` is not used to find tables. Iterating a service instantiates
 * every registered implementation, so a server would construct the client table and die on the spot
 * -- the exact failure the role split exists to prevent.
 */
@Serializable
public data class ProcedureManifest(
    public val entries: List<Entry> = emptyList(),
) {
    @Serializable
    public data class Entry(
        public val id: String,
        /** The generated class holding the body. Resolved only if this node holds [role]. */
        public val table: String,
        /** Null for bodies every node loads. */
        public val role: String? = null,
        /** The module that compiled it, so a duplicate id can name both culprits. */
        public val module: String = "",
    )

    public companion object {
        public const val RESOURCE: String = "META-INF/rpc/procedures.json"

        private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

        public fun parse(text: String): ProcedureManifest = json.decodeFromString(serializer(), text)

        public fun render(manifest: ProcedureManifest): String =
            json.encodeToString(serializer(), manifest)

        /**
         * Every manifest on the classpath, merged.
         *
         * `getResources` -- plural -- is the point: each module that compiles procedures ships its
         * own copy, and a node sees the union without caring which jar anything came from.
         */
        public fun load(loader: ClassLoader): ProcedureManifest {
            val merged = loader.getResources(RESOURCE).toList().flatMap { url ->
                parse(url.readText()).entries
            }

            // Two modules claiming one id is a build that cannot be run, not a race for the
            // classpath to settle. Say both names, because the fix is always in one of them.
            merged.groupBy { it.id }.forEach { (id, claims) ->
                if (claims.size > 1) {
                    val where = claims.joinToString(", ") { "${it.table} in ${it.module.ifEmpty { "?" }}" }
                    error("Procedure `$id` is claimed by more than one module: $where")
                }
            }
            return ProcedureManifest(merged)
        }
    }
}
