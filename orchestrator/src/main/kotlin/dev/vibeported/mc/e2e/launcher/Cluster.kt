package dev.vibeported.mc.e2e.launcher

import dev.vibeported.mc.e2e.protocol.NodeId
import dev.vibeported.mc.e2e.rpc.SocketHub
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * The game processes, started and stopped.
 *
 * No policy about them: it does not decide when a run is over, what to do about a crash, or whether
 * to try again. It starts what it was asked for, starts a client that turns out to be needed, and
 * reports which process has died so that whoever cares can decide.
 */
public class Cluster(
    private val plan: LaunchPlan,
    public val logDir: File,
    private val clients: List<String>,
) {

    private val processes = mutableListOf<GameProcess>()
    private var port: Int = 0

    init {
        // A game process outlives its parent quite happily, and an orchestrator that is killed --
        // by a build cancelled, or a crash -- would otherwise leave a server holding port 25565 and
        // two clients answering for a run that no longer exists. The next run then talks to them.
        Runtime.getRuntime().addShutdownHook(Thread { stop() })
    }

    public suspend fun start(hub: SocketHub) {
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

        // Started together rather than one after another. A client takes the better part of a
        // minute to reach a world, and waiting out each one in turn made the cost of a second
        // client the same as the cost of the first.
        val started = clients.map { name -> name to launchClient(name) }

        coroutineScope {
            started.forEach { (name, process) ->
                launch {
                    awaitNode(hub, NodeId.client(name), process)
                    println("e2e: client `$name` is up")
                }
            }
        }
    }

    /**
     * Starts one client that nobody asked for up front, and waits for it to be usable.
     *
     * This is the last resort behind a client named by an expression: the compiler could not work
     * the name out, the build did not declare it, and something has just addressed it. The call
     * waits here for a whole client to boot and join, which is why a name worth knowing in advance
     * is worth writing as a literal.
     */
    public suspend fun startClient(name: String, hub: SocketHub) {
        val existing = processes.firstOrNull { it.name == "client-$name" }
        if (existing != null && existing.isAlive) return

        println("e2e: client `$name` was addressed but is not running, so starting it now")
        val process = launchClient(name)
        awaitNode(hub, NodeId.client(name), process)
        println("e2e: client `$name` is up")
    }

    /**
     * One ModDevGradle client run is harvested as a template; every named client is that same
     * command with its own username and game directory.
     */
    private fun launchClient(name: String): GameProcess {
        val template = plan.clients.firstOrNull()
            ?: error("The launch plan has no client run to start clients from")

        println("e2e: starting client `$name`")
        val gameDir = File(File(template.workingDir).parentFile, "e2eClient-$name")
        seedClient(gameDir)
        val process = GameProcess.start(
            spec = template.copy(name = "client-$name", workingDir = gameDir.absolutePath),
            extraJvmArgs = nodeArgs() + listOf(
                "-De2e.node.role=CLIENT",
                "-De2e.node.name=$name",
                // The client joins the server itself; the two never learn about each other any
                // other way.
                "-De2e.server.address=${plan.serverAddress}",
            ) + windowArgs(processes.size),
            logDir = logDir,
            echo = ::println,
            // The username is the client's name as far as a test is concerned: it is what
            // waitForPlayer, teleport and lookAtPlayer look it up by on the server.
            extraProgramArgs = listOf(
                "--username", name,
                "--width", plan.clientWidth.toString(),
                "--height", plan.clientHeight.toString(),
            ),
        )
        processes += process
        return process
    }

    public fun deadProcess(): GameProcess? = processes.firstOrNull { !it.isAlive }

    public fun stop() {
        processes.forEach { it.stop() }
        processes.clear()
    }

    /**
     * Clears the screens a fresh client stops on, none of which anyone is there to click.
     *
     * A first launch shows the accessibility onboarding, and a first multiplayer join shows the
     * third-party server warning; NeoForge adds one of its own whenever any mod loads with a
     * warning, which is rarely even ours. Each one looks exactly like a hang from the outside.
     *
     * This lives here rather than in the Gradle plugin because only the run knows how many
     * clients there are and what they are called, and each one has a directory of its own.
     *
     * Only written when absent, so a directory someone has since adjusted by hand is left alone.
     */
    private fun seedClient(dir: File) {
        val config = File(dir, "config").apply { mkdirs() }
        val warnings = File(config, "neoforge-client.toml")
        if (!warnings.exists()) {
            warnings.writeText("showLoadWarnings = false" + System.lineSeparator())
        }

        val options = File(dir, "options.txt")
        if (!options.exists()) {
            options.writeText(
                listOf(
                    "onboardAccessibility:false",
                    "skipMultiplayerWarning:true",
                    "narrator:0",
                    "tutorialStep:none",
                    // An automated client spends its life unfocused.
                    "pauseOnLostFocus:false",
                ).joinToString(System.lineSeparator(), postfix = System.lineSeparator())
            )
        }
    }

    /**
     * Which of the run's windows this client is.
     *
     * Only the ordinal travels: how big the monitor is, and so where the window can go, is
     * knowable in the client and nowhere else.
     */
    private fun windowArgs(index: Int) = if (!plan.tileWindows) {
        emptyList()
    } else {
        listOf("-De2e.window.index=$index", "-De2e.window.count=${clients.size}")
    }

    private fun nodeArgs() = listOf(
        "-De2e.orchestrator.host=127.0.0.1",
        "-De2e.orchestrator.port=$port",
        // Read by the node that waits for a player to arrive or turn, which is the only party
        // that can see whether the effect landed.
        "-De2e.action.timeout.seconds=${plan.actionTimeoutSeconds}",
        // Where a client files its screenshots, so they land beside the report rather than in
        // whatever directory that game process happens to be running in.
        "-De2e.report.dir=${File(plan.reportDir).absolutePath}",
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
