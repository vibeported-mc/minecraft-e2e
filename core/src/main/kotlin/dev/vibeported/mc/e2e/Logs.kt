package dev.vibeported.mc.e2e

import dev.vibeported.mc.e2e.rpc.Event

/**
 * Where log lines from every node end up.
 *
 * A sink rather than a report, because the transport should not decide what a report is. Nodes emit
 * lines, the orchestrator forwards them here, and whatever is driving the run reads them if it
 * cares. Nothing breaks if nobody does.
 */
public object Logs {

    /** Called for every line, on whatever thread it arrived on. */
    public var listener: ((Event) -> Unit)? = null

    public fun emit(event: Event) {
        listener?.invoke(event)
    }
}
