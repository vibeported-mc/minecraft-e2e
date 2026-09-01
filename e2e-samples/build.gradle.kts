import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// The samples are the plugin's own dogfood. Rather than bootstrapping the published Gradle plugin
// inside the build that produces it, apply the compiler plugin straight from its project output --
// the same `-Xplugin=` wiring the Kotlin repo uses to test its own plugins.
val e2ePlugin: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    implementation(project(":e2e-api"))
    implementation(project(":e2e-runtime"))
    implementation(project(":e2e-mock-world"))
    e2ePlugin(project(":e2e-compiler-plugin"))
}

val indexDir: Provider<Directory> = layout.buildDirectory.dir("generated/e2e-index")

sourceSets.main {
    resources.srcDir(indexDir)
}

// The index is written by the Kotlin compiler plugin, so packaging it has to wait for compilation.
tasks.named<ProcessResources>("processResources") {
    dependsOn(tasks.named("compileKotlin"))
}

tasks.withType<KotlinCompile>().configureEach {
    val pluginClasspath: FileCollection = e2ePlugin
    val index = indexDir
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

tasks.register<JavaExec>("runE2e") {
    group = "verification"
    description = "Runs the sample suites through the in-process orchestrator and prints a report."
    mainClass.set("dev.vibeported.mc.e2e.samples.SampleRunnerKt")
    classpath = sourceSets["main"].runtimeClasspath
    javaLauncher.set(javaToolchains.launcherFor(java.toolchain))
}
