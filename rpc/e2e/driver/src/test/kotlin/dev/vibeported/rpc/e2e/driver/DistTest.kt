package dev.vibeported.rpc.e2e.driver

import dev.vibeported.rpc.NodeId
import dev.vibeported.rpc.ProcedureTable
import dev.vibeported.rpc.TableRegistry
import dev.vibeported.rpc.currentNode
import dev.vibeported.rpc.e2e.layer.anywhere
import dev.vibeported.rpc.e2e.layer.onlyOnB
import dev.vibeported.rpc.host.HubAddress
import dev.vibeported.rpc.host.RpcHost
import dev.vibeported.rpc.transport.SocketHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Three processes, three classpaths, and the claim the roles exist to support.
 *
 * Every other test in this repository runs its nodes in one JVM, which cannot express the thing
 * that actually goes wrong in a game: a dedicated server is dist-cleaned, so a body touching client
 * classes is not slow or awkward there -- the class holding it is not on the machine. The only
 * honest way to test that is separate processes whose classpaths genuinely differ.
 *
 * The layer jar is identical on both nodes and holds bodies for both roles. Node `a` has only the
 * common half; node `b` has both. Nothing tells node `a` to avoid the `B` table beyond its own
 * roles, and its jar contains that table's class file the whole time.
 *
 * The driver has neither half. After the plugin lifts the bodies out, the layer's own class
 * references neither `Alpha` nor `Beta`, so this process dispatches procedures it could not
 * possibly run -- which is what an orchestrator is.
 */
@Timeout(value = 3, unit = TimeUnit.MINUTES)
class DistTest {

    private val scope = CoroutineScope(Job())
    private val hub = SocketHub()
    private val running = mutableListOf<Node>()

    @AfterEach
    fun tearDown() {
        running.forEach { it.stop() }
        scope.cancel()
        runBlocking { hub.stop() }
    }

    @Test
    fun `a node loads only the tables its roles allow, from a jar holding both`() = runBlocking {
        hub.start(scope)
        val a = start("a", roles = "", classpath = CLASSPATH_A)
        val b = start("b", roles = "B", classpath = CLASSPATH_B)

        // The dist split, observable from outside: the same layer jar, and a different number of
        // procedures resolved out of it, decided entirely by the roles each node was given.
        assertEquals(1, a.procedures, "node a should hold only the table every node loads")
        assertEquals(2, b.procedures, "node b should hold that one and the B table too")

        joinAsDriver()
        awaitRoster(3)

        // A body every node can run, run on each of them.
        assertEquals("A", anywhere("a"))
        assertEquals("A", anywhere("b"))

        // And the one only node b can, on node b. `Beta` is on no other classpath in this test,
        // this process's included.
        assertEquals("A/AB", onlyOnB("b"))
    }

    @Test
    fun `the same call routed to the wrong node says which role it needed`() = runBlocking {
        hub.start(scope)
        start("a", roles = "", classpath = CLASSPATH_A)
        joinAsDriver()
        awaitRoster(2)

        val failure = assertThrows<Exception> { runBlocking { onlyOnB("a") } }
        val message = failure.message.orEmpty() + failure.cause?.message.orEmpty()

        // Refused here, on the caller, before anything is put on the wire: the roster says what
        // roles node `a` holds, and the body's own role says what it needs. That is stronger than
        // the far node refusing it, and much stronger than "no such procedure", which would send
        // whoever read it looking for a module that is not missing.
        assertTrue("onlyOnB" in message, message)
        assertTrue("needs role `B`" in message, message)
        assertTrue("a holds []" in message, message)
    }

    @Test
    fun `a node claiming a role its jars cannot support starts anyway, and fails on the call`() =
        runBlocking {
            // The limit of what loading a table can promise, pinned so nobody rediscovers it.
            //
            // Node `a` is handed role `B` without the jar that role needs. One might expect the
            // eager resolution to catch that as it starts; it does not, and cannot. The JVM loads a
            // class without resolving the classes named inside its method bodies, so the `B` table
            // instantiates perfectly well here with `Beta` absent -- and nothing discovers the gap
            // until a call runs the body that names it.
            //
            // Which is why a role is an assertion the deployment makes and the runtime cannot
            // check, and why the guarantee that does hold is the one in the test above: a node that
            // never claims a role never loads its table, and refuses the call by name instead.
            hub.start(scope)
            val a = start("a", roles = "B", classpath = CLASSPATH_A)
            assertEquals(2, a.procedures, "the B table loads here despite Beta being absent")

            joinAsDriver()
            awaitRoster(2)

            val failure = assertThrows<Exception> { runBlocking { onlyOnB("a") } }
            val said = failure.message.orEmpty() + failure.cause?.message.orEmpty()

            // Late, but not silent: the failure names the class that was not there.
            assertTrue("Beta" in said, said)
        }

    // --- the supervisor ------------------------------------------------------------------------

    private class Node(private val process: Process, val procedures: Int) {
        fun stop() {
            process.destroy()
            if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly()
        }
    }

    /** Starts a node and waits for it to say it has joined, so no test races the roster. */
    private fun start(id: String, roles: String, classpath: String): Node {
        val process = spawn(id, roles, classpath, hub.port)
        val transcript = drain(process)

        val line = requireNotNull(transcript.awaitLine("rpc.ready", 60, TimeUnit.SECONDS)) {
            "node `" + id + "` never reported ready. It said:\n" + transcript.text()
        }

        // `rpc.ready <id> <hub> <n> procedures`
        val node = Node(process, line.split(' ')[3].toInt())
        running += node
        return node
    }

    private fun spawn(id: String, roles: String, classpath: String, hubPort: Int): Process =
        ProcessBuilder(
            File(File(System.getProperty("java.home"), "bin"), "java").absolutePath,
            "-Drpc.node=$id",
            "-Drpc.roles=$roles",
            "-Drpc.hub=127.0.0.1:$hubPort",
            "-cp",
            classpath,
            "dev.vibeported.rpc.e2e.node.MainKt",
        ).redirectErrorStream(true).start()

    /**
     * Reads a process's output on a thread, and never on this one.
     *
     * Two hazards, and both present as a test that simply stops. A full pipe buffer blocks the node
     * itself, mid-call, with nothing to show for it; and a blocking read on this side waits on a
     * process that may have no intention of ever saying anything more -- which is exactly how this
     * test first hung, on a node that was expected to die and did not. So: one thread that only
     * reads, and every wait over here bounded.
     */
    private fun drain(process: Process): Transcript {
        val transcript = Transcript()
        Thread {
            runCatching { process.inputStream.bufferedReader().forEachLine(transcript::add) }
            transcript.done()
        }.apply {
            isDaemon = true
            name = "rpc-drain"
        }.start()
        return transcript
    }

    private class Transcript {
        private val lines = ConcurrentLinkedQueue<String>()
        private val finished = CountDownLatch(1)
        private val arrived = CountDownLatch(1)

        @Volatile
        private var wanted: String? = null

        @Volatile
        private var found: String? = null

        fun add(line: String) {
            lines += line
            if (found == null && wanted?.let(line::startsWith) == true) {
                found = line
                arrived.countDown()
            }
        }

        fun done() {
            finished.countDown()
        }

        /** The first line starting with [prefix], or null if none arrived in time. */
        fun awaitLine(prefix: String, timeout: Long, unit: TimeUnit): String? {
            wanted = prefix
            lines.firstOrNull { it.startsWith(prefix) }?.let { return it }
            arrived.await(timeout, unit)
            return found ?: lines.firstOrNull { it.startsWith(prefix) }
        }

        fun text(): String = lines.joinToString("\n")
    }

    /**
     * Joins this process as a node that serves nothing.
     *
     * An empty registry rather than one read off the classpath, because this process holds the
     * layer jar and neither half of the game: resolving its tables would fail, correctly, on the
     * very first one.
     */
    private suspend fun joinAsDriver() {
        RpcHost(
            id = NodeId("driver"),
            tables = TableRegistry.of(emptyList<ProcedureTable>()),
        ).connect(scope, HubAddress("127.0.0.1", hub.port))
    }

    private suspend fun awaitRoster(size: Int) {
        withTimeout(30_000) {
            while (currentNode().membership.snapshot().size < size) delay(10)
        }
    }

    private companion object {
        val CLASSPATH_A: String = System.getProperty("rpc.e2e.classpath.a")
        val CLASSPATH_B: String = System.getProperty("rpc.e2e.classpath.b")
    }
}
