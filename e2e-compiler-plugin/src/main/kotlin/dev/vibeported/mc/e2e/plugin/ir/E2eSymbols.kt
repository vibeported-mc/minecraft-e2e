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
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name

/**
 * Everything from `e2e-api` the rewritten code calls.
 *
 * Resolved once per module. A missing symbol here means the module was compiled without `e2e-api`
 * on its classpath, which is worth failing loudly for rather than silently skipping the transform.
 */
internal class E2eSymbols(private val pluginContext: IrPluginContext) {

    private fun classOf(name: String): IrClassSymbol =
        pluginContext.referenceClass(ClassId(PACKAGE, Name.identifier(name)))
            ?: error("e2e: $PACKAGE.$name is not on the compile classpath; is e2e-api a dependency?")

    val blockId: IrClassSymbol = classOf("BlockId")
    val sharedId: IrClassSymbol = classOf("SharedId")
    val nodeId: IrClassSymbol = classOf("NodeId")
    val nodeRole: IrClassSymbol = classOf("NodeRole")
    val blockScope: IrClassSymbol = classOf("BlockScope")
    val e2eBlockScope: IrClassSymbol = classOf("E2eBlockScope")
    val blockTable: IrClassSymbol = classOf("E2eBlockTable")
    val noSuchBlock: IrClassSymbol = classOf("NoSuchBlockException")

    val blockIdConstructor: IrConstructorSymbol = blockId.constructors.single()
    val sharedIdConstructor: IrConstructorSymbol = sharedId.constructors.single()

    /** `NodeId(role, index)`. */
    val nodeIdConstructor: IrConstructorSymbol = nodeId.constructors.single { it.owner.parameters.size == 2 }

    /** `NoSuchBlockException(id, table)`. */
    val noSuchBlockConstructor: IrConstructorSymbol = noSuchBlock.constructors.single()

    val dispatch: IrSimpleFunctionSymbol = e2eBlockScope.member("dispatch")
    val sharedGet: IrSimpleFunctionSymbol = e2eBlockScope.member("sharedGet")
    val sharedSet: IrSimpleFunctionSymbol = e2eBlockScope.member("sharedSet")

    /** The plugin-facing overloads: `suite(name, id, body)` and `e2e(name, id, driver)`. */
    val suiteWithId: IrSimpleFunctionSymbol =
        pluginContext.referenceFunctions(CallableId(PACKAGE, Name.identifier("suite")))
            .single { it.owner.parameters.size == 3 }

    val e2eWithId: IrSimpleFunctionSymbol =
        pluginContext.referenceFunctions(
            CallableId(ClassId(PACKAGE, Name.identifier("SuiteBuilder")), Name.identifier("e2e"))
        ).single { fn ->
            // Members carry a dispatch receiver, so the three declared arguments make four.
            fn.owner.parameters.size == 4
        }

    fun nodeRoleEntry(name: String): IrEnumEntrySymbol =
        nodeRole.owner.declarations.filterIsInstance<IrEnumEntry>()
            .single { it.name.asString() == name }
            .symbol

    private fun IrClassSymbol.member(name: String): IrSimpleFunctionSymbol =
        functions.single { it.owner.name.asString() == name }

    private companion object {
        val PACKAGE = FqName("dev.vibeported.mc.e2e")
    }
}
