package dev.vibeported.mc.e2e.samples

import dev.vibeported.mc.e2e.NodeId
import dev.vibeported.mc.e2e.NodeRole
import dev.vibeported.mc.e2e.cluster.LocalCluster
import dev.vibeported.mc.e2e.node.Facilities
import dev.vibeported.mc.e2e.report.ConsoleReporter
import dev.vibeported.mc.e2e.report.JsonReporter
import dev.vibeported.mc.e2e.world.MockClientWorld
import dev.vibeported.mc.e2e.world.MockServerWorld
import dev.vibeported.mc.e2e.world.MockWorldNetwork
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.exitProcess

/**
 * Runs every compiled suite through an in-process cluster.
 *
 * All this has to supply is what each node can offer a block; the tests themselves were found by
 * reading the index the compiler plugin wrote, with no reference to any of them by name here.
 */
fun main(): Unit = runBlocking {
    val network = MockWorldNetwork()

    val cluster = LocalCluster.start(
        scope = this,
        clients = 1,
        facilitiesFor = { node: NodeId ->
            when (node.role) {
                NodeRole.SERVER -> Facilities.of(MockServerWorld::class to network.server)
                NodeRole.CLIENT -> Facilities.of(MockClientWorld::class to network.client(node.index))
                NodeRole.ORCHESTRATOR -> Facilities.EMPTY
            }
        },
    )

    val report = try {
        cluster.runAll()
    } finally {
        cluster.close()
    }

    print(ConsoleReporter.render(report))

    val json = File("build/reports/e2e/report.json")
    json.parentFile.mkdirs()
    json.writeText(JsonReporter.render(report))
    println("wrote ${json.absolutePath}")

    if (!report.ok) exitProcess(1)
}
