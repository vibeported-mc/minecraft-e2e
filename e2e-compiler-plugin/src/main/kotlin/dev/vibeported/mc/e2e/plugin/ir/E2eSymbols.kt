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

    private fun classOf(packageName: FqName, name: String): IrClassSymbol =
        pluginContext.referenceClass(ClassId(packageName, Name.identifier(name)))
            ?: error("e2e: $packageName.$name is not on the compile classpath; is e2e-core a dependency?")

    // Ids travel on the wire, so they live in the protocol module. The two are kept in separate
    // packages because a mod jar and a library jar cannot both export one package to the module
    // graph FancyModLoader builds.
    val blockId: IrClassSymbol = classOf(PROTOCOL, "BlockId")
    val sharedId: IrClassSymbol = classOf(PROTOCOL, "SharedId")
    val nodeId: IrClassSymbol = classOf(PROTOCOL, "NodeId")
    val nodeRole: IrClassSymbol = classOf(PROTOCOL, "NodeRole")

    val blockScope: IrClassSymbol = classOf(PACKAGE, "BlockScope")
    val e2eBlockScope: IrClassSymbol = classOf(PACKAGE, "E2eBlockScope")
    val blockTable: IrClassSymbol = classOf(PACKAGE, "E2eBlockTable")
    val noSuchBlock: IrClassSymbol = classOf(PACKAGE, "NoSuchBlockException")

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
            // Both overloads take three parameters once the dispatch receiver is counted, so arity
            // cannot tell them apart. Only the plugin-facing one takes an id.
            fn.owner.parameters.any { it.name.asString() == "id" }
        }

    fun nodeRoleEntry(name: String): IrEnumEntrySymbol =
        nodeRole.owner.declarations.filterIsInstance<IrEnumEntry>()
            .single { it.name.asString() == name }
            .symbol

    private fun IrClassSymbol.member(name: String): IrSimpleFunctionSymbol =
        functions.single { it.owner.name.asString() == name }

    private companion object {
        val PACKAGE = FqName("dev.vibeported.mc.e2e")
        val PROTOCOL = FqName("dev.vibeported.mc.e2e.protocol")
    }
}
