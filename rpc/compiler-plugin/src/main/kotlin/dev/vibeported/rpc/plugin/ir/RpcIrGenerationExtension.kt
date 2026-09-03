package dev.vibeported.rpc.plugin.ir

import dev.vibeported.rpc.plugin.RoleIndex
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.name.FqName

/**
 * The backend half: finds the calls the frontend approved, and will lift their bodies.
 *
 * For now it only proves it can find them again, and that the role the frontend recorded can be
 * recovered here. That is the one thing lifting cannot be built without, because the annotation
 * carrying the role is gone by this point.
 */
public class RpcIrGenerationExtension : IrGenerationExtension {

    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        moduleFragment.files.forEach { file -> visit(file) }
    }

    private fun visit(file: IrFile) {
        file.acceptChildrenVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)

            override fun visitCall(expression: IrCall) {
                if (expression.symbol.owner.hasAnnotation(ENTRY_POINT)) {
                    record(file, expression)
                }
                expression.acceptChildrenVoid(this)
            }
        })
    }

    private fun record(file: IrFile, call: IrCall) {
        val lambda = (0 until call.arguments.size)
            .mapNotNull { call.arguments[it] as? IrFunctionExpression }
            .lastOrNull()

        Seen.calls += buildString {
            append(call.symbol.owner.name.asString())
            append(" callAt=").append(call.startOffset)
            append(" lambdaAt=").append(lambda?.startOffset ?: -1)
            append(" seen=").append(RoleIndex.wasSeen(file.fileEntry.name, call.startOffset))
            append(" role=").append(RoleIndex.roleAt(file.fileEntry.name, call.startOffset) ?: "-")
        }
    }

    /** What the backend found, for a test to read while the mechanism is being settled. */
    public object Seen {
        public val calls: MutableList<String> = mutableListOf()
        public fun reset(): Unit = calls.clear()
    }

    private companion object {
        private val ENTRY_POINT = FqName("dev.vibeported.rpc.RpcEntryPoint")
    }
}
