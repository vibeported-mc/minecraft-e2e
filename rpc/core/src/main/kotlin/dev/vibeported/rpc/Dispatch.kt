package dev.vibeported.rpc

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.KSerializer

/*
 * What a `rpcCall { }` becomes.
 *
 * The compiler plugin lifts the body into a table and rewrites the call site into one of these,
 * passing the id it assigned, the serializers it resolved from the static types, and the role it
 * read off the annotation. Nothing below decides anything the source did not already say.
 */

/**
 * Runs one procedure on one node.
 *
 * The local case is the one worth protecting: when the target is this node the body is invoked with
 * the real objects and nothing is encoded at all. That is what makes it affordable to build an
 * ordinary API out of these calls, where a helper reaching for a procedure on the node it is already
 * running on must not pay for a round trip to say so.
 */
@PluginGenerated
public suspend fun <R> dispatchTo(
    target: NodeId,
    procedure: String,
    role: String?,
    args: List<Any?>,
    argSerializers: List<KSerializer<*>>,
    resultSerializer: KSerializer<R>,
): R {
    val node = currentNode()

    if (target == node.id) {
        @Suppress("UNCHECKED_CAST")
        return node.tables.tableFor(procedure).invoke(procedure, node.services, args) as R
    }

    // Checked before sending, so a misrouted call fails here with both roles in the message rather
    // than as a stranger's stack trace arriving back over the wire.
    role?.let { required ->
        val info = node.membership.snapshot().firstOrNull { it.id == target }
        if (info != null && Role(required) !in info.roles) {
            error(
                "`$procedure` needs role `$required`, and $target holds " +
                    "${info.roles.map { it.value }.sorted()}. It cannot run this."
            )
        }
    }

    val encoded = args.mapIndexed { index, value ->
        node.format.encodeAny(argSerializers[index], value)
    }
    val result = node.outbound.call(target, procedure, encoded)

    @Suppress("UNCHECKED_CAST")
    return when {
        result == null -> null as R
        else -> node.format.decode(resultSerializer, result)
    }
}

/** Resolves a target to exactly one node, or says which of "none" or "several" went wrong. */
@PluginGenerated
public suspend fun <R> dispatch(
    target: RpcTarget,
    procedure: String,
    role: String?,
    args: List<Any?>,
    argSerializers: List<KSerializer<*>>,
    resultSerializer: KSerializer<R>,
): R {
    val matched = resolve(target, role)
    val only = when (matched.size) {
        1 -> matched.single()
        0 -> error("`$procedure` matched no node. ${describe(target, role)}")
        else -> error(
            "`$procedure` matched ${matched.size} nodes (${matched.joinToString()}), and a single " +
                "call needs one. Use a fan-out, or name the node."
        )
    }
    return dispatchTo(only, procedure, role, args, argSerializers, resultSerializer)
}

/**
 * Runs one procedure on every matching node, at once, failing as soon as any of them does.
 *
 * Structured concurrency does the work: the first failure cancels its siblings and propagates, which
 * is what a caller wants when one bad node has already made the answer meaningless.
 */
@PluginGenerated
public suspend fun <R> dispatchEach(
    target: RpcTarget,
    procedure: String,
    role: String?,
    args: List<Any?>,
    argSerializers: List<KSerializer<*>>,
    resultSerializer: KSerializer<R>,
    parallel: Boolean = true,
): Map<NodeId, R> {
    val nodes = resolve(target, role)
    if (!parallel) {
        return nodes.associateWith { dispatchTo(it, procedure, role, args, argSerializers, resultSerializer) }
    }
    return coroutineScope {
        nodes
            .map { id ->
                async {
                    id to dispatchTo(id, procedure, role, args, argSerializers, resultSerializer)
                }
            }
            .awaitAll()
            .toMap()
    }
}

/**
 * The same, except that nothing is cancelled and every node reports for itself.
 *
 * For the cases where one node being unreachable is information rather than the end of the matter --
 * asking every client what it can see, and wanting to know which one disagreed.
 */
@PluginGenerated
public suspend fun <R> dispatchEachCatching(
    target: RpcTarget,
    procedure: String,
    role: String?,
    args: List<Any?>,
    argSerializers: List<KSerializer<*>>,
    resultSerializer: KSerializer<R>,
    parallel: Boolean = true,
): Map<NodeId, Result<R>> {
    val nodes = resolve(target, role)
    suspend fun attempt(id: NodeId): Result<R> = runCatching {
        dispatchTo(id, procedure, role, args, argSerializers, resultSerializer)
    }

    if (!parallel) return nodes.associateWith { attempt(it) }

    return coroutineScope {
        nodes.map { id -> async { id to attempt(id) } }.awaitAll().toMap()
    }
}

/**
 * Which nodes a target names, narrowed by the body's own role.
 *
 * The narrowing is the interesting half. A body annotated for one role cannot be loaded by a node
 * without it, so a fan-out that matched such a node would be asking for a failure the source already
 * knew about. Intersecting here means the mistake cannot be expressed rather than being caught late.
 */
private suspend fun resolve(target: RpcTarget, role: String?): List<NodeId> {
    val node = currentNode()
    val required = role?.let(::Role)

    return when (target) {
        is RpcTarget.Exactly -> listOf(target.id)
        is RpcTarget.Where -> node.membership.snapshot()
            .filter { target.match(it) }
            .filter { required == null || required in it.roles }
            .map { it.id }
            .sortedBy { it.value }
    }
}

private fun describe(target: RpcTarget, role: String?): String = when (target) {
    is RpcTarget.Exactly -> "It named ${target.id}, which is not in the membership."
    is RpcTarget.Where -> "No node satisfied the predicate" +
        (role?.let { " and held role `$it`" } ?: "") + "."
}

@Suppress("UNCHECKED_CAST")
private fun WireFormat.encodeAny(serializer: KSerializer<*>, value: Any?): ByteArray =
    encode(serializer as KSerializer<Any?>, value)
