package dev.vibeported.mc.driver

import dev.vibeported.rpc.NodeId
import dev.vibeported.rpc.host.HubAddress
import dev.vibeported.rpc.host.RpcConnection
import dev.vibeported.rpc.host.RpcHost
import dev.vibeported.rpc.transport.SocketHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import java.io.File
import kotlin.coroutines.coroutineContext

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
 * Runs [body] with a hub, a node, and games it can start.
 *
 * This process becomes the middle of the star: it listens on a free port, joins its own cluster
 * under [DRIVER_NODE], and tells every game it starts where to dial. It claims no role, so it
 * resolves no procedure tables and can run none of the bodies it dispatches -- which is exactly what
 * a driver is.
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
 * exists -- so the next run talks to it.
 */
public suspend fun <R> cluster(
    plan: LaunchPlan = LaunchPlan.read(),
    logDir: File = defaultLogDir(),
    body: suspend ClusterScope.() -> R,
): R {
    // Before anything else, and it is not optional. This process runs no game, so nothing has
    // filled the registries -- and the moment a caller writes `ItemStack(Items.DIAMOND_SWORD)` to
    // send one, `Items` initialises against an empty registry and throws. What comes out is an
    // `ExceptionInInitializerError` with no message and a stack in `<clinit>`, which says nothing
    // whatsoever about registries. Both calls are idempotent, so a driver that happens to be inside
    // a game pays nothing.
    SharedConstants.tryDetectVersion()
    Bootstrap.bootStrap()

    val hub = SocketHub(0)
    val scope = CoroutineScope(coroutineContext + SupervisorJob())

    return try {
        hub.start(scope)
        val address = HubAddress("127.0.0.1", hub.port)
        println("mcdriver: hub listening on ${address.port}")

        val connection = RpcHost(
            id = NodeId(DRIVER_NODE),
            // No roles, so no tables. This process holds the jar every body was compiled into and
            // can run not one of them, which is the point: it dispatches.
            roles = emptySet(),
            // Load-bearing, and its absence is baffling. FancyModLoader loads mod classes in a
            // transforming loader of its own; resolving through any other gets a *second* copy of
            // every class, and a value handed across then fails to match a type it plainly is --
            // with an error naming the very type it says is missing.
            loader = ClusterScope::class.java.classLoader,
        ).connect(scope, address)

        val games = Games(plan, address, logDir, connection)
        try {
            games.body()
        } finally {
            games.stopAll()
            runCatching { connection.leave() }
            scope.cancel()
        }
    } finally {
        hub.stop()
    }
}

/** Beside the captures when the driver was told where those go, and under the working directory otherwise. */
private fun defaultLogDir(): File = File(captureDirectory() ?: File("."), "logs")

private class Games(
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
