package dev.vibeported.mc.e2e

import dev.vibeported.mc.e2e.protocol.ProcedureId
import dev.vibeported.mc.e2e.protocol.NodeId
import dev.vibeported.mc.e2e.rpc.InvokeProcedure
import kotlin.reflect.KClass

/**
 * Runs one lifted block, wherever it belongs.
 *
 * Every `server { }` and `client { }` in a suite becomes a call to this, with the ids and types the
 * compiler worked out. It is the single place that decides between a direct call and a round trip,
 * which is what lets the same source line mean "just call it" on the node that owns it and "send it
 * over there" everywhere else.
 */
@PluginGenerated
public suspend fun <R> invokeProcedure(
    id: String,
    target: NodeId,
    args: List<Any?>,
    argTypes: List<KClass<*>>,
    resultType: KClass<*>,
): R {
    val node = currentNode()
    val run = currentRun()
    val procedure = ProcedureId(id)

    @Suppress("UNCHECKED_CAST")
    if (node.self == target) {
        // Already where this belongs: hand over the real objects. Nothing is encoded, which is what
        // makes it affordable to build the gameplay DSL out of these same calls -- a server-side
        // helper reaching for `server { }` must not pay for a round trip to say so.
        val scope = node.scopes.create(run, procedure)
        return node.tables.tableFor(procedure).invoke(id, scope, args) as R
    }

    val encoded = args.mapIndexed { index, value -> node.codec.encode(argTypes[index], value) }
    val result = node.relay(
        InvokeProcedure(
            runId = run.runId,
            procedure = procedure,
            target = target,
            test = run.testName,
            args = encoded,
        )
    )

    @Suppress("UNCHECKED_CAST")
    return when {
        resultType == Unit::class -> Unit as R
        result == null -> null as R
        else -> node.codec.decode(resultType, result) as R
    }
}
