package dev.vibeported.mc.e2e.gradle

import net.neoforged.moddevgradle.dsl.NeoForgeExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.language.jvm.tasks.ProcessResources
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.File

/**
 * Everything a build needs to run end-to-end tests against a real NeoForge server and client.
 *
 * It applies Kotlin, serialization and ModDevGradle, creates the source set the suites live in,
 * generates that mod's metadata, applies the e2e compiler plugin to it, registers the two game runs,
 * seeds their directories, and adds `runE2eTests`. A consuming build is the plugins block and one
 * `mcE2E { }` block.
 *
 * It does not wrap ModDevGradle: `mcE2E { neoForge { } }` hands out the real extension, so a version
 * bump there cannot silently change what a build is allowed to say, and there is no passthrough here
 * to keep in step with it.
 */
class E2eGradlePlugin : Plugin<Project> {

    override fun apply(project: Project) {
        project.plugins.apply("org.jetbrains.kotlin.jvm")
        project.plugins.apply("org.jetbrains.kotlin.plugin.serialization")
        project.plugins.apply("net.neoforged.moddev")

        val settings = project.extensions.create("mcE2E", McE2eExtension::class.java, project).apply {
            sourceSetName.convention("e2eTest")
            serverAddress.convention("localhost:25565")
            reportDir.convention(project.layout.buildDirectory.dir("reports/e2e"))
            startupTimeoutSeconds.convention(900L)
            testTimeoutSeconds.convention(300L)
            callTimeoutSeconds.convention(120L)
            actionTimeoutSeconds.convention(10L)
            clientWidth.convention(1280)
            clientHeight.convention(720)
            tileWindows.convention(false)
            javaVersion.convention(25)
            e2eModId.convention(modId.map { "${it}_e2e" }.orElse("e2e_tests"))
        }

        project.extensions.configure(JavaPluginExtension::class.java) {
            it.toolchain.languageVersion.set(settings.javaVersion.map(JavaLanguageVersion::of))
        }
        project.tasks.withType(KotlinCompile::class.java).configureEach { task ->
            task.compilerOptions.jvmTarget.set(
                settings.javaVersion.map { JvmTarget.fromTarget(it.toString()) }
            )
        }

        // ModDevGradle adds repositories to the project, which makes Gradle prefer those over any a
        // settings file declares. Anything the framework needs has to be named again here.
        project.repositories.mavenCentral()
        project.repositories.maven { it.setUrl("https://maven.neoforged.net/releases") }
        project.repositories.maven { it.setUrl("https://thedarkcolour.github.io/KotlinForForge/") }

        val sourceSets = project.extensions.getByType(SourceSetContainer::class.java)
        val suites = sourceSets.maybeCreate(settings.sourceSetName.get())

        val compilerPlugin = project.configurations.create("e2eCompilerPlugin") {
            it.isCanBeConsumed = false
            it.isCanBeResolved = true
        }
        val orchestrator = project.configurations.create("e2eOrchestrator") {
            it.isCanBeConsumed = false
            it.isCanBeResolved = true
        }

        // The mod id, and so the generated metadata, depend on what the build says in `mcE2E { }`.
        project.afterEvaluate {
            configure(project, settings, suites, compilerPlugin, orchestrator)
        }
    }

    private fun configure(
        project: Project,
        settings: McE2eExtension,
        suites: SourceSet,
        compilerPlugin: FileCollection,
        orchestrator: FileCollection,
    ) {
        val neoForge = project.extensions.getByType(NeoForgeExtension::class.java)
        val modId = settings.e2eModId.get()

        // Minecraft lands on `main` by default; the suites are a source set of their own.
        neoForge.addModdingDependenciesTo(suites)
        neoForge.mods.create(modId) { it.sourceSet(suites) }

        val generatedDir = project.layout.buildDirectory.dir("generated/e2e")
        val indexDir = generatedDir.map { it.dir("index") }
        val metadataDir = generatedDir.map { it.dir("metadata") }

        suites.resources.srcDir(indexDir)
        suites.resources.srcDir(metadataDir)

        val generateMetadata = project.tasks.register("generateE2eModMetadata") { task ->
            task.description = "Writes the neoforge.mods.toml for the generated e2e test mod."
            task.outputs.dir(metadataDir)
            task.doLast {
                val out = File(metadataDir.get().asFile, "META-INF")
                out.mkdirs()
                File(out, "neoforge.mods.toml").writeText(modsToml(modId))
            }
        }

        val compileSuites = project.tasks.named(suites.getCompileTaskName("kotlin"), KotlinCompile::class.java)
        compileSuites.configure { task ->
            task.inputs.files(compilerPlugin)
            task.outputs.dir(indexDir)
            task.compilerOptions.freeCompilerArgs.addAll(
                project.provider {
                    compilerPlugin.files.map { "-Xplugin=${it.absolutePath}" } +
                        listOf("-P", "plugin:dev.vibeported.mc.e2e:indexDir=${indexDir.get().asFile.absolutePath}")
                }
            )
        }

        project.tasks.named(suites.processResourcesTaskName, ProcessResources::class.java).configure {
            // Both generated resource directories are task outputs, so packaging waits for them.
            it.dependsOn(compileSuites, generateMetadata)
        }

        val serverRunDir = project.layout.projectDirectory.dir("run/e2eServer")
        val clientRunDir = project.layout.projectDirectory.dir("run/e2eClient")

        neoForge.runs.create("e2eServer") { run ->
            run.server()
            // The run classpath comes from one source set, and it has to be this one: the framework
            // mod is a dependency of the suites, and FancyModLoader only finds it as a mod if its
            // jar is on that classpath.
            run.sourceSet.set(suites)
            run.gameDirectory.set(serverRunDir)
            run.programArgument("--nogui")
        }

        neoForge.runs.create("e2eClient") { run ->
            run.client()
            run.sourceSet.set(suites)
            run.gameDirectory.set(clientRunDir)
            // Vanilla joins the address on its own, which spares us reaching into ConnectScreen.
            run.programArguments.addAll("--quickPlayMultiplayer", settings.serverAddress.get())
        }

        val seedRunDirs = project.tasks.register("seedE2eRunDirs") { task ->
            task.description = "Writes the settings an unattended server needs."
            task.outputs.dir(serverRunDir)
            val extraProperties = settings.serverProperties.getOrElse(emptyList())
            task.doLast {
                seedServer(serverRunDir.asFile, extraProperties)
            }
        }

        val planFile = project.layout.buildDirectory.file("e2e/launch-plan.json")

        val harvest = project.tasks.register("harvestE2eLaunchPlan", HarvestLaunchPlanTask::class.java) { task ->
            task.group = "verification"
            task.description = "Records how ModDevGradle would launch the e2e client and server."
            task.serverRunTask.set("runE2eServer")
            // One harvested command is enough: the orchestrator spawns a process per client the
            // suites name, each with its own username and game directory.
            task.clientRunTasks.set(listOf("runE2eClient"))
            task.indexFiles.from(indexDir.map { it.file("META-INF/e2e/index.json") })
            task.reportDir.set(settings.reportDir)
            task.serverAddress.set(settings.serverAddress)
            task.startupTimeoutSeconds.set(settings.startupTimeoutSeconds)
            task.testTimeoutSeconds.set(settings.testTimeoutSeconds)
            task.callTimeoutSeconds.set(settings.callTimeoutSeconds)
            task.actionTimeoutSeconds.set(settings.actionTimeoutSeconds)
            task.clientWidth.set(settings.clientWidth)
            task.clientHeight.set(settings.clientHeight)
            task.tileWindows.set(settings.tileWindows)
            task.planFile.set(planFile)
            task.dependsOn(compileSuites, generateMetadata, project.tasks.named(suites.processResourcesTaskName))
            // The game runs off the runtime classpath, and a project on it is a jar somebody has to
            // build. Compiling the suites only needs the class directories, so without this the run
            // happily launches against whatever jar was lying there from last time.
            task.dependsOn(suites.runtimeClasspath)
            task.dependsOn(project.tasks.matching { it.name.startsWith("prepare") && it.name.endsWith("Run") })
        }

        project.tasks.register("runE2eTests", JavaExec::class.java) { task ->
            task.group = "verification"
            task.description = "Starts an orchestrated NeoForge server and client and runs the e2e suites."
            task.dependsOn(harvest, seedRunDirs)
            task.mainClass.set("dev.vibeported.mc.e2e.launcher.E2eMain")
            task.classpath = orchestrator
            task.argumentProviders.add { listOf(planFile.get().asFile.absolutePath) }
        }
    }

    /**
     * The suites have to be a mod for FancyModLoader to load them, but nothing about that mod is
     * interesting enough to make anyone write this file by hand.
     */
    private fun modsToml(modId: String): String = buildString {
        appendLine("modLoader = \"javafml\"")
        appendLine("loaderVersion = \"[0,)\"")
        appendLine("license = \"Generated by the minecraft-e2e Gradle plugin\"")
        appendLine()
        appendLine("[[mods]]")
        appendLine("modId = \"$modId\"")
        appendLine("version = \"0.0.0\"")
        appendLine("displayName = \"End-to-end suites\"")
        appendLine()
        appendLine("[[dependencies.$modId]]")
        appendLine("modId = \"e2e\"")
        appendLine("type = \"required\"")
        appendLine("versionRange = \"[0,)\"")
        appendLine("ordering = \"AFTER\"")
        appendLine("side = \"BOTH\"")
    }

    /**
     * A dedicated server refuses to boot without an accepted EULA, and the defaults it would write
     * are wrong for a test: a superflat world generates fast and is the same every time, and a dev
     * client has no session to authenticate with.
     */
    private fun seedServer(dir: File, extra: List<String>) {
        dir.mkdirs()
        File(dir, "eula.txt").writeText("eula=true" + System.lineSeparator())

        val defaults = listOf(
            "online-mode=false",
            "level-name=e2e",
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
            "motd=minecraft-e2e",
        )
        val overridden = extra.map { it.substringBefore('=') }.filter { it.isNotBlank() }.toSet()
        val lines = defaults.filterNot { it.substringBefore('=') in overridden } + extra
        File(dir, "server.properties")
            .writeText(lines.joinToString(System.lineSeparator(), postfix = System.lineSeparator()))
    }
}
