@file:OptIn(UnsafeDuringIrConstructionAPI::class)

package dev.vibeported.rpc.plugin.ir

import org.jetbrains.kotlin.GeneratedDeclarationKey
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
import org.jetbrains.kotlin.ir.builders.irBoolean
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
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
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
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.patchDeclarationParents
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.Name

internal object RpcPluginKey : GeneratedDeclarationKey()

/** Marks everything this plugin emits, so an IR dump says plainly who put it there. */
internal val RpcOrigin: IrDeclarationOrigin = IrDeclarationOrigin.GeneratedByPlugin(RpcPluginKey)

/**
 * Turns planned bodies into classes.
 *
 * The whole transform rests on one move: a body is not copied into its table, the function that
 * already backs the lambda is **re-parented** into it. Every symbol inside the body keeps pointing
 * at the same declarations, so nothing has to be remapped, and the lambda stops being a closure
 * simply because it is no longer nested in one.
 *
 * One table per role, never one per file. A dist-cleaned node lacks the classes some bodies touch,
 * and a class is what it can or cannot load -- so bodies that a node must not resolve have to live
 * somewhere it never looks.
 */
internal class RpcTransformer(
    private val context: IrPluginContext,
    private val symbols: RpcSymbols,
) {

    fun transform(plan: FilePlan) {
        plan.roles().forEach { role ->
            val procedures = plan.proceduresFor(role)
            if (procedures.isEmpty()) return@forEach

            val table = buildTable(plan, role)
            val methods = LinkedHashMap<String, IrSimpleFunction>()
            procedures.forEachIndexed { index, procedure ->
                methods[procedure.id] = lift(table, procedure, index)
            }

            buildProcedures(table, procedures)
            buildInvoke(table, plan, role, procedures, methods)
            buildDecodeArgs(table, procedures)
            buildEncodeResult(table, procedures)

            plan.file.declarations += table
        }

        // Necessarily in the same pass. Once a body has been re-parented into a table it is no
        // longer a lambda, and a call site still holding it as one leaves the backend lowering an
        // orphan: "No dispatch receiver allowed in wrappers". So every planned call is replaced here
        // and now -- with a stand-in until dispatch rewriting lands, but replaced.
        rewriteCallSites(plan)
    }

    /**
     * Replaces each lifted lambda with the handle standing for it.
     *
     * Only the argument changes; the call itself is left exactly as written. That is the whole
     * difference from dispatching here: `rpcCall` and everything like it are ordinary functions
     * that receive a body and decide what to do with it, so a new one can be written by anybody
     * without the plugin learning its name.
     */
    private fun rewriteCallSites(plan: FilePlan) {
        val planned = plan.procedures.groupBy { it.call }

        plan.file.transformChildrenVoid(object : IrElementTransformerVoid() {
            override fun visitCall(expression: IrCall): IrExpression {
                planned[expression]?.forEach { procedure ->
                    expression.arguments[procedure.argumentIndex] = handleFor(procedure)
                }
                return super.visitCall(expression)
            }
        })
    }

    private fun handleFor(procedure: ProcedurePlan): IrExpression {
        val builder = DeclarationIrBuilder(context, procedure.call.symbol)
        return with(builder) {
            irCallConstructor(symbols.liftedBodyConstructor, emptyList()).apply {
                arguments[0] = irString(procedure.id)
                arguments[1] = procedure.role?.let { irString(it) } ?: irNull()
                arguments[2] = listOfValues(
                    builder,
                    symbols.kSerializer.starProjectedType,
                    procedure.argumentTypes.map { serializerFor(builder, it) },
                )
                arguments[3] = serializerFor(builder, procedure.resultType)
            }
        }
    }

    // -- the table object ----------------------------------------------------------------------

    private fun buildTable(plan: FilePlan, role: String?): IrClass {
        val table = context.irFactory.buildClass {
            name = Name.identifier(plan.tableSimpleName(role))
            kind = ClassKind.OBJECT
            visibility = DescriptorVisibilities.PUBLIC
            modality = Modality.FINAL
            origin = RpcOrigin
        }
        table.parent = plan.file
        table.superTypes = listOf(symbols.procedureTable.defaultType)
        // IrClass.defaultType reads thisReceiver, so it cannot be what builds thisReceiver. Going
        // through the symbol sidesteps that.
        table.thisReceiver = table.buildReceiverParameter { type = table.symbol.typeWith() }

        table.addConstructor {
            isPrimary = true
            visibility = DescriptorVisibilities.PRIVATE
            returnType = table.symbol.typeWith()
            origin = RpcOrigin
        }.apply {
            body = DeclarationIrBuilder(context, symbol).irBlockBody {
                +irDelegatingConstructorCall(context.irBuiltIns.anyClass.owner.constructors.single())
                +IrInstanceInitializerCallImpl(startOffset, endOffset, table.symbol, context.irBuiltIns.unitType)
            }
        }
        return table
    }

    /**
     * Re-parents the lambda onto [table] as a private method.
     *
     * Its receiver becomes an ordinary `scope` parameter and its declared parameters stay exactly
     * where they were. Every [IrValueParameter] object is reused rather than replaced, so every read
     * of one already inside the body stays valid without a single remap.
     */
    private fun lift(table: IrClass, procedure: ProcedurePlan, index: Int): IrSimpleFunction {
        val function = procedure.lambda
        function.parameters
            .singleOrNull { it.kind == IrParameterKind.ExtensionReceiver }
            ?.apply {
                kind = IrParameterKind.Regular
                name = Name.identifier("scope")
            }

        function.name = Name.identifier(methodName(index, procedure))
        function.visibility = DescriptorVisibilities.PRIVATE
        function.origin = RpcOrigin
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

    // -- the interface members -----------------------------------------------------------------

    private fun buildProcedures(table: IrClass, procedures: List<ProcedurePlan>) {
        val function = table.addOverride(
            "procedures",
            context.irBuiltIns.setClass.typeWith(context.irBuiltIns.stringType),
        )
        function.body = DeclarationIrBuilder(context, function.symbol).irBlockBody {
            +irReturn(
                setOfStrings(this@irBlockBody, procedures.map { it.id }),
            )
        }
    }

    /** `invoke(id, services, args)`: pick the method, find the receiver, unpack the arguments. */
    private fun buildInvoke(
        table: IrClass,
        plan: FilePlan,
        role: String?,
        procedures: List<ProcedurePlan>,
        methods: Map<String, IrSimpleFunction>,
    ) {
        val invoke = table.addOverride("invoke", context.irBuiltIns.anyNType, isSuspend = true)
        val id = invoke.addValueParameter("procedure", context.irBuiltIns.stringType)
        val services = invoke.addValueParameter("services", symbols.services.defaultType)
        val args = invoke.addValueParameter("args", listType(context.irBuiltIns.anyNType))

        invoke.body = DeclarationIrBuilder(context, invoke.symbol).irBlockBody {
            // A linear scan, because dispatch happens once per call and an if-chain stays readable
            // in a decompiler -- which matters when the thing being debugged is a lifted body.
            procedures.forEach { procedure ->
                val method = methods.getValue(procedure.id)
                val receiverType = method.parameters[1].type

                +irIfThen(
                    context.irBuiltIns.unitType,
                    irEquals(irGet(id), irString(procedure.id)),
                    irReturn(
                        irCall(method.symbol).apply {
                            arguments[0] = irGet(invoke.parameters[0])
                            // The receiver comes from the node this landed on, which is what makes
                            // an injected client reachable from every body routed there.
                            arguments[1] = irCall(symbols.resolve).apply {
                                typeArguments[0] = receiverType
                                arguments[0] = irGet(services)
                                arguments[1] = classReference(this@irBlockBody, receiverType)
                            }
                            procedure.argumentTypes.forEachIndexed { index, type ->
                                arguments[index + 2] = irAs(elementAt(this@irBlockBody, args, index), type)
                            }
                        }
                    ),
                )
            }
            +throwNoSuchProcedure(this@irBlockBody, plan, role, id)
        }
    }

    /**
     * `decodeArgs(procedure, args, format)`: what arrived over the wire, as the objects it was.
     *
     * Generated here rather than done by the node because only this end knows what each parameter
     * was declared as. The node holds a list of byte arrays and no idea what any of them mean.
     */
    private fun buildDecodeArgs(table: IrClass, procedures: List<ProcedurePlan>) {
        val decode = table.addOverride("decodeArgs", listType(context.irBuiltIns.anyNType))
        val id = decode.addValueParameter("procedure", context.irBuiltIns.stringType)
        val args = decode.addValueParameter("args", listType(context.irBuiltIns.byteArray.defaultType))
        val format = decode.addValueParameter("format", symbols.wireFormat.defaultType)

        decode.body = DeclarationIrBuilder(context, decode.symbol).irBlockBody {
            procedures.forEach { procedure ->
                +irIfThen(
                    context.irBuiltIns.unitType,
                    irEquals(irGet(id), irString(procedure.id)),
                    irReturn(
                        listOfValues(
                            this@irBlockBody,
                            context.irBuiltIns.anyNType,
                            procedure.argumentTypes.mapIndexed { index, type ->
                                irCall(symbols.decode).apply {
                                    typeArguments[0] = type
                                    arguments[0] = irGet(format)
                                    arguments[1] = serializerFor(this@irBlockBody, type)
                                    arguments[2] = elementAt(this@irBlockBody, args, index)
                                }
                            },
                        )
                    ),
                )
            }
            +irReturn(listOfValues(this@irBlockBody, context.irBuiltIns.anyNType, emptyList()))
        }
    }

    /** `encodeResult(procedure, value, format)`. Null for a body that gives nothing back. */
    private fun buildEncodeResult(table: IrClass, procedures: List<ProcedurePlan>) {
        val encode = table.addOverride(
            "encodeResult",
            context.irBuiltIns.byteArray.defaultType.makeNullable(),
        )
        val id = encode.addValueParameter("procedure", context.irBuiltIns.stringType)
        val value = encode.addValueParameter("value", context.irBuiltIns.anyNType)
        val format = encode.addValueParameter("format", symbols.wireFormat.defaultType)

        encode.body = DeclarationIrBuilder(context, encode.symbol).irBlockBody {
            procedures.forEach { procedure ->
                val result = irIfThen(
                    context.irBuiltIns.unitType,
                    irEquals(irGet(id), irString(procedure.id)),
                    // Unit is not encoded at all. There is nothing in it to carry, and a caller
                    // that asked for nothing should not pay bytes to be told so.
                    if (Serializers.isUnit(procedure.resultType)) {
                        irReturn(irNull())
                    } else {
                        irReturn(
                            irCall(symbols.encode).apply {
                                typeArguments[0] = procedure.resultType
                                arguments[0] = irGet(format)
                                arguments[1] = serializerFor(this@irBlockBody, procedure.resultType)
                                arguments[2] = irAs(irGet(value), procedure.resultType)
                            }
                        )
                    },
                )
                +result
            }
            +irReturn(irNull())
        }
    }

    // -- small constructors --------------------------------------------------------------------

    private fun IrClass.addOverride(
        name: String,
        returnType: IrType,
        isSuspend: Boolean = false,
    ): IrSimpleFunction {
        val function = addFunction {
            this.name = Name.identifier(name)
            this.returnType = returnType
            this.modality = Modality.FINAL
            this.visibility = DescriptorVisibilities.PUBLIC
            this.isSuspend = isSuspend
            this.origin = RpcOrigin
        }
        val dispatchReceiver = function.buildReceiverParameter {
            type = this@addOverride.symbol.typeWith()
            this.name = Name.special("<this>")
        }
        dispatchReceiver.kind = IrParameterKind.DispatchReceiver
        function.parameters = listOf(dispatchReceiver)
        function.overriddenSymbols =
            listOf(symbols.procedureTable.functions.single { it.owner.name.asString() == name })
        return function
    }

    private fun throwNoSuchProcedure(
        builder: IrBuilderWithScope,
        plan: FilePlan,
        role: String?,
        id: IrValueParameter,
    ): IrExpression = with(builder) {
        IrThrowImpl(
            startOffset,
            endOffset,
            context.irBuiltIns.nothingType,
            irCallConstructor(symbols.noSuchProcedureConstructor, emptyList()).apply {
                arguments[0] = irGet(id)
            },
        )
    }.also { _ -> plan.tableClass(role) }

    private fun setOfStrings(builder: IrBuilderWithScope, values: List<String>): IrExpression =
        with(builder) {
            irCall(symbols.setOf).apply {
                typeArguments[0] = context.irBuiltIns.stringType
                arguments[0] = IrVarargImpl(
                    startOffset,
                    endOffset,
                    context.irBuiltIns.arrayClass.typeWith(context.irBuiltIns.stringType),
                    context.irBuiltIns.stringType,
                    values.map { irString(it) },
                )
            }
        }

    private fun elementAt(builder: IrBuilderWithScope, list: IrValueParameter, index: Int): IrExpression =
        with(builder) {
            irCall(symbols.listGet).apply {
                arguments[0] = irGet(list)
                arguments[1] = irInt(index)
            }
        }

    private fun classReference(builder: IrBuilderWithScope, type: IrType): IrExpression {
        val classifier = type.classOrNull
            ?: error("rpc: a procedure argument needs a concrete class type, got $type")
        return IrClassReferenceImpl(
            builder.startOffset,
            builder.endOffset,
            context.irBuiltIns.kClassClass.starProjectedType,
            classifier,
            type,
        )
    }

    /**
     * `serializer(T::class, emptyList(), false)`, cast to the serializer of that type.
     *
     * Whether a serializer exists was settled before anything was emitted; this is only how the
     * generated code gets hold of it.
     */
    private fun serializerFor(builder: IrBuilderWithScope, type: IrType): IrExpression = with(builder) {
        val lookup = irCall(symbols.serializerOf).apply {
            arguments[0] = classReference(builder, type)
            arguments[1] = irCall(symbols.emptyList).apply {
                typeArguments[0] = symbols.kSerializer.starProjectedType
            }
            arguments[2] = irBoolean(false)
        }
        irAs(lookup, symbols.kSerializer.typeWith(type))
    }

    private fun listOfValues(
        builder: IrBuilderWithScope,
        elementType: IrType,
        values: List<IrExpression>,
    ): IrExpression = with(builder) {
        irCall(symbols.listOf).apply {
            typeArguments[0] = elementType
            arguments[0] = IrVarargImpl(
                startOffset,
                endOffset,
                context.irBuiltIns.arrayClass.typeWith(elementType),
                elementType,
                values,
            )
        }
    }

    private fun listType(elementType: IrType): IrType = context.irBuiltIns.listClass.typeWith(elementType)

    /** `p0_caller_0`, so a stack trace inside a lifted body still says where it was written. */
    private fun methodName(index: Int, procedure: ProcedurePlan): String {
        val readable = procedure.id.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
        return "p${index}_$readable"
    }

    private companion object {
    }
}
