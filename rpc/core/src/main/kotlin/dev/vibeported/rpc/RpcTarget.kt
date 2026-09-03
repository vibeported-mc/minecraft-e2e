package dev.vibeported.rpc

/**
 * Who a call is for.
 *
 * Either one node by name, or every node matching a predicate. The predicate is an ordinary Kotlin
 * lambda and is evaluated where the call is made, against the local membership replica -- which is
 * what keeps it from having to be something that can cross a wire.
 */
public sealed interface RpcTarget {

    /** One node, named. Fails if it is not there. */
    public data class Exactly(public val id: NodeId) : RpcTarget

    /**
     * Every node the predicate accepts.
     *
     * Matching nothing is not an error here. A caller who meant "at least one" says so by asserting
     * on the result, which reads better than a flag that has to be remembered.
     */
    public data class Where(public val match: (NodeInfo) -> Boolean) : RpcTarget
}

/** @see RpcTarget.Exactly */
public fun node(id: String): RpcTarget = RpcTarget.Exactly(NodeId(id))

/** @see RpcTarget.Exactly */
public fun node(id: NodeId): RpcTarget = RpcTarget.Exactly(id)

/** Every node holding [role]. @see RpcTarget.Where */
public fun everyNodeWith(role: Role): RpcTarget = RpcTarget.Where { role in it.roles }

/** @see RpcTarget.Where */
public fun everyNodeWhere(match: (NodeInfo) -> Boolean): RpcTarget = RpcTarget.Where(match)
