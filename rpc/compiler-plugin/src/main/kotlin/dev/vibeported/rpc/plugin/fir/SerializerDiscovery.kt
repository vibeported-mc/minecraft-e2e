package dev.vibeported.rpc.plugin.fir

import dev.vibeported.rpc.plugin.SerializerIndex
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.getAnnotationByClassId
import org.jetbrains.kotlin.fir.expressions.FirGetClassCall
import org.jetbrains.kotlin.fir.extensions.predicate.LookupPredicate
import org.jetbrains.kotlin.fir.extensions.predicateBasedProvider
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/**
 * Finds the `@RpcSerializer` objects written in this compilation.
 *
 * The classpath half of the answer is read once, when the plugin is registered; this is the other
 * half, and it is what lets a module declare a serializer and use the type it covers in the same
 * breath. Asked lazily rather than collected up front, because a checker cannot know it runs after
 * every declaration has been seen -- the predicate provider has already indexed them by then.
 */
internal object SerializerDiscovery {

    val ANNOTATION: FqName = FqName("dev.vibeported.rpc.RpcSerializer")

    private val ANNOTATION_ID = ClassId.topLevel(ANNOTATION)
    private val FOR_TYPE = Name.identifier("forType")

    /** The predicate a FIR extension has to register before anything can be looked up by it. */
    val PREDICATE: LookupPredicate = LookupPredicate.create { annotated(ANNOTATION) }

    /**
     * Adds this compilation's own serializers to [index], once.
     *
     * Idempotent: every call site asks, and the answer cannot change within a compilation.
     */
    fun contribute(session: FirSession, index: SerializerIndex) {
        if (!index.markDiscovered()) return

        session.predicateBasedProvider.getSymbolsByPredicate(PREDICATE).forEach { symbol ->
            val declaration = symbol as? FirClassSymbol<*> ?: return@forEach
            val annotation = declaration.getAnnotationByClassId(ANNOTATION_ID, session) ?: return@forEach

            // `@RpcSerializer(BlockPos::class)` -- a class literal, so the argument is a `::class`
            // call whose resolved type is the class itself.
            val argument = annotation.argumentMapping.mapping[FOR_TYPE] as? FirGetClassCall ?: return@forEach
            val type = argument.argument.resolvedType.classId?.asFqNameString() ?: return@forEach

            index.declare(type = type, serializer = declaration.classId.asFqNameString())
        }
    }
}
