package dev.vibeported.mc.e2e.plugin

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.PluginOption
import com.tschuchort.compiletesting.SourceFile
import dev.vibeported.mc.e2e.protocol.E2eIndex
import kotlinx.serialization.json.Json
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import java.io.File

/**
 * Compiles a snippet with the plugin applied, exactly as a real module would be.
 *
 * Running the real compiler is the only way to test a compiler plugin honestly: the FIR checkers
 * have to see resolved calls, and the IR transform has to survive the backend all the way to
 * loadable classes.
 */
@OptIn(ExperimentalCompilerApi::class)
class E2eCompilation(private val workingDir: File) {

    val indexDir: File = File(workingDir, "e2e-index").apply { mkdirs() }

    fun compile(vararg files: Pair<String, String>): Result {
        val compilation = KotlinCompilation().apply {
            this.workingDir = this@E2eCompilation.workingDir
            sources = files.map { (name, code) -> SourceFile.kotlin(name, code) }
            compilerPluginRegistrars = listOf(E2eCompilerPluginRegistrar())
            commandLineProcessors = listOf(E2eCommandLineProcessor())
            pluginOptions = listOf(
                PluginOption(
                    pluginId = E2eCommandLineProcessor.PLUGIN_ID,
                    optionName = "indexDir",
                    optionValue = indexDir.absolutePath,
                )
            )
            inheritClassPath = true
            // Must match e2e-core, or inlining assertThat out of it is rejected.
            jvmTarget = "25"
            messageOutputStream = System.out
        }
        return Result(compilation.compile(), indexDir)
    }

    class Result(private val result: JvmCompilationResult, private val indexDir: File) {
        val exitCode: KotlinCompilation.ExitCode get() = result.exitCode
        val messages: String get() = result.messages
        val classLoader: ClassLoader get() = result.classLoader

        val succeeded: Boolean get() = exitCode == KotlinCompilation.ExitCode.OK

        /**
         * The index the plugin wrote, parsed through the very model `e2e-api` publishes.
         *
         * The plugin assembles that JSON by hand to avoid dragging a serialization library onto the
         * compiler classpath, so reading it back through [E2eIndex] here is what keeps the writer
         * and the reader from drifting apart.
         */
        fun index(): E2eIndex {
            val file = File(indexDir, E2eIndex.RESOURCE_PATH)
            check(file.isFile) { "The plugin wrote no index to ${file.absolutePath}" }
            return Json { ignoreUnknownKeys = true }.decodeFromString(E2eIndex.serializer(), file.readText())
        }
    }
}
