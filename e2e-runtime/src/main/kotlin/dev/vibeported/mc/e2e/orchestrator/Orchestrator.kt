package dev.vibeported.mc.e2e.orchestrator

import dev.vibeported.mc.e2e.E2eAssertionError
import dev.vibeported.mc.e2e.NodeId
import dev.vibeported.mc.e2e.SuiteDescriptor
import dev.vibeported.mc.e2e.TestDescriptor
import dev.vibeported.mc.e2e.node.Facilities
import dev.vibeported.mc.e2e.node.NodeBlockScope
import dev.vibeported.mc.e2e.node.TableRegistry
import dev.vibeported.mc.e2e.report.BlockRecord
import dev.vibeported.mc.e2e.report.LogLine
import dev.vibeported.mc.e2e.report.Outcome
import dev.vibeported.mc.e2e.report.RunReport
import dev.vibeported.mc.e2e.report.TestReport
import dev.vibeported.mc.e2e.rpc.Cancel
import dev.vibeported.mc.e2e.rpc.InvokeBlock
import dev.vibeported.mc.e2e.rpc.JsonValueCodec
import dev.vibeported.mc.e2e.rpc.Payload
import dev.vibeported.mc.e2e.rpc.RemoteFailure
import dev.vibeported.mc.e2e.rpc.RemoteInvocationException
import dev.vibeported.mc.e2e.rpc.Request
import dev.vibeported.mc.e2e.rpc.RpcPeer
import dev.vibeported.mc.e2e.rpc.SharedGet
import dev.vibeported.mc.e2e.rpc.SharedSet
import dev.vibeported.mc.e2e.rpc.ValueCodec
import dev.vibeported.mc.e2e.rpc.toRemoteFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Runs tests, owns the shared state, and is the only node that sees the whole run.
 *
 * Every block invocation and every shared read or write passes through here, including ones raised
 * on another node. That is what lets a single report interleave blocks and log lines from all of
 * them against one clock.
 */
public class Orchestrator(
    private val peer: RpcPeer,
    private val registry: TableRegistry,
    private val facilities: Facilities = Facilities.EMPTY,
    private val codec: ValueCodec = JsonValueCodec(),
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

    public suspend fun runAll(): RunReport {
        val startedAt = System.currentTimeMillis()
        val reports = registry.suites().flatMap { suite ->
            suite.tests.map { runTest(suite, it) }
        }
        return RunReport(reports, startedAt, System.currentTimeMillis() - startedAt)
    }

    public suspend fun runTest(suite: SuiteDescriptor, test: TestDescriptor): TestReport {
        val runId = "${test.id}#${runCounter.incrementAndGet()}"
        logs.clear()
        blocks.clear()
        val startedAt = System.currentTimeMillis()
        var failure: RemoteFailure? = null

        try {
            // The driver is a block like any other; it just happens to target the orchestrator.
            route(InvokeBlock(runId, test.driver, NodeId.ORCHESTRATOR))
        } catch (assertion: E2eAssertionError) {
            failure = assertion.toRemoteFailure(NodeId.ORCHESTRATOR)
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

    /** Single entry point for every payload, whether it arrived over RPC or from our own driver. */
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
            return if (payload.target == peer.self) runHere(payload) else peer.call(payload.target, payload)
        } catch (failure: Throwable) {
            outcome = when {
                failure is E2eAssertionError -> Outcome.FAILED
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

    private suspend fun runHere(payload: InvokeBlock): JsonElement? {
        val table = registry.tableFor(payload.block)
        val scope = NodeBlockScope(
            self = peer.self,
            runId = payload.runId,
            currentBlock = payload.block,
            facilities = facilities,
            codec = codec,
            emitLog = { logs += LogLine(it.from, it.block, it.atMillis, it.message) },
            // No round trip: we are the orchestrator, so our driver calls straight into route().
            toOrchestrator = ::route,
        )
        table.invoke(payload.block.value, scope)
        return null
    }
}
