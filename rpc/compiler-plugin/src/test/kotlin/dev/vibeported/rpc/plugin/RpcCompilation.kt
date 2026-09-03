package dev.vibeported.rpc.plugin

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.PluginOption
import com.tschuchort.compiletesting.SourceFile
import java.io.File

/**
 * Compiles a snippet with the plugin applied, exactly as a real module would be.
 *
 * Running the real compiler is the only honest way to test one: the checkers have to see resolved
 * calls, and anything the backend generates has to survive all the way to loadable classes.
 */
class RpcCompilation(private val workingDir: File) {

    val manifestDir: File = File(workingDir, "rpc-manifest").apply { mkdirs() }

    /** Types this compilation promises a serializer for. @see RpcCommandLineProcessor.OPTION_CONTEXTUAL */
    var contextual: List<String> = emptyList()

    fun compile(vararg files: Pair<String, String>): Result {
        val compilation = KotlinCompilation().apply {
            this.workingDir = this@RpcCompilation.workingDir
            sources = files.map { (name, code) -> SourceFile.kotlin(name, code) }
            compilerPluginRegistrars = listOf(RpcCompilerPluginRegistrar())
            commandLineProcessors = listOf(RpcCommandLineProcessor())
            pluginOptions = listOfNotNull(
                PluginOption(
                    pluginId = RpcCommandLineProcessor.PLUGIN_ID,
                    optionName = "manifestDir",
                    optionValue = manifestDir.absolutePath,
                ),
                contextual.takeIf { it.isNotEmpty() }?.let {
                    PluginOption(
                        pluginId = RpcCommandLineProcessor.PLUGIN_ID,
                        optionName = "contextual",
                        optionValue = it.joinToString(","),
                    )
                },
            )
            inheritClassPath = true
            messageOutputStream = System.out
        }
        return Result(compilation.compile())
    }

    class Result(private val result: JvmCompilationResult) {
        val messages: String get() = result.messages
        val succeeded: Boolean get() = result.exitCode == KotlinCompilation.ExitCode.OK
        val classLoader: ClassLoader get() = result.classLoader
    }
}
