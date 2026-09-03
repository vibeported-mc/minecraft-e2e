package dev.vibeported.rpc.plugin.ir

import dev.vibeported.rpc.plugin.RoleIndex
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment

/**
 * The backend half: plans what each file contributes, then records it.
 *
 * Lifting the bodies into those tables comes next; the plan and the manifest are what everything
 * after depends on, and are worth having correct on their own first.
 */
internal class RpcIrGenerationExtension(
    private val roles: RoleIndex,
    private val manifestDir: String?,
    private val messages: MessageCollector,
    /** Types whose serializer comes from the wire format's module rather than from their class. */
    private val contextual: Set<String> = emptySet(),
) : IrGenerationExtension {

    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        val plans = moduleFragment.files
            .map { file -> RpcPlanner(file, roles).plan() }
            .filterNot { it.isEmpty() }

        if (plans.isEmpty()) return

        val symbols = RpcSymbols(pluginContext)
        val transformer = RpcTransformer(pluginContext, symbols, contextual)
        plans.forEach(transformer::transform)

        ManifestWriter(manifestDir, moduleFragment.name.asString().trim('<', '>'), messages).write(plans)
    }
}
