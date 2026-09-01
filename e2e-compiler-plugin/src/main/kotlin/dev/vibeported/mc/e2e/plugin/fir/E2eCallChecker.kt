package dev.vibeported.mc.e2e.plugin.fir

import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirExpressionChecker
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirValueParameter
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneTypeOrNull
import org.jetbrains.kotlin.fir.declarations.FirVariable
import org.jetbrains.kotlin.fir.expressions.FirAnonymousFunctionExpression
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirReturnExpression
import org.jetbrains.kotlin.fir.expressions.FirVariableAssignment
import org.jetbrains.kotlin.fir.expressions.resolvedArgumentMapping
import org.jetbrains.kotlin.fir.expressions.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.references.toResolvedVariableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
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
                checkBodyIsDeclarative(expression, allowShared = true)
            }

            E2eCallables.PARALLEL -> {
                checkLambdaLiteral(expression)
                // A shared declaration inside a group would have no scope of its own to belong to,
                // so a parallel body is narrower than a test body: blocks and nothing else.
                checkBodyIsDeclarative(expression, allowShared = false)
            }

            E2eCallables.SERVER, E2eCallables.CLIENT -> {
                checkLambdaLiteral(expression)
                checkCaptures(expression)
                checkNotInsideAForeignLambda(expression)
            }

            else -> Unit
        }

        // Not tied to any particular function: anything that annotates a parameter gets this.
        checkClientNamesAreLiterals(expression)
    }

    /**
     * Every argument to a `@MinecraftClientName` parameter must be a string literal.
     *
     * The plugin collects these names so the orchestrator can start exactly the clients a suite
     * mentions. A name computed at runtime could not be collected, and would address a client that
     * was never launched.
     */
    context(context: CheckerContext, reporter: DiagnosticReporter)
    private fun checkClientNamesAreLiterals(expression: FirFunctionCall) {
        expression.resolvedArgumentMapping?.forEach { (argument, parameter) ->
            if (!parameter.isClientName()) return@forEach
            if (argument !is FirLiteralExpression || argument.value !is String) {
                reporter.reportOn(argument.source, E2eDiagnostics.E2E_CLIENT_NAME_NOT_LITERAL)
            }
        }
    }

    /**
     * A test body is a plan, not code.
     *
     * The compiler reads the blocks out of it as an ordered list of steps and then throws the body
     * away, so a statement written here would simply never run. Rejecting it is far kinder than
     * silently dropping it, which is what the transform would otherwise do.
     */
    context(context: CheckerContext, reporter: DiagnosticReporter)
    private fun checkBodyIsDeclarative(expression: FirFunctionCall, allowShared: Boolean) {
        val body = (expression.argumentFor("body") as? FirAnonymousFunctionExpression)
            ?.anonymousFunction ?: return

        body.body?.statements?.forEach { statement ->
            val allowed = when (statement) {
                // `val x = shared<BlockPos>()`
                is FirProperty -> allowShared && statement.initializer.isSharedCall()
                // `server { }` / `client { }`, or a `parallel { }` group of them
                is FirFunctionCall ->
                    statement.toResolvedCallableSymbol()?.callableId
                        .let { it in E2eCallables.BLOCKS || (allowShared && it == E2eCallables.PARALLEL) }
                // The implicit return a lambda body ends with is not the author saying anything.
                is FirReturnExpression -> true
                else -> false
            }
            if (!allowed) {
                reporter.reportOn(statement.source, E2eDiagnostics.E2E_TEST_BODY_NOT_DECLARATIVE)
            }
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
                // A shared value is a handle: mentioning it is a plain expression, so it may be
                // captured anywhere. Whether reading it is legal here is a question about suspend
                // functions, which the compiler already answers better than this checker could.
                if (symbol.isSharedValue()) return
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

    private fun FirVariableSymbol<*>.isSharedValue(): Boolean =
        (fir as? FirProperty)?.initializer.isSharedCall()

}

/** Rejects `var x = shared<T>()`, which silently would not be wired to anything. */
object E2eSharedPlacementChecker : FirExpressionChecker<FirFunctionCall>(MppCheckerKind.Common) {

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirFunctionCall) {
        if (expression.toResolvedCallableSymbol()?.callableId != E2eCallables.SHARED) return

        // Legal only as the initialiser of a local: `val pos = shared<BlockPos>()`.
        val owner = context.containingElements.lastOrNull { it is FirProperty } as? FirProperty
        if (owner == null || owner.initializer !== expression) {
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
    val PARALLEL: CallableId = CallableId(PACKAGE, Name.identifier("parallel"))

    /** Calls whose lambda argument is itself a lifted block, and so may contain further blocks. */
    val BLOCK_BODY_OWNERS: Set<CallableId> = setOf(E2E, SERVER, CLIENT, PARALLEL)

    /** The two calls that are a block. */
    val BLOCKS: Set<CallableId> = setOf(SERVER, CLIENT)
}

/** Whether an expression is the `shared<T>()` call that declares a shared value. */
internal fun FirExpression?.isSharedCall(): Boolean =
    (this as? FirFunctionCall)?.toResolvedCallableSymbol()?.callableId == E2eCallables.SHARED

/** Whether a parameter is annotated `@MinecraftClientName`. */
internal fun FirValueParameter.isClientName(): Boolean =
    annotations.any {
        it.annotationTypeRef.coneTypeOrNull?.classId?.asSingleFqName()?.asString() ==
            "dev.vibeported.mc.e2e.MinecraftClientName"
    }

internal fun FirFunctionCall.argumentFor(parameterName: String): FirExpression? =
    resolvedArgumentMapping?.entries?.firstOrNull { it.value.name.asString() == parameterName }?.key
