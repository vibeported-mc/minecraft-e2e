package dev.vibeported.mc.e2e

import dev.vibeported.mc.e2e.protocol.ProcedureId

/**
 * Thrown by every DSL function that the compiler plugin is supposed to have replaced.
 *
 * Reaching one of these at runtime means the module was compiled without the plugin, so the block
 * bodies were never lifted into a dispatch table and nothing can be routed to another node.
 */
public class PluginNotAppliedException(what: String) : IllegalStateException(
    "`$what` was not rewritten by the minecraft-e2e compiler plugin. " +
        "Apply the `dev.vibeported.mc.e2e` Gradle plugin to the module that declares this test."
)

/** A dispatch table was asked for a block id it does not contain. */
public class NoSuchProcedureException(
    public val id: ProcedureId,
    public val table: String,
) : IllegalArgumentException("Table $table has no block `$id`")
