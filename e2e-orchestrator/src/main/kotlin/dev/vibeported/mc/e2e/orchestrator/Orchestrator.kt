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
import dev.vibeported.mc.e2e.rpc.Cancel
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
import kotlinx.serialization.json.JsonElement
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
) {
    private val shared = SharedStore()

    /** Every block in the index, so a step id can be resolved to the node that should run it. */
    private val blockEntries: Map<BlockId, E2eIndex.BlockEntry> =
        index.files.flatMap { it.blocks }.associateBy { it.id }
    private val logs = CopyOnWriteArrayList<LogLine>()
    private val blocks = CopyOnWriteArrayList<BlockRecord>()
    private val runCounter = AtomicLong()

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
            // A test is an ordered list of blocks, so running it is walking that list. Nothing here
            // loads or executes test code: it only says which node runs what, and in what order.
            for (step in test.steps) {
                val entry = blockEntries[step]
                    ?: error("The index lists step `$step` for `${test.id}` but has no entry for it")
                route(InvokeBlock(runId, step, entry.target()))
            }
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

    private fun E2eIndex.BlockEntry.target(): NodeId = when (role) {
        NodeRole.SERVER -> NodeId.SERVER
        NodeRole.CLIENT -> NodeId.client(clientIndex)
        NodeRole.ORCHESTRATOR -> error("The orchestrator runs no blocks; `$id` should not target it")
    }

    /** Single entry point for every payload, whether it arrived over the wire or from [runTest]. */
    private suspend fun route(payload: Payload): JsonElement? = when (payload) {
        is SharedGet -> shared.get(payload.runId, payload.id)
        is SharedSet -> {
            shared.set(payload.runId, payload.id, payload.value)
            null
        }

        is InvokeBlock -> invoke(payload)
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
