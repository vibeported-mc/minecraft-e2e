package dev.vibeported.mc.e2e.plugin.ir

import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.types.IrType

internal enum class BlockRole { SERVER, CLIENT }

/**
 * One `var x by shared<T>()`.
 *
 * The declaration disappears with the test body it was written in; [id] is what the rewritten reads
 * and writes carry instead.
 */
internal class SharedPlan(
    val id: String,
    val name: String,
    /** The local the declaration created. It never survives: every mention becomes a handle. */
    val variable: IrVariable,
    /** The `T` in `Shared<T>`, which is what actually crosses the wire. */
    val type: IrType,
)

/**
 * One lifted block: a `server`/`client` body at any nesting depth.
 *
 * [lambda] is the function the frontend already built for the lambda literal. The transform moves
 * that very function into the generated table rather than copying its body, which keeps every
 * symbol inside it valid.
 */
internal class BlockPlan(
    val id: String,
    val role: BlockRole,
    val clientIndex: Int,
    /** The enclosing block, or null for a step written straight into the test body. */
    val parent: BlockPlan?,
    val testId: String,
    /** The `server`/`client` call this body was an argument to. */
    val call: IrCall,
    val lambda: IrSimpleFunction,
) {
    val children: MutableList<BlockPlan> = mutableListOf()

    fun selfAndDescendants(): List<BlockPlan> = listOf(this) + children.flatMap { it.selfAndDescendants() }
}

/**
 * One `e2e("...") { }`.
 *
 * A test body is declarative: shared declarations and an ordered list of blocks to run, nothing
 * else. So there is no body to execute and nothing to lift here -- [steps] is the whole test, and
 * the orchestrator walks it.
 */
internal class TestPlan(
    val id: String,
    val name: String,
    val call: IrCall,
) {
    val shared: MutableList<SharedPlan> = mutableListOf()
    val steps: MutableList<BlockPlan> = mutableListOf()
}

internal class SuitePlan(
    val id: String,
    val name: String,
    val call: IrCall,
    /** JVM getter on the file facade, in case a tool wants the descriptors back. */
    val accessor: String,
) {
    val tests: MutableList<TestPlan> = mutableListOf()
}

internal class FilePlan(
    val file: IrFile,
    val facadeClass: String,
    val tableSimpleName: String,
) {
    val suites: MutableList<SuitePlan> = mutableListOf()

    val tableClass: String
        get() = file.packageFqName.asString().let { pkg ->
            if (pkg.isEmpty()) tableSimpleName else "$pkg.$tableSimpleName"
        }

    fun blocks(): List<BlockPlan> = suites.flatMap { suite ->
        suite.tests.flatMap { test -> test.steps.flatMap { it.selfAndDescendants() } }
    }

    fun shared(): List<SharedPlan> = suites.flatMap { suite -> suite.tests.flatMap { it.shared } }

    fun isEmpty(): Boolean = suites.all { it.tests.isEmpty() }
}
