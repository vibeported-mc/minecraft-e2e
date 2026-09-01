package dev.vibeported.mc.e2e.plugin.ir

import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid

/**
 * Reads the structure of one file and assigns every block and shared value its stable id.
 *
 * Ids are structural -- suite name, test name, then a per-role ordinal within the enclosing block --
 * so reformatting a file, or editing an unrelated test in it, leaves them alone. The one thing that
 * does change an id is renaming the suite or test it belongs to, which is the trade for having ids
 * a human can read straight out of a report.
 */
internal class E2ePlanner(
    private val file: IrFile,
    private val messages: MessageCollector,
) {
    private val facadeClass = file.facadeClassName()

    fun plan(): FilePlan {
        val plan = FilePlan(
            file = file,
            facadeClass = facadeClass,
            // No dollar sign in the name: it keeps the generated class clear of the backend name
            // mangling rules, and the real class name is recorded in the index anyway.
            tableSimpleName = "E2eBlocks_" + facadeClass.substringAfterLast('.'),
        )

        file.declarations.filterIsInstance<IrProperty>().forEach { property ->
            val initializer = property.backingField?.initializer?.expression as? IrCall ?: return@forEach
            if (initializer.fqName() != E2eDsl.SUITE) return@forEach
            planSuite(plan, property, initializer)
        }
        return plan
    }

    private fun planSuite(plan: FilePlan, property: IrProperty, call: IrCall) {
        val name = call.constArgument("name") ?: run {
            report(call, "suite name must be a compile-time constant string")
            return
        }
        val body = call.lambdaArgument("body") ?: run {
            report(call, "suite body must be a lambda literal")
            return
        }
        val suite = SuitePlan(
            id = "$facadeClass:$name",
            name = name,
            call = call,
            accessor = jvmGetterName(property.name.asString()),
        )
        plan.suites += suite

        body.forEachCall { inner ->
            if (inner.fqName() == E2eDsl.E2E) planTest(suite, inner)
        }
    }

    private fun planTest(suite: SuitePlan, call: IrCall) {
        val name = call.constArgument("name") ?: run {
            report(call, "test name must be a compile-time constant string")
            return
        }
        val body = call.lambdaArgument("body") ?: run {
            report(call, "test body must be a lambda literal")
            return
        }
        val test = TestPlan(id = "${suite.id}/$name", name = name, call = call)
        suite.tests += test

        planShared(test, body)
        collectClientNames(test, body)
        // The test body is declarative, so its blocks are the test: an ordered list of steps for the
        // orchestrator to walk, rather than a body somebody has to run.
        planSteps(test, body)
    }

    /** Shared values may only be declared in a test body, so one walk of it finds them all. */
    private fun planShared(test: TestPlan, body: IrSimpleFunction) {
        body.body?.acceptChildrenVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)

            override fun visitVariable(declaration: IrVariable) {
                val initializer = declaration.initializer as? IrCall
                if (initializer?.fqName() == E2eDsl.SHARED) {
                    val name = declaration.name.asString()
                    if (test.shared.any { it.name == name }) {
                        report(initializer, "duplicate shared value `$name` in test `${test.name}`")
                    }
                    test.shared += SharedPlan(
                        id = "${test.id}#$name",
                        name = name,
                        variable = declaration,
                        // shared<T>() says T at the call site; the local is typed Shared<T>.
                        type = initializer.typeArguments.firstOrNull()
                            ?: error("e2e: shared() without a type argument at ${declaration.name}"),
                    )
                }
                declaration.acceptChildrenVoid(this)
            }
        })
    }

    /**
     * Turns a test body into ordered steps.
     *
     * A block written straight into the body is a step of its own; a `parallel { }` collects the
     * blocks inside it into one step that runs together. Ordinals keep counting across the whole
     * test, so wrapping two blocks in `parallel` does not renumber anything after them.
     */
    private fun planSteps(test: TestPlan, body: IrSimpleFunction) {
        val ordinals = mutableMapOf<String, Int>()

        body.body?.acceptChildrenVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)

            override fun visitFunctionExpression(expression: IrFunctionExpression) = Unit

            override fun visitCall(expression: IrCall) {
                when {
                    expression.fqName() == E2eDsl.PARALLEL -> {
                        val group = expression.lambdaArgument("body") ?: run {
                            report(expression, "a parallel block body must be a lambda literal")
                            return
                        }
                        val step = StepPlan(parallel = true)
                        test.steps += step
                        planBlocks(test, null, test.id, group, ordinals) { step.blocks += it }
                    }

                    expression.blockRole() != null -> {
                        val step = StepPlan(parallel = false)
                        test.steps += step
                        planBlock(test, null, test.id, expression, ordinals) { step.blocks += it }
                    }

                    else -> expression.acceptChildrenVoid(this)
                }
            }
        })
    }

    /**
     * Records every client name the test mentions, wherever it appears.
     *
     * The names are read off the callee's parameters rather than off a list of functions this plugin
     * knows about, so a framework method that gains a client name is collected here the moment it is
     * annotated, with nothing to change on this side.
     */
    private fun collectClientNames(test: TestPlan, body: IrSimpleFunction) {
        body.body?.acceptChildrenVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)

            override fun visitCall(expression: IrCall) {
                expression.symbol.owner.parameters.forEachIndexed { index, parameter ->
                    if (!parameter.isClientName()) return@forEachIndexed
                    val argument = expression.arguments.getOrNull(index)
                    // An omitted argument means the default, which is the default client.
                    if (argument == null) {
                        test.mentioned += DEFAULT_CLIENT
                    } else {
                        ((argument as? IrConst)?.value as? String)?.let { test.mentioned += it }
                    }
                }
                expression.acceptChildrenVoid(this)
            }
        })
    }

    /**
     * Assigns ordinals within [parent] and recurses.
     *
     * Deliberately does not descend into unrelated lambdas. A `server { }` raised from inside, say,
     * a `forEach` would take an ordinal that depends on runtime data, and the whole value of these
     * ids is that they do not. Such a call is reported rather than silently mis-numbered.
     */
    private fun planBlocks(
        test: TestPlan,
        parent: BlockPlan?,
        prefix: String,
        lambda: IrSimpleFunction,
        ordinals: MutableMap<String, Int>,
        collect: (BlockPlan) -> Unit,
    ) {
        lambda.body?.acceptChildrenVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)

            override fun visitFunctionExpression(expression: IrFunctionExpression) {
                // An unrecognised lambda: scanned only to complain about blocks hidden inside it.
                expression.function.body?.acceptChildrenVoid(object : IrVisitorVoid() {
                    override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)

                    override fun visitCall(expression: IrCall) {
                        val role = expression.blockRole()
                        if (role != null) {
                            report(
                                expression,
                                "a ${role.name.lowercase()} block cannot be declared inside another " +
                                    "lambda; its id would depend on how many times that lambda ran",
                            )
                        }
                        expression.acceptChildrenVoid(this)
                    }
                })
            }

            override fun visitCall(expression: IrCall) {
                if (expression.blockRole() == null) {
                    expression.acceptChildrenVoid(this)
                    return
                }
                planBlock(test, parent, prefix, expression, ordinals, collect)
            }
        })
    }

    /**
     * Plans one block and everything nested inside it.
     *
     * Ordinals are per label, and a client counts under its own name: `client[steve][0]` and
     * `client[alex][0]` rather than a shared counter, so adding a client to a test cannot renumber
     * another one.
     */
    private fun planBlock(
        test: TestPlan,
        parent: BlockPlan?,
        prefix: String,
        call: IrCall,
        ordinals: MutableMap<String, Int>,
        collect: (BlockPlan) -> Unit,
    ) {
        val role = call.blockRole() ?: return
        val body = call.lambdaArgument("body") ?: run {
            report(call, "a ${role.name.lowercase()} block body must be a lambda literal")
            return
        }

        val client = if (role == BlockRole.CLIENT) {
            call.constArgument("name") ?: DEFAULT_CLIENT
        } else {
            ""
        }
        val label = if (role == BlockRole.CLIENT) "client[$client]" else "server"
        val ordinal = ordinals.merge("$prefix/$label", 1, Int::plus)!! - 1
        val explicitId = call.constArgument("id")

        val block = BlockPlan(
            id = explicitId?.let { "$prefix/$it" } ?: "$prefix/$label[$ordinal]",
            role = role,
            client = client,
            parent = parent,
            testId = test.id,
            call = call,
            lambda = body,
        )
        collect(block)
        planBlocks(test, block, block.id, body, ordinals) { block.children += it }
    }

    private fun IrCall.blockRole(): BlockRole? = when (fqName()) {
        E2eDsl.SERVER -> BlockRole.SERVER
        E2eDsl.CLIENT -> BlockRole.CLIENT
        else -> null
    }

    private fun report(at: IrElement, message: String) {
        messages.report(CompilerMessageSeverity.ERROR, "e2e: $message", file.locationOf(at))
    }

    private companion object {
        /**
         * Kotlin names a property getter getFoo, except for a Boolean isFoo, which keeps its own
         * name. Matching that rule here is what lets the orchestrator call it by reflection.
         */
        fun jvmGetterName(propertyName: String): String =
            if (propertyName.startsWith("is") && propertyName.length > 2 && !propertyName[2].isLowerCase()) {
                propertyName
            } else {
                "get" + propertyName.replaceFirstChar { it.uppercaseChar() }
            }
    }
}

/** Fully qualified names of the DSL entry points, as they appear on resolved IR calls. */
internal const val DEFAULT_CLIENT: String = "default"

internal object E2eDsl {
    const val PACKAGE: String = "dev.vibeported.mc.e2e"
    const val SUITE: String = "$PACKAGE.suite"
    const val E2E: String = "$PACKAGE.SuiteBuilder.e2e"
    const val SERVER: String = "$PACKAGE.server"
    const val CLIENT: String = "$PACKAGE.client"
    const val SHARED: String = "$PACKAGE.shared"
    const val PARALLEL: String = "$PACKAGE.parallel"
    const val CLIENT_NAME_ANNOTATION: String = "$PACKAGE.MinecraftClientName"
}

/** Whether a parameter is annotated `@MinecraftClientName`. */
internal fun IrValueParameter.isClientName(): Boolean =
    annotations.any { it.type.classFqName?.asString() == E2eDsl.CLIENT_NAME_ANNOTATION }

internal fun IrCall.fqName(): String? = symbol.owner.fqNameWhenAvailable?.asString()

internal fun IrCall.argumentFor(parameterName: String): IrExpression? {
    val index = symbol.owner.parameters.indexOfFirst { it.name.asString() == parameterName }
    return if (index >= 0 && index < arguments.size) arguments[index] else null
}

internal fun IrCall.constArgument(parameterName: String): String? =
    (argumentFor(parameterName) as? IrConst)?.value as? String

internal fun IrCall.intArgument(parameterName: String): Int? =
    (argumentFor(parameterName) as? IrConst)?.value as? Int

internal fun IrCall.lambdaArgument(parameterName: String): IrSimpleFunction? =
    (argumentFor(parameterName) as? IrFunctionExpression)?.function

/** Visits every call under this lambda, without descending into nested lambdas. */
internal fun IrSimpleFunction.forEachCall(action: (IrCall) -> Unit) {
    body?.acceptChildrenVoid(object : IrVisitorVoid() {
        override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)

        override fun visitFunctionExpression(expression: IrFunctionExpression) = Unit

        override fun visitCall(expression: IrCall) {
            action(expression)
            expression.acceptChildrenVoid(this)
        }
    })
}
