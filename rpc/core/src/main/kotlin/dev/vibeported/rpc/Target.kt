package dev.vibeported.rpc

/**
 * Who a call is for.
 *
 * Either one node by name, or every node matching a predicate. The predicate is an ordinary Kotlin
 * lambda and is evaluated where the call is made, against the local membership replica -- which is
 * what keeps it from having to be something that can cross a wire.
 */
public sealed interface Target {

    /** One node, named. Fails if it is not there. */
    public data class Exactly(public val id: NodeId) : Target

    /**
     * Every node the predicate accepts.
     *
     * Matching nothing is not an error here. A caller who meant "at least one" says so by asserting
     * on the result, which reads better than a flag that has to be remembered.
     */
    public data class Where(public val match: (NodeInfo) -> Boolean) : Target
}

/** @see Target.Exactly */
public fun node(id: String): Target = Target.Exactly(NodeId(id))

/** @see Target.Exactly */
public fun node(id: NodeId): Target = Target.Exactly(id)

/** Every node holding [role]. @see Target.Where */
public fun everyNodeWith(role: Role): Target = Target.Where { role in it.roles }

/** @see Target.Where */
public fun everyNodeWhere(match: (NodeInfo) -> Boolean): Target = Target.Where(match)
