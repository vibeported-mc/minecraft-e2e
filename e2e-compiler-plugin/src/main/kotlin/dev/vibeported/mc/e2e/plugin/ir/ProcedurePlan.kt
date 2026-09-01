package dev.vibeported.mc.e2e.plugin.ir

import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.types.IrType

internal enum class ProcedureRole { SERVER, CLIENT }

/**
 * One lifted block: the body of a `server`/`client` call, wherever in the file it was written.
 *
 * Anywhere at all, now. A block used to be a step in a declared test, so the planner only looked
 * inside `e2e { }`; it is an ordinary call today, so a helper function in a library is as legitimate
 * a place for one as a test is -- which is exactly what lets the gameplay DSL be built out of them.
 */
internal class ProcedurePlan(
    val id: String,
    val role: ProcedureRole,
    /** The client this addresses, when the compiler could work it out. Empty otherwise. */
    val client: String,
    /** The enclosing block, or null when this one was written straight into ordinary code. */
    val parent: ProcedurePlan?,
    /** The `server`/`client` call this body was an argument to. */
    val call: IrCall,
    val lambda: IrSimpleFunction,
    /** The declared types of the block's arguments, in order. */
    val argumentTypes: List<IrType>,
    /** What the block gives back. `Unit` when it gives back nothing. */
    val resultType: IrType,
) {
    val children: MutableList<ProcedurePlan> = mutableListOf()

    fun selfAndDescendants(): List<ProcedurePlan> = listOf(this) + children.flatMap { it.selfAndDescendants() }
}

internal class FilePlan(
    val file: IrFile,
    val facadeClass: String,
    val serverTableSimpleName: String,
    val clientTableSimpleName: String,
) {
    val roots: MutableList<ProcedurePlan> = mutableListOf()

    /** Every client this file names in a way the compiler could resolve. */
    val mentionedClients: MutableSet<String> = mutableSetOf()

    fun blocks(): List<ProcedurePlan> = roots.flatMap { it.selfAndDescendants() }

    fun blocks(role: ProcedureRole): List<ProcedurePlan> = blocks().filter { it.role == role }

    fun tableClass(role: ProcedureRole): String = qualify(
        when (role) {
            ProcedureRole.SERVER -> serverTableSimpleName
            ProcedureRole.CLIENT -> clientTableSimpleName
        }
    )

    fun clients(): Set<String> = mentionedClients.toSortedSet()

    fun isEmpty(): Boolean = roots.isEmpty()

    private fun qualify(simpleName: String): String =
        file.packageFqName.asString().let { if (it.isEmpty()) simpleName else "$it.$simpleName" }
}
