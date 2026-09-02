package dev.vibeported.mc.e2e.node

import dev.vibeported.mc.e2e.protocol.ProcedureId

/**
 * Whatever this node can leave behind when a procedure fails.
 *
 * A hook rather than a feature, because the transport has no idea what a screenshot is and no way
 * to take one. The gameplay module knows how to photograph a client and registers itself here; on a
 * server, or in a process with no game at all, nothing does and nothing is captured.
 */
public object FailureArtifacts {

    /** Returns a path to whatever it captured, or null if it could not. Never throws. */
    public var capturer: (suspend (procedure: ProcedureId, label: String) -> String?)? = null

    internal suspend fun capture(procedure: ProcedureId, label: String): String? = try {
        capturer?.invoke(procedure, label)
    } catch (ignored: Throwable) {
        // A capture that fails must not replace the failure being reported.
        null
    }
}
