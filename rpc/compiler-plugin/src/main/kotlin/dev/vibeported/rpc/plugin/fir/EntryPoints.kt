package dev.vibeported.rpc.plugin.fir

import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.FirAnonymousFunctionExpression
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirFunctionTypeConversionExpression
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.resolvedArgumentMapping
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirValueParameterSymbol
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/**
 * Finding the places a procedure body is written.
 *
 * By a marked *parameter* rather than a marked function, which is what lets a body be handed from
 * one function to another. Anyone can write `forEachRpcCallRandom(where) { }` -- it takes a body at
 * an `@RpcLift` parameter, does what it likes with the target list, and passes the body to a call.
 * The plugin never learns its name.
 */
internal object EntryPoints {

    private val LIFT = ClassId.topLevel(FqName("dev.vibeported.rpc.RpcLift"))
    private val ROLE = ClassId.topLevel(FqName("dev.vibeported.rpc.RpcRole"))
    private val BODIES = (0..5)
        .map { ClassId.topLevel(FqName("dev.vibeported.rpc.RpcBody$it")) }
        .toSet()

    /** An argument written at a body parameter, kept with the parameter that declared it. */
    class Lifted(val body: FirExpression, val parameter: FirValueParameterSymbol)

    /** Every argument written at a parameter that takes a body. */
    fun liftedArguments(expression: FirFunctionCall): List<Lifted> =
        expression.resolvedArgumentMapping
            ?.entries
            ?.mapNotNull { (argument, parameter) ->
                val symbol = parameter.symbol
                symbol.takeIf { it.annotations.any { annotation -> annotation.classId() == LIFT } }
                    ?.let { Lifted(argument, it) }
            }
            .orEmpty()

    /**
     * The lambda behind an argument, seeing past the conversion the frontend inserted.
     *
     * A body parameter is a `fun interface`, so a lambda written at one arrives wrapped in a SAM
     * conversion rather than bare. That wrapper is the price of the interface being an honest type
     * instead of a function type, and unwrapping it is the whole cost.
     */
    fun asLambda(expression: FirExpression): FirAnonymousFunctionExpression? = when (expression) {
        is FirAnonymousFunctionExpression -> expression
        is FirFunctionTypeConversionExpression -> asLambda(expression.expression)
        else -> null
    }

    /**
     * Whether a call is someone running a procedure body where it stands.
     *
     * `RpcBodyN.run` exists so that a lambda has something to convert to, and for nothing else. A
     * generated table calls the lifted function directly, and the value a forwarding function holds
     * is a handle naming a procedure -- so every call to `run` is a body about to fail at run time
     * saying it is not here.
     */
    fun isBodyInvocation(expression: FirFunctionCall): Boolean {
        val callee = expression.calleeReference.toResolvedCallableSymbol() ?: return false
        if (callee.name.asString() != "run") return false
        return callee.callableId?.classId in BODIES
    }

    /**
     * Whether an expression is a body that was already lifted somewhere else.
     *
     * The only thing that can be is a reference to an enclosing `@RpcLift` parameter: whatever was
     * written there has been through this same check. Anything else -- a lambda in a variable, a
     * function reference -- is a body nothing ever lifted, and would fail at the far end of a chain
     * with no clue as to which link dropped it.
     */
    fun isForwardedBody(expression: FirExpression): Boolean {
        val access = expression as? FirPropertyAccessExpression ?: return false
        val parameter = access.calleeReference.toResolvedCallableSymbol() as? FirValueParameterSymbol
            ?: return false
        return parameter.annotations.any { it.classId() == LIFT }
    }

    /**
     * Which table this body belongs in: what the lambda says, else the parameter, else the file.
     *
     * Three places, in the order of how specific they are. The lambda is the exception written at
     * one call site; the parameter is what a call like `client { }` fixes for every body handed to
     * it; the file is the default for everything in it. A function-level annotation would be a
     * fourth place to look and is deliberately not read.
     */
    fun roleOf(lifted: Lifted?, context: CheckerContext): String? {
        val onLambda = (lifted?.body as? FirAnonymousFunctionExpression)?.let { lambda ->
            roleIn(lambda.annotations) ?: roleIn(lambda.anonymousFunction.annotations)
        }
        val onParameter = lifted?.parameter?.let(::roleOfParameter)
        return onLambda ?: onParameter ?: context.containingFileSymbol?.annotations?.let { roleIn(it) }
    }

    /** The `role` given to the `@RpcLift` on a body parameter, or null when it was left empty. */
    private fun roleOfParameter(parameter: FirValueParameterSymbol): String? {
        val annotation = parameter.annotations.firstOrNull { it.classId() == LIFT } ?: return null
        val argument = annotation.argumentMapping.mapping[Name.identifier("role")]
        return ((argument as? FirLiteralExpression)?.value as? String)?.takeIf { it.isNotEmpty() }
    }

    fun roleIn(annotations: List<FirAnnotation>): String? {
        val annotation = annotations.firstOrNull { it.classId() == ROLE } ?: return null
        val argument = annotation.argumentMapping.mapping.values.firstOrNull()
        return (argument as? FirLiteralExpression)?.value as? String
    }

    private fun FirAnnotation.classId(): ClassId? = annotationTypeRef.coneType.classId
}
