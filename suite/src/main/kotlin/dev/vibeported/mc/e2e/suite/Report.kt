package dev.vibeported.mc.e2e.suite

import dev.vibeported.mc.e2e.protocol.AssertionFailure
import dev.vibeported.rpc.transport.RemoteFailure
import kotlinx.serialization.Serializable

/**
 * Whether a failure was an assertion saying no, rather than something going wrong.
 *
 * Read from the type name because that is all a failure carries across a wire -- the exception
 * itself cannot travel. It used to be a boolean field set by the node that threw; deriving it here
 * keeps the transport from having to know what an assertion is.
 */
public val RemoteFailure.isAssertion: Boolean
    get() = type == AssertionFailure::class.java.name

@Serializable
public enum class Outcome {
    PASSED,

    /** An `assertThat` said no. The framework worked; the code under test did not. */
    FAILED,

    /** Something else threw -- a bug in the test, the harness, or a node that fell over. */
    ERROR,
}

/**
 * One captured line, as the report holds it.
 *
 * Separate from the `LogLine` a node sends, because they answer to different things: that one is a
 * wire type and this one is the report's schema. They happen to look alike today.
 */
@Serializable
public data class LogLine(
    public val node: String,
    public val atMillis: Long,
    public val message: String,
)

@Serializable
public data class TestReport(
    public val suiteId: String,
    public val suiteName: String,
    public val testId: String,
    public val testName: String,
    public val outcome: Outcome,
    public val durationMillis: Long,
    public val log: List<LogLine>,
    /** Evidence nodes collected while this test ran -- screenshots, mostly. */
    public val artifacts: List<String> = emptyList(),
    public val failure: RemoteFailure? = null,
)

@Serializable
public data class RunReport(
    public val tests: List<TestReport>,
    public val startedAtMillis: Long,
    public val durationMillis: Long,
) {
    public val passed: Int get() = tests.count { it.outcome == Outcome.PASSED }
    public val failed: Int get() = tests.count { it.outcome == Outcome.FAILED }
    public val errored: Int get() = tests.count { it.outcome == Outcome.ERROR }
    public val ok: Boolean get() = failed == 0 && errored == 0
}
