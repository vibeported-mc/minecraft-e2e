@file:OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)

package dev.vibeported.rpc.plugin.ir

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/**
 * Everything from `:rpc:core` that generated code refers to.
 *
 * Resolved once per module. A missing symbol means the module was compiled without core on its
 * classpath, which is worth failing loudly for rather than quietly skipping the transform and
 * leaving a build that produces tables nothing can load.
 */
internal class RpcSymbols(private val context: IrPluginContext) {

    private fun classOf(packageName: FqName, name: String): IrClassSymbol =
        context.referenceClass(ClassId(packageName, Name.identifier(name)))
            ?: error("rpc: $packageName.$name is not on the compile classpath; is :rpc:core a dependency?")

    val procedureTable: IrClassSymbol = classOf(CORE, "ProcedureTable")
    val noSuchProcedure: IrClassSymbol = classOf(CORE, "NoSuchProcedureException")
    val services: IrClassSymbol = classOf(CORE, "Services")
    val wireFormat: IrClassSymbol = classOf(CORE, "WireFormat")

    val noSuchProcedureConstructor: IrConstructorSymbol = noSuchProcedure.constructors.single()

    /** `Services.resolve(KClass<T>): T`, which is how a body finds its receiver on the node. */
    val resolve: IrSimpleFunctionSymbol = services.functions.single { function ->
        function.owner.name.asString() == "resolve" &&
            function.owner.parameters.count { it.name.asString() != "<this>" } == 1
    }

    val setOf: IrSimpleFunctionSymbol = context
        .referenceFunctions(CallableId(FqName("kotlin.collections"), Name.identifier("setOf")))
        .single { it.owner.parameters.size == 1 && it.owner.parameters.single().varargElementType != null }

    val listGet: IrSimpleFunctionSymbol = context.irBuiltIns.listClass.functions
        .single { it.owner.name.asString() == "get" }

    val kSerializer: IrClassSymbol = classOf(FqName("kotlinx.serialization"), "KSerializer")

    /**
     * `serializer(KClass, List<KSerializer<*>>, Boolean): KSerializer<Any?>`.
     *
     * A lookup by class rather than a statically bound serializer. Whether one *exists* is settled
     * at compile time, which is the guarantee that matters; how it is obtained at run time is an
     * implementation detail, and this needs no per-type code emitted for it.
     */
    val serializerOf: IrSimpleFunctionSymbol = context
        .referenceFunctions(CallableId(FqName("kotlinx.serialization"), Name.identifier("serializer")))
        .single { it.owner.parameters.size == 3 }

    val emptyList: IrSimpleFunctionSymbol = context
        .referenceFunctions(CallableId(FqName("kotlin.collections"), Name.identifier("emptyList")))
        .single()

    val listOf: IrSimpleFunctionSymbol = context
        .referenceFunctions(CallableId(FqName("kotlin.collections"), Name.identifier("listOf")))
        .single { it.owner.parameters.size == 1 && it.owner.parameters.single().varargElementType != null }

    val encode: IrSimpleFunctionSymbol = wireFormat.functions.single { it.owner.name.asString() == "encode" }
    val decode: IrSimpleFunctionSymbol = wireFormat.functions.single { it.owner.name.asString() == "decode" }

    /** Called by the halves that are scaffolding until serializer resolution lands. */
    val notGenerated: IrSimpleFunctionSymbol = context
        .referenceFunctions(CallableId(CORE, Name.identifier("serializationNotGenerated")))
        .single()

    private companion object {
        private val CORE = FqName("dev.vibeported.rpc")
    }
}
