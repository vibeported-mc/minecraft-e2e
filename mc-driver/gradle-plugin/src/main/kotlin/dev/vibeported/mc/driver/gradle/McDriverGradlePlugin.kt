package dev.vibeported.mc.driver.gradle

import net.neoforged.moddevgradle.dsl.NeoForgeExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.testing.Test
import java.io.File

/**
 * Two game runs, a task that reads them, and the wiring that hangs off both.
 *
 * The `driverServer` and `driverClient` runs exist to be **harvested rather than started**: a
 * Minecraft command line cannot be reconstructed by hand, so `harvestDriverLaunchPlan` reads them
 * and writes down how ModDevGradle would have launched one. The driver replays that, as many clients
 * as it likes, each with its own username and game directory.
 *
 * Everything else here is a dependency between tasks. It declares nothing on the build's behalf: the
 * mod, and whether there are unit tests at all, are the build's own statements in `neoForge { }`, and
 * this reads them.
 *
 * ```kotlin
 * neoForge {
 *     version = "…"
 *     mods { create("example") { sourceSet(sourceSets.main.get()) } }
 *     unitTest { enable(); testedMod = mods.getByName("example") }
 *
 *     mcDriver { tileWindows = true }   // optional; every setting has a default
 * }
 * ```
 *
 * It does not wrap ModDevGradle. The consuming build applies MDG itself and this configures it, so a
 * version bump there cannot silently change what a build is allowed to say, and there is no
 * passthrough here to keep in step.
 */
public class McDriverGradlePlugin : Plugin<Project> {

    override fun apply(project: Project) {
        // Applied by the consuming build, not from here, and deliberately so. This plugin lives in
        // an included build, so applying ModDevGradle from it would load a second copy beside the
        // one every other module uses -- and two copies both apply `gradle-idea-ext` to the root
        // project, colliding on the `settings` extension during an IDE import with an error that
        // names neither plugin.
        project.afterEvaluate {
            require(it.plugins.hasPlugin(MODDEV)) {
                "The driver plugin configures ModDevGradle rather than applying it, so a build " +
                    "using it needs `id(\"$MODDEV\")` in its own plugins block."
            }
        }

        project.plugins.withId(MODDEV) {
            val neoForge = project.extensions.getByType(NeoForgeExtension::class.java)

            // Nested inside `neoForge`, because that is what it configures. An extension created
            // through `ExtensionContainer.create` is decorated and so is itself `ExtensionAware`,
            // which is what makes a block inside a block possible at all.
            val settings = (neoForge as ExtensionAware).extensions
                .create("mcDriver", McDriverExtension::class.java).apply {
                    serverAddress.convention("localhost:25565")
                    clientWidth.convention(1280)
                    clientHeight.convention(720)
                    tileWindows.convention(false)
                    captureDir.convention(project.layout.buildDirectory.dir("mcdriver"))
                }

            // The mods, the runs and whether testing is on are only settled once the build script
            // has run.
            project.afterEvaluate { configure(it, neoForge, settings) }
        }
    }

    private fun configure(project: Project, neoForge: NeoForgeExtension, settings: McDriverExtension) {
        // Applied by the consuming build, not from here, and deliberately so. This plugin lives in
        // an included build, so applying ModDevGradle from it would load a second copy beside the
        // one every other module uses -- and two copies both apply `gradle-idea-ext` to the root
        // project, colliding on the `settings` extension during an IDE import with an error that
        // names neither plugin.
        require(project.plugins.hasPlugin("net.neoforged.moddev")) {
            "The driver plugin configures ModDevGradle rather than applying it, so a build using " +
                "it needs `id(\"net.neoforged.moddev\")` in its own plugins block."
        }

        val sourceSets = project.extensions.getByType(SourceSetContainer::class.java)

        // Whether the build turned ModDevGradle's unit testing on. `configureTesting` registers this
        // task and nothing else does, so asking for it answers the question exactly -- and asking is
        // the point: the driver does not enable testing on a build's behalf, it notices.
        val testing = project.tasks.findByName(MDG_PREPARE_TEST) != null

        // The games are launched from the *test* classpath when there is one, and that is not an
        // accident. A body written inside a test -- `server { ... }` in an `@Test` method -- is
        // lifted into a table compiled with the tests, and the game asked to run it has to be able
        // to resolve that table. Launch from `main` and every such call comes back as "no procedure
        // ... on any classpath here", which is true and says nothing about why. The test classpath
        // contains main's, so nothing is lost by taking the wider one when it is modded at all.
        val runSources = sourceSets.getByName(
            if (testing) SourceSet.TEST_SOURCE_SET_NAME else SourceSet.MAIN_SOURCE_SET_NAME
        )

        val planFile = project.layout.buildDirectory.file("mcdriver/launch-plan.json")
        val captureDir = settings.captureDir.get().asFile
        val serverRunDir = project.layout.projectDirectory.dir("run/driverServer")
        val clientRunDir = project.layout.projectDirectory.dir("run/driverClient")

        neoForge.runs.create(SERVER_RUN) { run ->
            run.server()
            run.sourceSet.set(runSources)
            run.gameDirectory.set(serverRunDir)
            run.programArgument("--nogui")
        }

        neoForge.runs.create(CLIENT_RUN) { run ->
            run.client()
            run.sourceSet.set(runSources)
            run.gameDirectory.set(clientRunDir)
            // Vanilla joins the address on its own, which spares reaching into ConnectScreen.
            run.programArguments.addAll("--quickPlayMultiplayer", settings.serverAddress.get())
        }

        if (testing) configureTesting(project, neoForge, sourceSets, planFile, captureDir)

        val harvest = registerHarvest(project, settings, runSources, planFile)
        val seed = registerSeed(project, settings, serverRunDir)

        if (testing) project.tasks.withType(Test::class.java).configureEach { it.dependsOn(harvest, seed) }
    }

    /**
     * Hangs the driver off ModDevGradle's JUnit environment, when a build asked for one.
     *
     * Almost none of this is ours. `unitTest { enable() }` -- which the *build* writes, not this --
     * puts NeoForge's `junit-fml` on the test runtime classpath, and that is a
     * `LauncherSessionListener` which boots FancyModLoader and swaps the thread context classloader
     * to the transforming one *before* JUnit discovers anything. So the test classes, and every
     * Minecraft class they name, resolve exactly as the game's do.
     *
     * What is added here is the two things a driver needs on top: the test output being part of the
     * mod the *games* load, and knowing where the launch plan and the captures are.
     */
    private fun configureTesting(
        project: Project,
        neoForge: NeoForgeExtension,
        sourceSets: SourceSetContainer,
        planFile: Provider<RegularFile>,
        captureDir: File,
    ) {
        // ModDevGradle does not enforce this, and the failure when it is missing is baffling: with no
        // tested mod the test output never reaches `-Dfml.modFolders`, the test classes load on the
        // Gradle worker's classloader instead of the transforming one, and every Minecraft type they
        // name is a second copy of itself. What that reads like is a class not matching a type it
        // plainly is. Said plainly here instead.
        val tested = neoForge.unitTest.testedMod.orNull ?: error(
            "neoForge { unitTest { testedMod = ... } } says which mod the tests belong to, and the " +
                "driver needs it as much as ModDevGradle does. Without it, test classes load " +
                "outside FancyModLoader's class loader and every Minecraft type they name is a " +
                "second copy of itself."
        )

        // And the test output becomes part of that mod for the game runs as well. ModDevGradle adds
        // it for the JUnit run alone, which leaves the server and the client holding a jar whose
        // procedure tables they cannot see.
        sourceSets.findByName(SourceSet.TEST_SOURCE_SET_NAME)?.let { tests -> tested.sourceSet(tests) }

        project.tasks.withType(Test::class.java).configureEach { task ->
            task.systemProperty("mcdriver.launch.plan", planFile.get().asFile.absolutePath)
            task.systemProperty("mcdriver.capture.dir", captureDir.absolutePath)

            // Gradle turns assertions on for a test JVM; Minecraft has never run with them on, and
            // neither has any mod. This JVM hosts the game rather than testing arithmetic, so it
            // gets the JVM the game expects.
            //
            // Not a theoretical tidiness. With `-ea`, Create fails to load: Catnip writes
            // `new BlockState(Blocks.AIR, null, null)` as an interface static, and vanilla's
            // `StateHolder` constructor opens with `assert propertyKeys.length == ...`. Assertions
            // off, that null is never read and the sentinel is fine. Assertions on, a mod that
            // works everywhere else dies during registration, and what you get is
            // `NullPointerException: Cannot read the array length because "propertyKeys" is null`
            // out of a static initialiser, which says nothing at all about `-ea`.
            task.enableAssertions = false
        }
    }

    private fun registerHarvest(
        project: Project,
        settings: McDriverExtension,
        runSources: SourceSet,
        planFile: Provider<RegularFile>,
    ): TaskProvider<HarvestLaunchPlanTask> =
        project.tasks.register(HARVEST, HarvestLaunchPlanTask::class.java) { task ->
            task.group = GROUP
            task.description = "Records how ModDevGradle would launch the driver's client and server."
            task.serverRunTask.set(runTaskName(SERVER_RUN))
            task.clientRunTask.set(runTaskName(CLIENT_RUN))
            task.serverAddress.set(settings.serverAddress)
            task.clientWidth.set(settings.clientWidth)
            task.clientHeight.set(settings.clientHeight)
            task.tileWindows.set(settings.tileWindows)
            task.captureDir.set(settings.captureDir)
            task.planFile.set(planFile)

            // The games run off the runtime classpath, and a project on it is a jar somebody has to
            // build. Compiling only needs the class directories, so without this a run happily
            // launches against whatever jar was lying there from last time.
            task.dependsOn(runSources.runtimeClasspath)
            task.dependsOn(project.tasks.named(runSources.processResourcesTaskName))
            task.dependsOn(project.tasks.matching { it.name.startsWith("prepare") && it.name.endsWith("Run") })
        }

    private fun registerSeed(
        project: Project,
        settings: McDriverExtension,
        serverRunDir: Directory,
    ): TaskProvider<*> =
        project.tasks.register(SEED) { task ->
            task.group = GROUP
            task.description = "Writes the settings an unattended server needs, and clears its world."
            task.outputs.dir(serverRunDir)
            // It deletes a world every time it runs, so being skipped as up to date would leave the
            // previous run's world in place -- the exact thing it exists to prevent.
            task.outputs.upToDateWhen { false }
            val extra = settings.serverProperties.getOrElse(emptyList())
            task.doLast { seedServer(serverRunDir.asFile, extra) }
        }

    /**
     * The settings an unattended server needs, and a world that is not last run's.
     *
     * A world left over remembers where every player stood, what they were holding, and whether they
     * were alive -- and a player who died in the previous run comes back dead, so anything waiting
     * for them to be up and about waits for someone who is never going to get up.
     */
    private fun seedServer(dir: File, extra: List<String>) {
        dir.mkdirs()
        File(dir, "eula.txt").writeText("eula=true" + System.lineSeparator())
        File(dir, LEVEL_NAME).deleteRecursively()

        val defaults = listOf(
            "online-mode=false",
            "level-name=$LEVEL_NAME",
            "level-type=minecraft\\:flat",
            "generate-structures=false",
            "spawn-npcs=false",
            "spawn-animals=false",
            "spawn-monsters=false",
            "spawn-protection=0",
            "allow-nether=false",
            "max-players=4",
            "view-distance=8",
            "server-port=25565",
            "sync-chunk-writes=false",
            "motd=mcdriver",
        )
        val overridden = extra.map { it.substringBefore('=') }.filter { it.isNotBlank() }.toSet()
        val lines = defaults.filterNot { it.substringBefore('=') in overridden } + extra
        File(dir, "server.properties")
            .writeText(lines.joinToString(System.lineSeparator(), postfix = System.lineSeparator()))
    }

    /** ModDevGradle names a run's task by capitalising the run's own name. */
    private fun runTaskName(run: String): String =
        "run" + run.replaceFirstChar { it.uppercase() }

    private companion object {
        const val GROUP = "mcdriver"

        const val MODDEV = "net.neoforged.moddev"

        /** Registered by ModDevGradle's `unitTest { enable() }`, and by nothing else. */
        const val MDG_PREPARE_TEST = "prepareNeoForgeTestFiles"

        const val SERVER_RUN = "driverServer"
        const val CLIENT_RUN = "driverClient"

        const val HARVEST = "harvestDriverLaunchPlan"
        const val SEED = "seedDriverRunDirs"

        const val LEVEL_NAME = "world"
    }
}
