@file:OptIn(UnsafeDuringIrConstructionAPI::class)

package dev.vibeported.rpc.plugin.ir

import dev.vibeported.rpc.plugin.SerializerIndex
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationContainer
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.expressions.IrClassReference
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.name.FqName

/**
 * Finds the `@RpcSerializer` objects written in this compilation, again -- from the backend.
 *
 * The frontend already looks for them, but only when it is asked: a checker runs on calls, so a
 * module that declares a serializer and makes no call of its own would never look, and would publish
 * nothing for the modules downstream. Here there is no such condition. Every declaration in the
 * module is in front of us, and what this pass records is exactly what gets written to the manifest.
 *
 * The two passes agree by construction -- both read the same annotation into the same index, and
 * [SerializerIndex.declare] is idempotent -- so which of them saw a serializer first does not matter.
 */
internal object SerializerScan {

    private val ANNOTATION = FqName("dev.vibeported.rpc.RpcSerializer")

    fun contribute(module: IrModuleFragment, index: SerializerIndex) {
        module.files.forEach { file -> scan(file, index) }
    }

    private fun scan(container: IrDeclarationContainer, index: SerializerIndex) {
        container.declarations.filterIsInstance<IrClass>().forEach { declaration ->
            declare(declaration, index)
            // Nested, because an object serializing a type reads well as a member of something --
            // a `Serializers` holder, or the class it belongs to.
            scan(declaration, index)
        }
    }

    private fun declare(declaration: IrClass, index: SerializerIndex) {
        val annotation = declaration.annotations
            .firstOrNull { it.type.classFqName == ANNOTATION }
            ?: return

        // `@RpcSerializer(BlockPos::class)`: a class literal, which survives into IR as a reference
        // carrying the type it names.
        val forType = annotation.arguments.firstOrNull() as? IrClassReference ?: return
        val type = forType.classType.classFqName ?: return

        index.declare(type = type.asString(), serializer = declaration.kotlinFqName.asString())
    }
}
