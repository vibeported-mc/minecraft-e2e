package dev.vibeported.mc.driver.gradle

import net.neoforged.moddevgradle.dsl.NeoForgeExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.SourceSet
import java.io.File

/**
 * Everything a build needs to drive a real NeoForge server and client.
 *
 * Three runs and two tasks. The server and client runs exist to be *harvested* rather than started
 * by Gradle -- the driver starts them itself, as many clients as it likes, each with its own
 * username and game directory -- and the third run is the driver, launched through
 * `dev.vibeported.mc.driver.launcher.Launch` so that it comes up inside a prepared NeoForge
 * environment with no game in it.
 *
 * A consuming build is a plugins block and an `mcDriver { }` naming two things:
 *
 * ```kotlin
 * mcDriver {
 *     sourceSet = sourceSets.main.get()
 *     mainClass = "com.example.Smoke"
 * }
 * ```
 *
 * It does not wrap ModDevGradle. The consuming build applies MDG itself and this configures it, so a
 * version bump there cannot silently change what a build is allowed to say, and there is no
 * passthrough here to keep in step.
 */
public class McDriverGradlePlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val settings = project.extensions.create("mcDriver", McDriverExtension::class.java).apply {
            dist.convention("DEDICATED_SERVER")
            serverAddress.convention("localhost:25565")
            clientWidth.convention(1280)
            clientHeight.convention(720)
            tileWindows.convention(false)
            captureDir.convention(project.layout.buildDirectory.dir("mcdriver"))
            javaVersion.convention(25)
        }

        // The launcher jar. It has to be on the run's classpath and it is not a mod -- `Launch` runs
        // before the transforming loader exists -- so it goes on as an ordinary runtime dependency.
        project.configurations.create(LAUNCHER) {
            it.isCanBeConsumed = false
            it.isCanBeResolved = true
            it.description = "The FancyModLoader entrypoint the driver run is started through."
        }

        // The source set and the main class are only known once the build script has run.
        project.afterEvaluate { configure(it, settings) }
    }

    private fun configure(project: Project, settings: McDriverExtension) {
        // Applied by the consuming build, not from here, and deliberately so. This plugin lives in
        // an included build, so applying ModDevGradle from it would load a second copy beside the
        // one every other module uses -- and two copies both apply `gradle-idea-ext` to the root
        // project, colliding on the `settings` extension during an IDE import with an error that
        // names neither plugin.
        require(project.plugins.hasPlugin("net.neoforged.moddev")) {
            "The driver plugin configures ModDevGradle rather than applying it, so a build using " +
                "it needs `id(\"net.neoforged.moddev\")` in its own plugins block."
        }

        val neoForge = project.extensions.getByType(NeoForgeExtension::class.java)
        val sources = settings.sourceSet.orNull ?: error(
            "mcDriver { sourceSet = ... } says which source set the games are launched from. " +
                "It is usually `sourceSets.main.get()`."
        )
        val mainClass = settings.mainClass.orNull?.takeIf { it.isNotBlank() } ?: error(
            "mcDriver { mainClass = ... } names the class whose main() runs once the loader is up."
        )

        // The launcher boots the loader and is then loaded through it, so it belongs on the same
        // runtime classpath as everything else rather than off to one side.
        project.configurations.named(sources.runtimeOnlyConfigurationName).configure {
            it.extendsFrom(project.configurations.getByName(LAUNCHER))
        }

        val planFile = project.layout.buildDirectory.file("mcdriver/launch-plan.json")
        val captureDir = settings.captureDir.get().asFile
        val serverRunDir = project.layout.projectDirectory.dir("run/driverServer")
        val clientRunDir = project.layout.projectDirectory.dir("run/driverClient")

        neoForge.runs.create(SERVER_RUN) { run ->
            run.server()
            run.sourceSet.set(sources)
            run.gameDirectory.set(serverRunDir)
            run.programArgument("--nogui")
        }

        neoForge.runs.create(CLIENT_RUN) { run ->
            run.client()
            run.sourceSet.set(sources)
            run.gameDirectory.set(clientRunDir)
            // Vanilla joins the address on its own, which spares reaching into ConnectScreen.
            run.programArguments.addAll("--quickPlayMultiplayer", settings.serverAddress.get())
        }

        neoForge.runs.create(DRIVER_RUN) { run ->
            // A server run for the dist the loader has to prepare, and then the launcher instead of
            // Minecraft: no world is ever created here, because nothing calls the game's own main.
            run.server()
            run.sourceSet.set(sources)
            run.gameDirectory.set(project.layout.projectDirectory.dir("run/driver"))
            run.mainClass.set(LAUNCHER_MAIN)
            run.jvmArgument("-Dmcdriver.launch.main=$mainClass")
            run.jvmArgument("-Dmcdriver.launch.dist=${settings.dist.get()}")
            run.jvmArgument("-Dmcdriver.launch.plan=${planFile.get().asFile.absolutePath}")
            run.jvmArgument("-Dmcdriver.capture.dir=${captureDir.absolutePath}")
        }

        val harvest = project.tasks.register(HARVEST, HarvestLaunchPlanTask::class.java) { task ->
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
            task.dependsOn(sources.runtimeClasspath)
            task.dependsOn(project.tasks.named(sources.processResourcesTaskName))
            task.dependsOn(project.tasks.matching { it.name.startsWith("prepare") && it.name.endsWith("Run") })
        }

        val seed = project.tasks.register(SEED) { task ->
            task.group = GROUP
            task.description = "Writes the settings an unattended server needs, and clears its world."
            task.outputs.dir(serverRunDir)
            // It deletes a world every time it runs, so being skipped as up to date would leave the
            // previous run's world in place -- the exact thing it exists to prevent.
            task.outputs.upToDateWhen { false }
            val extra = settings.serverProperties.getOrElse(emptyList())
            task.doLast { seedServer(serverRunDir.asFile, extra) }
        }

        project.tasks.named(runTaskName(DRIVER_RUN)).configure { it.dependsOn(harvest, seed) }

        project.tasks.register("runDriver") { task ->
            task.group = GROUP
            task.description = "Runs ${mainClass}.main inside a prepared NeoForge environment, with a cluster it can start."
            task.dependsOn(project.tasks.named(runTaskName(DRIVER_RUN)))
        }
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

        /** Where a build says which launcher jar to use. */
        const val LAUNCHER = "mcDriverLauncher"

        const val LAUNCHER_MAIN = "dev.vibeported.mc.driver.launcher.Launch"

        const val SERVER_RUN = "driverServer"
        const val CLIENT_RUN = "driverClient"
        const val DRIVER_RUN = "driverMain"

        const val HARVEST = "harvestDriverLaunchPlan"
        const val SEED = "seedDriverRunDirs"

        const val LEVEL_NAME = "world"
    }
}
