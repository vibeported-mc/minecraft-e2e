package dev.vibeported.mc.e2e.world

import dev.vibeported.mc.e2e.NodeScope
import dev.vibeported.mc.e2e.facility

/**
 * This node's read-only view of the world.
 *
 * Read-only on purpose: it is the same call on the server and on a client, and only one of them is
 * allowed to write. Ask for [serverWorld] when you mean to change something.
 */
public val NodeScope.world: World get() = facility()

/** The authoritative world. Only resolves inside a `server { }` block. */
public val NodeScope.serverWorld: MockServerWorld get() = facility()

/** This client's replica. Only resolves inside a `client { }` block. */
public val NodeScope.clientWorld: MockClientWorld get() = facility()
