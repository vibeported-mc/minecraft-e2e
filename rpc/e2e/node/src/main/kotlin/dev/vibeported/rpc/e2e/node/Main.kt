package dev.vibeported.rpc.e2e.node

import dev.vibeported.rpc.NodeId
import dev.vibeported.rpc.Role
import dev.vibeported.rpc.host.HubAddress
import dev.vibeported.rpc.host.RpcHost
import dev.vibeported.rpc.transport.SocketHub
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking

/**
 * A node in a process of its own, driven entirely from the command line.
 *
 * ```
 * java -Drpc.node=b -Drpc.roles=B -Drpc.hub=127.0.0.1:5000 -cp ... dev.vibeported.rpc.e2e.node.MainKt
 * ```
 *
 * Told where the hub is rather than discovering it, which is the whole first cut of membership: the
 * supervisor that started this process knows the address, and passing it down is one property. A
 * beacon to find peers with no supervisor at all is a later problem.
 *
 * This is a fixture, not a supported entry point -- [RpcHost] is the supported part, and a real host
 * is usually started by something other than a `main`. What this adds is a program that can be
 * forked with a classpath of somebody else's choosing, which is the only way to test the dist split.
 */
public fun main(): Unit = runBlocking {
    val id = NodeId(requireProperty("rpc.node"))
    val roles = System.getProperty("rpc.roles").orEmpty()
        .split(',')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .map(::Role)
        .toSet()

    // `rpc.hub.serve` runs the hub here as well: the middle of a star is a relay with no opinions
    // about calls, so whichever process starts first may as well hold it.
    val hosted = System.getProperty("rpc.hub.serve")?.toIntOrNull()?.let { SocketHub(it) }
    hosted?.start(this)

    val hub = hosted
        ?.let { HubAddress("127.0.0.1", it.port) }
        ?: HubAddress.parse(requireProperty("rpc.hub"))

    val connection = RpcHost(id = id, roles = roles).connect(this, hub)

    // One line on stdout, and it is a protocol rather than a log: a supervisor reads it to know the
    // node has joined, and how many procedures its roles let it resolve.
    println("rpc.ready $id $hub ${connection.node.tables.procedures().size} procedures")

    Runtime.getRuntime().addShutdownHook(
        Thread {
            runBlocking { connection.leave() }
            hosted?.let { runBlocking { it.stop() } }
        }
    )

    awaitCancellation()
}

private fun requireProperty(name: String): String =
    System.getProperty(name) ?: error(
        "An RPC host needs -D$name. It is told its identity and where the hub is; it discovers " +
            "neither."
    )
