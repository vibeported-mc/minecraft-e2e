package dev.vibeported.rpc.e2e.layer

import dev.vibeported.rpc.RpcRole
import dev.vibeported.rpc.e2e.a.Alpha
import dev.vibeported.rpc.e2e.b.Beta
import dev.vibeported.rpc.node
import dev.vibeported.rpc.rpcCall

/**
 * The layer both nodes load, holding bodies only one of them can run.
 *
 * This jar is the dist constraint in miniature. It is compiled against both halves and shipped to
 * every node, so the class file for the `B` body is physically present on a node that has no `Beta`
 * to run it -- which is exactly the shape a NeoForge mod jar has on a dedicated server, and exactly
 * the shape `ServiceLoader` cannot cope with, since iterating a service instantiates every
 * implementation and would construct that table on the node least able to hold it.
 *
 * What saves it is that the two bodies land in different classes. The plugin puts an unannotated
 * body in `CallsKt_Rpc`, which every node loads, and a `@RpcRole("B")` body in `CallsKt_Rpc_B`,
 * which only a node holding `B` ever resolves. A node without `Beta` reads the name of the second
 * from the manifest, declines to load it, and starts.
 */
suspend fun anywhere(target: String): String = rpcCall(node(target)) { Alpha.callA() }

suspend fun onlyOnB(target: String): String =
    rpcCall(node(target)) @RpcRole("B") { Alpha.callA() + "/" + Beta.callB() }
