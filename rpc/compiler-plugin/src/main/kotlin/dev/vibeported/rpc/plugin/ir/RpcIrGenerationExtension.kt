package dev.vibeported.rpc.plugin.ir

import dev.vibeported.rpc.plugin.RoleIndex
import dev.vibeported.rpc.plugin.SerializerIndex
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
    /** Types something wrote a serializer for. @see dev.vibeported.rpc.RpcSerializer */
    private val serializers: SerializerIndex = SerializerIndex(),
) : IrGenerationExtension {

    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        SerializerScan.contribute(moduleFragment, serializers)

        val plans = moduleFragment.files
            .map { file -> RpcPlanner(file, roles).plan() }
            .filterNot { it.isEmpty() }

        val symbols = RpcSymbols(pluginContext)
        val transformer = RpcTransformer(pluginContext, symbols, serializers)
        plans.forEach(transformer::transform)

        // Not conditional on there being procedures: a module can exist to declare serializers for
        // the modules that do, and its manifest is the only way they hear about them.
        ManifestWriter(manifestDir, moduleFragment.name.asString().trim('<', '>'), messages)
            .write(plans, serializers.declarations())
    }
}
