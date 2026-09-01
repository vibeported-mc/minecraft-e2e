package dev.vibeported.mc.e2e.orchestrator

import dev.vibeported.mc.e2e.E2eIndex
import dev.vibeported.mc.e2e.NodeId
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
 * It runs no test code itself, and cannot: the lifted block bodies live in a mod jar compiled
 * against Minecraft, while this is a plain JVM with no game on its classpath. So each test driver is
 * dispatched to the server node like any other block, and everything the driver then asks for comes
 * back through here -- which is what gives one report a single ordering over two game processes, and
 * what lets a server block hand work to a client that it has no connection to.
 */
public class Orchestrator(
    private val peer: RpcPeer,
    private val index: E2eIndex,
) {
    private val shared = SharedStore()
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
            route(InvokeBlock(runId, test.driver, NodeId.SERVER))
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
