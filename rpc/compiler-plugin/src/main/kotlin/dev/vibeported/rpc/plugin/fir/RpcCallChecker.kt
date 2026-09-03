package dev.vibeported.rpc.plugin.fir

import dev.vibeported.rpc.plugin.RoleIndex
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirExpressionChecker
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.declarations.FirVariable
import org.jetbrains.kotlin.fir.expressions.FirAnonymousFunctionExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirVariableAssignment
import org.jetbrains.kotlin.fir.references.toResolvedVariableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirValueParameterSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirVariableSymbol
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid

/**
 * Checks a procedure call as it is written.
 *
 * One rule earns its keep, and it is the capture rule. A body is lifted out of its closure at
 * compile time, so a reference to anything around it is not a style problem: it is code that cannot
 * possibly run on the node it is being sent to. Catching that under the cursor is the whole reason
 * this plugin has a frontend half.
 */
internal object RpcCallChecker : FirExpressionChecker<FirFunctionCall>(MppCheckerKind.Common) {

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirFunctionCall) {
        val callee = EntryPoints.calleeOf(expression) ?: return
        val body = EntryPoints.bodyArgument(expression, callee) ?: return

        if (body !is FirAnonymousFunctionExpression) {
            reporter.reportOn(body.source, RpcDiagnostics.BODY_NOT_LITERAL)
            return
        }

        // Written down for the backend, which cannot see the annotation: an expression target forces
        // SOURCE retention, so the role is gone by the time a body is lifted into a table. Keyed by
        // the call rather than the lambda -- an annotated lambda begins at the annotation here and
        // at the brace there, so only the call site is spelled the same in both.
        val file = context.containingFileSymbol?.fir?.sourceFile?.path
        val offset = expression.source?.startOffset
        if (file != null && offset != null) {
            RoleIndex.record(file, offset, EntryPoints.roleOf(body, context))
        }

        checkCaptures(body.anonymousFunction)
    }

    /**
     * Rejects any reference out of the body.
     *
     * Anything declared inside is fine, its own parameters are fine, and so is anything top-level or
     * static, because every node loads the same jars. What cannot survive the trip is a local of the
     * enclosing function -- and since a body takes arguments, the fix is always to pass it as one.
     */
    context(context: CheckerContext, reporter: DiagnosticReporter)
    private fun checkCaptures(lambda: FirAnonymousFunction) {
        val declaredInside = declaredWithin(lambda)

        lambda.body?.accept(object : FirVisitorVoid() {
            override fun visitElement(element: FirElement) = element.acceptChildren(this)

            override fun visitPropertyAccessExpression(propertyAccessExpression: FirPropertyAccessExpression) {
                // Only a bare reference can be a captured local. Reached through a receiver it is a
                // member of something, and the thing worth complaining about is the receiver, which
                // the walk arrives at on its own. Without this, a member of a local class is itself
                // reported as local and one capture is announced twice.
                if (propertyAccessExpression.explicitReceiver == null) {
                    inspect(
                        propertyAccessExpression,
                        propertyAccessExpression.calleeReference.toResolvedVariableSymbol(),
                    )
                }
                propertyAccessExpression.acceptChildren(this)
            }

            override fun visitVariableAssignment(variableAssignment: FirVariableAssignment) {
                // Writing to a captured local is as impossible as reading one, so the target is
                // inspected as well as the value.
                val target = variableAssignment.lValue as? FirPropertyAccessExpression
                if (target?.explicitReceiver == null) {
                    inspect(variableAssignment, target?.calleeReference?.toResolvedVariableSymbol())
                }

                // And then inside the target, which is the case that matters: in `captured.field = x`
                // the assignment's own callee is `field`, an ordinary member of some class, and the
                // captured local is reachable only as its receiver.
                variableAssignment.lValue.acceptChildren(this)
                variableAssignment.rValue.accept(this)
            }

            private fun inspect(at: FirElement, symbol: FirVariableSymbol<*>?) {
                if (symbol == null || symbol in declaredInside) return
                if (!symbol.isLocal()) return
                reporter.reportOn(at.source, RpcDiagnostics.ILLEGAL_CAPTURE, symbol.name.asString())
            }
        })
    }

    /** Everything declared within [lambda], including inside lambdas of its own. */
    private fun declaredWithin(lambda: FirAnonymousFunction): Set<FirVariableSymbol<*>> {
        val symbols = mutableSetOf<FirVariableSymbol<*>>()
        lambda.valueParameters.forEach { symbols += it.symbol }
        lambda.body?.accept(object : FirVisitorVoid() {
            override fun visitElement(element: FirElement) {
                if (element is FirVariable) symbols += element.symbol
                if (element is FirAnonymousFunction) element.valueParameters.forEach { symbols += it.symbol }
                element.acceptChildren(this)
            }
        })
        return symbols
    }

    private fun FirVariableSymbol<*>.isLocal(): Boolean = when (this) {
        is FirValueParameterSymbol -> true
        is FirPropertySymbol -> isLocal
        else -> false
    }
}
