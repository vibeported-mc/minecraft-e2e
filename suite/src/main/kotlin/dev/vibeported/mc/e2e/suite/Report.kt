package dev.vibeported.mc.e2e.suite

import dev.vibeported.mc.e2e.protocol.ProcedureId
import dev.vibeported.mc.e2e.protocol.NodeId
import dev.vibeported.mc.e2e.rpc.RemoteFailure
import kotlinx.serialization.Serializable

@Serializable
public enum class Outcome {
    PASSED,

    /** An `assertThat` said no. The framework worked; the code under test did not. */
    FAILED,

    /** Something else threw -- a bug in the test, the harness, or a node that fell over. */
    ERROR,
}

@Serializable
public data class LogLine(
    public val node: NodeId,
    public val procedure: ProcedureId?,
    public val atMillis: Long,
    public val message: String,
)

@Serializable
public data class BlockRecord(
    public val id: ProcedureId,
    public val node: NodeId,
    public val startedAtMillis: Long,
    public val durationMillis: Long,
    public val outcome: Outcome,
)

@Serializable
public data class TestReport(
    public val suiteId: String,
    public val suiteName: String,
    public val testId: String,
    public val testName: String,
    public val outcome: Outcome,
    public val durationMillis: Long,
    public val blocks: List<BlockRecord>,
    public val log: List<LogLine>,
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
