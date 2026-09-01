import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.moddev)
    alias(libs.plugins.kotlin.serialization)
    id("dev.vibeported.mc.e2e")
}

// Minecraft 26.2 resolves against a Java 25 runtime -- FancyModLoader 11 refuses to hand its
// artifacts to anything older -- so this module overrides the toolchain the rest of the build uses.
java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25
}

// ModDevGradle adds its own repositories to this project, which makes Gradle prefer project
// repositories here and ignore the ones declared in settings.
repositories {
    mavenCentral()
    maven("https://maven.neoforged.net/releases")
    maven("https://thedarkcolour.github.io/KotlinForForge/") {
        content { includeGroup("thedarkcolour") }
    }
}

// The suites compile into a mod of their own, separate from the framework mod, so a game launch can
// load the framework without dragging somebody else's tests in with it.
val tests: SourceSet = sourceSets.create("tests")

val e2ePlugin: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    // Everything belongs to the suites: `main` has no sources here at all, and anything left on it
    // would only put another directory in front of FancyModLoader to reject.
    "testsImplementation"(project(":e2e-core"))
    "testsImplementation"(libs.kotlinforforge)

    e2ePlugin(project(":e2e-compiler-plugin"))

    // What `runE2e` launches the orchestrator with.
    "e2eOrchestrator"(project(":e2e-orchestrator"))
}

neoForge {
    version = libs.versions.neoforge.get()

    // Minecraft lands on `main` by default; the suites are a source set of their own and need it too.
    addModdingDependenciesTo(tests)

    mods {
        create("e2e_tests") { sourceSet(tests) }
    }

    runs {
        create("e2eServer") {
            server()
            // The run classpath comes from one source set, and it has to be this one: the framework
            // mod is a dependency of the suites, and FancyModLoader only finds it as a mod if its
            // jar is on that classpath.
            sourceSet = tests
            gameDirectory = layout.projectDirectory.dir("run/e2eServer")
            programArgument("--nogui")
        }

        create("e2eClient") {
            client()
            sourceSet = tests
            gameDirectory = layout.projectDirectory.dir("run/e2eClient")
            // Vanilla joins the address on its own, which spares us reaching into ConnectScreen.
            programArguments.addAll("--quickPlayMultiplayer", "localhost:25565")
        }
    }
}

// The suites are what the compiler plugin exists for, so it is applied to that source set only.
val testsIndexDir: Provider<Directory> = layout.buildDirectory.dir("generated/e2e-index")

tests.resources.srcDir(testsIndexDir)

tasks.named<KotlinCompile>("compileTestsKotlin") {
    val pluginClasspath: FileCollection = e2ePlugin
    val index = testsIndexDir
    inputs.files(pluginClasspath).withNormalizer(ClasspathNormalizer::class)
    outputs.dir(index)
    compilerOptions {
        freeCompilerArgs.addAll(
            providers.provider {
                pluginClasspath.files.map { "-Xplugin=${it.absolutePath}" } +
                    listOf("-P", "plugin:dev.vibeported.mc.e2e:indexDir=${index.get().asFile.absolutePath}")
            }
        )
    }
}

tasks.named<ProcessResources>("processTestsResources") {
    dependsOn(tasks.named("compileTestsKotlin"))
}

/**
 * Seeds both run directories with the settings an unattended run needs.
 *
 * A dedicated server refuses to boot without an accepted EULA, and the defaults it would otherwise
 * write are wrong for a test run: a superflat world generates fast and is the same every time, and
 * a dev client has no session to authenticate with.
 *
 * The client needs one thing too. NeoForge shows an interactive screen when any mod loads with a
 * warning and waits there for a click, which for an automated client is simply a hang -- and the
 * warning is rarely even ours.
 */
val seedE2eRunDirs by tasks.registering {
    val runDir = layout.projectDirectory.dir("run/e2eServer")
    val clientDir = layout.projectDirectory.dir("run/e2eClient")
    outputs.dir(runDir)
    outputs.dir(clientDir)
    doLast {
        File(clientDir.asFile, "config").mkdirs()
        File(clientDir.asFile, "config/neoforge-client.toml").writeText("showLoadWarnings = false" + System.lineSeparator())

        val dir = runDir.asFile
        dir.mkdirs()
        File(dir, "eula.txt").writeText("eula=true\n")
        File(dir, "server.properties").writeText(
            """
            online-mode=false
            level-name=e2e
            level-type=minecraft\:flat
            generate-structures=false
            spawn-npcs=false
            spawn-animals=false
            spawn-monsters=false
            spawn-protection=0
            allow-nether=false
            max-players=4
            view-distance=8
            server-port=25565
            sync-chunk-writes=false
            motd=minecraft-e2e
            """.trimIndent() + "\n"
        )
    }
}

e2e {
    serverRunTask = "runE2eServer"
    clientRunTasks = listOf("runE2eClient")
    indexFiles.from(testsIndexDir.map { it.file("META-INF/e2e/index.json") })
    serverAddress = "localhost:25565"
}

tasks.named("runE2e") {
    dependsOn(seedE2eRunDirs, tasks.named("compileTestsKotlin"), tasks.named("processTestsResources"))
}

tasks.named("harvestE2eLaunchPlan") {
    // The plan records the run classpaths, which are only complete once ModDevGradle has prepared
    // them, and it points at the index that compiling the suites produces.
    dependsOn(tasks.matching { it.name.startsWith("prepare") && it.name.endsWith("Run") })
    dependsOn(tasks.named("compileTestsKotlin"), tasks.named("processTestsResources"))
}
