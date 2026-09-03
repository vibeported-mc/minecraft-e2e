package dev.vibeported.rpc.plugin

import dev.vibeported.rpc.plugin.fir.RpcFirExtensionRegistrar
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter

public class RpcCompilerPluginRegistrar : CompilerPluginRegistrar() {

    override val pluginId: String = RpcCommandLineProcessor.PLUGIN_ID

    override val supportsK2: Boolean = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        if (configuration.get(RpcCommandLineProcessor.KEY_ENABLED) == false) return

        FirExtensionRegistrarAdapter.registerExtension(RpcFirExtensionRegistrar())
    }
}
