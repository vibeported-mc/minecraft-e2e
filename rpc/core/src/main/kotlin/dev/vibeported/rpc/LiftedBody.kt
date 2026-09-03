// This declares the machinery, so of course it names it.
@file:OptIn(PluginGenerated::class)

package dev.vibeported.rpc

import kotlinx.serialization.KSerializer

/**
 * Marks the parameter a procedure body arrives in.
 *
 * The plugin lifts a lambda written at such a parameter into a table and replaces it with a
 * [LiftedBody]. Anywhere else, the argument is passed along untouched -- which is what lets one
 * function take a body and hand it to another without either knowing about the plugin.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
public annotation class RpcLift

/**
 * A body that has been lifted out of its call site, and everything needed to dispatch it.
 *
 * This is what a lambda becomes. It implements every body shape so it can be passed wherever one is
 * expected, and none of those implementations does anything: a lifted body does not run here, it
 * runs on the node the call is addressed to. Reaching one of them means a body was handed to
 * something that never dispatched it.
 */
@PluginGenerated
public class LiftedBody(
    public val id: String,
    public val role: String?,
    public val argSerializers: List<KSerializer<*>>,
    public val resultSerializer: KSerializer<*>,
) : RpcBody0<Any?, Any?>, RpcBody1<Any?, Any?, Any?>, RpcBody2<Any?, Any?, Any?, Any?>, RpcBody3<Any?, Any?, Any?, Any?, Any?>, RpcBody4<Any?, Any?, Any?, Any?, Any?, Any?>, RpcBody5<Any?, Any?, Any?, Any?, Any?, Any?, Any?> {
    override suspend fun Any?.run(): Any? = notHere()
    override suspend fun Any?.run(a1: Any?): Any? = notHere()
    override suspend fun Any?.run(a1: Any?, a2: Any?): Any? = notHere()
    override suspend fun Any?.run(a1: Any?, a2: Any?, a3: Any?): Any? = notHere()
    override suspend fun Any?.run(a1: Any?, a2: Any?, a3: Any?, a4: Any?): Any? = notHere()
    override suspend fun Any?.run(a1: Any?, a2: Any?, a3: Any?, a4: Any?, a5: Any?): Any? = notHere()

    private fun notHere(): Nothing = error(
        "`$id` was never dispatched. A lifted body runs on the node a call addresses, so something " +
            "took this one and did not pass it to a call."
    )
}

/** The lifted body behind a lambda, or a complaint naming the likely cause. */
internal fun Any.asLifted(): LiftedBody = this as? LiftedBody ?: error(
    "This procedure body was never lifted. Was the RPC compiler plugin applied to the module that " +
        "wrote the lambda?"
)
