package dev.vibeported.rpc.plugin.fir

import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirExpressionChecker
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.FirAnonymousFunctionExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.references.toResolvedNamedFunctionSymbol
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName

/**
 * Finds out what the frontend can actually see, before anything is built on the answer.
 *
 * The open question is whether `@RpcRole` survives to FIR when written on a trailing lambda. It has
 * to be SOURCE-retained -- Kotlin allows no other retention on an expression target -- so it is
 * certainly gone by the time IR runs, and if it never reaches FIR either then the annotation form
 * cannot work at all and the role has to be an ordinary argument instead.
 *
 * Records rather than reports, because a probe wants an answer, not a diagnostic. The compiler runs
 * in this very process under kctfork, so a list is the shortest path to one.
 */
internal object RoleProbe : FirExpressionChecker<FirFunctionCall>(MppCheckerKind.Common) {

    /** What the probe saw, for a test to read. */
    object Seen {
        val calls: MutableList<String> = mutableListOf()
        fun reset() { calls.clear() }
    }

    private val ENTRY_POINT = ClassId.topLevel(FqName("dev.vibeported.rpc.RpcEntryPoint"))
    private val ROLE = ClassId.topLevel(FqName("dev.vibeported.rpc.RpcRole"))

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirFunctionCall) {
        val callee = expression.calleeReference.toResolvedNamedFunctionSymbol() ?: return
        val isEntryPoint = callee.annotations.any { it.classId() == ENTRY_POINT }

        if (!isEntryPoint) return

        val lambda = expression.arguments.filterIsInstance<FirAnonymousFunctionExpression>().lastOrNull()

        // Both places the annotation might have landed, so the answer says which -- or that it is
        // nowhere, which is just as useful to know now.
        val onExpression = lambda?.annotations?.roleValue()
        val onFunction = lambda?.anonymousFunction?.annotations?.roleValue()
        val onEnclosingFile = context.containingFileSymbol?.annotations?.roleValue()

        Seen.calls += buildString {
            append(callee.name.asString())
            append(" expr=").append(onExpression ?: "-")
            append(" fun=").append(onFunction ?: "-")
            append(" file=").append(onEnclosingFile ?: "-")
        }
    }

    private fun List<FirAnnotation>.roleValue(): String? {
        val annotation = firstOrNull { it.classId() == ROLE } ?: return null
        val argument = annotation.argumentMapping.mapping.values.firstOrNull()
        return (argument as? FirLiteralExpression)?.value as? String
    }

    private fun FirAnnotation.classId(): ClassId? = annotationTypeRef.coneType.classId
}
