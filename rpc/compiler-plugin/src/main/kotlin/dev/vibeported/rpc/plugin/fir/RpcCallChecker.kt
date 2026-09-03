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
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid

/**
 * Checks a procedure call as it is written.
 *
 * One rule earns its keep, and it is the capture rule. A body is lifted out of its closure at
 * compile time, so a reference to anything around it is not a style problem: it is code that cannot
 * possibly run on the node it is being sent to. Catching that under the cursor is the whole reason
 * this plugin has a frontend half.
 */
internal class RpcCallChecker(
    private val roles: RoleIndex,
    /** Types this build supplies serializers for. @see RpcCommandLineProcessor.OPTION_CONTEXTUAL */
    private val contextual: Set<String> = emptySet(),
) : FirExpressionChecker<FirFunctionCall>(MppCheckerKind.Common) {

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirFunctionCall) {
        // Running a body by hand. Checked at the callee rather than the receiver, because there
        // are several ways to reach one -- `with(body) { scope.run() }` puts it in an implicit
        // receiver -- and none of them is ever right: a table invokes the lifted function directly,
        // so nothing legitimate calls `RpcBodyN.run` at all.
        if (EntryPoints.isBodyInvocation(expression)) {
            reporter.reportOn(expression.source, RpcDiagnostics.BODY_INVOKED_LOCALLY)
        }

        EntryPoints.liftedArguments(expression).forEach { lifted ->
            val lambda = EntryPoints.asLambda(lifted.body)
            when {
                lambda != null -> lift(expression, lambda, lifted)

                // A body being passed along, which is exactly how a chain of calls is meant to work.
                EntryPoints.isForwardedBody(lifted.body) -> Unit

                else -> reporter.reportOn(lifted.body.source, RpcDiagnostics.BODY_NOT_LITERAL)
            }
        }
    }

    context(context: CheckerContext, reporter: DiagnosticReporter)
    private fun lift(
        call: FirFunctionCall,
        body: FirAnonymousFunctionExpression,
        lifted: EntryPoints.Lifted,
    ) {
        // Written down for the backend, which cannot see the annotation: an expression target forces
        // SOURCE retention, so the role is gone by the time a body is lifted into a table. Keyed by
        // the call rather than the lambda -- an annotated lambda begins at the annotation here and
        // at the brace there, so only the call site is spelled the same in both.
        val containingFile = context.containingFileSymbol?.fir
        val path = containingFile?.sourceFile?.path
        val offset = call.source?.startOffset
        if (containingFile != null && path != null && offset != null) {
            roles.record(
                packageName = containingFile.packageDirective.packageFqName.asString(),
                filePath = path,
                offset = offset,
                role = EntryPoints.roleOf(EntryPoints.Lifted(body, lifted.parameter), context),
            )
        }

        checkCaptures(body.anonymousFunction)
        checkSerializable(body.anonymousFunction)
    }

    /**
     * Rejects an argument or a result nothing can encode.
     *
     * Read off the lambda rather than the call's type arguments, so that the message lands on the
     * declaration that named the type. This is the check the old runtime codec lookup could not
     * make: there, a body returning a `java.io.File` compiled, ran, and failed halfway through a
     * test with nothing pointing back to the signature.
     */
    context(context: CheckerContext, reporter: DiagnosticReporter)
    private fun checkSerializable(lambda: FirAnonymousFunction) {
        lambda.valueParameters.forEach { parameter ->
            val type = parameter.returnTypeRef.coneType
            Serializability.refuse(type, context.session, contextual)?.let { why ->
                reporter.reportOn(
                    parameter.source ?: lambda.source,
                    RpcDiagnostics.UNSERIALIZABLE_TYPE,
                    "argument '${parameter.name.asString()}'",
                    Serializability.render(type),
                    why,
                )
            }
        }

        val result = lambda.returnTypeRef.coneType
        Serializability.refuse(result, context.session, contextual)?.let { why ->
            reporter.reportOn(
                lambda.source,
                RpcDiagnostics.UNSERIALIZABLE_TYPE,
                "result",
                Serializability.render(result),
                why,
            )
        }
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
