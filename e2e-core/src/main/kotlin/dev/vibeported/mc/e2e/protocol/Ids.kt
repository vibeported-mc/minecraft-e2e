package dev.vibeported.mc.e2e.protocol

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
public value class ProcedureId(public val value: String) {
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
 * Addresses one participant. There is one orchestrator and one server; clients are distinguished by
 * name, so `client("steve") { }` addresses the client playing as steve.
 *
 * A name rather than an index because a test says who it means, and a report that says
 * `client[steve]` needs no lookup to read.
 */
@Serializable
public data class NodeId(
    public val role: NodeRole,
    /** The client name for a client, and empty for the server and the orchestrator. */
    public val name: String = "",
) {
    override fun toString(): String =
        if (role == NodeRole.CLIENT) "client[$name]" else role.name.lowercase()

    public companion object {
        public val ORCHESTRATOR: NodeId = NodeId(NodeRole.ORCHESTRATOR)
        public val SERVER: NodeId = NodeId(NodeRole.SERVER)
        public fun client(name: String): NodeId = NodeId(NodeRole.CLIENT, name)
    }
}
