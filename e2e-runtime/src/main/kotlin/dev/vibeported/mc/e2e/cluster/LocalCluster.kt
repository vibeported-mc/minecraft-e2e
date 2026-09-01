package dev.vibeported.mc.e2e.cluster

import dev.vibeported.mc.e2e.NodeId
import dev.vibeported.mc.e2e.node.Facilities
import dev.vibeported.mc.e2e.node.NodeRunner
import dev.vibeported.mc.e2e.node.TableRegistry
import dev.vibeported.mc.e2e.orchestrator.Orchestrator
import dev.vibeported.mc.e2e.report.RunReport
import dev.vibeported.mc.e2e.rpc.InMemoryHub
import dev.vibeported.mc.e2e.rpc.RpcPeer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * An orchestrator, a server and some clients, all in this JVM, wired through [InMemoryHub].
 *
 * The nodes still only reach each other through the transport, so nothing above this class knows
 * they are not separate processes. Swapping in a socket transport is meant to be a change to this
 * one factory and nothing else.
 */
public class LocalCluster internal constructor(
    private val hub: InMemoryHub,
    public val orchestrator: Orchestrator,
    public val nodes: List<NodeRunner>,
    private val jobs: List<Job>,
) : AutoCloseable {

    public suspend fun runAll(): RunReport = orchestrator.runAll()

    override fun close() {
        jobs.forEach { it.cancel() }
        hub.shutdown()
    }

    public companion object {
        public fun start(
            scope: CoroutineScope,
            clients: Int = 1,
            registry: TableRegistry = TableRegistry.load(),
            callTimeout: Duration = 60.seconds,
            facilitiesFor: (NodeId) -> Facilities = { Facilities.EMPTY },
        ): LocalCluster {
            require(clients >= 1) { "A cluster needs at least one client" }
            val hub = InMemoryHub()

            val orchestrator = Orchestrator(
                peer = RpcPeer(hub.connect(NodeId.ORCHESTRATOR), callTimeout),
                registry = registry,
                facilities = facilitiesFor(NodeId.ORCHESTRATOR),
            )

            val runners = buildList {
                add(NodeId.SERVER)
                repeat(clients) { add(NodeId.client(it)) }
            }.map { id ->
                NodeRunner(
                    id = id,
                    peer = RpcPeer(hub.connect(id), callTimeout),
                    registry = registry,
                    facilities = facilitiesFor(id),
                )
            }

            val jobs = buildList {
                add(orchestrator.start(scope))
                runners.forEach { add(it.start(scope)) }
            }
            return LocalCluster(hub, orchestrator, runners, jobs)
        }
    }
}
