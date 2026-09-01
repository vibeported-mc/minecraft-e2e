package dev.vibeported.mc.e2e.launcher

import dev.vibeported.mc.e2e.Node
import dev.vibeported.mc.e2e.ScopeFactory
import dev.vibeported.mc.e2e.mc.McValueCodec
import dev.vibeported.mc.e2e.node.TableRegistry
import dev.vibeported.mc.e2e.orchestrator.Orchestrator
import dev.vibeported.mc.e2e.protocol.NodeId
import dev.vibeported.mc.e2e.rpc.RpcPeer
import dev.vibeported.mc.e2e.rpc.SocketHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds

/**
 * Brings the cluster up, wires the transport, and runs somebody else main.
 *
 * That is the whole of it. It knows nothing about suites, tests, reports or retries: it starts a
 * dedicated server and the clients that were asked for, makes `server { }` and `client { }` work
 * from anywhere in this process, and hands over. Whatever runs next decides what a test is.
 *
 * Loaded through FancyModLoader transforming class loader, so the framework and the suites live in
 * the same transformed world as the game.
 */
public object OrchestratorBootstrap {

    private const val PLAN_PROPERTY = "e2e.launch.plan"

    @JvmStatic
    public fun main(args: Array<String>) {
        val planFile = System.getProperty(PLAN_PROPERTY)?.let(::File)
            ?: error("e2e: -D" + PLAN_PROPERTY + " was not set, so there is nothing to start")

        val json = Json { ignoreUnknownKeys = true }
        val plan = json.decodeFromString(LaunchPlan.serializer(), planFile.readText())

        val failed = runBlocking { run(plan, args) }
        exitProcess(if (failed) 1 else 0)
    }

    private suspend fun run(plan: LaunchPlan, args: Array<String>): Boolean {
        val logDir = File(File(plan.reportDir).apply { mkdirs() }, "logs")

        // Every client the compiler could name, from every module on the classpath, plus whatever
        // the build declared. Anything else starts the first time a call is addressed to it.
        val tables = TableRegistry.load()
        val upFront = (tables.clients() + plan.clientNames).distinct().sorted()
        println("e2e: starting " + upFront.size + " client(s) up front: " + upFront)

        return SocketHub(plan.port).use { hub ->
            val scope = CoroutineScope(kotlin.coroutines.coroutineContext + SupervisorJob())
            hub.start(scope)
            println("e2e: transport listening on port " + hub.port)

            val cluster = Cluster(plan, logDir, upFront)
            val orchestrator = Orchestrator(
                peer = RpcPeer(hub.transport(), callTimeout = plan.callTimeoutSeconds.seconds),
                connected = hub::connected,
                startClient = { name -> cluster.startClient(name, hub) },
            )
            orchestrator.start(scope)
            cluster.start(hub)

            // Installed process-wide as well as in the context: a main that starts its own
            // `runBlocking`, as a JUnit test would, must still be able to call a procedure.
            val node = Node(
                self = NodeId.ORCHESTRATOR,
                tables = tables,
                codec = McValueCodec(),
                relay = orchestrator::route,
                scopes = ScopeFactory { _, block ->
                    error("`" + block + "` was addressed to the orchestrator, which runs no game")
                },
            )
            Node.install(node)

            try {
                invokeMain(plan.mainClass, args)
                false
            } catch (failure: Throwable) {
                failure.printStackTrace()
                true
            } finally {
                cluster.stop()
                scope.cancel()
            }
        }
    }

    /**
     * Calls the configured main by reflection.
     *
     * By name rather than by interface on purpose: anything with a `main` qualifies, so a JUnit
     * console launcher is as valid an entrypoint here as a hand-written runner.
     */
    private fun invokeMain(className: String, args: Array<String>) {
        require(className.isNotEmpty()) { "e2e: no main class was configured for the orchestrator" }
        val loader = Thread.currentThread().contextClassLoader
        val type = Class.forName(className, true, loader)
        val main = type.methods.firstOrNull { it.name == "main" && it.parameterCount == 1 }
            ?: error("e2e: " + className + " has no main(String[]) to call")
        println("e2e: running " + className + ".main")
        main.invoke(null, args)
    }
}
