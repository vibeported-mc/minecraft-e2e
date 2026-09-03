package dev.vibeported.rpc

/**
 * What a procedure body sees on the node that runs it.
 *
 * Deliberately thin, and deliberately extensible. A layer that knows what its nodes are for declares
 * its own scope and its own calls returning it, so a body written for a game client sees that
 * client and a body written for a worker sees the worker:
 *
 * ```
 * class MinecraftClientScope(node: NodeInfo, services: Services, val minecraftClient: Minecraft) : RpcScope
 *
 * suspend fun <R> client(name: String, @RpcLift body: RpcBody0<MinecraftClientScope, R>): R =
 *     rpcCall(node(name), body)
 * ```
 *
 * Nothing about that needs the compiler plugin. The scope reaches the body because the node it lands
 * on provides one, and the body is dispatched because `client` handed it to a call.
 */
public interface RpcScope {
    /** The node this body ended up on. */
    public val node: NodeInfo

    /** What that node has to offer. @see Services */
    public val services: Services
}

/** The scope a node provides for itself, when nothing richer was registered. */
internal class NodeScope(
    override val node: NodeInfo,
    override val services: Services,
) : RpcScope
