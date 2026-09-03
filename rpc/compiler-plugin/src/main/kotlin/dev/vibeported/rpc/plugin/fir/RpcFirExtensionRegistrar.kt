package dev.vibeported.rpc.plugin.fir

import dev.vibeported.rpc.plugin.RoleIndex
import dev.vibeported.rpc.plugin.SerializerIndex
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.expression.ExpressionCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirExpressionChecker
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar

internal class RpcFirExtensionRegistrar(
    private val roles: RoleIndex,
    private val serializers: SerializerIndex,
) : FirExtensionRegistrar() {
    override fun ExtensionRegistrarContext.configurePlugin() {
        // A lambda rather than a constructor reference, because the checker needs the indexes
        // as well as the session.
        +{ session: FirSession -> RpcCheckersExtension(session, roles, serializers) }
    }
}

internal class RpcCheckersExtension(
    session: FirSession,
    roles: RoleIndex,
    private val serializers: SerializerIndex,
) : FirAdditionalCheckersExtension(session) {

    /**
     * Without this, looking `@RpcSerializer` objects up finds nothing at all.
     *
     * The predicate provider indexes only what a plugin has declared an interest in, so a lookup
     * for an unregistered predicate is not an error -- it is an empty list, which reads exactly
     * like a module that declared no serializers.
     */
    override fun FirDeclarationPredicateRegistrar.registerPredicates() {
        register(SerializerDiscovery.PREDICATE)
    }

    override val expressionCheckers: ExpressionCheckers = object : ExpressionCheckers() {
        override val functionCallCheckers: Set<FirExpressionChecker<FirFunctionCall>> =
            setOf(RpcCallChecker(roles, serializers))
    }
}
