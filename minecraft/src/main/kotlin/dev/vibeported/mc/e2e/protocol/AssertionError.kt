package dev.vibeported.mc.e2e.protocol

/**
 * Failure of an `assertThat` inside a test block.
 *
 * It lives here rather than in the DSL because both ends care: the node throws it, and the runner
 * has to tell it apart from a framework error to decide whether a test FAILED or ERRORed. The
 * exception itself cannot cross a socket, so what travels is its type name -- which means this type
 * has to be resolvable on both sides for the two to agree on what it was.
 */
public class AssertionFailure(message: String) : AssertionError(message)
