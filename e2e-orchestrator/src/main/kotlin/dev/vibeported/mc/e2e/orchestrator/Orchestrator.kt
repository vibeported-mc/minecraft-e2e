package dev.vibeported.mc.e2e.orchestrator

import dev.vibeported.mc.e2e.protocol.E2eIndex
import dev.vibeported.mc.e2e.protocol.BlockId
import dev.vibeported.mc.e2e.protocol.NodeId
import dev.vibeported.mc.e2e.protocol.NodeRole
import dev.vibeported.mc.e2e.report.BlockRecord
import dev.vibeported.mc.e2e.report.LogLine
import dev.vibeported.mc.e2e.report.Outcome
import dev.vibeported.mc.e2e.report.RunReport
import dev.vibeported.mc.e2e.report.TestReport
import dev.vibeported.mc.e2e.rpc.AwaitPlayer
import dev.vibeported.mc.e2e.rpc.Cancel
import dev.vibeported.mc.e2e.rpc.ControlPlayer
import dev.vibeported.mc.e2e.rpc.InvokeBlock
import dev.vibeported.mc.e2e.rpc.Payload
import dev.vibeported.mc.e2e.rpc.RemoteFailure
import dev.vibeported.mc.e2e.rpc.RemoteInvocationException
import dev.vibeported.mc.e2e.rpc.Request
import dev.vibeported.mc.e2e.rpc.RpcPeer
import dev.vibeported.mc.e2e.rpc.SharedGet
import dev.vibeported.mc.e2e.rpc.SharedSet
import dev.vibeported.mc.e2e.rpc.toRemoteFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns the shared state, relays every block invocation, and is the only party that sees the whole
 * run.
 *
 * It runs no test code itself, and needs none: a test is an ordered list of blocks, so running one is
 * walking that list and telling each node which block to run. The lifted bodies live in a mod jar
 * compiled against Minecraft and this is a plain JVM with no game on its classpath, which is exactly
 * why nothing here has to load them.
 *
 * Everything a running block then asks for comes back through here -- which is what gives one report
 * a single ordering over two game processes, and what lets a server block hand work to a client it
 * has no connection to.
 */
public class Orchestrator(
    private val peer: RpcPeer,
    private val index: E2eIndex,
    /**
     * Wall clock for one whole test.
     *
     * Distinct from the peer's call timeout, which bounds a single block invocation: a test that
     * waits on a shared value nobody writes never makes a call that could time out, so without this
     * it would wait for the run to be killed instead of failing with a reason.
     */
    private val testTimeout: Duration = 5.minutes,
) {
    private val shared = SharedStore()

    /** Every block in the index, so a step id can be resolved to the node that should run it. */
    private val blockEntries: Map<BlockId, E2eIndex.BlockEntry> =
        index.files.flatMap { it.blocks }.associateBy { it.id }
    private val logs = CopyOnWriteArrayList<LogLine>()
    private val blocks = CopyOnWriteArrayList<BlockRecord>()
    private val runCounter = AtomicLong()

    /**
     * The read a test is currently parked on, if any.
     *
     * Recorded so a test that times out can say what it was waiting for. Without it, the most common
     * failure -- a block that forgot to write a shared value -- reports only that time ran out.
     */
    @Volatile
    private var waitingOn: SharedGet? = null

    public fun start(scope: CoroutineScope): Job {
        peer.onRequest = { request: Request -> route(request.payload) }
        peer.onEvent = { event ->
            logs += LogLine(event.from, event.block, event.atMillis, event.message)
        }
        return peer.start(scope)
    }

    public fun tests(): List<Pair<E2eIndex.SuiteEntry, E2eIndex.TestEntry>> =
        index.files.flatMap { file -> file.suites.flatMap { suite -> suite.tests.map { suite to it } } }

    public suspend fun runAll(): RunReport {
        val startedAt = System.currentTimeMillis()
        val reports = tests().map { (suite, test) -> runTest(suite, test) }
        return RunReport(reports, startedAt, System.currentTimeMillis() - startedAt)
    }

    public suspend fun runTest(suite: E2eIndex.SuiteEntry, test: E2eIndex.TestEntry): TestReport {
        val runId = "${test.id}#${runCounter.incrementAndGet()}"
        logs.clear()
        blocks.clear()
        val startedAt = System.currentTimeMillis()
        var failure: RemoteFailure? = null

        try {
            withTimeout(testTimeout) {
                // A test is an ordered list of steps, so running it is walking that list. Nothing
                // here loads or executes test code: it only says which node runs what, in what order.
                for (step in test.steps) {
                    if (step.parallel) {
                        // The one place ordering is deliberately given up. Failing the whole step if
                        // any member fails is what coroutineScope does, and what a test means by it.
                        coroutineScope {
                            step.blocks.forEach { block -> launch { dispatch(runId, test, block) } }
                        }
                    } else {
                        step.blocks.forEach { block -> dispatch(runId, test, block) }
                    }
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            failure = timedOut(test)
        } catch (remote: RemoteInvocationException) {
            failure = remote.failure
        } catch (error: Throwable) {
            failure = error.toRemoteFailure(NodeId.ORCHESTRATOR)
        } finally {
            shared.clear(runId)
        }

        return TestReport(
            suiteId = suite.id,
            suiteName = suite.name,
            testId = test.id,
            testName = test.name,
            outcome = when {
                failure == null -> Outcome.PASSED
                failure.assertion -> Outcome.FAILED
                else -> Outcome.ERROR
            },
            durationMillis = System.currentTimeMillis() - startedAt,
            blocks = blocks.toList(),
            log = logs.toList(),
            failure = failure,
        )
    }

    /**
     * What the test ran out of time doing.
     *
     * A forgotten write is the commonest way to reach here, and "the test timed out" would leave
     * whoever reads the report to guess which value was missing, so the parked read says it instead.
     */
    private fun timedOut(test: E2eIndex.TestEntry): RemoteFailure {
        val parked = waitingOn
        val detail = if (parked == null) {
            "no node was waiting on the orchestrator, so a block was still running"
        } else {
            "still waiting on shared value `${parked.id.value}`"
        }
        return RemoteFailure(
            type = "dev.vibeported.mc.e2e.orchestrator.TestTimedOut",
            message = "`${test.name}` ran for $testTimeout without finishing; $detail",
            stack = "",
        )
    }

    private suspend fun dispatch(runId: String, test: E2eIndex.TestEntry, block: BlockId) {
        val entry = blockEntries[block]
            ?: error("The index lists step `$block` for `${test.id}` but has no entry for it")
        route(InvokeBlock(runId, block, entry.target(), test.name))
    }

    private fun E2eIndex.BlockEntry.target(): NodeId = when (role) {
        NodeRole.SERVER -> NodeId.SERVER
        NodeRole.CLIENT -> NodeId.client(client)
        NodeRole.ORCHESTRATOR -> error("The orchestrator runs no blocks; `$id` should not target it")
    }

    /** Single entry point for every payload, whether it arrived over the wire or from [runTest]. */
    private suspend fun route(payload: Payload): JsonElement? = when (payload) {
        is SharedGet -> if (payload.await) {
            waitingOn = payload
            try {
                shared.await(payload.runId, payload.id, payload.timeoutMillis?.milliseconds)
            } finally {
                waitingOn = null
            }
        } else {
            shared.peek(payload.runId, payload.id)
        }
        is SharedSet -> {
            shared.set(payload.runId, payload.id, payload.value)
            null
        }

        is InvokeBlock -> invoke(payload)

        // Relayed rather than acted on: this process has no game in it. Moving a player is the
        // server's job, and only a client can confirm it has caught up.
        is ControlPlayer -> peer.call(NodeId.SERVER, payload)
        is AwaitPlayer -> peer.call(NodeId.client(payload.client), payload)

        is Cancel -> null
    }

    private suspend fun invoke(payload: InvokeBlock): JsonElement? {
        val startedAt = System.currentTimeMillis()
        var outcome = Outcome.PASSED
        try {
            return peer.call(payload.target, payload)
        } catch (failure: Throwable) {
            outcome = when {
                failure is RemoteInvocationException && failure.failure.assertion -> Outcome.FAILED
                else -> Outcome.ERROR
            }
            throw failure
        } finally {
            blocks += BlockRecord(
                id = payload.block,
                node = payload.target,
                startedAtMillis = startedAt,
                durationMillis = System.currentTimeMillis() - startedAt,
                outcome = outcome,
            )
        }
    }
}
