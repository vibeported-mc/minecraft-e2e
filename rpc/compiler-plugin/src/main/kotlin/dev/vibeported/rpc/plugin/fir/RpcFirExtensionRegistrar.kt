package dev.vibeported.rpc.plugin.fir

import dev.vibeported.rpc.plugin.RoleIndex
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.expression.ExpressionCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirExpressionChecker
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar

internal class RpcFirExtensionRegistrar(
    private val roles: RoleIndex,
    private val contextual: Set<String>,
) : FirExtensionRegistrar() {
    override fun ExtensionRegistrarContext.configurePlugin() {
        // A lambda rather than a constructor reference, because the checker needs the index
        // as well as the session.
        +{ session: FirSession -> RpcCheckersExtension(session, roles, contextual) }
    }
}

internal class RpcCheckersExtension(
    session: FirSession,
    roles: RoleIndex,
    contextual: Set<String>,
) : FirAdditionalCheckersExtension(session) {

    override val expressionCheckers: ExpressionCheckers = object : ExpressionCheckers() {
        override val functionCallCheckers: Set<FirExpressionChecker<FirFunctionCall>> =
            setOf(RpcCallChecker(roles, contextual))
    }
}
