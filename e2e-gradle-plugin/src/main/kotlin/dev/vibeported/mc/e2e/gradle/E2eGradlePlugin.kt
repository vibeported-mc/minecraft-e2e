package dev.vibeported.mc.e2e.gradle

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.language.jvm.tasks.ProcessResources
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption

/**
 * Applies the e2e compiler plugin to a module and wires up where its test index is written.
 *
 * The index has to end up on the runtime classpath, because it is how the orchestrator discovers
 * tests. That means it is a generated resource, produced by compilation, which is why packaging
 * has to be told to wait for the compiler.
 */
class E2eGradlePlugin : KotlinCompilerPluginSupportPlugin {

    override fun apply(target: Project) {
        super.apply(target)

        val indexDir = target.layout.buildDirectory.dir(INDEX_DIR)

        target.plugins.withId("org.jetbrains.kotlin.jvm") {
            target.extensions.getByType(SourceSetContainer::class.java)
                .getByName("main")
                .resources
                .srcDir(indexDir)

            target.tasks.withType(ProcessResources::class.java).configureEach {
                it.dependsOn(target.tasks.named("compileKotlin"))
            }
        }

        target.dependencies.add("implementation", "$GROUP:e2e-api:$VERSION")
    }

    override fun getCompilerPluginId(): String = "dev.vibeported.mc.e2e"

    override fun getPluginArtifact(): SubpluginArtifact =
        SubpluginArtifact(groupId = GROUP, artifactId = "e2e-compiler-plugin", version = VERSION)

    override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean =
        kotlinCompilation.target.project.plugins.hasPlugin(E2eGradlePlugin::class.java)

    override fun applyToCompilation(
        kotlinCompilation: KotlinCompilation<*>,
    ): Provider<List<SubpluginOption>> {
        val project = kotlinCompilation.target.project
        val indexDir = project.layout.buildDirectory.dir(INDEX_DIR)
        return project.provider {
            listOf(SubpluginOption(key = "indexDir", value = indexDir.get().asFile.absolutePath))
        }
    }

    private companion object {
        const val INDEX_DIR = "generated/e2e-index"

        /** Written by the build, so these can never drift from the artifacts actually published. */
        private val coordinates: java.util.Properties = java.util.Properties().apply {
            E2eGradlePlugin::class.java
                .getResourceAsStream("coordinates.properties")
                ?.use { load(it) }
                ?: error("e2e-gradle-plugin was built without its coordinates.properties resource")
        }

        val GROUP: String = coordinates.getProperty("group")
        val VERSION: String = coordinates.getProperty("version")
    }
}
