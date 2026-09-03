@file:OptIn(UnsafeDuringIrConstructionAPI::class)

package dev.vibeported.rpc.plugin.ir

import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.util.kotlinFqName

/**
 * What the backend needs to know about a type it is about to encode.
 *
 * Only the one question, because whether a type *can* be encoded is settled in the frontend, where
 * the answer can be put under the cursor. @see dev.vibeported.rpc.plugin.fir.Serializability
 */
internal object Serializers {

    /** A body giving back nothing still has to answer the wire, and answers it with null. */
    fun isUnit(type: IrType): Boolean =
        type.classOrNull?.owner?.kotlinFqName?.asString() == "kotlin.Unit"
}
