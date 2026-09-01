import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.moddev)
    alias(libs.plugins.kotlin.serialization)
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

dependencies {
    api(libs.coroutines.core)
    api(libs.serialization.json)
    // The value codec resolves a serializer from the runtime class of an argument.
    implementation(libs.kotlin.reflect)

    // Kotlin as a loaded mod. A dev run would have the stdlib on its classpath anyway, but a mod jar
    // shipped to anyone else would not, and this is the version pairing that has to hold either way.
    implementation(libs.kotlinforforge)
}

neoForge {
    version = libs.versions.neoforge.get()

    mods {
        create("e2e") { sourceSet(sourceSets.main.get()) }
    }
}
