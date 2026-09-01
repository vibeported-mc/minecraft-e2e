package dev.vibeported.mc.e2e.gradle

import net.neoforged.moddevgradle.dsl.NeoForgeExtension
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * The whole surface of a build that runs e2e tests.
 *
 * ```kotlin
 * mcE2E {
 *     neoForge {
 *         version = "26.2.0.69"
 *         mods { create("mymod") { sourceSet(sourceSets.main.get()) } }
 *     }
 *     modId = "mymod"
 * }
 * ```
 */
abstract class McE2eExtension @Inject constructor(private val project: Project) {

    /**
     * ModDevGradle's own extension, handed over unwrapped.
     *
     * Not a facade: it is the real `NeoForgeExtension`, so anything this plugin has not thought to
     * expose is still reachable, and nothing has to be kept in step with ModDevGradle as it changes.
     */
    fun neoForge(action: Action<NeoForgeExtension>) {
        action.execute(project.extensions.getByType(NeoForgeExtension::class.java))
    }

    /** The mod under test. Its id seeds the id of the generated test mod. */
    abstract val modId: Property<String>

    /** Overrides the generated test mod id, which is otherwise [modId] plus `_e2e`. */
    abstract val e2eModId: Property<String>

    /** Name of the source set the suites live in, and so of `src/<name>/kotlin`. */
    abstract val sourceSetName: Property<String>

    /** How many clients to launch. One for now; the ids already carry a client index. */
    abstract val clients: Property<Int>

    /** Where the client is told to join once it is up. */
    abstract val serverAddress: Property<String>

    abstract val reportDir: DirectoryProperty

    /** How long a game process may take to reach the orchestrator before the run gives up. */
    abstract val startupTimeoutSeconds: Property<Long>

    /** How long one block may run before the orchestrator stops waiting for it. */
    abstract val testTimeoutSeconds: Property<Long>

    /**
     * The Java version the game runs on. Minecraft 26.2 needs 25, and resolution fails outright on
     * anything older, so this is not a preference so much as a fact about the version.
     */
    abstract val javaVersion: Property<Int>

    /** Extra lines for the generated `server.properties`, or replacements for the defaults. */
    abstract val serverProperties: ListProperty<String>
}
