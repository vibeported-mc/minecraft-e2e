package dev.vibeported.mc.driver

import dev.vibeported.rpc.NodeId
import dev.vibeported.rpc.host.HubAddress
import dev.vibeported.rpc.host.RpcConnection
import dev.vibeported.rpc.host.RpcHost
import dev.vibeported.rpc.transport.SocketHub
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import java.io.File

/** The node this process joins as: it holds the middle of the star and runs no game. */
public const val DRIVER_NODE: String = "driver"

/**
 * Starting and stopping the games.
 *
 * The one scope in this driver that is not the receiver of a lifted body. Everything here runs in
 * *this* process -- spawning a JVM is not something a game can be asked to do -- so it is an
 * ordinary Kotlin receiver, and the reason it is a receiver at all is that these two need somewhere
 * to keep the hub and the processes they started.
 *
 * No policy about any of it. It does not decide when a run is over, what to do about a crash, or
 * whether to try again; it starts what it is asked for and says which process has died.
 */
@DriverDsl
public interface ClusterScope {

    /** Where this cluster's hub is listening, which every game is told. */
    public val hub: HubAddress

    /**
     * Starts the dedicated server and waits for it to join.
     *
     * Joining is what readiness means here, and it is a stronger claim than the socket being open: a
     * game dials in when it is worth driving -- a started server, a client actually standing in a
     * level -- so a node on the roster is a node that can be asked to do something.
     */
    public suspend fun startServer()

    /** Starts a client under that username and waits for it to join. Does nothing if it is up. */
    public suspend fun startClient(name: String)

    /** Starts several clients at once, which is much faster than one after another. */
    public suspend fun startClients(vararg names: String)

    /** The first process that has died, or null while they are all up. */
    public fun deadProcess(): GameProcess?
}

/**
 * A cluster that is open until somebody closes it.
 *
 * The form [cluster] is built out of, and the one anything outliving a single lambda needs -- a test
 * framework, most obviously, which has to open the games once and hand them to many tests before
 * closing them at the end of a run.
 *
 * Prefer [cluster] where a lambda will do. This is the shape for when it will not.
 */
public class Cluster private constructor(
    private val socket: SocketHub,
    private val scope: CoroutineScope,
    private val connection: RpcConnection,
    private val games: Games,
) : ClusterScope by games, AutoCloseable {

    /**
     * Stops every game, leaves the cluster and closes the hub.
     *
     * Idempotent, because the two callers -- a `use` block and a test framework's teardown -- can
     * both plausibly fire.
     */
    override fun close() {
        if (closed) return
        closed = true
        games.stopAll()
        runCatching { runBlocking { connection.leave() } }
        scope.cancel()
        runCatching { runBlocking { socket.stop() } }
    }

    private var closed = false

    public companion object {

        /**
         * Opens one, and leaves it open.
         *
         * This process becomes the middle of the star: it listens on a free port, joins its own
         * cluster under [DRIVER_NODE], and tells every game it starts where to dial. It claims no
         * role, so it resolves no procedure tables and can run none of the bodies it dispatches --
         * which is exactly what a driver is.
         */
        public suspend fun open(
            plan: LaunchPlan = LaunchPlan.read(),
            logDir: File = defaultLogDir(),
        ): Cluster {
            // Before anything else, and it is not optional when this is a plain `main`. A process
            // running no game has filled no registries, and the moment anything names an item to
            // send one, `Items` initialises against an empty registry and throws -- as an
            // `ExceptionInInitializerError` with no message and a stack in `<clinit>`, which says
            // nothing whatsoever about registries. Under JUnit, NeoForge's own `JUnitMain` has
            // already done this; both calls are idempotent, so it costs nothing to say it twice.
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()

            // Its own scope rather than the caller's, because this outlives the call that made it.
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("mcdriver"))
            val socket = SocketHub(0)

            try {
                socket.start(scope)
                val address = HubAddress("127.0.0.1", socket.port)
                println("mcdriver: hub listening on ${address.port}")

                val connection = RpcHost(
                    id = NodeId(DRIVER_NODE),
                    // No roles, so no tables. This process holds the jar every body was compiled
                    // into and can run not one of them, which is the point: it dispatches.
                    roles = emptySet(),
                    // Load-bearing, and its absence is baffling. FancyModLoader loads mod classes
                    // in a transforming loader of its own; resolving through any other gets a
                    // *second* copy of every class, and a value handed across then fails to match a
                    // type it plainly is -- with an error naming the very type it says is missing.
                    loader = ClusterScope::class.java.classLoader,
                ).connect(scope, address)

                return Cluster(socket, scope, connection, Games(plan, address, logDir, connection))
            } catch (failure: Throwable) {
                // A half-opened cluster still holds a port and possibly a coroutine or two.
                scope.cancel()
                runCatching { socket.stop() }
                throw failure
            }
        }
    }
}

/**
 * Runs [body] with a hub, a node, and games it can start.
 *
 * ```kotlin
 * cluster {
 *     startServer()
 *     startClient("alex")
 *
 *     withTimeout(30.seconds) { waitForPlayer("alex") }
 *     worldBuild { at(0, 64, 0) { "minecraft:stone" } }
 * }
 * ```
 *
 * Everything is torn down on the way out, including when [body] throws. A game process outlives its
 * parent quite happily, and one left behind holds port 25565 and answers for a run that no longer
 * exists -- so the next run talks to it. @see Cluster.open for the form that stays open.
 */
public suspend fun <R> cluster(
    plan: LaunchPlan = LaunchPlan.read(),
    logDir: File = defaultLogDir(),
    body: suspend ClusterScope.() -> R,
): R = Cluster.open(plan, logDir).use { it.body() }

/** Beside the captures when the driver was told where those go, and under the working directory otherwise. */
internal fun defaultLogDir(): File = File(captureDirectory() ?: File("."), "logs")

internal class Games(
    private val plan: LaunchPlan,
    private val address: HubAddress,
    private val logDir: File,
    private val connection: RpcConnection,
) : ClusterScope {

    private val processes = mutableListOf<GameProcess>()

    /** Registered once, so a driver killed outright does not leave games behind. */
    private val shutdown = Thread { stopAll() }.also { Runtime.getRuntime().addShutdownHook(it) }

    override val hub: HubAddress get() = address

    override suspend fun startServer() {
        if (joined(SERVER_NODE)) return

        println("mcdriver: starting the dedicated server")
        val process = GameProcess.start(
            spec = plan.server,
            extraJvmArgs = nodeArgs(SERVER_NODE, SERVER_ROLE.value),
            logDir = logDir,
            echo = ::println,
        )
        processes += process
        awaitNode(SERVER_NODE, process)
        println("mcdriver: the server is up")
    }

    override suspend fun startClient(name: String) {
        if (joined(name)) return
        awaitNode(name, launchClient(name))
        println("mcdriver: client `$name` is up")
    }

    override suspend fun startClients(vararg names: String) {
        // Started together rather than one after another. A client takes the better part of a
        // minute to reach a world, and waiting each one out in turn made the second cost as much as
        // the first.
        val started = names.filterNot { joined(it) }.map { it to launchClient(it) }
        coroutineScope {
            started.forEach { (name, process) ->
                launch {
                    awaitNode(name, process)
                    println("mcdriver: client `$name` is up")
                }
            }
        }
    }

    override fun deadProcess(): GameProcess? = processes.firstOrNull { !it.isAlive }

    fun stopAll() {
        processes.forEach { it.stop() }
        processes.clear()
        runCatching { Runtime.getRuntime().removeShutdownHook(shutdown) }
    }

    private fun launchClient(name: String): GameProcess {
        val template = plan.client
            ?: error("mcdriver: the launch plan has no client run to start clients from")

        println("mcdriver: starting client `$name`")
        val gameDir = File(File(template.workingDir).parentFile, "client-$name")
        seedClient(gameDir)

        val process = GameProcess.start(
            spec = template.copy(name = "client-$name", workingDir = gameDir.absolutePath),
            extraJvmArgs = nodeArgs(name, CLIENT_ROLE.value) + windowArgs(),
            // The username is the client's name everywhere else: it is what a player is looked up
            // by on the server, and what the node calls itself.
            extraProgramArgs = listOf(
                "--username", name,
                "--width", plan.clientWidth.toString(),
                "--height", plan.clientHeight.toString(),
            ),
            logDir = logDir,
            echo = ::println,
        )
        processes += process
        return process
    }

    private fun nodeArgs(node: String, role: String) = buildList {
        add("-D$NODE_PROPERTY=$node")
        add("-D$ROLES_PROPERTY=$role")
        add("-D$HUB_PROPERTY=$address")
        // Where a game files its pictures, so they land where this process was told to put things
        // rather than in whatever directory that game happens to be running in.
        captureDirectory()?.let { add("-D$CAPTURE_DIR_PROPERTY=${it.absolutePath}") }
    }

    /**
     * Which of the run's windows this client is.
     *
     * Only the ordinal travels: how big the monitor is, and so where a window can go, is knowable in
     * the client and nowhere else.
     */
    private fun windowArgs(): List<String> = if (!plan.tileWindows) {
        emptyList()
    } else {
        listOf(
            "-Dmcdriver.window.index=${processes.size}",
            "-Dmcdriver.window.count=${processes.size + 1}",
        )
    }

    private fun joined(node: String): Boolean =
        connection.membership.snapshot().any { it.id.value == node }

    /**
     * Waits for a node, and gives up the moment its process dies.
     *
     * Without the liveness check a game that fails on startup leaves the driver waiting out whatever
     * deadline the caller set, with nothing to show, when the reason is already in its log.
     */
    private suspend fun awaitNode(node: String, process: GameProcess) {
        while (!joined(node)) {
            if (!process.isAlive) {
                error(
                    "mcdriver: the ${process.name} process exited with code ${process.exitCode()} " +
                        "before joining the cluster. See ${process.logFile}"
                )
            }
            delay(250)
        }
    }

    /**
     * Clears the screens a fresh client stops on, none of which anybody is there to click.
     *
     * A first launch shows the accessibility onboarding, and a first multiplayer join shows the
     * third-party server warning; NeoForge adds one of its own whenever any mod loads with a
     * warning, which is rarely even ours. Each one looks exactly like a hang from the outside.
     *
     * Here rather than in the Gradle plugin because only a running driver knows how many clients
     * there are and what they are called, and each one has a directory of its own.
     *
     * Only written when absent, so a directory somebody has since adjusted by hand is left alone.
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
                    // A driven client spends its life unfocused.
                    "pauseOnLostFocus:false",
                ).joinToString(System.lineSeparator(), postfix = System.lineSeparator())
            )
        }
    }
}
