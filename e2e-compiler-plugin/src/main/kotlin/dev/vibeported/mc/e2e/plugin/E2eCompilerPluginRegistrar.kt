package dev.vibeported.mc.e2e.plugin

import dev.vibeported.mc.e2e.plugin.fir.E2eFirExtensionRegistrar
import dev.vibeported.mc.e2e.plugin.ir.E2eIrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.messageCollector
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter

@OptIn(ExperimentalCompilerApi::class)
public class E2eCompilerPluginRegistrar : CompilerPluginRegistrar() {

    override val pluginId: String = E2eCommandLineProcessor.PLUGIN_ID

    override val supportsK2: Boolean = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        if (configuration.get(E2eCommandLineProcessor.KEY_ENABLED) == false) return

        val messages: MessageCollector = configuration.messageCollector
        val indexDir = configuration.get(E2eCommandLineProcessor.KEY_INDEX_DIR)

        // The frontend half is what puts errors under the cursor in the IDE.
        FirExtensionRegistrarAdapter.registerExtension(E2eFirExtensionRegistrar())

        IrGenerationExtension.registerExtension(
            E2eIrGenerationExtension(indexDir = indexDir, messages = messages)
        )
    }
}
