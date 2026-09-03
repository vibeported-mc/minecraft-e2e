package dev.vibeported.rpc.plugin.ir

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
public class RpcIrGenerationExtension(
    private val manifestDir: String?,
    private val messages: MessageCollector,
) : IrGenerationExtension {

    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        val plans = moduleFragment.files
            .map { file -> RpcPlanner(file).plan() }
            .filterNot { it.isEmpty() }

        if (plans.isEmpty()) return

        ManifestWriter(manifestDir, moduleFragment.name.asString().trim('<', '>'), messages).write(plans)
    }
}
