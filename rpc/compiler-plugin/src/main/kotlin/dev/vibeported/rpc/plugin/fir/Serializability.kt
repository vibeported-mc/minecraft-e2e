package dev.vibeported.rpc.plugin.fir

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.resolve.toRegularClassSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.renderReadable
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName

/**
 * Whether a value can cross a wire, decided while the types are still under the cursor.
 *
 * This is the half of the design that had to be a compile-time answer. The old framework looked a
 * codec up by class at run time, so an argument nothing could encode failed in the middle of a test
 * -- once, memorably, for returning a `java.io.File`. Asking here means the same mistake is a
 * message on the type that caused it, in the editor, before anything is run.
 */
internal object Serializability {

    private val SERIALIZABLE = ClassId.topLevel(FqName("kotlinx.serialization.Serializable"))

    /** What kotlinx can encode without being told anything. */
    private val BUILT_IN = setOf(
        "kotlin.Boolean", "kotlin.Byte", "kotlin.Char", "kotlin.Short", "kotlin.Int",
        "kotlin.Long", "kotlin.Float", "kotlin.Double", "kotlin.String", "kotlin.Unit",
    )

    /** How the type should read in a message: `List<Int>`, not its internal rendering. */
    fun render(type: ConeKotlinType): String = type.renderReadable()

    /**
     * Why this type cannot be sent, or null when it can.
     *
     * A refusal rather than a boolean, because the only useful thing to do with the answer is put
     * it in front of whoever wrote the type.
     */
    fun refuse(type: ConeKotlinType, session: FirSession): String? {
        val name = type.classId?.asSingleFqName()?.asString()
            ?: return "it has no concrete class, so nothing can be resolved for it"

        if (name in BUILT_IN) return null

        // A generic type erases to its class, and the serializer looked up from that class would
        // silently encode the wrong thing. Refusing is the honest answer until argument serializers
        // are resolved properly for them.
        if (type.typeArguments.isNotEmpty()) {
            return "it has type arguments, and only the class survives to the lookup -- so a " +
                "serializer for it would encode the wrong thing. Wrap it in a @Serializable class"
        }

        val declaration = type.toRegularClassSymbol(session)
            ?: return "its declaration cannot be resolved here"
        if (declaration.hasAnnotation(SERIALIZABLE, session)) return null

        return "it is neither a primitive nor annotated @Serializable, so kotlinx has no " +
            "serializer for it. Annotate the class, or pass something that already is serializable"
    }
}
