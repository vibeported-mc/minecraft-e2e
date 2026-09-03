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
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
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
internal class RpcPlanner(
    private val file: IrFile,
    private val roles: RoleIndex,
) {

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
                liftablePositions(expression).forEach { index ->
                    planCall(plan, enclosing, ordinal++, expression, index)
                }
                // Still descend: a body may hold calls of its own, and each needs an id.
                expression.acceptChildrenVoid(this)
            }
        })
    }

    /**
     * Argument positions holding a lambda written in place, at a parameter marked `@RpcLift`.
     *
     * A position whose argument is anything else -- most usually a body being forwarded from an
     * enclosing `@RpcLift` parameter -- is left alone. That single rule is what lets a chain of
     * functions pass a body along without any of them knowing about the others.
     */
    private fun liftablePositions(call: IrCall): List<Int> {
        val parameters = call.symbol.owner.parameters
        return (0 until call.arguments.size).filter { index ->
            val parameter = parameters.getOrNull(index) ?: return@filter false
            parameter.hasAnnotation(LIFT) && call.arguments[index].asLambda() != null
        }
    }

    private fun planCall(plan: FilePlan, enclosing: String, ordinal: Int, call: IrCall, index: Int) {
        val lambda = call.arguments[index]?.asLambda()?.function ?: return

        // Read off the body itself rather than the call's type arguments: the body knows what it
        // takes and what it gives back, and a forwarding chain would have obscured both.
        val arguments = lambda.parameters
            .filter { it.kind == IrParameterKind.Regular }
            .map { it.type }

        plan.procedures += ProcedurePlan(
            id = "${plan.facade}.$enclosing/$ordinal",
            role = roles.roleAt(
                packageName = file.packageFqName.asString(),
                filePath = file.fileEntry.name,
                offset = call.startOffset,
            ),
            call = call,
            argumentIndex = index,
            lambda = lambda,
            argumentTypes = arguments,
            resultType = lambda.returnType,
        )
    }

    /** A lambda literal, whether or not the frontend wrapped it in a SAM conversion. */
    private fun IrExpression?.asLambda(): IrFunctionExpression? = when (this) {
        is IrFunctionExpression -> this
        is IrTypeOperatorCall -> argument as? IrFunctionExpression
        else -> null
    }

    /** `Sample.kt` becomes `SampleKt`, the name the JVM already knows the file's top level by. */
    private fun facadeName(): String {
        val base = file.fileEntry.name
            .replace('\\', '/')
            .substringAfterLast('/')
            .removeSuffix(".kt")
        return base.replaceFirstChar { it.uppercase() } + "Kt"
    }

    private companion object {
        private val LIFT = FqName("dev.vibeported.rpc.RpcLift")
    }
}
