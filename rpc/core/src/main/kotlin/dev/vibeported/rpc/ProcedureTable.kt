package dev.vibeported.rpc

/**
 * The generated home of a set of procedure bodies.
 *
 * The compiler plugin emits one of these per source file and per role. Lookup is by a stable string
 * id, which is what lets a node run a body whose source lived in another process entirely.
 *
 * The split by role is not tidiness. A node whose classpath lacks the classes a body touches cannot
 * load the class those bodies live in -- not call it, *load* it -- so the bodies have to be in
 * separate classes and only the right ones may ever be resolved.
 *
 * Serialization lives here rather than in the dispatcher because only the generated code knows what
 * each parameter was declared as. A node handed a list of byte arrays has no idea what they mean;
 * the table was generated from the very lambda that named their types.
 */
public interface ProcedureTable {

    /**
     * Every id this table owns.
     *
     * Declared rather than inferred so a node can check the manifest it read against the tables it
     * loaded, and say so when a stale build leaves them disagreeing.
     */
    public val procedures: Set<String>

    /**
     * Runs one body against the services of the node it landed on.
     *
     * [services] rather than a receiver, because the generated code knows which receiver its body
     * declared and can ask for it. That keeps the receiver's type off the wire and out of the
     * dispatcher, which would otherwise have to carry a type it has no use for.
     */
    public suspend fun invoke(procedure: String, services: Services, args: List<Any?>): Any?

    /** Turns arguments that arrived over the wire back into the objects the body expects. */
    public fun decodeArgs(procedure: String, args: List<ByteArray>, format: WireFormat): List<Any?>

    /** Encodes what a body returned. Null when it returns nothing. */
    public fun encodeResult(procedure: String, value: Any?, format: WireFormat): ByteArray?
}

/** Thrown when a table is handed an id it does not own. */
public class NoSuchProcedureException(public val procedure: String) :
    RuntimeException("No procedure `$procedure` in this table")
