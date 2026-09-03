@file:OptIn(UnsafeDuringIrConstructionAPI::class)

package dev.vibeported.rpc.plugin.ir

import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.name.FqName

/**
 * Whether a value can cross a wire, decided while the types are still in view.
 *
 * This is the half of the design that had to be a compile-time answer. The old framework looked a
 * codec up by class at run time, so an argument nothing could encode failed in the middle of a test
 * -- once, memorably, for returning a `java.io.File`. Deciding here means the same mistake is a
 * message under the cursor naming the type.
 */
internal object Serializers {

    private val SERIALIZABLE = FqName("kotlinx.serialization.Serializable")

    /** What kotlinx can encode without being told anything. */
    private val BUILT_IN = setOf(
        "kotlin.Boolean", "kotlin.Byte", "kotlin.Char", "kotlin.Short", "kotlin.Int",
        "kotlin.Long", "kotlin.Float", "kotlin.Double", "kotlin.String", "kotlin.Unit",
    )

    fun isUnit(type: IrType): Boolean = type.classOrNull?.owner?.kotlinFqName?.asString() == "kotlin.Unit"

    /**
     * Why this type cannot be sent, or null when it can.
     *
     * A refusal rather than a boolean, because the only useful thing to do with the answer is put it
     * in front of whoever wrote the type.
     */
    fun refuse(type: IrType): String? {
        val declaration = type.classOrNull?.owner
            ?: return "it has no concrete class, so nothing can be resolved for it"

        val name = declaration.kotlinFqName.asString()
        if (name in BUILT_IN) return null

        // A generic type erases to its class, and the serializer looked up from that class would
        // silently encode the wrong thing. Refusing is the honest answer until argument serializers
        // are resolved properly for them.
        val arguments = (type as? IrSimpleType)?.arguments.orEmpty()
        if (arguments.isNotEmpty()) {
            return "it has type arguments, and only the class survives to the lookup -- so a " +
                "serializer for it would encode the wrong thing. Wrap it in a @Serializable class."
        }

        if (declaration.hasAnnotation(SERIALIZABLE)) return null

        return "it is neither a primitive nor annotated @Serializable, so kotlinx has no serializer " +
            "for it. Annotate the class, or pass something that is already serializable."
    }
}
