package dev.vibeported.rpc.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.TaskProvider
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
        val compilerPlugin = project.configurations.create(COMPILER_PLUGIN) {
            it.isCanBeConsumed = false
            it.isCanBeResolved = true
            it.description = "The RPC compiler plugin, added to every Kotlin compilation in this project."
        }

        // Only once Kotlin is there to be wired into. Applying this plugin first is the ordinary
        // way a build script reads, and it should not be the reason a build fails.
        project.plugins.withId("org.jetbrains.kotlin.jvm") {
            project.extensions.getByType(SourceSetContainer::class.java).configureEach { sourceSet ->
                wire(project, sourceSet, compilerPlugin)
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
            // A manifest describes a *module*; a compiler plugin sees only the round it runs in.
            // Those are the same thing exactly when the round is the whole module, and incremental
            // compilation is precisely the arrangement in which they are not: a round that
            // recompiles one file rewrites the manifest with that file's procedures and nothing
            // else. Measured, not feared -- thirteen entries became one after touching an unrelated
            // file, and the jar that came out was quietly missing twelve procedures no node could
            // then find. The frontend goes wrong the same way, refusing a type whose
            // `@RpcSerializer` lives in a file this round did not compile.
            //
            // Reconstructing whole-module state from partial rounds is possible -- attribute every
            // entry to a source file, merge, drop entries whose file is gone -- and it is a great
            // deal of machinery whose failures look exactly like the one above. Compiling the
            // module is cheaper and cannot be subtly wrong.
            task.incremental = false

            task.outputs.dir(manifestDir)
            task.compilerOptions.freeCompilerArgs.addAll(
                project.provider {
                    listOf(
                        "-P", "plugin:$PLUGIN_ID:manifestDir=${manifestDir.get().asFile.absolutePath}",
                    )
                }
            )
        }

        // The manifest is written by the compiler, so packaging resources has to wait for it.
        project.tasks.named(sourceSet.processResourcesTaskName).configure { it.dependsOn(compile) }

        publishToClassesVariant(project, sourceSet, manifestDir, compile)
    }

    /**
     * Puts the manifest on the compile classpath of everything downstream.
     *
     * A resource directory is enough to get it into the jar, and for a while that looked like the
     * whole job -- until a consumer was handed this project's *class directories* instead of its
     * jar, which is what Gradle's compile avoidance does and what a ModDevGradle project gets. Those
     * directories hold no resources, so the plugin compiling downstream saw a dependency that had
     * declared nothing, and refused a type it had a serializer for all along.
     *
     * So the manifest is published as another artifact of that same `classes` variant. Consumers
     * taking the jar are unaffected; consumers taking directories now get one more.
     */
    private fun publishToClassesVariant(
        project: Project,
        sourceSet: SourceSet,
        manifestDir: Provider<Directory>,
        compile: TaskProvider<KotlinCompile>,
    ) {
        // `apiElements` exists for the main source set alone, which is also the only one anything
        // can compile against.
        if (sourceSet.name != SourceSet.MAIN_SOURCE_SET_NAME) return

        project.configurations.named(sourceSet.apiElementsConfigurationName).configure { elements ->
            elements.outgoing.variants.findByName(CLASSES)?.artifact(manifestDir) { artifact ->
                artifact.type = ArtifactTypeDefinition.JVM_CLASS_DIRECTORY
                artifact.builtBy(compile)
            }
        }
    }

    private companion object {
        /** Where a consuming build says which compiler plugin to use. */
        const val COMPILER_PLUGIN = "rpcCompilerPlugin"

        /** Gradle's name for the secondary variant carrying class directories rather than a jar. */
        const val CLASSES = "classes"

        /** Matches `RpcCommandLineProcessor.PLUGIN_ID`, which is what the compiler keys options by. */
        const val PLUGIN_ID = "dev.vibeported.rpc"

        const val SERIALIZATION = "org.jetbrains.kotlin.plugin.serialization"
    }
}
