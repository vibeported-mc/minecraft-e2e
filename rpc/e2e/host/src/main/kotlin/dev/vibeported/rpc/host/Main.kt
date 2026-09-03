package dev.vibeported.rpc.host

import dev.vibeported.rpc.NodeId
import dev.vibeported.rpc.Role
import dev.vibeported.rpc.transport.SocketHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/**
 * A node in a process of its own.
 *
 * ```
 * java -Drpc.node=b -Drpc.roles=B -Drpc.hub=127.0.0.1:5000 -cp ... dev.vibeported.rpc.host.MainKt
 * ```
 *
 * Told where the hub is rather than discovering it, which is the whole first cut of membership: the
 * supervisor that started this process knows the address, and passing it down is one property. A
 * beacon to find peers with no supervisor at all is a later problem, and naming it now would cost a
 * membership protocol nothing yet needs.
 *
 * `rpc.hub.serve` runs the hub here as well. The middle of a star is a relay with no opinions about
 * calls, so whichever process starts first may as well hold it -- and in a test that is the process
 * doing the driving.
 */
public fun main(): Unit = runBlocking {
    val id = NodeId(requireProperty("rpc.node"))
    val roles = System.getProperty("rpc.roles").orEmpty()
        .split(',')
        .filter { it.isNotBlank() }
        .map { Role(it.trim()) }
        .toSet()

    val hosted = System.getProperty("rpc.hub.serve")?.toIntOrNull()?.let { SocketHub(it) }
    hosted?.start(this)

    val hub = hosted
        ?.let { HubAddress("127.0.0.1", it.port) }
        ?: HubAddress.parse(requireProperty("rpc.hub"))

    val node = NodeHost.join(scope = this, id = id, roles = roles, hub = hub)

    // One line on stdout, and it is a protocol rather than a log: a supervisor reads it to know the
    // node has joined and, when the hub was asked for on port zero, which port it landed on.
    println("rpc.ready $id $hub ${node.node.tables.procedures().size} procedures")

    Runtime.getRuntime().addShutdownHook(
        Thread {
            runBlocking { node.leave() }
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
