package dev.vibeported.rpc.plugin.ir

import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.types.IrType

/**
 * One body to be lifted: what it is called, where it goes, and what crosses the wire.
 *
 * [role] is null for a body every node loads. That is not the same as "no role was written" -- a
 * file with no annotation and a call with none genuinely does belong in the table everybody loads,
 * so the absence is the answer rather than a missing one.
 */
internal class ProcedurePlan(
    val id: String,
    val role: String?,
    val call: IrCall,
    val lambda: IrSimpleFunction,
    /** Declared argument types, in order. Empty for a body that takes none. */
    val argumentTypes: List<IrType>,
    /** What the body gives back; `Unit` when it gives back nothing. */
    val resultType: IrType,
)

/**
 * Everything one source file contributes.
 *
 * Grouped by role, because that is what decides which class each body lands in -- and a class is
 * the unit a dist-cleaned node can or cannot load.
 */
internal class FilePlan(val file: IrFile, val facade: String) {

    val procedures: MutableList<ProcedurePlan> = mutableListOf()

    fun isEmpty(): Boolean = procedures.isEmpty()

    /** Every distinct role in this file, the default one included as null. */
    fun roles(): List<String?> = procedures.map { it.role }.distinct()

    fun proceduresFor(role: String?): List<ProcedurePlan> = procedures.filter { it.role == role }

    /**
     * `SampleKt_Rpc` for the bodies every node loads, `SampleKt_Rpc_client` for a role.
     *
     * Named after the file so that a stack trace or a `NoClassDefFoundError` says where to look,
     * and suffixed by role so that every body sharing a role shares one class -- which is the whole
     * point, since the class is what a node can or cannot resolve.
     */
    fun tableSimpleName(role: String?): String =
        if (role == null) "${facade}_Rpc" else "${facade}_Rpc_$role"

    fun tableClass(role: String?): String {
        val packageName = file.packageFqName.asString()
        val simple = tableSimpleName(role)
        return if (packageName.isEmpty()) simple else "$packageName.$simple"
    }
}
