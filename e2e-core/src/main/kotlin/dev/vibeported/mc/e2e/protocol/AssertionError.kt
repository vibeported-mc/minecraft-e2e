package dev.vibeported.mc.e2e.protocol

/**
 * Failure of an `assertThat` inside a test block.
 *
 * It lives with the wire types rather than the DSL because both ends care: the node throws it, and
 * the orchestrator has to tell it apart from a framework error to decide whether a test FAILED or
 * ERRORed. That distinction crosses the socket, so the type has to be visible on both sides.
 */
public class AssertionFailure(message: String) : AssertionError(message)
