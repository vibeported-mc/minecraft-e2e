package dev.vibeported.mc.e2e.launcher

import dev.vibeported.mc.e2e.ClientStarter
import dev.vibeported.mc.e2e.CurrentTest
import dev.vibeported.mc.e2e.ORCHESTRATOR_NODE
import dev.vibeported.mc.e2e.ORCHESTRATOR_ROLE
import dev.vibeported.rpc.NodeId
import dev.vibeported.rpc.Services
import dev.vibeported.rpc.host.HubAddress
import dev.vibeported.rpc.host.RpcConnection
import dev.vibeported.rpc.host.RpcHost
import dev.vibeported.rpc.transport.SocketHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.system.exitProcess

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

        // Whatever the build declared. A client nobody named starts the first time a call is
        // addressed to it -- which used to need a set the compiler collected, and does not any
        // more: `client(name)` asks this process to start one when the roster has no such node.
        val upFront = plan.clientNames.distinct().sorted()
        println("e2e: starting " + upFront.size + " client(s) up front: " + upFront)

        val hub = SocketHub(plan.port)
        return try {
            val scope = CoroutineScope(kotlin.coroutines.coroutineContext + SupervisorJob())
            hub.start(scope)
            println("e2e: transport listening on port " + hub.port)

            // The cluster reads the roster to decide a process is ready, and the node that holds
            // that roster does not exist yet -- so it reads through a reference filled in below.
            var joined: RpcConnection? = null
            val cluster = Cluster(
                plan = plan,
                logDir = logDir,
                clients = upFront,
                port = hub.port,
                joined = {
                    joined?.membership?.snapshot()?.map { it.id.value }?.toSet().orEmpty()
                },
            )

            // This process is a node like any other, and one that serves procedures: starting a
            // client, taking a log line, filing a screenshot. It runs no game, so it resolves no
            // game tables -- the `orchestrator` role is what keeps those apart.
            val services = Services()
            services.provide(ClientStarter::class, ClientStarter { name -> cluster.startClient(name) })
            // This node is told which test is running like any other -- the announcement goes to
            // everyone, and it lands here too.
            services.provide(CurrentTest::class, CurrentTest())

            val connection = RpcHost(
                id = NodeId(ORCHESTRATOR_NODE),
                roles = setOf(ORCHESTRATOR_ROLE),
                services = services,
                // The test bodies run *here*, so this is the process that encodes a `BlockPos` on
                // its way to the server. It needs the game's serializers as much as a game does.
                // This one is load-bearing and its absence is baffling. FancyModLoader loads mod
                // classes in a transforming loader of its own; resolving tables through any other
                // loader gets a *second* copy of every class in them, and a receiver registered
                // here then fails to match the one a generated table asks for -- with an error
                // listing the very type it says is missing.
                loader = OrchestratorBootstrap::class.java.classLoader,
            ).connect(scope, HubAddress("127.0.0.1", hub.port))
            joined = connection

            cluster.start()

            try {
                invokeMain(plan.mainClass, args)
                false
            } catch (failure: Throwable) {
                failure.printStackTrace()
                true
            } finally {
                cluster.stop()
                runCatching { connection.leave() }
                scope.cancel()
            }
        } finally {
            hub.stop()
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
