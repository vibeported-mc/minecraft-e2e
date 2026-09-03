package dev.vibeported.rpc

/**
 * Marks a function whose trailing lambda is a procedure body.
 *
 * The reason this is an annotation and not a fixed list of names in the plugin: a layer built on
 * this framework wants its own vocabulary with its own receivers -- `client(name) { }` handing the
 * body a game client, `worker(id) { }` handing it a job runner -- and the body must still be lifted.
 * A wrapper that merely forwarded the lambda could not be lifted at all, because by then the literal
 * is somewhere else. So the plugin rewrites calls to anything wearing this, wherever it is declared.
 *
 * The function's own body is never executed; [notApplied] is what it says when the plugin is missing.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
public annotation class RpcEntryPoint

/**
 * What a procedure body sees on the node that runs it.
 *
 * Deliberately thin. Anything richer belongs to the layer that knows what its nodes are for, which
 * declares its own receiver and its own [RpcEntryPoint] to reach it.
 */
public interface RpcScope {
    /** The node this body ended up on. */
    public val node: NodeInfo

    /** What that node has to offer. @see Services */
    public val services: Services
}

internal class NodeScope(override val node: NodeInfo, override val services: Services) : RpcScope

/**
 * Says that the compiler plugin was not applied, in the one place that can tell.
 *
 * These functions are rewritten at every call site, so reaching a body at run time means the build
 * was missing the plugin -- and the message has to say so, because nothing else about the failure
 * would suggest it.
 */
private fun notApplied(): Nothing = error(
    "The RPC compiler plugin did not rewrite this call. Apply `dev.vibeported.rpc` to the module " +
        "that contains it; without it a procedure body is just a lambda that never leaves home."
)

/*
 * The entry points, at each arity.
 *
 * A body may not capture anything from around it -- it runs in another process, where those values
 * do not exist -- so everything it needs arrives as an argument and comes back as a parameter. The
 * arities are boilerplate in the strict sense, and will be generated once the shape settles.
 */

@RpcEntryPoint
public suspend fun <R> rpcCall(target: RpcTarget, body: suspend RpcScope.() -> R): R = notApplied()

@RpcEntryPoint
public suspend fun <A1, R> rpcCall(target: RpcTarget, a1: A1, body: suspend RpcScope.(A1) -> R): R = notApplied()

@RpcEntryPoint
public suspend fun <A1, A2, R> rpcCall(
    target: RpcTarget,
    a1: A1,
    a2: A2,
    body: suspend RpcScope.(A1, A2) -> R,
): R = notApplied()

@RpcEntryPoint
public suspend fun <A1, A2, A3, R> rpcCall(
    target: RpcTarget,
    a1: A1,
    a2: A2,
    a3: A3,
    body: suspend RpcScope.(A1, A2, A3) -> R,
): R = notApplied()

/** Every matching node, in parallel, failing as soon as one of them does. */
@RpcEntryPoint
public suspend fun <R> forEachRpcCall(
    target: RpcTarget,
    parallel: Boolean = true,
    body: suspend RpcScope.() -> R,
): Map<NodeId, R> = notApplied()

@RpcEntryPoint
public suspend fun <A1, R> forEachRpcCall(
    target: RpcTarget,
    a1: A1,
    parallel: Boolean = true,
    body: suspend RpcScope.(A1) -> R,
): Map<NodeId, R> = notApplied()

/** Every matching node, in parallel, each reporting for itself. */
@RpcEntryPoint
public suspend fun <R> forEachRpcCallCatching(
    target: RpcTarget,
    parallel: Boolean = true,
    body: suspend RpcScope.() -> R,
): Map<NodeId, Result<R>> = notApplied()

@RpcEntryPoint
public suspend fun <A1, R> forEachRpcCallCatching(
    target: RpcTarget,
    a1: A1,
    parallel: Boolean = true,
    body: suspend RpcScope.(A1) -> R,
): Map<NodeId, Result<R>> = notApplied()
