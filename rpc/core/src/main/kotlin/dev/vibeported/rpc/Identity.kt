package dev.vibeported.rpc

import kotlinx.serialization.Serializable

/**
 * Who a node is.
 *
 * Opaque and free-form on purpose. The framework never parses one, so a deployment is free to say
 * `server`, `client:steve`, or a UUID, and nothing downstream has an opinion about which.
 */
@JvmInline
@Serializable
public value class NodeId(public val value: String) {
    override fun toString(): String = value
}

/**
 * What a node is for.
 *
 * A string rather than an enum, because the set of roles belongs to whoever is deploying: a game has
 * a server and clients, a build farm has coordinators and workers, and neither should have to widen
 * an enum in this module to say so.
 *
 * Roles carry real weight -- they decide which procedure tables a node loads at all -- so they are a
 * type of their own rather than a bare string that could be confused with a node's name.
 */
@JvmInline
@Serializable
public value class Role(public val value: String) {
    override fun toString(): String = value
}

/**
 * A node as the rest of the cluster sees it.
 *
 * [tags] exist so a fan-out can select on something the framework knows nothing about -- which world
 * a client is in, which shard a worker owns -- without every such idea needing a field here.
 */
@Serializable
public data class NodeInfo(
    public val id: NodeId,
    public val roles: Set<Role> = emptySet(),
    public val tags: Map<String, String> = emptyMap(),
) {
    public operator fun contains(role: Role): Boolean = role in roles

    override fun toString(): String = when {
        roles.isEmpty() -> id.value
        else -> "${id.value}${roles.map { it.value }.sorted()}"
    }
}
