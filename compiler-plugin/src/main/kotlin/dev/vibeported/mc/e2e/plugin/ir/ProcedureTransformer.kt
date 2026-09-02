@file:OptIn(UnsafeDuringIrConstructionAPI::class)

package dev.vibeported.mc.e2e.plugin.ir

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.declarations.addConstructor
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.declarations.buildReceiverParameter
import org.jetbrains.kotlin.ir.builders.irAs
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irCallConstructor
import org.jetbrains.kotlin.ir.builders.irDelegatingConstructorCall
import org.jetbrains.kotlin.ir.builders.irEquals
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irIfThen
import org.jetbrains.kotlin.ir.builders.irInt
import org.jetbrains.kotlin.ir.builders.irNull
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrClassReferenceImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrInstanceInitializerCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrThrowImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrVarargImpl
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.makeNullable
import org.jetbrains.kotlin.ir.types.starProjectedType
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.patchDeclarationParents
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.Name

internal object PluginKey : org.jetbrains.kotlin.GeneratedDeclarationKey()

/** Marks everything this plugin emits, so it is obvious in an IR dump who put it there. */
internal val PluginOrigin: IrDeclarationOrigin = IrDeclarationOrigin.GeneratedByPlugin(PluginKey)

/**
 * Turns the planned blocks into code.
 *
 * The whole transform rests on one move: a block body is not copied into the generated table, the
 * frontend function that already backs the lambda is re-parented into it. Every symbol inside the
 * body keeps pointing at the same declarations, so nothing has to be remapped, and the lambda stops
 * being a closure simply because it is no longer nested in one.
 *
 * One table per role, not one per file. A dedicated server is dist-cleaned, so client classes are
 * not on its classpath; a table holding both roles would be a class it could not verify.
 */
internal class ProcedureTransformer(
    private val context: IrPluginContext,
    private val symbols: RuntimeSymbols,
) {
    fun transform(plan: FilePlan) {
        ProcedureRole.entries.forEach { role ->
            val blocks = plan.blocks(role)
            if (blocks.isEmpty()) return@forEach

            val table = buildTableObject(plan, role)
            val methods = LinkedHashMap<String, IrSimpleFunction>()

            blocks.forEachIndexed { index, block ->
                methods[block.id] = liftIntoTable(table, block, index)
            }
            buildInvoke(table, plan, role, blocks, methods)
            buildDecodeArgs(table, blocks)
            buildEncodeResult(table, blocks)
            plan.file.declarations += table
        }

        // Bodies are rewritten after every table exists, so a block nested inside another one finds
        // its own lifted method already in place.
        plan.blocks().forEach { rewriteBody(it) }
        rewriteCallSites(plan)
    }

    // -- the table objects --------------------------------------------------------------------

    private fun buildTableObject(plan: FilePlan, role: ProcedureRole): IrClass {
        val table = context.irFactory.buildClass {
            name = Name.identifier(
                when (role) {
                    ProcedureRole.SERVER -> plan.serverTableSimpleName
                    ProcedureRole.CLIENT -> plan.clientTableSimpleName
                }
            )
            kind = ClassKind.OBJECT
            visibility = DescriptorVisibilities.PUBLIC
            modality = Modality.FINAL
            origin = PluginOrigin
        }
        table.parent = plan.file
        table.superTypes = listOf(symbols.blockTable.defaultType)
        // IrClass.defaultType reads thisReceiver, so it cannot be what builds thisReceiver.
        // Going through the symbol sidesteps that.
        table.thisReceiver = table.buildReceiverParameter { type = table.symbol.typeWith() }

        table.addConstructor {
            isPrimary = true
            visibility = DescriptorVisibilities.PRIVATE
            returnType = table.symbol.typeWith()
            origin = PluginOrigin
        }.apply {
            body = DeclarationIrBuilder(context, symbol).irBlockBody {
                +irDelegatingConstructorCall(context.irBuiltIns.anyClass.owner.constructors.single())
                +IrInstanceInitializerCallImpl(
                    startOffset, endOffset, table.symbol, context.irBuiltIns.unitType,
                )
            }
        }
        return table
    }

    /**
     * Re-parents the lambda function onto [table] as a private method.
     *
     * The lambda receiver becomes an ordinary `scope` argument and its declared parameters stay
     * exactly where they were. Every [IrValueParameter] object is reused rather than replaced, so
     * every read of one already in the body stays valid.
     */
    private fun liftIntoTable(table: IrClass, block: ProcedurePlan, index: Int): IrSimpleFunction {
        val function = block.lambda
        val scope = function.parameters.single { it.kind == IrParameterKind.ExtensionReceiver }
        scope.kind = IrParameterKind.Regular
        scope.name = Name.identifier("scope")

        function.name = Name.identifier(methodName(index, block))
        function.visibility = DescriptorVisibilities.PRIVATE
        function.origin = PluginOrigin
        function.parent = table

        val dispatchReceiver = function.buildReceiverParameter {
            type = table.symbol.typeWith()
            name = Name.special("<this>")
        }
        dispatchReceiver.kind = IrParameterKind.DispatchReceiver
        function.parameters = listOf(dispatchReceiver) + function.parameters

        table.declarations += function
        function.patchDeclarationParents(table)
        return function
    }

    /** `invoke(id, scope, args)`: pick the method, cast the scope, unpack the arguments. */
    private fun buildInvoke(
        table: IrClass,
        plan: FilePlan,
        role: ProcedureRole,
        blocks: List<ProcedurePlan>,
        methods: Map<String, IrSimpleFunction>,
    ) {
        val invoke = table.addOverride("invoke", context.irBuiltIns.anyNType, isSuspend = true)
        val id = invoke.addValueParameter("id", context.irBuiltIns.stringType)
        val scope = invoke.addValueParameter("scope", context.irBuiltIns.anyType)
        val args = invoke.addValueParameter("args", listOfType(context.irBuiltIns.anyNType))

        val scopeType = when (role) {
            ProcedureRole.SERVER -> symbols.serverScope.defaultType
            ProcedureRole.CLIENT -> symbols.clientScope.defaultType
        }

        invoke.body = DeclarationIrBuilder(context, invoke.symbol).irBlockBody {
            // A short linear scan: dispatch happens once per block, and an if-chain keeps the
            // generated code readable in a decompiler, which matters when debugging a lifted body.
            blocks.forEach { block ->
                val method = methods.getValue(block.id)
                +irIfThen(
                    context.irBuiltIns.unitType,
                    irEquals(irGet(id), irString(block.id)),
                    irReturn(
                        irCall(method.symbol).apply {
                            arguments[0] = irGet(invoke.parameters[0])
                            arguments[1] = irAs(irGet(scope), scopeType)
                            block.argumentTypes.forEachIndexed { index, type ->
                                arguments[index + 2] = irAs(elementAt(this@irBlockBody, args, index), type)
                            }
                        }
                    ),
                )
            }
            +noSuchBlock(this, plan, role, id)
        }
    }

    /**
     * `decodeArgs(id, args, codec)`: turns what arrived over the wire back into objects.
     *
     * Generated rather than done by the node because only this end knows what each parameter was
     * declared as; the node has a list of encoded values and no idea what any of them mean.
     */
    private fun buildDecodeArgs(table: IrClass, blocks: List<ProcedurePlan>) {
        val decode = table.addOverride("decodeArgs", listOfType(context.irBuiltIns.anyNType))
        val id = decode.addValueParameter("id", context.irBuiltIns.stringType)
        val args = decode.addValueParameter("args", listOfType(symbols.jsonElement.defaultType))
        val codec = decode.addValueParameter("codec", symbols.valueCodec.defaultType)

        decode.body = DeclarationIrBuilder(context, decode.symbol).irBlockBody {
            blocks.forEach { block ->
                +irIfThen(
                    context.irBuiltIns.unitType,
                    irEquals(irGet(id), irString(block.id)),
                    irReturn(
                        listOf(
                            this@irBlockBody,
                            context.irBuiltIns.anyNType,
                            block.argumentTypes.mapIndexed { index, type ->
                                irCall(symbols.decode).apply {
                                    arguments[0] = irGet(codec)
                                    arguments[1] = classReference(this@irBlockBody, type)
                                    arguments[2] = elementAt(this@irBlockBody, args, index)
                                }
                            },
                        )
                    ),
                )
            }
            +irReturn(listOf(this, context.irBuiltIns.anyNType, emptyList()))
        }
    }

    /** `encodeResult(id, value, codec)`: the same trick for what the block gave back. */
    private fun buildEncodeResult(table: IrClass, blocks: List<ProcedurePlan>) {
        val encode = table.addOverride("encodeResult", symbols.jsonElement.defaultType.makeNullable())
        val id = encode.addValueParameter("id", context.irBuiltIns.stringType)
        val value = encode.addValueParameter("value", context.irBuiltIns.anyNType)
        val codec = encode.addValueParameter("codec", symbols.valueCodec.defaultType)

        encode.body = DeclarationIrBuilder(context, encode.symbol).irBlockBody {
            blocks.forEach { block ->
                // A block that returns nothing has nothing to encode, and saying so here keeps the
                // Unit instance off the wire entirely.
                if (block.resultType == context.irBuiltIns.unitType) return@forEach
                +irIfThen(
                    context.irBuiltIns.unitType,
                    irEquals(irGet(id), irString(block.id)),
                    irReturn(
                        irCall(symbols.encode).apply {
                            arguments[0] = irGet(codec)
                            arguments[1] = classReference(this@irBlockBody, block.resultType)
                            arguments[2] = irGet(value)
                        }
                    ),
                )
            }
            +irReturn(irNull())
        }
    }

    // -- rewriting -----------------------------------------------------------------------------

    /** Every `server`/`client` call inside a lifted body becomes a dispatch of its own. */
    private fun rewriteBody(block: ProcedurePlan) {
        val method = block.lambda
        method.body?.transformChildrenVoid(object : IrElementTransformerVoid() {
            override fun visitCall(expression: IrCall): IrExpression {
                val child = block.children.firstOrNull { it.call === expression }
                    ?: return super.visitCall(expression)
                val builder = DeclarationIrBuilder(
                    context, method.symbol, expression.startOffset, expression.endOffset,
                )
                return invokeProcedureCall(builder, child)
            }
        })
    }

    /** And so does every one written in ordinary code, which is where a test starts. */
    private fun rewriteCallSites(plan: FilePlan) {
        val roots = plan.roots.associateBy { it.call }
        plan.file.transformChildrenVoid(object : IrElementTransformerVoid() {
            override fun visitCall(expression: IrCall): IrExpression {
                val block = roots[expression] ?: return super.visitCall(expression)
                val builder = DeclarationIrBuilder(
                    context, plan.file.symbol, expression.startOffset, expression.endOffset,
                )
                return invokeProcedureCall(builder, block)
            }
        })
    }

    /**
     * `invokeProcedure(id, target, args, argTypes, resultType)`.
     *
     * The arguments go across as the objects they are; the runtime decides whether this node is
     * already the target and can simply call the body, or whether they have to be encoded first.
     */
    private fun invokeProcedureCall(builder: IrBuilderWithScope, block: ProcedurePlan): IrExpression =
        with(builder) {
            val values = block.call.procedureArguments(block.role)
            irCall(symbols.invokeProcedure).apply {
                typeArguments[0] = block.resultType
                arguments[0] = irString(block.id)
                arguments[1] = nodeId(builder, block)
                arguments[2] = listOf(builder, context.irBuiltIns.anyNType, values)
                arguments[3] = listOf(
                    builder,
                    context.irBuiltIns.kClassClass.starProjectedType,
                    block.argumentTypes.map { classReference(builder, it) },
                )
                arguments[4] = classReference(builder, block.resultType)
            }
        }

    // -- small constructors --------------------------------------------------------------------

    private fun IrClass.addOverride(name: String, returnType: IrType, isSuspend: Boolean = false): IrSimpleFunction {
        val function = addFunction {
            this.name = Name.identifier(name)
            this.returnType = returnType
            this.modality = Modality.FINAL
            this.visibility = DescriptorVisibilities.PUBLIC
            this.isSuspend = isSuspend
            this.origin = PluginOrigin
        }
        val dispatchReceiver = function.buildReceiverParameter {
            type = this@addOverride.symbol.typeWith()
            this.name = Name.special("<this>")
        }
        dispatchReceiver.kind = IrParameterKind.DispatchReceiver
        function.parameters = listOf(dispatchReceiver)
        function.overriddenSymbols =
            listOf(symbols.blockTable.functions.single { it.owner.name.asString() == name })
        return function
    }

    private fun noSuchBlock(
        builder: IrBuilderWithScope,
        plan: FilePlan,
        role: ProcedureRole,
        id: IrValueParameter,
    ): IrExpression = with(builder) {
        IrThrowImpl(
            startOffset,
            endOffset,
            context.irBuiltIns.nothingType,
            irCallConstructor(symbols.noSuchBlockConstructor, emptyList()).apply {
                arguments[0] = blockId(builder, irGet(id))
                arguments[1] = irString(plan.tableClass(role))
            },
        )
    }

    private fun listOf(
        builder: IrBuilderWithScope,
        elementType: IrType,
        elements: List<IrExpression>,
    ): IrExpression = with(builder) {
        irCall(symbols.listOf).apply {
            typeArguments[0] = elementType
            arguments[0] = IrVarargImpl(
                startOffset,
                endOffset,
                context.irBuiltIns.arrayClass.typeWith(elementType),
                elementType,
                elements,
            )
        }
    }

    private fun elementAt(
        builder: IrBuilderWithScope,
        list: IrValueParameter,
        index: Int,
    ): IrExpression = with(builder) {
        irCall(symbols.listGet).apply {
            arguments[0] = irGet(list)
            arguments[1] = irInt(index)
        }
    }

    private fun blockId(builder: IrBuilderWithScope, value: IrExpression): IrExpression =
        builder.irCallConstructor(symbols.blockIdConstructor, emptyList()).apply { arguments[0] = value }

    private fun nodeId(builder: IrBuilderWithScope, block: ProcedurePlan): IrExpression = with(builder) {
        val roleName = when (block.role) {
            ProcedureRole.SERVER -> "SERVER"
            ProcedureRole.CLIENT -> "CLIENT"
        }
        val entry = symbols.nodeRoleEntry(roleName)
        irCallConstructor(symbols.nodeIdConstructor, emptyList()).apply {
            arguments[0] = org.jetbrains.kotlin.ir.expressions.impl.IrGetEnumValueImpl(
                startOffset, endOffset, symbols.nodeRole.defaultType, entry,
            )
            // A server has no name, and a client is addressed by whatever the call site said --
            // a literal usually, but an expression is fine now that clients start on demand.
            arguments[1] = when (block.role) {
                ProcedureRole.SERVER -> irString("")
                ProcedureRole.CLIENT -> block.call.argumentFor("name") ?: irString(DEFAULT_CLIENT)
            }
        }
    }

    private fun classReference(builder: IrBuilderWithScope, type: IrType): IrExpression {
        val classifier = type.classOrNull
            ?: error("e2e: a block argument must have a concrete class type, got " + type)
        return IrClassReferenceImpl(
            builder.startOffset,
            builder.endOffset,
            context.irBuiltIns.kClassClass.starProjectedType,
            classifier,
            type,
        )
    }

    private fun listOfType(elementType: IrType): IrType =
        context.irBuiltIns.listClass.typeWith(elementType)

    private fun methodName(index: Int, block: ProcedurePlan): String {
        val readable = block.id.substringAfterLast('/').map { if (it.isLetterOrDigit()) it else '_' }
        return "b" + index + "_" + readable.joinToString("")
    }
}
