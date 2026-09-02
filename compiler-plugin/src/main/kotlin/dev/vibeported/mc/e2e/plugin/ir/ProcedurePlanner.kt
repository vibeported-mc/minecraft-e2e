package dev.vibeported.mc.e2e.plugin.ir

import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid

/**
 * Finds every `server`/`client` call in a file and gives each one a stable id.
 *
 * Ids are lexical: the declaration a call sits in, then a per-role ordinal in source order. That is
 * a change of kind from the old scheme, which numbered blocks by their position in a declared test
 * and so could only exist inside one. A call in a loop or a lambda is fine now, because the id
 * describes where the block was written rather than how many times it runs.
 */
internal class ProcedurePlanner(
    private val file: IrFile,
    private val messages: MessageCollector,
) {
    private val facadeClass = file.facadeClassName()

    fun plan(): FilePlan {
        val simpleName = facadeClass.substringAfterLast('.')
        val plan = FilePlan(
            file = file,
            facadeClass = facadeClass,
            // No dollar sign: it keeps the generated classes clear of the backend name mangling
            // rules, and the real class names are recorded in the index anyway.
            serverTableSimpleName = "ServerProcedures_" + simpleName,
            clientTableSimpleName = "ClientProcedures_" + simpleName,
        )
        file.declarations.forEach { plan.planContainer(it) }
        return plan
    }

    /**
     * Plans one top-level declaration.
     *
     * Its name is what block ids hang off, so a block keeps its id when an unrelated declaration
     * beside it changes. Renaming the function holding it does change those ids, which is the trade
     * for ids a person can read straight out of a report.
     */
    private fun FilePlan.planContainer(declaration: IrDeclaration) {
        when (declaration) {
            is IrClass -> declaration.declarations.forEach { planContainer(it) }

            is IrProperty -> {
                val prefix = facadeClass + "." + declaration.name.asString()
                declaration.backingField?.initializer?.let { planWithin(this, null, prefix, it) }
            }

            is IrSimpleFunction -> {
                val prefix = facadeClass + "." + declaration.name.asString()
                declaration.body?.let { planWithin(this, null, prefix, it) }
            }

            else -> Unit
        }
    }

    /**
     * Walks everything under [root], nested lambdas included.
     *
     * Descending into a `forEach` or a `repeat` used to be forbidden, because an ordinal that
     * depended on how many times a lambda ran was no ordinal at all. Ordinals are per call site now,
     * so a block written inside a loop has exactly one id however often it executes.
     */
    private fun planWithin(plan: FilePlan, parent: ProcedurePlan?, prefix: String, root: IrElement) {
        val ordinals = mutableMapOf<String, Int>()

        root.acceptChildrenVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)

            override fun visitCall(expression: IrCall) {
                collectClientNames(plan, expression)
                val role = expression.blockRole()
                if (role == null) {
                    expression.acceptChildrenVoid(this)
                    return
                }
                val block = planBlock(plan, parent, prefix, ordinals, expression, role) ?: return
                if (parent == null) plan.roots += block else parent.children += block
            }
        })
    }

    private fun planBlock(
        plan: FilePlan,
        parent: ProcedurePlan?,
        prefix: String,
        ordinals: MutableMap<String, Int>,
        call: IrCall,
        role: ProcedureRole,
    ): ProcedurePlan? {
        val body = call.lambdaArgument("body") ?: run {
            report(call, "a " + role.name.lowercase() + " block body must be a lambda literal")
            return null
        }

        // server<A1..An, R> and client<A1..An, R>: everything but the last is an argument type.
        val typeArguments = call.typeArguments
        if (typeArguments.isEmpty() || typeArguments.any { it == null }) {
            report(call, "a block needs its argument and result types to be resolved")
            return null
        }

        val client = if (role == ProcedureRole.CLIENT) call.constArgument("name").orEmpty() else ""
        val label = when {
            role == ProcedureRole.SERVER -> "server"
            client.isEmpty() -> "client[*]"
            else -> "client[" + client + "]"
        }
        val ordinal = ordinals.merge(prefix + "/" + label, 1, Int::plus)!! - 1
        val explicit = call.constArgument("id")

        val block = ProcedurePlan(
            id = if (explicit != null) prefix + "/" + explicit else prefix + "/" + label + "[" + ordinal + "]",
            role = role,
            client = client,
            parent = parent,
            call = call,
            lambda = body,
            argumentTypes = typeArguments.dropLast(1).map { it!! },
            resultType = typeArguments.last()!!,
        )
        if (client.isNotEmpty()) plan.mentionedClients += client

        // Nested blocks hang off this one, so their ids stay stable when a sibling changes.
        body.body?.let { planWithin(plan, block, block.id, it) }
        return block
    }

    /**
     * Records every client name the file mentions in a way the compiler can resolve.
     *
     * Read off the callee parameters rather than a list of functions this plugin knows about, so a
     * framework method that gains a client name is collected the moment it is annotated. A name that
     * cannot be resolved is no longer an error: it simply cannot be collected, and the orchestrator
     * starts that client when it first sees it instead.
     */
    private fun collectClientNames(plan: FilePlan, call: IrCall) {
        call.symbol.owner.parameters.forEachIndexed { index, parameter ->
            if (!parameter.isClientName()) return@forEachIndexed
            val argument = call.arguments.getOrNull(index)
            if (argument == null) {
                plan.mentionedClients += DEFAULT_CLIENT
            } else {
                ((argument as? IrConst)?.value as? String)?.let { plan.mentionedClients += it }
            }
        }
    }

    private fun IrCall.blockRole(): ProcedureRole? = when (fqName()) {
        CallNames.SERVER -> ProcedureRole.SERVER
        CallNames.CLIENT -> ProcedureRole.CLIENT
        else -> null
    }

    private fun report(at: IrElement, message: String) {
        messages.report(CompilerMessageSeverity.ERROR, "e2e: " + message, file.locationOf(at))
    }
}

internal const val DEFAULT_CLIENT: String = "default"

/** Fully qualified names of the call primitives, as they appear on resolved IR calls. */
internal object CallNames {
    const val PACKAGE: String = "dev.vibeported.mc.e2e"
    const val SERVER: String = PACKAGE + ".server"
    const val CLIENT: String = PACKAGE + ".client"
    const val CLIENT_NAME_ANNOTATION: String = PACKAGE + ".MinecraftClientName"
}

/** Whether a parameter is annotated `@MinecraftClientName`. */
internal fun IrValueParameter.isClientName(): Boolean =
    annotations.any { it.type.classFqName?.asString() == CallNames.CLIENT_NAME_ANNOTATION }

internal fun IrCall.fqName(): String? = symbol.owner.fqNameWhenAvailable?.asString()

internal fun IrCall.argumentFor(parameterName: String): IrExpression? {
    val index = symbol.owner.parameters.indexOfFirst { it.name.asString() == parameterName }
    return if (index >= 0 && index < arguments.size) arguments[index] else null
}

internal fun IrCall.constArgument(parameterName: String): String? =
    (argumentFor(parameterName) as? IrConst)?.value as? String

internal fun IrCall.lambdaArgument(parameterName: String): IrSimpleFunction? =
    (argumentFor(parameterName) as? IrFunctionExpression)?.function

/**
 * The positional arguments a block was called with.
 *
 * Everything the caller wrote except the machinery: the body itself, the pinned id, and the client
 * name, none of which the block body ever sees.
 */
internal fun IrCall.procedureArguments(role: ProcedureRole): List<IrExpression> {
    val owner = symbol.owner
    return owner.parameters.mapIndexedNotNull { index, parameter ->
        val name = parameter.name.asString()
        val machinery = name == "body" || name == "id" || (role == ProcedureRole.CLIENT && name == "name")
        if (machinery) null else arguments.getOrNull(index)
    }
}
