package dev.vibeported.rpc.plugin

import dev.vibeported.rpc.plugin.fir.RpcFirExtensionRegistrar
import dev.vibeported.rpc.plugin.ir.RpcIrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.messageCollector
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter

public class RpcCompilerPluginRegistrar : CompilerPluginRegistrar() {

    override val pluginId: String = RpcCommandLineProcessor.PLUGIN_ID

    override val supportsK2: Boolean = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        if (configuration.get(RpcCommandLineProcessor.KEY_ENABLED) == false) return

        // One index, created here and given to both halves, so it lives exactly as long as this
        // compilation. A shared object would outlive every compilation in a Gradle daemon and
        // let two modules read each other's roles.
        val roles = RoleIndex()

        FirExtensionRegistrarAdapter.registerExtension(RpcFirExtensionRegistrar(roles))
        IrGenerationExtension.registerExtension(
            RpcIrGenerationExtension(
                roles = roles,
                manifestDir = configuration.get(RpcCommandLineProcessor.KEY_MANIFEST_DIR),
                messages = configuration.messageCollector,
            )
        )
    }
}
