package dev.vibeported.rpc.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

/**
 * Wires the RPC compiler plugin into a build, and packages what it emits.
 *
 * Two jobs, and the second is the one that is easy to forget. Lifting a body into a table is no use
 * if nothing can find the table afterwards: the plugin writes a names-only manifest naming every
 * procedure and the class holding it, and that file has to end up in the jar beside the classes. A
 * module compiled with the compiler plugin but without this one produces tables nobody looks up,
 * and the failure arrives much later as a procedure that does not exist.
 *
 * Applied per source set rather than to the project, because a manifest describes a compilation.
 * Tests are compilations too, and a body written in one is as real as any other.
 */
public class RpcGradlePlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val settings = project.extensions.create("rpc", RpcExtension::class.java)

        val compilerPlugin = project.configurations.create(COMPILER_PLUGIN) {
            it.isCanBeConsumed = false
            it.isCanBeResolved = true
            it.description = "The RPC compiler plugin, added to every Kotlin compilation in this project."
        }

        // Only once Kotlin is there to be wired into. Applying this plugin first is the ordinary
        // way a build script reads, and it should not be the reason a build fails.
        project.plugins.withId("org.jetbrains.kotlin.jvm") {
            project.extensions.getByType(SourceSetContainer::class.java).configureEach { sourceSet ->
                wire(project, sourceSet, compilerPlugin, settings)
            }
        }

        project.afterEvaluate { evaluated ->
            require(evaluated.plugins.hasPlugin(SERIALIZATION)) {
                // Not applied from here on purpose. Doing so would need kotlin-serialization on this
                // plugin's runtime classpath, and an included build exports that onto the consuming
                // script's plugin classpath -- a second copy of a Kotlin subplugin, which is the
                // exact shape of the collision that broke IDE import for its neighbour. Asking is
                // one line in a build script; the failure it prevents is a serializer missing at
                // run time, which is the class of mistake this whole framework exists to move
                // forward to compile time.
                "The RPC plugin needs kotlinx.serialization, which encodes everything a procedure " +
                    "sends. Add `id(\"$SERIALIZATION\")` to the plugins block of " +
                    "${evaluated.path}. Without it a @Serializable argument compiles and then " +
                    "fails at the first call saying its serializer is not found."
            }
        }
    }

    private fun wire(
        project: Project,
        sourceSet: SourceSet,
        compilerPlugin: Configuration,
        settings: RpcExtension,
    ) {
        val compileTaskName = sourceSet.getCompileTaskName("kotlin")
        if (project.tasks.findByName(compileTaskName) == null) return

        // Through Kotlin's own configuration rather than as a `-Xplugin=` string in freeCompilerArgs.
        // Both reach the compiler, but only this one is part of the model an IDE imports: a raw
        // argument is an opaque string it never parses into a plugin, so the checkers never run in
        // the editor and a captured local shows up only at build time.
        val classpath = "kotlinCompilerPluginClasspath" +
            sourceSet.name.replaceFirstChar { it.uppercase() }
        project.configurations.findByName(classpath)?.extendsFrom(compilerPlugin)

        // One directory per compilation, and a resource root as well as a task output. The first is
        // what puts the manifest in the jar; the second is what makes a stale one get rebuilt.
        val manifestDir = project.layout.buildDirectory.dir("generated/rpc/${sourceSet.name}")
        sourceSet.resources.srcDir(manifestDir)

        val compile = project.tasks.named(compileTaskName, KotlinCompile::class.java)
        compile.configure { task ->
            task.outputs.dir(manifestDir)
            task.compilerOptions.freeCompilerArgs.addAll(
                project.provider {
                    val options = mutableListOf(
                        "-P", "plugin:$PLUGIN_ID:manifestDir=${manifestDir.get().asFile.absolutePath}",
                    )
                    // One `-P` per type, never a comma-joined list. A comma inside a `-P` value is
                    // how the Kotlin CLI separates one plugin option from the next, so a joined
                    // list arrives as a second option with no `plugin:` prefix -- and the compiler
                    // says only "Wrong plugin option format", naming neither the option nor us.
                    settings.contextual.get().forEach { type ->
                        options += "-P"
                        options += "plugin:$PLUGIN_ID:contextual=$type"
                    }
                    options
                }
            )
        }

        // The manifest is written by the compiler, so packaging resources has to wait for it.
        project.tasks.named(sourceSet.processResourcesTaskName).configure { it.dependsOn(compile) }
    }

    private companion object {
        /** Where a consuming build says which compiler plugin to use. */
        const val COMPILER_PLUGIN = "rpcCompilerPlugin"

        /** Matches `RpcCommandLineProcessor.PLUGIN_ID`, which is what the compiler keys options by. */
        const val PLUGIN_ID = "dev.vibeported.rpc"

        const val SERIALIZATION = "org.jetbrains.kotlin.plugin.serialization"
    }
}
