package dev.vibeported.rpc

/**
 * The tables this node is allowed to load, loaded.
 *
 * Eagerly, so that a table class the manifest names and this classpath lacks brings the node down
 * while it is starting rather than at some later call.
 *
 * That guarantee is narrower than it first looks, and the difference decides the whole design. The
 * JVM resolves a class's own supertypes when it loads it, but not the classes named inside its
 * method bodies -- those wait until the method runs. So a table whose *bodies* touch classes this
 * node lacks loads perfectly well here and fails on the first call that needs them. Measured, not
 * assumed: `Class.forName` on a table referencing an absent class returns an instance quite
 * happily.
 *
 * Which is exactly why roles carry the weight rather than the classpath. A node that does not hold
 * a role never resolves its table at all, so the misrouted call is refused by name, before anything
 * is loaded -- and that refusal is a guarantee, where "the class was missing" would only have been
 * a hope. A node that *claims* a role its jars cannot support is a lie the runtime cannot catch
 * early, and fails on the call.
 */
public class TableRegistry private constructor(
    private val byProcedure: Map<String, ProcedureTable>,
    private val manifest: ProcedureManifest,
    private val roles: Set<Role>,
) {

    /** The table owning [procedure], or a failure that says why this node has not got it. */
    public fun tableFor(procedure: String): ProcedureTable =
        byProcedure[procedure] ?: error(explain(procedure))

    public fun knows(procedure: String): Boolean = procedure in byProcedure

    /**
     * Everything this node actually resolved, which is not everything the manifest named.
     *
     * The difference between the two is the dist split made visible, and is worth a host being able
     * to say out loud as it starts: a node holding no roles has fewer of these than one holding
     * several, from exactly the same jars.
     */
    public fun procedures(): Set<String> = byProcedure.keys

    private fun explain(procedure: String): String {
        val entry = manifest.entries.firstOrNull { it.id == procedure }
            ?: return "No procedure `$procedure` on any classpath here. Was its module compiled " +
                "with the RPC plugin?"

        val owner = entry.role
            ?: return "Procedure `$procedure` should have been loaded by every node, but was not. " +
                "Its table `${entry.table}` is missing from this classpath."

        return "Procedure `$procedure` belongs to role `$owner`; this node holds " +
            "${roles.map { it.value }.sorted()}. It was routed to the wrong node."
    }

    public companion object {

        /**
         * A registry over tables that are already in hand.
         *
         * For embedding and for tests: no manifest, no classpath scan, nothing resolved by name.
         * The role filtering that [load] performs has already happened by construction, because a
         * caller holding a table has necessarily been able to load it.
         */
        public fun of(tables: List<ProcedureTable>, roles: Set<Role> = emptySet()): TableRegistry {
            val byProcedure = HashMap<String, ProcedureTable>()
            tables.forEach { table -> table.procedures().forEach { byProcedure[it] = table } }
            return TableRegistry(byProcedure, ProcedureManifest(), roles)
        }

        /**
         * Reads every manifest, then resolves only the tables [roles] permits.
         *
         * A table is instantiated through its `INSTANCE` field because the plugin emits it as a
         * Kotlin `object`; there is nothing to construct and nothing to pass.
         */
        public fun load(
            roles: Set<Role>,
            loader: ClassLoader = TableRegistry::class.java.classLoader,
        ): TableRegistry {
            val manifest = ProcedureManifest.load(loader)
            val wanted = manifest.entries.filter { it.role == null || Role(it.role) in roles }

            val tables = HashMap<String, ProcedureTable>()
            wanted.map { it.table }.distinct().forEach { className ->
                val table = instantiate(className, loader, roles)
                table.procedures().forEach { id -> tables[id] = table }
            }

            // A table that does not own what the manifest says it owns means the two were built at
            // different times. Caught here rather than as a puzzling absence on the first call.
            wanted.forEach { entry ->
                if (entry.id !in tables) {
                    error(
                        "The manifest says `${entry.id}` lives in ${entry.table}, but that table " +
                            "does not own it. The manifest and the compiled tables are out of step; " +
                            "rebuild ${entry.module.ifEmpty { "the module that owns it" }}."
                    )
                }
            }
            return TableRegistry(tables, manifest, roles)
        }

        private fun instantiate(className: String, loader: ClassLoader, roles: Set<Role>): ProcedureTable =
            try {
                Class.forName(className, true, loader).getField("INSTANCE").get(null) as ProcedureTable
            } catch (missing: NoClassDefFoundError) {
                // The dist constraint, caught. Naming the class it could not reach is the whole
                // value of failing here: it is nearly always a body annotated with the wrong role.
                error(
                    "Table `$className` cannot be loaded on a node holding " +
                        "${roles.map { it.value }.sorted()}: it needs `${missing.message}`, which is " +
                        "not on this classpath. Check the @RpcRole on the bodies in it."
                )
            }
    }
}
