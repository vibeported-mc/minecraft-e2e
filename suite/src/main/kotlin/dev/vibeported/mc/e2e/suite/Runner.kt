package dev.vibeported.mc.e2e.suite

import dev.vibeported.mc.e2e.Artifact
import dev.vibeported.mc.e2e.ArtifactSink
import dev.vibeported.mc.e2e.LogSink
import dev.vibeported.mc.e2e.TestContext
import dev.vibeported.mc.e2e.announceTest
import dev.vibeported.rpc.currentNode
import dev.vibeported.rpc.transport.RemoteCallException
import dev.vibeported.rpc.transport.RemoteFailure
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.system.exitProcess
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Runs suites and reports on them.
 *
 * This is a driver, not the framework. It decides what a test is, how long one may take, what a
 * report looks like and what happens when one fails -- and it does all of it through the same
 * `server { }` and `client { }` calls a test uses, with no privileged access to anything.
 */
public object Runner {

    private val runCounter = AtomicLong()

    /**
     * The whole of a `main` for most suites.
     *
     * Prints the report, writes it beside the run, and exits with a code a build can read.
     */
    public fun run(vararg suites: Suite, timeout: Duration = 5.minutes): Nothing {
        val report = runBlocking { runAll(suites.toList(), timeout) }

        print(ConsoleReporter.render(report))

        val reportDir = System.getProperty("e2e.report.dir")?.let(::File)
        if (reportDir != null) {
            reportDir.mkdirs()
            File(reportDir, "report.json").writeText(JsonReporter.render(report))
            println("e2e: report written to " + File(reportDir, "report.json").absolutePath)
        }

        exitProcess(if (report.ok) 0 else 1)
    }

    public suspend fun runAll(suites: List<Suite>, timeout: Duration = 5.minutes): RunReport {
        val startedAt = System.currentTimeMillis()

        // Nodes send their log lines and their evidence here, as ordinary calls. Registered on this
        // process's own node, which is what the orchestrator role's table resolves them against.
        val services = currentNode().services
        services.provide(LogSink::class, LogSink { collected += it })
        services.provide(ArtifactSink::class, ArtifactSink { evidence += it })

        val reports = suites.flatMap { suite -> suite.tests.map { runTest(suite, it, timeout) } }
        return RunReport(reports, startedAt, System.currentTimeMillis() - startedAt)
    }

    // Written to from whichever thread a call arrived on, drained per test.
    private val collected = CopyOnWriteArrayList<dev.vibeported.mc.e2e.LogLine>()
    private val evidence = CopyOnWriteArrayList<Artifact>()

    private suspend fun runTest(suite: Suite, test: Test, timeout: Duration): TestReport {
        val runId = suite.name + "/" + test.name + "#" + runCounter.incrementAndGet()
        collected.clear()
        evidence.clear()

        // Told once, to everyone, rather than carried on every request. A node needs this to file a
        // screenshot or a log line under the right test, and putting it in the transport would have
        // meant the transport knowing what a test is.
        announceTest(TestContext(runId = runId, testName = test.name))

        val startedAt = System.currentTimeMillis()
        var failure: RemoteFailure? = null

        try {
            withTimeout(timeout) { test.body() }
        } catch (expired: TimeoutCancellationException) {
            failure = RemoteFailure(
                type = "dev.vibeported.mc.e2e.suite.TestTimedOut",
                message = "`" + test.name + "` ran for " + timeout + " without finishing",
                stack = "",
            )
        } catch (remote: RemoteCallException) {
            failure = remote.remote
        } catch (thrown: Throwable) {
            failure = RemoteFailure(
                type = thrown::class.java.name,
                message = thrown.message,
                stack = thrown.stackTraceToString(),
            )
        }

        return TestReport(
            suiteId = suite.name,
            suiteName = suite.name,
            testId = runId,
            testName = test.name,
            outcome = when {
                failure == null -> Outcome.PASSED
                failure.isAssertion -> Outcome.FAILED
                else -> Outcome.ERROR
            },
            durationMillis = System.currentTimeMillis() - startedAt,
            log = collected.map { LogLine(it.node, it.atMillis, it.message) },
            artifacts = evidence.map { it.path },
            failure = failure,
        )
    }
}
