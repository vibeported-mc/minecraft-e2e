@file:OptIn(UnsafeDuringIrConstructionAPI::class)

package dev.vibeported.mc.e2e.plugin.ir

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrEnumEntry
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.IrEnumEntrySymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/**
 * Everything from `:core` the rewritten code calls.
 *
 * Resolved once per module. A missing symbol here means the module was compiled without `:core`
 * on its classpath, which is worth failing loudly for rather than silently skipping the transform.
 */
internal class RuntimeSymbols(private val pluginContext: IrPluginContext) {

    private fun classOf(packageName: FqName, name: String): IrClassSymbol =
        pluginContext.referenceClass(ClassId(packageName, Name.identifier(name)))
            ?: error("e2e: " + packageName + "." + name + " is not on the compile classpath; is core a dependency?")

    val blockId: IrClassSymbol = classOf(PROTOCOL, "ProcedureId")
    val nodeId: IrClassSymbol = classOf(PROTOCOL, "NodeId")
    val nodeRole: IrClassSymbol = classOf(PROTOCOL, "NodeRole")

    val serverScope: IrClassSymbol = classOf(PACKAGE, "ServerScope")
    val clientScope: IrClassSymbol = classOf(PACKAGE, "ClientScope")
    val blockTable: IrClassSymbol = classOf(PACKAGE, "ProcedureTable")
    val noSuchBlock: IrClassSymbol = classOf(PACKAGE, "NoSuchProcedureException")
    val valueCodec: IrClassSymbol = classOf(RPC, "ValueCodec")
    val jsonElement: IrClassSymbol = classOf(JSON, "JsonElement")

    val blockIdConstructor: IrConstructorSymbol = blockId.constructors.single()

    /** `NodeId(role, name)`. */
    val nodeIdConstructor: IrConstructorSymbol = nodeId.constructors.single { it.owner.parameters.size == 2 }

    /** `NoSuchProcedureException(id, table)`. */
    val noSuchBlockConstructor: IrConstructorSymbol = noSuchBlock.constructors.single()

    val encode: IrSimpleFunctionSymbol = valueCodec.member("encode")
    val decode: IrSimpleFunctionSymbol = valueCodec.member("decode")

    /** The one runtime entry point every rewritten call goes through. */
    val invokeProcedure: IrSimpleFunctionSymbol =
        pluginContext.referenceFunctions(CallableId(PACKAGE, Name.identifier("invokeProcedure"))).single()

    /** `listOf(vararg elements)`, which is how the plugin builds the lists it passes. */
    val listOf: IrSimpleFunctionSymbol =
        pluginContext.referenceFunctions(CallableId(COLLECTIONS, Name.identifier("listOf")))
            .single { it.owner.parameters.size == 1 && it.owner.parameters[0].varargElementType != null }

    /** `List.get(index)`, for unpacking the arguments a table is handed. */
    val listGet: IrSimpleFunctionSymbol =
        pluginContext.irBuiltIns.listClass.functions.single { it.owner.name.asString() == "get" }

    fun nodeRoleEntry(name: String): IrEnumEntrySymbol =
        nodeRole.owner.declarations.filterIsInstance<IrEnumEntry>()
            .single { it.name.asString() == name }
            .symbol

    private fun IrClassSymbol.member(name: String): IrSimpleFunctionSymbol =
        functions.single { it.owner.name.asString() == name }

    private companion object {
        val PACKAGE = FqName("dev.vibeported.mc.e2e")
        val PROTOCOL = FqName("dev.vibeported.mc.e2e.protocol")
        val RPC = FqName("dev.vibeported.mc.e2e.rpc")
        val JSON = FqName("kotlinx.serialization.json")
        val COLLECTIONS = FqName("kotlin.collections")
    }
}
