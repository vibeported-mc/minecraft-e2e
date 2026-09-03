package dev.vibeported.rpc.plugin

import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CliOptionProcessingException
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey

public class RpcCommandLineProcessor : CommandLineProcessor {

    override val pluginId: String = PLUGIN_ID

    override val pluginOptions: Collection<CliOption> =
        listOf(OPTION_MANIFEST_DIR, OPTION_ENABLED, OPTION_CONTEXTUAL)

    override fun processOption(
        option: AbstractCliOption,
        value: String,
        configuration: CompilerConfiguration,
    ) {
        when (option.optionName) {
            OPTION_MANIFEST_DIR.optionName -> configuration.put(KEY_MANIFEST_DIR, value)
            OPTION_ENABLED.optionName -> configuration.put(KEY_ENABLED, value.toBooleanStrict())
            OPTION_CONTEXTUAL.optionName -> configuration.put(
                KEY_CONTEXTUAL,
                value.split(',').map(String::trim).filter(String::isNotEmpty).toSet(),
            )

            else -> throw CliOptionProcessingException("Unknown option: ${option.optionName}")
        }
    }

    public companion object {
        public const val PLUGIN_ID: String = "dev.vibeported.rpc"

        public val KEY_MANIFEST_DIR: CompilerConfigurationKey<String> =
            CompilerConfigurationKey.create("rpc manifest output directory")
        public val KEY_ENABLED: CompilerConfigurationKey<Boolean> =
            CompilerConfigurationKey.create("rpc plugin enabled")
        public val KEY_CONTEXTUAL: CompilerConfigurationKey<Set<String>> =
            CompilerConfigurationKey.create("rpc contextually serialized types")

        public val OPTION_MANIFEST_DIR: CliOption = CliOption(
            optionName = "manifestDir",
            valueDescription = "<path>",
            description = "Directory to write META-INF/rpc/procedures.json into.",
            required = false,
        )
        public val OPTION_ENABLED: CliOption = CliOption(
            optionName = "enabled",
            valueDescription = "<true|false>",
            description = "Turns the transformation off without removing the plugin from the classpath.",
            required = false,
        )

        /**
         * Types serialized against the format's `SerializersModule` rather than by their own class.
         *
         * Listed here rather than annotated at the call site because a body's parameter types are
         * *inferred* -- `server(pos) { p -> }` names no type at all -- so for a type nobody owns,
         * such as one from a game, there is nowhere to write `@Contextual`. A build that supplies
         * the serializers names the types in the same place.
         */
        public val OPTION_CONTEXTUAL: CliOption = CliOption(
            optionName = "contextual",
            valueDescription = "<fqName,fqName,...>",
            description = "Types whose serializer is resolved from the wire format's SerializersModule.",
            required = false,
        )
    }
}
