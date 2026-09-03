package dev.vibeported.mc.e2e

import dev.vibeported.rpc.RpcRole
import dev.vibeported.rpc.currentNode
import dev.vibeported.rpc.forEachRpcCall
import dev.vibeported.rpc.node
import dev.vibeported.rpc.rpcCall
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable

/**
 * Which test is running, as every node in the run sees it.
 *
 * It used to ride in every request, beside the procedure and its arguments. It does not any more:
 * the framework underneath carries procedures and arguments and nothing else, on purpose, so this
 * is announced once per test instead of repeated on every call. A node keeps the last thing it was
 * told.
 */
@Serializable
public data class TestContext(
    public val runId: String,
    public val testName: String,
) {
    public companion object {
        public val NONE: TestContext = TestContext(runId = "anonymous", testName = "")
    }
}

/** What a node remembers about the test it is serving. Registered once, replaced per test. */
public class CurrentTest {

    @Volatile
    public var value: TestContext = TestContext.NONE
}

/**
 * Tells every node in the run which test is starting.
 *
 * A fan-out rather than a field on each request, and worth the one round trip: a node needs this to
 * file a screenshot or a log line under the right test, and repeating it on every call put the test
 * framework's vocabulary into a transport that has no business knowing what a test is.
 */
public suspend fun announceTest(context: TestContext) {
    forEachRpcCall({ true }, context) { announced ->
        services.resolve(CurrentTest::class).value = announced
    }
}

/** One captured line, on its way to the report. */
@Serializable
public data class LogLine(
    public val node: String,
    public val test: String,
    public val atMillis: Long,
    public val message: String,
)

/** Where the orchestrator puts log lines. Registered by whatever is assembling the report. */
public fun interface LogSink {
    public fun accept(line: LogLine)
}

/** Starts a game client that nobody started up front. Registered by the orchestrator. */
public fun interface ClientStarter {
    public suspend fun start(name: String)
}

/**
 * Hands a log line to the orchestrator.
 *
 * An ordinary call, because everything crossing this wire is a serializable value handed to a
 * procedure -- there is no side channel and no second message type. The cost is honest: this is a
 * round trip where the framework it replaces had fire-and-forget, so callers launch it rather than
 * awaiting it inside a body.
 */
public suspend fun reportLog(line: LogLine) {
    rpcCall(node(ORCHESTRATOR_NODE), line) @RpcRole("orchestrator") { sent ->
        services.resolve(LogSink::class).accept(sent)
    }
}

/**
 * Makes sure a client is running before something is addressed to it.
 *
 * A name the run knew about is already up. One built at run time -- read from a parameter, or
 * assembled -- lands here and costs a launch, which is the price of letting a client be named by an
 * expression rather than a literal.
 *
 * Checked against this node's own roster first, so the common case is a set lookup and no traffic
 * at all.
 */
public suspend fun awaitClient(name: String, timeoutMillis: Long = 300_000) {
    val here = currentNode()
    if (here.info.id.value == name) return
    if (here.membership.snapshot().any { it.id.value == name }) return

    rpcCall(node(ORCHESTRATOR_NODE), name) @RpcRole("orchestrator") { wanted ->
        services.resolve(ClientStarter::class).start(wanted)
    }

    // The orchestrator has started the process; it is on the roster once it has a world and a
    // player of its own, which is what that side calls ready.
    withTimeout(timeoutMillis) {
        while (here.membership.snapshot().none { it.id.value == name }) delay(50)
    }
}

/** Every client currently in the run. */
public suspend fun connectedClients(): Set<String> =
    currentNode().membership.snapshot()
        .filter { CLIENT_ROLE in it.roles }
        .map { it.id.value }
        .toSet()

/** Evidence a node collected about a failure, on its way to the report. */
@Serializable
public data class Artifact(
    public val node: String,
    public val test: String,
    public val procedure: String,
    /** Where the node put it. Every process shares a report directory, so a path is enough. */
    public val path: String,
)

/** Where the orchestrator files evidence. Registered by whatever is assembling the report. */
public fun interface ArtifactSink {
    public fun accept(artifact: Artifact)
}

/** Hands the orchestrator a picture of a node at the moment it failed. */
public suspend fun reportArtifact(artifact: Artifact) {
    rpcCall(node(ORCHESTRATOR_NODE), artifact) @RpcRole("orchestrator") { sent ->
        services.resolve(ArtifactSink::class).accept(sent)
    }
}

/**
 * How this node photographs itself, when it can.
 *
 * Registered rather than built in: only a client knows how to take a picture, and neither the
 * framework nor the server has any idea what one is. A node that registers nothing simply reports
 * no evidence.
 */
public object FailureArtifacts {

    /** Returns where it put the picture, or null if it could not take one. */
    public var capturer: (suspend (procedure: String, test: String) -> String?)? = null

    internal suspend fun capture(procedure: String, test: String): String? = try {
        capturer?.invoke(procedure, test)
    } catch (ignored: Throwable) {
        // A capture that fails must not replace the failure being reported.
        null
    }
}
