package dev.vibeported.mc.e2e.launcher

import dev.vibeported.mc.e2e.protocol.E2eIndex
import dev.vibeported.mc.e2e.protocol.NodeId
import dev.vibeported.mc.e2e.orchestrator.Orchestrator
import dev.vibeported.mc.e2e.report.ConsoleReporter
import dev.vibeported.mc.e2e.report.JsonReporter
import dev.vibeported.mc.e2e.report.RunReport
import dev.vibeported.mc.e2e.rpc.RpcPeer
import dev.vibeported.mc.e2e.rpc.SocketHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds

/**
 * The orchestrator process.
 *
 * It starts the dedicated server, waits for it to dial in, starts the client and waits for the same,
 * then runs every test in the index and reports. It is deliberately the only party that talks to
 * both game processes: they never connect to each other, so everything one hands the other passes
 * through here and lands in one ordered log.
 */
public object E2eMain {

    @JvmStatic
    public fun main(args: Array<String>) {
        val planFile = args.firstOrNull()?.let(::File)
            ?: error("Usage: E2eMain <launch-plan.json>")

        val json = Json { ignoreUnknownKeys = true }
        val plan = json.decodeFromString(LaunchPlan.serializer(), planFile.readText())
        val index = loadIndex(plan, json)

        if (index.files.flatMap { it.suites }.flatMap { it.tests }.isEmpty()) {
            println("e2e: no tests were found in ${plan.indexFiles}. Nothing to run.")
            exitProcess(0)
        }

        val reportDir = File(plan.reportDir).apply { mkdirs() }
        val logDir = File(reportDir, "logs")
        val processes = mutableListOf<GameProcess>()

        val report = try {
            runOrchestrated(plan, index, logDir, processes)
        } finally {
            processes.forEach { it.stop() }
        }

        print(ConsoleReporter.render(report))
        File(reportDir, "report.json").writeText(JsonReporter.render(report))
        println("e2e: report written to ${File(reportDir, "report.json").absolutePath}")
        println("e2e: process logs in ${logDir.absolutePath}")

        exitProcess(if (report.ok) 0 else 1)
    }

    private fun runOrchestrated(
        plan: LaunchPlan,
        index: E2eIndex,
        logDir: File,
        processes: MutableList<GameProcess>,
    ): RunReport = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())

        SocketHub(plan.port).use { hub ->
            hub.start(scope)
            val port = hub.port
            println("e2e: orchestrator listening on port $port")

            val orchestrator = Orchestrator(
                peer = RpcPeer(hub.transport(), callTimeout = plan.testTimeoutSeconds.seconds),
                index = index,
            )
            orchestrator.start(scope)

            println("e2e: starting the dedicated server")
            val server = GameProcess.start(
                spec = plan.server,
                extraJvmArgs = nodeArgs(port) + listOf("-De2e.node.role=SERVER"),
                logDir = logDir,
                echo = ::println,
            )
            processes += server
            awaitNode(hub, NodeId.SERVER, server, plan.startupTimeoutSeconds, logDir)
            println("e2e: server is up")

            plan.clients.forEachIndexed { clientIndex, spec ->
                println("e2e: starting client $clientIndex")
                val client = GameProcess.start(
                    spec = spec,
                    extraJvmArgs = nodeArgs(port) + listOf(
                        "-De2e.node.role=CLIENT",
                        "-De2e.node.index=$clientIndex",
                        // The client joins the server itself; the two never learn about each other
                        // any other way.
                        "-De2e.server.address=${plan.serverAddress}",
                    ),
                    logDir = logDir,
                    echo = ::println,
                )
                processes += client
                awaitNode(hub, NodeId.client(clientIndex), client, plan.startupTimeoutSeconds, logDir)
                println("e2e: client $clientIndex is up")
            }

            val result = orchestrator.runAll()
            scope.cancel()
            result
        }
    }

    /**
     * Waits for a node, but gives up the moment its process dies.
     *
     * Without the liveness check a game that fails on startup leaves the orchestrator sitting out
     * the whole startup timeout with nothing to show, when the reason is already in its log.
     */
    private suspend fun awaitNode(
        hub: SocketHub,
        node: NodeId,
        process: GameProcess,
        timeoutSeconds: Long,
        logDir: File,
    ) {
        val deadline = System.nanoTime() + timeoutSeconds * 1_000_000_000L
        while (System.nanoTime() < deadline) {
            if (node in hub.connected()) return
            if (!process.isAlive) {
                error(
                    "The ${process.name} process exited with code ${process.exitCode()} before " +
                        "reaching the orchestrator. See ${logDir.resolve(process.name + ".log")}"
                )
            }
            delay(250)
        }
        error("$node never reached the orchestrator. See ${logDir.resolve(process.name + ".log")}")
    }

    private fun nodeArgs(port: Int) = listOf(
        "-De2e.orchestrator.host=127.0.0.1",
        "-De2e.orchestrator.port=$port",
    )

    private fun loadIndex(plan: LaunchPlan, json: Json): E2eIndex {
        val files = plan.indexFiles.map(::File).filter { it.isFile }.flatMap {
            json.decodeFromString(E2eIndex.serializer(), it.readText()).files
        }
        return E2eIndex(files)
    }
}
