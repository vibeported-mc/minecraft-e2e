package dev.vibeported.mc.e2e

import kotlinx.serialization.Serializable

/**
 * Identifies one lifted block in the generated dispatch table.
 *
 * The value is structural and human readable, because it is what the reports print:
 * `dev.example.MovementKt:movement/block moved/server[0]/client[0]`. It deliberately carries no
 * line numbers, so reformatting a test file does not churn every id in it.
 */
@JvmInline
@Serializable
public value class BlockId(public val value: String) {
    override fun toString(): String = value
}

/** Identifies one `shared` value: `dev.example.MovementKt:movement/block moved#pos`. */
@JvmInline
@Serializable
public value class SharedId(public val value: String) {
    override fun toString(): String = value
}

/** Which kind of process a block runs on. */
@Serializable
public enum class NodeRole {
    ORCHESTRATOR,
    SERVER,
    CLIENT,
}

/**
 * Addresses one participant. There is exactly one orchestrator and one server for now; clients are
 * distinguished by [index], so `client(1) { }` addresses the second client.
 */
@Serializable
public data class NodeId(
    public val role: NodeRole,
    public val index: Int = 0,
) {
    override fun toString(): String =
        if (role == NodeRole.CLIENT) "client[$index]" else role.name.lowercase()

    public companion object {
        public val ORCHESTRATOR: NodeId = NodeId(NodeRole.ORCHESTRATOR)
        public val SERVER: NodeId = NodeId(NodeRole.SERVER)
        public fun client(index: Int = 0): NodeId = NodeId(NodeRole.CLIENT, index)
    }
}
