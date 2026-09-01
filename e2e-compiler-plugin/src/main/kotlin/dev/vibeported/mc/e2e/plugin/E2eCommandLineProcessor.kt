package dev.vibeported.mc.e2e.plugin

import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CliOptionProcessingException
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey

@OptIn(ExperimentalCompilerApi::class)
public class E2eCommandLineProcessor : CommandLineProcessor {

    override val pluginId: String = PLUGIN_ID

    override val pluginOptions: Collection<CliOption> = listOf(OPTION_INDEX_DIR, OPTION_ENABLED)

    override fun processOption(
        option: AbstractCliOption,
        value: String,
        configuration: CompilerConfiguration,
    ) {
        when (option.optionName) {
            OPTION_INDEX_DIR.optionName -> configuration.put(KEY_INDEX_DIR, value)
            OPTION_ENABLED.optionName -> configuration.put(KEY_ENABLED, value.toBooleanStrict())
            else -> throw CliOptionProcessingException("Unknown option: ${option.optionName}")
        }
    }

    public companion object {
        public const val PLUGIN_ID: String = "dev.vibeported.mc.e2e"

        public val KEY_INDEX_DIR: CompilerConfigurationKey<String> =
            CompilerConfigurationKey.create("e2e index output directory")
        public val KEY_ENABLED: CompilerConfigurationKey<Boolean> =
            CompilerConfigurationKey.create("e2e plugin enabled")

        public val OPTION_INDEX_DIR: CliOption = CliOption(
            optionName = "indexDir",
            valueDescription = "<path>",
            description = "Directory to write META-INF/e2e/index.json into; usually a generated resources dir.",
            required = false,
        )
        public val OPTION_ENABLED: CliOption = CliOption(
            optionName = "enabled",
            valueDescription = "<true|false>",
            description = "Turns the transformation off without removing the plugin from the classpath.",
            required = false,
        )
    }
}
