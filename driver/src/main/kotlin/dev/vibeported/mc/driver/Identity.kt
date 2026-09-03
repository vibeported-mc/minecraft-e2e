package dev.vibeported.mc.driver

import dev.vibeported.rpc.Role
import dev.vibeported.rpc.host.HubAddress
import java.io.File

/**
 * Who a game process is in a cluster, and how it was told.
 *
 * Node identity reuses the framework's own convention -- `rpc.node`, `rpc.roles`, `rpc.hub` -- so
 * anything that can launch an RPC node can launch a game the same way. Settings that are the
 * driver's own business get their own prefix.
 */

/** The dedicated server. There is one, so it needs no name of its own. */
public const val SERVER_NODE: String = "server"

/** A body only the dedicated server can run. */
public val SERVER_ROLE: Role = Role("server")

/**
 * A body only a game client can run.
 *
 * The role that earns its keep: a dedicated server is dist-cleaned, so the table holding these
 * bodies names classes it does not have. It never claims this role, so it never resolves that table.
 */
public val CLIENT_ROLE: Role = Role("client")

/** `rpc.node` -- this process's name, which for a client is also its username. */
public const val NODE_PROPERTY: String = "rpc.node"

/** `rpc.roles` -- what this process can run, comma separated. */
public const val ROLES_PROPERTY: String = "rpc.roles"

/** `rpc.hub` -- `host:port` of the middle of the star. Told, never discovered. */
public const val HUB_PROPERTY: String = "rpc.hub"

/** `mcdriver.capture.dir` -- where screenshots and recordings are written. */
public const val CAPTURE_DIR_PROPERTY: String = "mcdriver.capture.dir"

/**
 * The roles this process was started with, lowercased.
 *
 * One definition, read from every corner of the mod, and that is the point rather than tidiness:
 * two literals compared in two places is how the client-side hooks once stopped installing without
 * anything failing.
 */
public fun startedRoles(): Set<String> =
    System.getProperty(ROLES_PROPERTY).orEmpty()
        .split(',')
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
        .toSet()

/** Whether this process was started to be a game client. */
public fun startedAsClient(): Boolean = CLIENT_ROLE.value in startedRoles()

/** Whether this process was started to be the dedicated server. */
public fun startedAsServer(): Boolean = SERVER_ROLE.value in startedRoles()

/** This process's node name, or null when it was not started as part of a cluster. */
public fun startedNodeName(): String? = System.getProperty(NODE_PROPERTY)

/** Where the hub is, or null when this process is not part of a cluster. */
public fun hubAddress(): HubAddress? =
    System.getProperty(HUB_PROPERTY)?.takeIf { it.isNotBlank() }?.let(HubAddress::parse)

/**
 * Where captured files go, or null when nobody said.
 *
 * A driver is *told* where to write rather than deciding: naming a directory after a run, or a test,
 * is the business of whatever is driving.
 */
public fun captureDirectory(): File? =
    System.getProperty(CAPTURE_DIR_PROPERTY)?.takeIf { it.isNotBlank() }?.let(::File)
