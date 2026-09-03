package dev.vibeported.mc.e2e.suite

import kotlinx.serialization.json.Json

/** Renders a run for a terminal. */
public object ConsoleReporter {

    public fun render(report: RunReport): String = buildString {
        report.tests.forEach { test ->
            appendLine("${mark(test.outcome)} ${test.suiteName} > ${test.testName}  (${test.durationMillis} ms)")
            if (test.log.isNotEmpty()) {
                appendLine("    log:")
                test.log.sortedBy { it.atMillis }.forEach {
                    appendLine("      [${it.node}] ${it.message}")
                }
            }
            test.failure?.let { failure ->
                appendLine("    ${if (failure.isAssertion) "assertion failed" else failure.type}: ${failure.message}")
                test.artifacts.forEach { appendLine("    evidence: $it") }
                if (!failure.isAssertion) {
                    failure.stack.lineSequence().take(12).forEach { appendLine("      $it") }
                }
            }
        }
        appendLine()
        appendLine(
            "${report.tests.size} test(s): ${report.passed} passed, " +
                "${report.failed} failed, ${report.errored} errored  (${report.durationMillis} ms)"
        )
    }

    private fun mark(outcome: Outcome) = when (outcome) {
        Outcome.PASSED -> "PASS"
        Outcome.FAILED -> "FAIL"
        Outcome.ERROR -> "ERR "
    }
}

/** Renders a run as JSON, for CI to pick apart. */
public object JsonReporter {
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    public fun render(report: RunReport): String = json.encodeToString(RunReport.serializer(), report)
}
