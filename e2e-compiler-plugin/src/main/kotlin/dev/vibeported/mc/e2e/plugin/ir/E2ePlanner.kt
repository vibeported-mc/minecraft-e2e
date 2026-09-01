package dev.vibeported.mc.e2e.plugin.ir

import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrLocalDelegatedProperty
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
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
        val testId = "${suite.id}/$name"
        val driver = BlockPlan(
            id = "$testId/driver",
            role = BlockRole.ORCHESTRATOR,
            clientIndex = 0,
            parent = null,
            testId = testId,
            call = call,
            lambda = body,
        )
        val test = TestPlan(id = testId, name = name, call = call, driver = driver)
        suite.tests += test

        planShared(test)
        planNestedBlocks(test, driver)
    }

    /** Shared values may only be declared in a driver body, so one walk of it finds them all. */
    private fun planShared(test: TestPlan) {
        test.driver.lambda.body?.acceptChildrenVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)

            override fun visitLocalDelegatedProperty(declaration: IrLocalDelegatedProperty) {
                val delegate = declaration.delegate?.initializer as? IrCall
                if (delegate?.fqName() == E2eDsl.SHARED) {
                    val name = declaration.name.asString()
                    if (test.shared.any { it.name == name }) {
                        report(delegate, "duplicate shared value `$name` in test `${test.name}`")
                    }
                    test.shared += SharedPlan(
                        id = "${test.id}#$name",
                        name = name,
                        property = declaration,
                        type = declaration.type,
                    )
                }
                declaration.acceptChildrenVoid(this)
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
    private fun planNestedBlocks(test: TestPlan, parent: BlockPlan) {
        val ordinals = mutableMapOf<BlockRole, Int>()

        parent.lambda.body?.acceptChildrenVoid(object : IrVisitorVoid() {
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
                val role = expression.blockRole()
                if (role == null) {
                    expression.acceptChildrenVoid(this)
                    return
                }
                val body = expression.lambdaArgument("body") ?: run {
                    report(expression, "a ${role.name.lowercase()} block body must be a lambda literal")
                    return
                }
                val ordinal = ordinals.merge(role, 1, Int::plus)!! - 1
                val clientIndex = if (role == BlockRole.CLIENT) expression.intArgument("index") ?: 0 else 0
                val explicitId = expression.constArgument("id")
                val label = role.name.lowercase()
                val child = BlockPlan(
                    id = explicitId?.let { "${parent.pathPrefix}/$it" } ?: "${parent.pathPrefix}/$label[$ordinal]",
                    role = role,
                    clientIndex = clientIndex,
                    parent = parent,
                    testId = test.id,
                    call = expression,
                    lambda = body,
                )
                parent.children += child
                planNestedBlocks(test, child)
            }
        })
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
internal object E2eDsl {
    const val PACKAGE: String = "dev.vibeported.mc.e2e"
    const val SUITE: String = "$PACKAGE.suite"
    const val E2E: String = "$PACKAGE.SuiteBuilder.e2e"
    const val SERVER: String = "$PACKAGE.server"
    const val CLIENT: String = "$PACKAGE.client"
    const val SHARED: String = "$PACKAGE.shared"
}

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
