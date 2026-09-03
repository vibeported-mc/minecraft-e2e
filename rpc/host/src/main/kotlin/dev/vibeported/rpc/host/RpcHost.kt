package dev.vibeported.rpc.host

import dev.vibeported.rpc.CborWireFormat
import dev.vibeported.rpc.Membership
import dev.vibeported.rpc.NodeId
import dev.vibeported.rpc.NodeInfo
import dev.vibeported.rpc.ProcedureServer
import dev.vibeported.rpc.Role
import dev.vibeported.rpc.RpcNode
import dev.vibeported.rpc.SerializerRegistry
import dev.vibeported.rpc.Services
import dev.vibeported.rpc.TableRegistry
import dev.vibeported.rpc.WireFormat
import dev.vibeported.rpc.transport.HUB
import kotlinx.serialization.cbor.Cbor
import dev.vibeported.rpc.transport.LiveMembership
import dev.vibeported.rpc.transport.RpcPeer
import dev.vibeported.rpc.transport.SocketTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** Where the middle of the star is. Told, never discovered. @see RpcHost */
public data class HubAddress(val host: String, val port: Int) {

    override fun toString(): String = "$host:$port"

    public companion object {
        /** Parses `host:port`, which is the form a system property or a flag takes. */
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

/** A node that has joined, and the way to make it leave again. */
public interface RpcConnection {

    public val node: RpcNode

    /** Who else is out there, as this node currently sees it. */
    public val membership: Membership

    /**
     * Leaves in a way the rest of the cluster hears about immediately.
     *
     * Without the goodbye the hub learns of the departure only when the socket breaks, whenever the
     * OS gets round to noticing -- so a caller can be left waiting on a node that has already gone.
     */
    public suspend fun leave()
}

/**
 * Puts a node in a process, and connects it to a hub.
 *
 * The piece that otherwise gets written once per program: connect, resolve the tables this node's
 * roles permit, wire the peer, announce, install. Six lines, and the one that is easy to forget --
 * [RpcNode.install] -- fails much later and somewhere else, in code that has no idea a node was
 * needed.
 *
 * Everything about it is a constructor parameter because the three programs that want one differ in
 * every particular: a game passes its event loop as [dispatcher] and the game itself in [services];
 * an orchestrator passes an empty [tables] because it drives a cluster and runs none of its
 * procedures; a plain worker passes nothing but what it read from its command line.
 */
public class RpcHost(
    public val id: NodeId,
    public val roles: Set<Role> = emptySet(),
    /** What this node offers the bodies that land on it. @see Services */
    public val services: Services = Services(),
    /**
     * How values are encoded, when the classpath's own answer will not do.
     *
     * The default is CBOR over every serializer the classpath declared -- assembled by
     * [SerializerRegistry] from the manifests the compiler wrote, so a module that declares an
     * `@RpcSerializer` is understood by every node that has it and nothing has to be registered
     * here. Pass one only to change the encoding itself.
     */
    format: WireFormat? = null,
    /**
     * What this node serves, when it should not be read off the classpath.
     *
     * Null is the ordinary answer and means "whatever the manifest and [roles] permit". An empty
     * registry is how a caller-only process is written -- one that holds the jar the bodies were
     * written in and none of the jars they need, where resolving tables would fail on the first.
     */
    public val tables: TableRegistry? = null,
    /**
     * Where a procedure body runs.
     *
     * The default runs it wherever the call arrived, which is right for a worker and wrong for
     * anything with an event loop: a game hands its loop here, and every statement in every body
     * then runs on the game thread with no wrapper to remember. Suspending inside a body releases
     * the loop, so the loop keeps turning and the body resumes back on it.
     */
    public val dispatcher: CoroutineContext = EmptyCoroutineContext,
    /**
     * Told when a body throws here, before the failure goes back to whoever called it.
     *
     * For evidence only, and it changes nothing that travels: the failure crosses as its type,
     * message and stack exactly as it would have. What this buys is the chance to take a picture of
     * a node at the moment it failed -- which is the cheapest evidence there is for the failures
     * hardest to read -- and to send that picture as an ordinary value through an ordinary call.
     *
     * Anything it throws is swallowed. Evidence that fails to be collected must not replace the
     * failure being reported.
     */
    public val onBodyFailure: suspend (procedure: String, failure: Throwable) -> Unit = { _, _ -> },
    public val loader: ClassLoader = RpcHost::class.java.classLoader,
) {

    // Built from `loader` rather than from this class's own, which is the same distinction the
    // table registry makes: under a mod loader the two are different, and the one holding the
    // serializers is the one the caller named.
    public val format: WireFormat = format
        ?: CborWireFormat(Cbor { serializersModule = SerializerRegistry.load(loader) })

    public suspend fun connect(scope: CoroutineScope, hub: HubAddress): RpcConnection {
        val transport = SocketTransport.connect(id, hub.host, hub.port)
        val peer = RpcPeer(transport, HUB)
        val membership = LiveMembership()

        val node = RpcNode(
            info = NodeInfo(id, roles),
            tables = tables ?: TableRegistry.load(roles, loader),
            membership = membership,
            services = services,
            format = format,
            outbound = peer,
        )

        // The dispatcher wraps the whole handler rather than only the body. Decoding an argument is
        // a few bytes of work, and splitting the two would mean a second seam for the sake of it.
        val server = ProcedureServer(node)
        peer.onRequest = { procedure, args ->
            withContext(dispatcher) {
                try {
                    server.handle(procedure, args)
                } catch (cancelled: CancellationException) {
                    // Asked to stop, which is not a failure and has nothing to photograph.
                    throw cancelled
                } catch (failure: Throwable) {
                    runCatching { onBodyFailure(procedure, failure) }
                    throw failure
                }
            }
        }
        peer.onRoster = { membership.update(it) }

        peer.start(scope)
        peer.announce(node.info)

        // A process has one node, and code it calls into is free to start a `runBlocking` of its
        // own -- which inherits no coroutine context at all. @see RpcNode.install
        RpcNode.install(node)
        return Connected(node, membership, peer)
    }

    private class Connected(
        override val node: RpcNode,
        override val membership: Membership,
        private val peer: RpcPeer,
    ) : RpcConnection {

        override suspend fun leave() {
            peer.leave()
            RpcNode.uninstall()
        }
    }
}
