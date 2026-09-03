package dev.vibeported.rpc.plugin.fir

import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.FirAnonymousFunctionExpression
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.expressions.resolvedArgumentMapping
import org.jetbrains.kotlin.fir.references.toResolvedNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName

/**
 * Recognising the calls this plugin is about.
 *
 * By annotation rather than by name, so a layer built on the framework can declare its own
 * vocabulary -- `client(name) { }` handing the body a game client -- and have those bodies lifted
 * too. A wrapper that merely forwarded its lambda could not be lifted at all, because by the time
 * the plugin saw the forwarding call there would be no literal left to take.
 */
internal object EntryPoints {

    private val ENTRY_POINT = ClassId.topLevel(FqName("dev.vibeported.rpc.RpcEntryPoint"))
    private val ROLE = ClassId.topLevel(FqName("dev.vibeported.rpc.RpcRole"))

    fun calleeOf(expression: FirFunctionCall): FirNamedFunctionSymbol? {
        val callee = expression.calleeReference.toResolvedNamedFunctionSymbol() ?: return null
        return callee.takeIf { symbol -> symbol.annotations.any { it.classId() == ENTRY_POINT } }
    }

    /**
     * The argument holding the body, found by position rather than by name.
     *
     * The last parameter, because that is what a trailing lambda binds to and the only thing every
     * entry point is guaranteed to agree on -- a downstream layer is free to call the parameter
     * whatever reads best.
     */
    fun bodyArgument(expression: FirFunctionCall, callee: FirNamedFunctionSymbol): FirExpression? {
        val last = callee.valueParameterSymbols.lastOrNull() ?: return null
        return expression.resolvedArgumentMapping
            ?.entries
            ?.firstOrNull { it.value.symbol == last }
            ?.key
    }

    /**
     * Which table this body belongs in: what the lambda says, else the file.
     *
     * A function-level annotation would sit between the two, and is not read yet -- the file and the
     * call cover what a dist split actually needs, and a third place to look is a third place to
     * have to explain.
     */
    fun roleOf(body: FirExpression?, context: CheckerContext): String? {
        val onLambda = (body as? FirAnonymousFunctionExpression)?.let { lambda ->
            lambda.annotations.roleValue() ?: lambda.anonymousFunction.annotations.roleValue()
        }
        return onLambda ?: context.containingFileSymbol?.annotations?.roleValue()
    }

    fun roleIn(annotations: List<FirAnnotation>): String? = annotations.roleValue()

    private fun List<FirAnnotation>.roleValue(): String? {
        val annotation = firstOrNull { it.classId() == ROLE } ?: return null
        val argument = annotation.argumentMapping.mapping.values.firstOrNull()
        return (argument as? FirLiteralExpression)?.value as? String
    }

    private fun FirAnnotation.classId(): ClassId? = annotationTypeRef.coneType.classId
}
