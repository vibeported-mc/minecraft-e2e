package dev.vibeported.rpc.plugin.fir

import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirExpressionChecker
import org.jetbrains.kotlin.fir.expressions.FirAnonymousFunctionExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall

/**
 * Records where each call's role came from, so a test can assert it.
 *
 * Temporary. Once bodies are lifted the role appears in the generated manifest and can be asserted
 * there, at which point this and its test go away. It survives for now because the fact it pins --
 * that a SOURCE-retained annotation on a lambda reaches the frontend at all -- is one a Kotlin
 * upgrade could quietly take back.
 */
internal object RoleProbe : FirExpressionChecker<FirFunctionCall>(MppCheckerKind.Common) {

    object Seen {
        val calls: MutableList<String> = mutableListOf()
        fun reset() { calls.clear() }
    }

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirFunctionCall) {
        val callee = EntryPoints.calleeOf(expression) ?: return
        val body = EntryPoints.bodyArgument(expression, callee) as? FirAnonymousFunctionExpression

        val onExpression = body?.annotations?.let { EntryPoints.roleIn(it) }
        val onFunction = body?.anonymousFunction?.annotations?.let { EntryPoints.roleIn(it) }
        val onFile = context.containingFileSymbol?.annotations?.let { EntryPoints.roleIn(it) }

        Seen.calls += buildString {
            append(callee.name.asString())
            append(" expr=").append(onExpression ?: "-")
            append(" fun=").append(onFunction ?: "-")
            append(" file=").append(onFile ?: "-")
        }
    }
}
