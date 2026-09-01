package dev.vibeported.mc.e2e.plugin

import dev.vibeported.mc.e2e.protocol.E2eIndex
import dev.vibeported.mc.e2e.protocol.NodeId
import dev.vibeported.mc.e2e.node.NodeRunner
import dev.vibeported.mc.e2e.node.TableRegistry
import dev.vibeported.mc.e2e.orchestrator.Orchestrator
import dev.vibeported.mc.e2e.report.RunReport
import dev.vibeported.mc.e2e.rpc.InMemoryHub
import dev.vibeported.mc.e2e.rpc.RpcPeer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * A whole cluster in one JVM, for testing what the compiler plugin emitted.
 *
 * Real runs put the nodes in separate Minecraft processes, but the plugin cannot tell the
 * difference: it emits a table keyed by id and calls through the scope, so wiring the same
 * orchestrator and node runners over the in-memory hub exercises exactly the generated code without
 * needing a game.
 */
class InProcessCluster private constructor(
    private val hub: InMemoryHub,
    val orchestrator: Orchestrator,
    private val jobs: List<Job>,
) : AutoCloseable {

    suspend fun runAll(): RunReport = orchestrator.runAll()

    override fun close() {
        jobs.forEach { it.cancel() }
        hub.shutdown()
    }

    companion object {
        fun start(
            scope: CoroutineScope,
            index: E2eIndex,
            loader: ClassLoader,
            clients: Int = 1,
        ): InProcessCluster {
            val hub = InMemoryHub()
            val registry = TableRegistry(index, loader)

            val orchestrator = Orchestrator(
                peer = RpcPeer(hub.connect(NodeId.ORCHESTRATOR)),
                index = index,
            )

            val runners = buildList {
                add(NodeId.SERVER)
                repeat(clients) { add(NodeId.client(it)) }
            }.map { id ->
                NodeRunner(
                    id = id,
                    peer = RpcPeer(hub.connect(id)),
                    registry = registry,
                    // No game here, so no game thread to run blocks on. These snippets only exercise
                    // the generated table and the shared-value plumbing, never Minecraft itself.
                    server = null,
                    client = null,
                )
            }

            val jobs = buildList {
                add(orchestrator.start(scope))
                runners.forEach { add(it.start(scope)) }
            }
            return InProcessCluster(hub, orchestrator, jobs)
        }
    }
}
