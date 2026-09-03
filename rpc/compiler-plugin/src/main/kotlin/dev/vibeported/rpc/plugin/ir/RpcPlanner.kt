@file:OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)

package dev.vibeported.rpc.plugin.ir

import dev.vibeported.rpc.plugin.RoleIndex
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.name.FqName

/**
 * Works out what each file contributes before anything is rewritten.
 *
 * Separated from the rewriting because the two want different things: planning needs to see the
 * whole file to assign stable ids, while rewriting needs every table to exist before a body nested
 * inside another can find its own lifted method.
 */
internal class RpcPlanner(private val file: IrFile) {

    fun plan(): FilePlan {
        val plan = FilePlan(file, facadeName())
        file.declarations.forEach { declaration -> planIn(plan, declaration) }
        return plan
    }

    private fun planIn(plan: FilePlan, declaration: IrDeclaration) {
        when (declaration) {
            is IrClass -> declaration.declarations.forEach { planIn(plan, it) }

            is IrProperty -> declaration.backingField?.initializer?.let {
                walk(plan, declaration.name.asString(), it)
            }

            is IrSimpleFunction -> declaration.body?.let {
                walk(plan, declaration.name.asString(), it)
            }

            else -> Unit
        }
    }

    /**
     * Every entry-point call under [root], nested lambdas included.
     *
     * A body written inside a `repeat` or a `forEach` is fine: the ordinal counts call sites, not
     * executions, so it has exactly one id however many times it runs.
     */
    private fun walk(plan: FilePlan, enclosing: String, root: IrElement) {
        var ordinal = 0

        root.acceptChildrenVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)

            override fun visitCall(expression: IrCall) {
                if (!expression.symbol.owner.hasAnnotation(ENTRY_POINT)) {
                    expression.acceptChildrenVoid(this)
                    return
                }
                planCall(plan, enclosing, ordinal++, expression)
                // Still descend: a body may hold entry-point calls of its own, and each needs an id.
                expression.acceptChildrenVoid(this)
            }
        })
    }

    private fun planCall(plan: FilePlan, enclosing: String, ordinal: Int, call: IrCall) {
        val lambda = call.trailingLambda() ?: return

        // rpcCall<A1..An, R>: everything but the last type argument is an argument type. A body
        // whose types did not resolve is not one this plugin can encode, and the frontend has
        // already refused it.
        val typeArguments = call.typeArguments
        if (typeArguments.isEmpty() || typeArguments.any { it == null }) return

        plan.procedures += ProcedurePlan(
            id = "${plan.facade}.$enclosing/$ordinal",
            role = RoleIndex.roleAt(file.fileEntry.name, call.startOffset),
            call = call,
            lambda = lambda.function,
            argumentTypes = typeArguments.dropLast(1).map { it!! },
            resultType = typeArguments.last()!!,
        )
    }

    /**
     * The lambda a body was written as.
     *
     * The last function-typed argument, which is what a trailing lambda binds to. Anything else has
     * already been refused by the frontend, so its absence here means only that this call was not
     * written the way an entry point requires.
     */
    private fun IrCall.trailingLambda(): IrFunctionExpression? =
        (0 until arguments.size).mapNotNull { arguments[it] as? IrFunctionExpression }.lastOrNull()

    /** `Sample.kt` becomes `SampleKt`, the name the JVM already knows the file's top level by. */
    private fun facadeName(): String {
        val base = file.fileEntry.name
            .replace('\\', '/')
            .substringAfterLast('/')
            .removeSuffix(".kt")
        return base.replaceFirstChar { it.uppercase() } + "Kt"
    }

    private companion object {
        private val ENTRY_POINT = FqName("dev.vibeported.rpc.RpcEntryPoint")
    }
}
