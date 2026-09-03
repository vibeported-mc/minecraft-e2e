package dev.vibeported.rpc.host

import dev.vibeported.rpc.NodeId
import dev.vibeported.rpc.NodeInfo
import dev.vibeported.rpc.ProcedureServer
import dev.vibeported.rpc.Role
import dev.vibeported.rpc.RpcNode
import dev.vibeported.rpc.Services
import dev.vibeported.rpc.TableRegistry
import dev.vibeported.rpc.transport.HUB
import dev.vibeported.rpc.transport.LiveMembership
import dev.vibeported.rpc.transport.RpcPeer
import dev.vibeported.rpc.transport.SocketTransport
import kotlinx.coroutines.CoroutineScope

/** Where the middle of the star is. Told, never discovered -- @see NodeHost. */
public data class HubAddress(val host: String, val port: Int) {

    override fun toString(): String = "$host:$port"

    public companion object {
        /** Parses `host:port`, which is the form the system property takes. */
        public fun parse(value: String): HubAddress {
            val separator = value.lastIndexOf(':')
            require(separator > 0 && separator < value.length - 1) {
                "An RPC hub address is `host:port`, and `$value` is not."
            }
            val port = value.substring(separator + 1).toIntOrNull()
            requireNotNull(port) { "`$value` has no port number." }
            return HubAddress(value.substring(0, separator), port)
        }
    }
}

/** A node that has joined, and the two things needed to make it leave again. */
public class RunningNode internal constructor(
    public val node: RpcNode,
    private val peer: RpcPeer,
) {
    /**
     * Leaves in a way the rest of the cluster hears about immediately.
     *
     * Without the goodbye the hub learns of the departure only when the socket breaks, which is
     * whenever the OS gets round to it -- so a caller can be left waiting on a node that has
     * already exited.
     */
    public suspend fun leave() {
        peer.leave()
        RpcNode.uninstall()
    }
}

/**
 * Puts a node in a process and connects it to the hub.
 *
 * This is the piece that had no home before: the transport knew how to carry a call and the core
 * knew how to run one, but assembling a node out of them was six lines every caller wrote for
 * itself. Six lines is enough to get wrong -- forgetting [RpcNode.install], most usefully, which
 * fails much later and somewhere else.
 *
 * The tables come from the manifest and from [roles], never from a list passed in. That is the
 * whole dist story in one line: a node resolves the table classes its roles permit and no others,
 * eagerly, so a body naming a class this process does not have brings it down here rather than in
 * the middle of a call.
 */
public object NodeHost {

    public suspend fun join(
        scope: CoroutineScope,
        id: NodeId,
        roles: Set<Role> = emptySet(),
        hub: HubAddress,
        services: Services = Services(),
        loader: ClassLoader = NodeHost::class.java.classLoader,
        /**
         * What this node serves, when it should not be read off the classpath.
         *
         * Null is the ordinary answer and means "whatever the manifest and [roles] permit". Passing
         * an empty registry is how a caller-only process is written -- an orchestrator that drives
         * a cluster and runs none of its procedures. That is not a special case bolted on: such a
         * process holds the jar the bodies were written in and none of the jars they need, so
         * loading its tables would be exactly the failure this eager resolution exists to raise.
         */
        tables: TableRegistry? = null,
    ): RunningNode {
        val transport = SocketTransport.connect(id, hub.host, hub.port)
        val peer = RpcPeer(transport, HUB)
        val membership = LiveMembership()

        val node = RpcNode(
            info = NodeInfo(id, roles),
            tables = tables ?: TableRegistry.load(roles, loader),
            membership = membership,
            services = services,
            outbound = peer,
        )

        peer.onRequest = ProcedureServer(node)::handle
        peer.onRoster = { membership.update(it) }
        peer.start(scope)
        peer.announce(node.info)

        // A process has one node, and code it calls into is free to start a `runBlocking` of its
        // own -- which inherits no coroutine context at all. @see RpcNode.install
        RpcNode.install(node)
        return RunningNode(node, peer)
    }
}
