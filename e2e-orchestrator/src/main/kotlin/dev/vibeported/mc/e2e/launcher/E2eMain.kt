package dev.vibeported.mc.e2e.launcher

import dev.vibeported.mc.e2e.orchestrator.Orchestrator
import dev.vibeported.mc.e2e.protocol.E2eIndex
import dev.vibeported.mc.e2e.protocol.NodeId
import dev.vibeported.mc.e2e.report.ConsoleReporter
import dev.vibeported.mc.e2e.report.JsonReporter
import dev.vibeported.mc.e2e.report.Outcome
import dev.vibeported.mc.e2e.report.RunReport
import dev.vibeported.mc.e2e.report.TestReport
import dev.vibeported.mc.e2e.rpc.RemoteFailure
import dev.vibeported.mc.e2e.rpc.RpcPeer
import dev.vibeported.mc.e2e.rpc.SocketHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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
 * It starts the dedicated server and a client, waits for each to dial in, then runs every test in
 * the index and reports. It is deliberately the only party that talks to both game processes: they
 * never connect to each other, so everything one hands the other passes through here and lands in
 * one ordered log.
 */
public object E2eMain {

    /** Enough consecutive crashes to conclude the suite, not the game, is the problem. */
    private const val MAX_CONSECUTIVE_RESTARTS = 3

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
        val cluster = Cluster(plan, logDir)

        val report = try {
            runOrchestrated(plan, index, cluster)
        } finally {
            cluster.stop()
        }

        print(ConsoleReporter.render(report))
        File(reportDir, "report.json").writeText(JsonReporter.render(report))
        println("e2e: report written to ${File(reportDir, "report.json").absolutePath}")
        println("e2e: process logs in ${logDir.absolutePath}")

        exitProcess(if (report.ok) 0 else 1)
    }

    private fun runOrchestrated(plan: LaunchPlan, index: E2eIndex, cluster: Cluster): RunReport =
        runBlocking {
            val scope = CoroutineScope(coroutineContext + SupervisorJob())

            SocketHub(plan.port).use { hub ->
                hub.start(scope)
                println("e2e: orchestrator listening on port ${hub.port}")

                val orchestrator = Orchestrator(
                    peer = RpcPeer(hub.transport(), callTimeout = plan.testTimeoutSeconds.seconds),
                    index = index,
                )
                orchestrator.start(scope)

                cluster.start(hub)

                val startedAt = System.currentTimeMillis()
                val reports = mutableListOf<TestReport>()
                var consecutiveRestarts = 0

                for ((suite, test) in orchestrator.tests()) {
                    val running = scope.async { orchestrator.runTest(suite, test) }

                    // Watch the game processes while the test runs. Waiting for the call timeout
                    // instead would spend minutes discovering what the exit code already said.
                    var crashed: GameProcess? = null
                    while (running.isActive) {
                        crashed = cluster.deadProcess()
                        if (crashed != null) {
                            running.cancel()
                            break
                        }
                        delay(200)
                    }

                    if (crashed == null) {
                        reports += running.await()
                        consecutiveRestarts = 0
                        continue
                    }

                    reports += crashReport(suite, test, crashed, cluster.logDir)
                    println("e2e: ${crashed.name} died during ${test.name}, restarting the cluster")

                    if (++consecutiveRestarts > MAX_CONSECUTIVE_RESTARTS) {
                        println(
                            "e2e: $MAX_CONSECUTIVE_RESTARTS restarts in a row, so the run is " +
                                "abandoned rather than repeating a crash for every remaining test."
                        )
                        break
                    }
                    cluster.restart(hub)
                }

                scope.cancel()
                RunReport(reports, startedAt, System.currentTimeMillis() - startedAt)
            }
        }

    private fun crashReport(
        suite: E2eIndex.SuiteEntry,
        test: E2eIndex.TestEntry,
        crashed: GameProcess,
        logDir: File,
    ) = TestReport(
        suiteId = suite.id,
        suiteName = suite.name,
        testId = test.id,
        testName = test.name,
        outcome = Outcome.ERROR,
        durationMillis = 0,
        blocks = emptyList(),
        log = emptyList(),
        failure = RemoteFailure(
            type = "dev.vibeported.mc.e2e.launcher.GameProcessDied",
            message = "The ${crashed.name} process exited with code ${crashed.exitCode()} " +
                "while this test was running",
            stack = "See ${logDir.resolve(crashed.name + ".log")}",
        ),
    )

    private fun loadIndex(plan: LaunchPlan, json: Json): E2eIndex {
        val files = plan.indexFiles.map(::File).filter { it.isFile }.flatMap {
            json.decodeFromString(E2eIndex.serializer(), it.readText()).files
        }
        return E2eIndex(files)
    }

    /**
     * The game processes, and the ability to put them back.
     *
     * A restart resets the world: nothing a test built survives it. That matters for a suite whose
     * later tests lean on what earlier ones left behind, and it is the price of continuing at all.
     */
    private class Cluster(private val plan: LaunchPlan, val logDir: File) {

        private val processes = mutableListOf<GameProcess>()
        private var port: Int = 0

        suspend fun start(hub: SocketHub) {
            port = hub.port

            println("e2e: starting the dedicated server")
            val server = GameProcess.start(
                spec = plan.server,
                extraJvmArgs = nodeArgs() + listOf("-De2e.node.role=SERVER"),
                logDir = logDir,
                echo = ::println,
            )
            processes += server
            awaitNode(hub, NodeId.SERVER, server)
            println("e2e: server is up")

            plan.clients.forEachIndexed { clientIndex, spec ->
                println("e2e: starting client $clientIndex")
                val client = GameProcess.start(
                    spec = spec,
                    extraJvmArgs = nodeArgs() + listOf(
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
                awaitNode(hub, NodeId.client(clientIndex), client)
                println("e2e: client $clientIndex is up")
            }
        }

        /** Both, not just the dead one: a client whose server vanished is in no state to continue. */
        suspend fun restart(hub: SocketHub) {
            stop()
            start(hub)
        }

        fun deadProcess(): GameProcess? = processes.firstOrNull { !it.isAlive }

        fun stop() {
            processes.forEach { it.stop() }
            processes.clear()
        }

        private fun nodeArgs() = listOf(
            "-De2e.orchestrator.host=127.0.0.1",
            "-De2e.orchestrator.port=$port",
        )

        /**
         * Waits for a node, but gives up the moment its process dies.
         *
         * Without the liveness check a game that fails on startup leaves the orchestrator sitting
         * out the whole startup timeout with nothing to show, when the reason is already in its log.
         */
        private suspend fun awaitNode(hub: SocketHub, node: NodeId, process: GameProcess) {
            val deadline = System.nanoTime() + plan.startupTimeoutSeconds * 1_000_000_000L
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
    }
}
