import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.moddev)
    alias(libs.plugins.kotlin.serialization)
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25
}

repositories {
    mavenCentral()
    maven("https://maven.neoforged.net/releases")
    maven("https://thedarkcolour.github.io/KotlinForForge/") {
        content { includeGroup("thedarkcolour") }
    }
}

val e2eCompilerPlugin: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    api(project(":e2e-core"))
    implementation(libs.kotlinforforge)
    e2eCompilerPlugin(project(":e2e-compiler-plugin"))
}

// The gameplay verbs are built out of the same `server { }` and `client { }` calls a test uses, so
// this module needs the plugin every bit as much as a suite does -- and dogfooding it here is what
// proves the primitives are good enough to build on.
val generatedIndex = layout.buildDirectory.dir("generated/e2e/index")

sourceSets.main.get().resources.srcDir(generatedIndex)

tasks.named<KotlinCompile>("compileKotlin") {
    inputs.files(e2eCompilerPlugin)
    outputs.dir(generatedIndex)
    compilerOptions.freeCompilerArgs.addAll(
        provider {
            e2eCompilerPlugin.files.map { "-Xplugin=${it.absolutePath}" } +
                listOf(
                    "-P",
                    "plugin:dev.vibeported.mc.e2e:indexDir=${generatedIndex.get().asFile.absolutePath}",
                )
        }
    )
}

tasks.named("processResources") { dependsOn("compileKotlin") }

neoForge {
    version = libs.versions.neoforge.get()

    mods {
        create("e2e_dsl") { sourceSet(sourceSets.main.get()) }
    }
}
