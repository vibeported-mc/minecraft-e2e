package dev.vibeported.mc.e2e.gradle

import net.neoforged.moddevgradle.dsl.NeoForgeExtension
import net.neoforged.moddevgradle.internal.Branding
import net.neoforged.moddevgradle.internal.IdeIntegration
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.language.jvm.tasks.ProcessResources
import org.gradle.api.tasks.TaskProvider
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.File

/**
 * Everything a build needs to run end-to-end tests against a real NeoForge server and client.
 *
 * It applies Kotlin and serialization, configures the ModDevGradle the consuming build applied,
 * creates the source set the suites live in,
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

        val settings = project.extensions.create("mcE2E", McE2eExtension::class.java, project).apply {
            sourceSetName.convention("e2eTest")
            serverAddress.convention("localhost:25565")
            reportDir.convention(project.layout.buildDirectory.dir("reports/e2e"))
            startupTimeoutSeconds.convention(900L)
            callTimeoutSeconds.convention(120L)
            orchestratorMain.convention("")
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
        // Declared always, wired only when `blockDsl { }` asks for it: a build that never opts in
        // never puts the generator on a classpath, and a build that does has somewhere to say so.
        project.configurations.create("e2eCodegen") {
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
        // Applied by the consuming build, not by this plugin, and deliberately so. This plugin
        // lives in an included build, so applying ModDevGradle from here would load a second copy of
        // it beside the one every other module uses. Two copies both apply `gradle-idea-ext` to the
        // root project during an IDE import, and the second collides on the `settings` extension --
        // an import that fails with an error naming neither plugin. One classloader, no collision.
        require(project.plugins.hasPlugin("net.neoforged.moddev")) {
            "The e2e plugin configures ModDevGradle rather than applying it, so a build using it " +
                "needs `id(\"net.neoforged.moddev\")` in its own plugins block."
        }
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

        // Through the Kotlin plugin's own configuration rather than as a `-Xplugin=` string in
        // freeCompilerArgs. Both reach the compiler, but only this one is part of the model an IDE
        // imports: a raw argument is an opaque string it never parses into a plugin, so the
        // checkers never run in the editor and a rejected capture shows up only at build time.
        val pluginDependency = project.configurations.getByName("e2eCompilerPlugin")

        // The suites, which is the compilation that actually needs it.
        val suitesClasspath =
            "kotlinCompilerPluginClasspath" + suites.name.replaceFirstChar { it.uppercase() }
        project.configurations.named(suitesClasspath).configure { it.extendsFrom(pluginDependency) }

        // And `main`, which needs it only so that an editor can see it. The VS Code language
        // server imports `kotlinCompilerPluginClasspathMain` and no other variant, and models one
        // module per Gradle project rather than one per source set -- so `main` is where it looks
        // for the plugin that governs every source root, the suites included. Harmless in a build:
        // `main` holds no procedures, so the plugin finds nothing to lift there.
        if (suitesClasspath != MAIN_PLUGIN_CLASSPATH) {
            project.configurations
                .named(MAIN_PLUGIN_CLASSPATH)
                .configure { it.extendsFrom(pluginDependency) }
        }

        val compileSuites = project.tasks.named(suites.getCompileTaskName("kotlin"), KotlinCompile::class.java)
        compileSuites.configure { task ->
            task.outputs.dir(indexDir)
            task.compilerOptions.freeCompilerArgs.addAll(
                project.provider {
                    listOf("-P", "plugin:dev.vibeported.mc.e2e:indexDir=${indexDir.get().asFile.absolutePath}")
                }
            )
        }

        project.tasks.named(suites.processResourcesTaskName, ProcessResources::class.java).configure {
            // Both generated resource directories are task outputs, so packaging waits for them.
            it.dependsOn(compileSuites, generateMetadata)
        }

        val planFile = project.layout.buildDirectory.file("e2e/launch-plan.json")

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

        // The orchestrator boots the loader and is then loaded through it, so it has to be on the
        // same runtime classpath as the suites rather than off to one side.
        project.configurations.named(suites.runtimeOnlyConfigurationName).configure {
            it.extendsFrom(project.configurations.getByName("e2eOrchestrator"))
        }

        val orchestratorRunDir = project.layout.projectDirectory.dir("run/e2eOrchestrator")

        neoForge.runs.create("e2eOrchestrator") { run ->
            // A server run, because that is the dist the loader has to prepare, and then a main
            // class of ours instead of Minecraft: no world is ever created.
            run.server()
            run.sourceSet.set(suites)
            run.gameDirectory.set(orchestratorRunDir)
            run.mainClass.set("dev.vibeported.mc.e2e.launcher.OrchestratorEntrypoint")
            run.jvmArgument("-De2e.launch.plan=${planFile.get().asFile.absolutePath}")
            run.jvmArgument("-De2e.report.dir=${settings.reportDir.get().asFile.absolutePath}")
        }

        val seedRunDirs = project.tasks.register("seedE2eRunDirs") { task ->
            task.description = "Writes the settings an unattended server needs, and clears its world."
            task.outputs.dir(serverRunDir)
            // It deletes a world every time it runs, so being skipped as up to date would leave the
            // previous run's world in place -- which is the exact thing it exists to prevent.
            task.outputs.upToDateWhen { false }
            val extraProperties = settings.serverProperties.getOrElse(emptyList())
            task.doLast {
                seedServer(serverRunDir.asFile, extraProperties)
            }
        }

        val harvest = project.tasks.register("harvestE2eLaunchPlan", HarvestLaunchPlanTask::class.java) { task ->
            task.group = "verification"
            task.description = "Records how ModDevGradle would launch the e2e client and server."
            task.serverRunTask.set("runE2eServer")
            // One harvested command is enough: the orchestrator spawns a process per client the
            // suites name, each with its own username and game directory.
            task.clientRunTasks.set(listOf("runE2eClient"))
            task.reportDir.set(settings.reportDir)
            task.mainClass.set(settings.orchestratorMain)
            task.clientNames.set(settings.clients)
            task.serverAddress.set(settings.serverAddress)
            task.startupTimeoutSeconds.set(settings.startupTimeoutSeconds)
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

        // The orchestrator is started by ModDevGradle like any other run, so `runE2eTests` is a
        // name for that run plus everything it needs prepared first.
        project.tasks.register("runE2eTests") { task ->
            task.group = "verification"
            task.description = "Starts an orchestrated NeoForge cluster and runs the configured main."
            task.dependsOn(harvest, seedRunDirs, project.tasks.named("runE2eOrchestrator"))
        }

        project.tasks.named("runE2eOrchestrator").configure { task ->
            task.dependsOn(harvest, seedRunDirs)
        }

        if (settings.blockDsl.enabled.getOrElse(false)) {
            generateBlockDsl(project, settings, modId, neoForge, compileSuites)
        }
    }

    /**
     * A Kotlin name for every block the game loads, and a builder for the properties each one has.
     *
     * Reached by starting the game, because nothing else knows: a modded block registers itself, and
     * a property's legal values can come from a predicate, so neither the jar nor the source says
     * what a build may write. The generator boots FancyModLoader, reads the registry and writes
     * Kotlin; this wires that into the build.
     *
     * Only called when a build opted in, since booting the game is far too much to charge every
     * build for a feature it may not use.
     */
    private fun generateBlockDsl(
        project: Project,
        settings: McE2eExtension,
        suitesModId: String,
        neoForge: NeoForgeExtension,
        compileSuites: TaskProvider<KotlinCompile>,
    ) {
        val blocksDir = project.layout.buildDirectory.dir("generated/e2e/blocks")
        val namespaces = settings.blockDsl.namespaces.getOrElse(emptyList())

        // A source set of its own, holding no source, purely to give the run a classpath.
        //
        // It cannot be the suites': the generated names are compiled into those, so a run that used
        // them would have to be built before it could produce what building them needs. A separate
        // classpath breaks that circle, and has the better side of also keeping the generator out of
        // the server and client runs, where it has no business being loaded.
        val generatorSources = project.extensions
            .getByType(SourceSetContainer::class.java)
            .maybeCreate("blockDslGenerator")
        neoForge.addModdingDependenciesTo(generatorSources)
        project.configurations
            .named(generatorSources.runtimeOnlyConfigurationName)
            .configure { it.extendsFrom(project.configurations.getByName("e2eCodegen")) }

        neoForge.runs.create("e2eCodegen") { run ->
            // A server run for the dist the loader must prepare, and then our main class instead of
            // Minecraft's: the registry is filled, no world is ever created.
            run.server()
            run.sourceSet.set(generatorSources)
            // Every mod except the suites. ModDevGradle puts a declared mod's classes on every
            // run, and the suites are a mod -- so loading them here would mean building the names
            // this run exists to write before it could run. Everything else stays: the mod under
            // test is exactly what a build wants named, and it is only the suites that circle back.
            run.loadedMods.set(
                project.provider { neoForge.mods.filter { it.name != suitesModId }.toSet() }
            )
            run.gameDirectory.set(project.layout.projectDirectory.dir("run/e2eCodegen"))
            run.mainClass.set("dev.vibeported.mc.e2e.codegen.launcher.CodegenEntrypoint")
            run.jvmArgument("-De2e.blockdsl.out=${blocksDir.get().asFile.absolutePath}")
            run.jvmArgument("-De2e.blockdsl.namespaces=${namespaces.joinToString(",")}")
        }

        val run = project.tasks.named("runE2eCodegen")
        run.configure { task ->
            // Up-to-dateness is what keeps this off the critical path of an ordinary build: the
            // answer changes when the mods change and at no other time, so that is what it watches.
            task.outputs.dir(blocksDir)
            task.inputs.property("namespaces", namespaces)
            task.inputs.files(
                project.configurations.getByName(generatorSources.runtimeClasspathConfigurationName)
            )
        }

        val generate = project.tasks.register("generateBlockDsl") { task ->
            task.group = "build"
            task.description = "Writes a Kotlin name for every block the loaded mods register."
            task.dependsOn(run)
        }

        // Kotlin, not resources: this is source the suites compile against, and the plugin has only
        // ever generated resources before.
        project.extensions.getByType(KotlinJvmProjectExtension::class.java)
            .sourceSets.getByName(settings.sourceSetName.get())
            .kotlin.srcDir(blocksDir)

        compileSuites.configure { it.dependsOn(generate) }

        // So the names are there when the project opens, rather than after someone remembers to
        // build. ModDevGradle uses this same hook to prepare Minecraft during a sync.
        IdeIntegration.of(project, Branding.MDG).runTaskOnProjectSync(generate)
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

        // A world left over from the last run is not a world a test can reason about. It remembers
        // where every player stood, what they were holding, and whether they were alive -- and a
        // player who died in the previous run comes back dead, so `waitForPlayer` waits forever for
        // someone who is never going to get up.
        File(dir, LEVEL_NAME).deleteRecursively()

        val defaults = listOf(
            "online-mode=false",
            "level-name=" + LEVEL_NAME,
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

    private companion object {
        /** The world a run builds, deleted and rebuilt every time so a test starts from nothing. */
        const val LEVEL_NAME = "e2e"
    }
}

/** Kotlin's compiler-plugin classpath for the `main` compilation. @see E2eGradlePlugin */
private const val MAIN_PLUGIN_CLASSPATH = "kotlinCompilerPluginClasspathMain"
