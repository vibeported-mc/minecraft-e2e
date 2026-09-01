package dev.vibeported.mc.e2e.plugin.ir

import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrLocalDelegatedProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.types.IrType

internal enum class BlockRole { ORCHESTRATOR, SERVER, CLIENT }

/**
 * One `var x by shared<T>()`.
 *
 * [property] is deleted from the driver body during the transform; [id] is what the rewritten reads
 * and writes carry instead.
 */
internal class SharedPlan(
    val id: String,
    val name: String,
    val property: IrLocalDelegatedProperty,
    val type: IrType,
)

/**
 * One lifted block: a test driver, or a `server`/`client` body at any nesting depth.
 *
 * [lambda] is the function the frontend already built for the lambda literal. The transform moves
 * that very function into the generated table rather than copying its body, which keeps every
 * symbol inside it valid.
 */
internal class BlockPlan(
    val id: String,
    val role: BlockRole,
    val clientIndex: Int,
    val parent: BlockPlan?,
    val testId: String,
    /** The `e2e`/`server`/`client` call this body was an argument to. */
    val call: IrCall,
    val lambda: IrSimpleFunction,
) {
    val children: MutableList<BlockPlan> = mutableListOf()

    /**
     * What a child block prefixes its id with. A driver contributes the test id rather than its own,
     * so the common case reads as ".../block moved/server[0]" with no "/driver" segment in the way.
     */
    val pathPrefix: String get() = if (role == BlockRole.ORCHESTRATOR) testId else id

    fun selfAndDescendants(): List<BlockPlan> = listOf(this) + children.flatMap { it.selfAndDescendants() }
}

internal class TestPlan(
    val id: String,
    val name: String,
    val call: IrCall,
    val driver: BlockPlan,
) {
    val shared: MutableList<SharedPlan> = mutableListOf()
}

internal class SuitePlan(
    val id: String,
    val name: String,
    val call: IrCall,
    /** JVM getter on the file facade, so the orchestrator can recover this suite by reflection. */
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

    fun blocks(): List<BlockPlan> =
        suites.flatMap { suite -> suite.tests.flatMap { it.driver.selfAndDescendants() } }

    fun shared(): List<SharedPlan> = suites.flatMap { suite -> suite.tests.flatMap { it.shared } }

    fun isEmpty(): Boolean = suites.all { it.tests.isEmpty() }
}
