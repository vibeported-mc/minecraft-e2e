package dev.vibeported.mc.e2e.plugin.fir

import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirExpressionChecker
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirVariable
import org.jetbrains.kotlin.fir.expressions.FirAnonymousFunctionExpression
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
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
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/**
 * Checks every DSL call as it is written.
 *
 * The capture rule is the one that earns its keep. A block body is lifted out of its closure at
 * compile time, so a reference to anything around it is not a style problem, it is code that cannot
 * possibly run on the node it is being sent to. Catching that here, under the cursor, is the whole
 * reason the plugin has a frontend half at all.
 */
object E2eCallChecker : FirExpressionChecker<FirFunctionCall>(MppCheckerKind.Common) {

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirFunctionCall) {
        when (expression.toResolvedCallableSymbol()?.callableId) {
            E2eCallables.SUITE -> {
                checkConstantName(expression)
                checkLambdaLiteral(expression)
                checkDuplicateTestNames(expression)
            }

            E2eCallables.E2E -> {
                checkConstantName(expression)
                checkLambdaLiteral(expression)
                checkDuplicateSharedNames(expression)
            }

            E2eCallables.SERVER, E2eCallables.CLIENT -> {
                checkLambdaLiteral(expression)
                checkCaptures(expression)
                checkNotInsideAForeignLambda(expression)
            }

            else -> Unit
        }
    }

    /**
     * A block only gets a stable ordinal if it is written straight into an enclosing block.
     *
     * Put one inside, say, a `forEach` and how many blocks exist becomes a runtime question, which
     * no compile-time table can answer.
     */
    context(context: CheckerContext, reporter: DiagnosticReporter)
    private fun checkNotInsideAForeignLambda(expression: FirFunctionCall) {
        val elements = context.containingElements
        val lambdaIndex = elements.indexOfLast { it is FirAnonymousFunction }
        if (lambdaIndex < 0) return

        // Whatever call that lambda was an argument to decides whether it is an e2e block.
        val owner = elements.take(lambdaIndex).lastOrNull { it is FirFunctionCall } as? FirFunctionCall
        val ownerId = owner?.toResolvedCallableSymbol()?.callableId
        if (ownerId !in E2eCallables.BLOCK_BODY_OWNERS) {
            reporter.reportOn(expression.source, E2eDiagnostics.E2E_BLOCK_IN_NESTED_LAMBDA)
        }
    }

    context(context: CheckerContext, reporter: DiagnosticReporter)
    private fun checkDuplicateTestNames(expression: FirFunctionCall) {
        val body = (expression.argumentFor("body") as? FirAnonymousFunctionExpression)
            ?.anonymousFunction ?: return

        val seen = mutableSetOf<String>()
        body.body?.accept(object : FirVisitorVoid() {
            override fun visitElement(element: FirElement) = element.acceptChildren(this)

            override fun visitFunctionCall(functionCall: FirFunctionCall) {
                if (functionCall.toResolvedCallableSymbol()?.callableId == E2eCallables.E2E) {
                    val name = (functionCall.argumentFor("name") as? FirLiteralExpression)?.value as? String
                    if (name != null && !seen.add(name)) {
                        reporter.reportOn(
                            functionCall.argumentFor("name")?.source,
                            E2eDiagnostics.E2E_DUPLICATE_NAME,
                            name,
                        )
                    }
                }
                functionCall.acceptChildren(this)
            }
        })
    }

    context(context: CheckerContext, reporter: DiagnosticReporter)
    private fun checkDuplicateSharedNames(expression: FirFunctionCall) {
        val body = (expression.argumentFor("body") as? FirAnonymousFunctionExpression)
            ?.anonymousFunction ?: return

        val seen = mutableSetOf<String>()
        body.body?.accept(object : FirVisitorVoid() {
            override fun visitElement(element: FirElement) = element.acceptChildren(this)

            override fun visitProperty(property: FirProperty) {
                val delegate = property.delegate as? FirFunctionCall
                if (delegate?.toResolvedCallableSymbol()?.callableId == E2eCallables.SHARED) {
                    val name = property.name.asString()
                    if (!seen.add(name)) {
                        reporter.reportOn(property.source, E2eDiagnostics.E2E_DUPLICATE_NAME, name)
                    }
                }
                property.acceptChildren(this)
            }
        })
    }

    context(context: CheckerContext, reporter: DiagnosticReporter)
    private fun checkConstantName(expression: FirFunctionCall) {
        val name = expression.argumentFor("name") ?: return
        if (name !is FirLiteralExpression || name.value !is String) {
            reporter.reportOn(name.source, E2eDiagnostics.E2E_NAME_NOT_CONSTANT)
        }
    }

    context(context: CheckerContext, reporter: DiagnosticReporter)
    private fun checkLambdaLiteral(expression: FirFunctionCall) {
        val body = expression.argumentFor("body") ?: return
        if (body !is FirAnonymousFunctionExpression) {
            reporter.reportOn(body.source, E2eDiagnostics.E2E_BLOCK_NOT_LITERAL)
        }
    }

    /**
     * Rejects any reference out of the block that is not a `shared` value.
     *
     * Anything declared inside the block is fine, and so is anything top-level or static, because
     * every node loads the same jar. What cannot survive the trip is a local of the enclosing
     * lambda, and that is exactly what this refuses.
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
                inspect(propertyAccessExpression, propertyAccessExpression.calleeReference.toResolvedVariableSymbol())
                propertyAccessExpression.acceptChildren(this)
            }

            override fun visitVariableAssignment(variableAssignment: FirVariableAssignment) {
                // The target is the lValue; writing to a captured local is just as impossible as
                // reading one, so both sides are inspected.
                val target = variableAssignment.lValue as? FirPropertyAccessExpression
                inspect(variableAssignment, target?.calleeReference?.toResolvedVariableSymbol())
                variableAssignment.rValue.accept(this)
            }

            private fun inspect(at: FirElement, symbol: FirVariableSymbol<*>?) {
                if (symbol == null || symbol in declaredInside) return
                if (!symbol.isLocalToEnclosingCode()) return
                if (symbol.isSharedDelegate()) return
                reporter.reportOn(
                    at.source,
                    E2eDiagnostics.E2E_ILLEGAL_CAPTURE,
                    symbol.name.asString(),
                )
            }
        })
    }

    /** Every value declared within [lambda], including inside nested lambdas of its own. */
    private fun collectDeclaredSymbols(lambda: FirAnonymousFunction): Set<FirVariableSymbol<*>> {
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

    private fun FirVariableSymbol<*>.isLocalToEnclosingCode(): Boolean = when (this) {
        is FirValueParameterSymbol -> true
        is FirPropertySymbol -> isLocal
        else -> false
    }

    private fun FirVariableSymbol<*>.isSharedDelegate(): Boolean {
        val property = fir as? FirProperty ?: return false
        val delegate = property.delegate as? FirFunctionCall ?: return false
        return delegate.toResolvedCallableSymbol()?.callableId == E2eCallables.SHARED
    }

}

/** Rejects `var x = shared<T>()`, which silently would not be wired to anything. */
object E2eSharedPlacementChecker : FirExpressionChecker<FirFunctionCall>(MppCheckerKind.Common) {

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirFunctionCall) {
        if (expression.toResolvedCallableSymbol()?.callableId != E2eCallables.SHARED) return

        // Legal only as the delegate of a property: `var pos by shared<BlockPos>()`.
        val owner = context.containingElements.lastOrNull { it is FirProperty } as? FirProperty
        if (owner == null || owner.delegate !== expression) {
            reporter.reportOn(expression.source, E2eDiagnostics.E2E_SHARED_MISPLACED)
        }
    }
}

internal object E2eCallables {
    private val PACKAGE = FqName("dev.vibeported.mc.e2e")
    private val SUITE_BUILDER = ClassId(PACKAGE, Name.identifier("SuiteBuilder"))

    val SUITE: CallableId = CallableId(PACKAGE, Name.identifier("suite"))
    val E2E: CallableId = CallableId(SUITE_BUILDER, Name.identifier("e2e"))
    val SERVER: CallableId = CallableId(PACKAGE, Name.identifier("server"))
    val CLIENT: CallableId = CallableId(PACKAGE, Name.identifier("client"))
    val SHARED: CallableId = CallableId(PACKAGE, Name.identifier("shared"))

    /** Calls whose lambda argument is itself a lifted block, and so may contain further blocks. */
    val BLOCK_BODY_OWNERS: Set<CallableId> = setOf(E2E, SERVER, CLIENT)
}

internal fun FirFunctionCall.argumentFor(parameterName: String): FirExpression? =
    resolvedArgumentMapping?.entries?.firstOrNull { it.value.name.asString() == parameterName }?.key
