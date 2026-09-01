package dev.vibeported.mc.e2e.plugin.fir

import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirExpressionChecker
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.declarations.FirVariable
import org.jetbrains.kotlin.fir.expressions.FirAnonymousFunctionExpression
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirVariableAssignment
import org.jetbrains.kotlin.fir.expressions.resolvedArgumentMapping
import org.jetbrains.kotlin.fir.expressions.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.references.toResolvedVariableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirValueParameterSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirVariableSymbol
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/**
 * Checks every `server`/`client` call as it is written.
 *
 * One rule earns its keep here, and it is the capture rule. A block body is lifted out of its
 * closure at compile time, so a reference to anything around it is not a style problem: it is code
 * that cannot possibly run on the node it is being sent to. Catching that under the cursor is the
 * whole reason the plugin has a frontend half at all.
 *
 * The rules that governed the old declarative test body are gone with it -- a test is ordinary code
 * now, so there is nothing left to restrict about how it is written.
 */
object ProcedureCallChecker : FirExpressionChecker<FirFunctionCall>(MppCheckerKind.Common) {

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirFunctionCall) {
        if (expression.toResolvedCallableSymbol()?.callableId !in Primitives.BLOCKS) return
        checkLambdaLiteral(expression)
        checkCaptures(expression)
    }

    /**
     * A block body has to be a lambda written in place.
     *
     * A function reference would have no stable identity to key the dispatch table by, and no body
     * for the plugin to lift.
     */
    context(context: CheckerContext, reporter: DiagnosticReporter)
    private fun checkLambdaLiteral(expression: FirFunctionCall) {
        val body = expression.argumentFor("body") ?: return
        if (body !is FirAnonymousFunctionExpression) {
            reporter.reportOn(body.source, ProcedureDiagnostics.PROCEDURE_NOT_LITERAL)
        }
    }

    /**
     * Rejects any reference out of the block.
     *
     * Anything declared inside it is fine, its own arguments are fine, and so is anything top-level
     * or static, because every node loads the same jars. What cannot survive the trip is a local of
     * the enclosing function -- and now that a block takes arguments, the fix is to pass it as one.
     */
    context(context: CheckerContext, reporter: DiagnosticReporter)
    private fun checkCaptures(expression: FirFunctionCall) {
        val lambda = (expression.argumentFor("body") as? FirAnonymousFunctionExpression)
            ?.anonymousFunction
            ?: return

        val declaredInside = collectDeclaredSymbols(lambda)

        lambda.body?.accept(object : FirVisitorVoid() {
            override fun visitElement(element: FirElement) = element.acceptChildren(this)

            override fun visitPropertyAccessExpression(propertyAccessExpression: FirPropertyAccessExpression) {
                // Only a bare reference can be a captured local. Reached through a receiver it is a
                // member of something, and the thing worth complaining about is the receiver -- which
                // the walk below arrives at on its own. Without this, a member of a local class is
                // itself reported as local, and one capture is announced twice.
                if (propertyAccessExpression.explicitReceiver == null) {
                    inspect(
                        propertyAccessExpression,
                        propertyAccessExpression.calleeReference.toResolvedVariableSymbol(),
                    )
                }
                propertyAccessExpression.acceptChildren(this)
            }

            override fun visitVariableAssignment(variableAssignment: FirVariableAssignment) {
                // Writing to a captured local is just as impossible as reading one, so the target
                // is inspected as well as the value.
                val target = variableAssignment.lValue as? FirPropertyAccessExpression
                if (target?.explicitReceiver == null) {
                    inspect(variableAssignment, target?.calleeReference?.toResolvedVariableSymbol())
                }

                // And then *inside* the target, which is the case that matters: in
                // `captured.field = x` the assignment's own callee is `field`, an ordinary member of
                // some class, and the captured local is only reachable as its receiver. The same
                // goes for `captured[i] = x`, where the target is a call rather than a property at
                // all. Walking the children reaches the local in both, and cannot double-report the
                // plain `captured = x` case, which has no children to walk.
                variableAssignment.lValue.acceptChildren(this)
                variableAssignment.rValue.accept(this)
            }

            private fun inspect(at: FirElement, symbol: FirVariableSymbol<*>?) {
                if (symbol == null || symbol in declaredInside) return
                if (!symbol.isLocalToEnclosingCode()) return
                reporter.reportOn(
                    at.source,
                    ProcedureDiagnostics.ILLEGAL_CAPTURE,
                    symbol.name.asString(),
                )
            }
        })
    }

    /** Every value declared within [lambda], including inside nested lambdas of its own. */
    private fun collectDeclaredSymbols(lambda: FirAnonymousFunction): Set<FirVariableSymbol<*>> {
        val symbols = mutableSetOf<FirVariableSymbol<*>>()
        lambda.valueParameters.forEach { symbols += it.symbol }
        lambda.receiverParameter?.symbol?.let { }
        lambda.body?.accept(object : FirVisitorVoid() {
            override fun visitElement(element: FirElement) {
                if (element is FirVariable) symbols += element.symbol
                if (element is FirAnonymousFunction) element.valueParameters.forEach { symbols += it.symbol }
                element.acceptChildren(this)
            }
        })
        return symbols
    }

    private fun FirVariableSymbol<*>.isLocalToEnclosingCode(): Boolean = when (this) {
        is FirValueParameterSymbol -> true
        is FirPropertySymbol -> isLocal
        else -> false
    }
}

internal object Primitives {
    private val PACKAGE = FqName("dev.vibeported.mc.e2e")

    val SERVER: CallableId = CallableId(PACKAGE, Name.identifier("server"))
    val CLIENT: CallableId = CallableId(PACKAGE, Name.identifier("client"))

    /** The two calls that lift a body onto another node. */
    val BLOCKS: Set<CallableId> = setOf(SERVER, CLIENT)
}

internal fun FirFunctionCall.argumentFor(parameterName: String): FirExpression? =
    resolvedArgumentMapping?.entries?.firstOrNull { it.value.name.asString() == parameterName }?.key
