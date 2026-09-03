package dev.vibeported.rpc.testkit

import dev.vibeported.rpc.NodeId
import dev.vibeported.rpc.NodeInfo
import dev.vibeported.rpc.ProcedureServer
import dev.vibeported.rpc.ProcedureTable
import dev.vibeported.rpc.Role
import dev.vibeported.rpc.RpcNode
import dev.vibeported.rpc.Services
import dev.vibeported.rpc.TableRegistry
import dev.vibeported.rpc.transport.HUB
import dev.vibeported.rpc.transport.InMemoryFabric
import dev.vibeported.rpc.transport.LiveMembership
import dev.vibeported.rpc.transport.RpcPeer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/**
 * A whole cluster inside one test.
 *
 * The wiring a node needs -- transport, peer, membership replica, table registry, and the two
 * callbacks that join them -- is half a dozen lines that are the same every time and wrong in an
 * interesting way if any of them is forgotten. Forgetting to feed the roster, for instance, leaves
 * a fan-out that quietly matches nobody.
 *
 * Every node here shares one [InMemoryFabric], which is the same star the sockets form. Behaviour
 * that holds here holds there, with the exception of anything about framing or connections -- and
 * those are tested against real sockets instead.
 */
public class RpcCluster(private val scope: CoroutineScope) {

    private val fabric = InMemoryFabric()
    private val joined = LinkedHashMap<NodeId, RpcNode>()

    /**
     * Adds a node, announces it, and returns it ready to call and be called.
     *
     * [tables] is a registry rather than a list because that is what a node outside a test holds:
     * one it built by reading the manifests on its own classpath and resolving only what its roles
     * permit. A cluster that assembled the registry itself would be testing a shape nothing else
     * uses, and would quietly hide the step where a role decides what a node can load.
     */
    public suspend fun join(
        id: String,
        roles: Set<String> = emptySet(),
        tables: TableRegistry = TableRegistry.of(emptyList()),
        services: Services = Services(),
    ): RpcNode {
        val nodeId = NodeId(id)
        val transport = fabric.connect(nodeId)
        val peer = RpcPeer(transport, HUB)
        val membership = LiveMembership()

        val node = RpcNode(
            info = NodeInfo(nodeId, roles.map(::Role).toSet()),
            tables = tables,
            membership = membership,
            services = services,
            outbound = peer,
        )

        peer.onRequest = ProcedureServer(node)::handle
        peer.onRoster = { membership.update(it) }
        peer.start(scope)
        peer.announce(node.info)

        joined[nodeId] = node
        return node
    }

    /** Kills a node the way a crash does: no goodbye, just a connection that stops being there. */
    public suspend fun kill(id: String) {
        fabric.disconnect(NodeId(id))
        joined.remove(NodeId(id))
    }

    /**
     * Waits until every node can see every other.
     *
     * Membership arrives asynchronously, so a test that fans out immediately after joining would be
     * racing the roster and would sometimes match too few nodes. Waiting once here is cheaper than
     * every test learning that the hard way.
     */
    public suspend fun awaitEveryoneSeesEveryone(timeoutMillis: Long = 5_000) {
        withTimeout(timeoutMillis) {
            while (joined.values.any { it.membership.snapshot().size < joined.size }) delay(1)
        }
    }
}
