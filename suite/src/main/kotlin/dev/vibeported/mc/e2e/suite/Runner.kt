package dev.vibeported.mc.e2e.suite

import dev.vibeported.mc.e2e.Logs
import dev.vibeported.mc.e2e.RunContext
import dev.vibeported.mc.e2e.protocol.AssertionFailure
import dev.vibeported.mc.e2e.rpc.RemoteInvocationException
import dev.vibeported.mc.e2e.rpc.toRemoteFailure
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
        val reports = suites.flatMap { suite -> suite.tests.map { runTest(suite, it, timeout) } }
        return RunReport(reports, startedAt, System.currentTimeMillis() - startedAt)
    }

    private suspend fun runTest(suite: Suite, test: Test, timeout: Duration): TestReport {
        val runId = suite.name + "/" + test.name + "#" + runCounter.incrementAndGet()
        val lines = CopyOnWriteArrayList<LogLine>()
        Logs.listener = { event ->
            lines += LogLine(event.from, event.procedure, event.atMillis, event.message)
        }

        val startedAt = System.currentTimeMillis()
        var failure: dev.vibeported.mc.e2e.rpc.RemoteFailure? = null

        try {
            withTimeout(timeout) {
                // The run identity travels with every call made underneath, which is what lets a
                // node file its screenshots and log lines under the right test.
                withContext(RunContext(runId, test.name)) { test.body() }
            }
        } catch (expired: TimeoutCancellationException) {
            failure = dev.vibeported.mc.e2e.rpc.RemoteFailure(
                type = "dev.vibeported.mc.e2e.suite.TestTimedOut",
                message = "`" + test.name + "` ran for " + timeout + " without finishing",
                stack = "",
            )
        } catch (remote: RemoteInvocationException) {
            failure = remote.failure
        } catch (thrown: Throwable) {
            failure = thrown.toRemoteFailure()
        } finally {
            Logs.listener = null
        }

        return TestReport(
            suiteId = suite.name,
            suiteName = suite.name,
            testId = runId,
            testName = test.name,
            outcome = when {
                failure == null -> Outcome.PASSED
                failure.assertion -> Outcome.FAILED
                else -> Outcome.ERROR
            },
            durationMillis = System.currentTimeMillis() - startedAt,
            blocks = emptyList(),
            log = lines.toList(),
            failure = failure,
        )
    }
}
