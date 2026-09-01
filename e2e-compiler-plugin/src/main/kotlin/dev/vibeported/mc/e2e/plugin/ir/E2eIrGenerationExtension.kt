package dev.vibeported.mc.e2e.plugin.ir

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment

public class E2eIrGenerationExtension(
    private val indexDir: String?,
    private val messages: MessageCollector,
) : IrGenerationExtension {

    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        val plans = moduleFragment.files.mapNotNull { file ->
            val plan = E2ePlanner(file, messages).plan()
            plan.takeUnless { it.isEmpty() }
        }
        if (plans.isEmpty()) return

        val symbols = E2eSymbols(pluginContext)
        val transformer = E2eTransformer(pluginContext, symbols)
        plans.forEach(transformer::transform)

        E2eIndexWriter(indexDir, messages).write(plans)
    }
}
