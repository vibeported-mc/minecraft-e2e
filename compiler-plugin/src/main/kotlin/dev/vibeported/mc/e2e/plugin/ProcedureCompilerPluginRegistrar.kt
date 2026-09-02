package dev.vibeported.mc.e2e.plugin

import dev.vibeported.mc.e2e.plugin.fir.ProcedureFirExtensionRegistrar
import dev.vibeported.mc.e2e.plugin.ir.ProcedureIrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.messageCollector
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter

@OptIn(ExperimentalCompilerApi::class)
public class ProcedureCompilerPluginRegistrar : CompilerPluginRegistrar() {

    override val pluginId: String = ProcedureCommandLineProcessor.PLUGIN_ID

    override val supportsK2: Boolean = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        if (configuration.get(ProcedureCommandLineProcessor.KEY_ENABLED) == false) return

        val messages: MessageCollector = configuration.messageCollector
        val indexDir = configuration.get(ProcedureCommandLineProcessor.KEY_INDEX_DIR)

        // The frontend half is what puts errors under the cursor in the IDE.
        FirExtensionRegistrarAdapter.registerExtension(ProcedureFirExtensionRegistrar())

        IrGenerationExtension.registerExtension(
            ProcedureIrGenerationExtension(indexDir = indexDir, messages = messages)
        )
    }
}
