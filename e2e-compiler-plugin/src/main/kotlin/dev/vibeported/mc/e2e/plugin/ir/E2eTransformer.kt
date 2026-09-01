@file:OptIn(UnsafeDuringIrConstructionAPI::class)

package dev.vibeported.mc.e2e.plugin.ir

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.builders.declarations.addConstructor
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.declarations.buildReceiverParameter
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irCallConstructor
import org.jetbrains.kotlin.ir.builders.irDelegatingConstructorCall
import org.jetbrains.kotlin.ir.builders.irEquals
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irIfThen
import org.jetbrains.kotlin.ir.builders.irInt
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrClassReferenceImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrCompositeImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrInstanceInitializerCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrThrowImpl
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.starProjectedType
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.patchDeclarationParents
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.Name

internal object E2ePluginKey : org.jetbrains.kotlin.GeneratedDeclarationKey()

/** Marks everything this plugin emits, so it is obvious in an IR dump who put it there. */
internal val E2eOrigin: IrDeclarationOrigin = IrDeclarationOrigin.GeneratedByPlugin(E2ePluginKey)

/**
 * Turns the planned structure into code.
 *
 * The whole transform rests on one move: a block body is not copied into the generated table, the
 * frontend function that already backs the lambda is *re-parented* into it. Every symbol inside the
 * body keeps pointing at the same declarations, so nothing has to be remapped, and the lambda stops
 * being a closure simply because it is no longer nested in one.
 */
internal class E2eTransformer(
    private val context: IrPluginContext,
    private val symbols: E2eSymbols,
) {
    fun transform(plan: FilePlan) {
        val table = buildTableObject(plan)
        val methods = LinkedHashMap<String, IrSimpleFunction>()

        plan.blocks().forEachIndexed { index, block ->
            val method = liftIntoTable(table, block, index)
            methods[block.id] = method
            rewriteBody(plan, block, method)
        }

        buildInvoke(table, plan, methods)
        plan.file.declarations += table

        rewriteDeclarationCalls(plan)
    }

    // -- the table object ---------------------------------------------------------------------

    private fun buildTableObject(plan: FilePlan): IrClass {
        val table = context.irFactory.buildClass {
            name = Name.identifier(plan.tableSimpleName)
            kind = ClassKind.OBJECT
            visibility = DescriptorVisibilities.PUBLIC
            modality = Modality.FINAL
            origin = E2eOrigin
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
            origin = E2eOrigin
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
     * The lambda receiver becomes an ordinary `scope` argument. Its [IrValueParameter] object is
     * reused rather than replaced, so every read of it already in the body stays valid.
     */
    private fun liftIntoTable(table: IrClass, block: BlockPlan, index: Int): IrSimpleFunction {
        val function = block.lambda
        val scope = function.parameters.single { it.kind == IrParameterKind.ExtensionReceiver }
        scope.kind = IrParameterKind.Regular
        scope.name = Name.identifier("scope")

        function.name = Name.identifier(methodName(index, block))
        function.visibility = DescriptorVisibilities.PRIVATE
        function.origin = E2eOrigin
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

    private fun buildInvoke(table: IrClass, plan: FilePlan, methods: Map<String, IrSimpleFunction>) {
        val invoke = table.addFunction {
            name = Name.identifier("invoke")
            returnType = context.irBuiltIns.anyNType
            modality = Modality.FINAL
            visibility = DescriptorVisibilities.PUBLIC
            isSuspend = true
            origin = E2eOrigin
        }
        val dispatchReceiver = invoke.buildReceiverParameter {
            type = table.symbol.typeWith()
            name = Name.special("<this>")
        }
        dispatchReceiver.kind = IrParameterKind.DispatchReceiver
        invoke.parameters = listOf(dispatchReceiver)
        val idParameter = invoke.addValueParameter("id", context.irBuiltIns.stringType)
        val scopeParameter = invoke.addValueParameter("scope", symbols.blockScope.defaultType)
        invoke.overriddenSymbols = listOf(symbols.blockTable.functions.single { it.owner.name.asString() == "invoke" })

        invoke.body = DeclarationIrBuilder(context, invoke.symbol).irBlockBody {
            // A short linear scan: dispatch happens once per block, and an if-chain keeps the
            // generated code readable in a decompiler, which matters when debugging a lifted body.
            methods.forEach { (id, method) ->
                +irIfThen(
                    context.irBuiltIns.unitType,
                    irEquals(irGet(idParameter), irString(id)),
                    irReturn(
                        irCall(method.symbol).apply {
                            arguments[0] = irGet(invoke.parameters[0])
                            arguments[1] = irGet(scopeParameter)
                        }
                    ),
                )
            }
            +IrThrowImpl(
                startOffset,
                endOffset,
                context.irBuiltIns.nothingType,
                irCallConstructor(symbols.noSuchBlockConstructor, emptyList()).apply {
                    arguments[0] = blockId(this@irBlockBody, irGet(idParameter))
                    arguments[1] = irString(plan.tableClass)
                },
            )
        }
    }

    // -- rewriting a lifted body --------------------------------------------------------------

    private fun rewriteBody(plan: FilePlan, block: BlockPlan, method: IrSimpleFunction) {
        val scope = method.parameters.single { it.kind == IrParameterKind.Regular }
        val test = plan.suites.flatMap { it.tests }.single { it.id == block.testId }

        val sharedByAccessor = HashMap<IrSimpleFunctionSymbol, SharedPlan>()
        test.shared.forEach { shared ->
            shared.property.getter.symbol.let { sharedByAccessor[it] = shared }
            shared.property.setter?.symbol?.let { sharedByAccessor[it] = shared }
        }

        method.body?.transformChildrenVoid(object : IrElementTransformerVoid() {

            /** Deletes the `by shared<T>()` declaration; the reads and writes no longer need it. */
            override fun visitLocalDelegatedProperty(
                declaration: org.jetbrains.kotlin.ir.declarations.IrLocalDelegatedProperty,
            ): IrStatement {
                if (test.shared.none { it.property === declaration }) return super.visitLocalDelegatedProperty(declaration)
                return IrCompositeImpl(
                    declaration.startOffset,
                    declaration.endOffset,
                    context.irBuiltIns.unitType,
                    null,
                    emptyList(),
                )
            }

            override fun visitCall(expression: IrCall): IrExpression {
                val builder = DeclarationIrBuilder(context, method.symbol, expression.startOffset, expression.endOffset)

                sharedByAccessor[expression.symbol]?.let { shared ->
                    val isGetter = shared.property.getter.symbol == expression.symbol
                    expression.transformChildrenVoid(this)
                    return if (isGetter) {
                        sharedGetCall(builder, scope, shared)
                    } else {
                        sharedSetCall(builder, scope, shared, expression.arguments.last()!!)
                    }
                }

                val child = block.children.firstOrNull { it.call === expression }
                if (child != null) return dispatchCall(builder, scope, child)

                return super.visitCall(expression)
            }
        })
    }

    private fun sharedGetCall(
        builder: IrBuilderWithScope,
        scope: IrValueParameter,
        shared: SharedPlan,
    ): IrExpression = with(builder) {
        val read = irCall(symbols.sharedGet).apply {
            arguments[0] = irGet(scope)
            arguments[1] = sharedId(builder, shared.id)
            arguments[2] = classReference(shared.type)
        }
        // sharedGet is typed Any? because the wire is; the cast puts the property type back.
        org.jetbrains.kotlin.ir.expressions.impl.IrTypeOperatorCallImpl(
            startOffset, endOffset, shared.type,
            org.jetbrains.kotlin.ir.expressions.IrTypeOperator.CAST, shared.type, read,
        )
    }

    private fun sharedSetCall(
        builder: IrBuilderWithScope,
        scope: IrValueParameter,
        shared: SharedPlan,
        value: IrExpression,
    ): IrExpression = with(builder) {
        irCall(symbols.sharedSet).apply {
            arguments[0] = irGet(scope)
            arguments[1] = sharedId(builder, shared.id)
            arguments[2] = classReference(shared.type)
            arguments[3] = value
        }
    }

    private fun dispatchCall(
        builder: IrBuilderWithScope,
        scope: IrValueParameter,
        block: BlockPlan,
    ): IrExpression = with(builder) {
        irCall(symbols.dispatch).apply {
            arguments[0] = irGet(scope)
            arguments[1] = blockId(builder, irString(block.id))
            arguments[2] = nodeId(builder, block)
        }
    }

    // -- rewriting the declaration calls -------------------------------------------------------

    /**
     * Replaces `suite(name) { }` and `e2e(name) { }` with the overloads that carry ids.
     *
     * The suite builder body stays where it is: it is not lifted, it just runs locally to report
     * what tests exist. Only the `e2e` body has moved, so it is replaced by its block id.
     */
    private fun rewriteDeclarationCalls(plan: FilePlan) {
        val e2eCalls = plan.suites.flatMap { suite -> suite.tests.map { it.call to it } }.toMap()
        val suiteCalls = plan.suites.associateBy { it.call }

        plan.file.transformChildrenVoid(object : IrElementTransformerVoid() {
            override fun visitCall(expression: IrCall): IrExpression {
                suiteCalls[expression]?.let { suite ->
                    expression.transformChildrenVoid(this)
                    val builder = DeclarationIrBuilder(context, symbols.suiteWithId, expression.startOffset, expression.endOffset)
                    return builder.irCall(symbols.suiteWithId).apply {
                        arguments[0] = expression.arguments[0]
                        arguments[1] = builder.irString(suite.id)
                        arguments[2] = expression.arguments[1]
                    }
                }
                e2eCalls[expression]?.let { test ->
                    val builder = DeclarationIrBuilder(context, symbols.e2eWithId, expression.startOffset, expression.endOffset)
                    // The body goes nowhere: it was declarative, and its blocks are already in the
                    // table and listed as this test's steps in the index. Only the id survives.
                    return builder.irCall(symbols.e2eWithId).apply {
                        // 0 is the SuiteBuilder receiver, which the original call already carries.
                        arguments[0] = expression.arguments[0]
                        arguments[1] = expression.arguments[1]
                        arguments[2] = builder.irString(test.id)
                    }
                }
                return super.visitCall(expression)
            }
        })
    }

    // -- small constructors --------------------------------------------------------------------

    private fun blockId(builder: IrBuilderWithScope, value: IrExpression): IrExpression =
        builder.irCallConstructor(symbols.blockIdConstructor, emptyList()).apply { arguments[0] = value }

    private fun sharedId(builder: IrBuilderWithScope, value: String): IrExpression =
        builder.irCallConstructor(symbols.sharedIdConstructor, emptyList())
            .apply { arguments[0] = builder.irString(value) }

    private fun nodeId(builder: IrBuilderWithScope, block: BlockPlan): IrExpression {
        val roleName = when (block.role) {
            BlockRole.SERVER -> "SERVER"
            BlockRole.CLIENT -> "CLIENT"
        }
        val entry = symbols.nodeRoleEntry(roleName)
        return builder.irCallConstructor(symbols.nodeIdConstructor, emptyList()).apply {
            arguments[0] = org.jetbrains.kotlin.ir.expressions.impl.IrGetEnumValueImpl(
                builder.startOffset, builder.endOffset, symbols.nodeRole.defaultType, entry,
            )
            arguments[1] = builder.irInt(block.clientIndex)
        }
    }

    private fun IrBuilderWithScope.classReference(type: IrType): IrExpression {
        val classifier = type.classOrNull
            ?: error("e2e: a shared value must have a concrete class type, got $type")
        return IrClassReferenceImpl(
            startOffset,
            endOffset,
            context.irBuiltIns.kClassClass.starProjectedType,
            classifier,
            type,
        )
    }

    private companion object {
        /**
         * A readable, unique method name. Readable because it is what shows up in a stack trace
         * from inside a lifted block, which is the first thing anyone debugging one will see.
         */
        fun methodName(index: Int, block: BlockPlan): String {
            val tail = block.id.substringAfterLast('/')
            val safe = tail.map { if (it.isLetterOrDigit() || it == '_') it else '_' }.joinToString("")
            return "b${index}_$safe"
        }
    }
}
