package dev.vibeported.mc.e2e

import dev.vibeported.rpc.Role

/**
 * Who is in a run, by the names the framework underneath addresses them by.
 *
 * Plain strings, because that is what an open role system is. The framework this replaces had a
 * closed `ORCHESTRATOR`/`SERVER`/`CLIENT` enum, and that single decision is most of what welded it
 * to one game -- a third kind of participant meant editing the transport.
 */

/** The dedicated server. There is one, so it needs no name of its own. */
public const val SERVER_NODE: String = "server"

/** The process driving the run. It holds the hub, and runs no game. */
public const val ORCHESTRATOR_NODE: String = "orchestrator"

/** The client a helper acts on when nobody said which. */
public const val DEFAULT_CLIENT: String = "default"

/** A body only the dedicated server can run. */
public val SERVER_ROLE: Role = Role("server")

/**
 * A body only a game client can run.
 *
 * The one that earns its keep: a dedicated server is dist-cleaned, so the table holding these bodies
 * names classes it does not have. It never claims this role, so it never resolves that table.
 */
public val CLIENT_ROLE: Role = Role("client")

/** A body only the orchestrator can run -- starting a client, collecting a log line. */
public val ORCHESTRATOR_ROLE: Role = Role("orchestrator")

/**
 * How a game process is told what it is in the run.
 *
 * One definition, read from both mods, and that is the point rather than tidiness: these used to be
 * two literals compared in two places, and the moment one of them said `client` where the other
 * said `CLIENT`, the client-side hooks silently stopped installing -- no error, no failing test,
 * just a keyboard that was never taken and screenshots that were never captured.
 */
public const val ROLE_PROPERTY: String = "e2e.node.role"

/** The client's username, which is also how a test addresses it. */
public const val NODE_NAME_PROPERTY: String = "e2e.node.name"

/**
 * The role this process was started as, or null when it is not part of a test run.
 *
 * Lowercased here so no caller has to remember which case the launcher used.
 */
public fun startedRole(): String? = System.getProperty(ROLE_PROPERTY)?.lowercase()

/** The name this process answers to, or the default client. */
public fun startedNodeName(): String = System.getProperty(NODE_NAME_PROPERTY) ?: DEFAULT_CLIENT
