import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.moddev)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    explicitApi()
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

// One way of composing tests out of the procedure calls, and deliberately not the only one: nothing
// in core or dsl knows this module exists, so a JUnit runner could replace it wholesale.
dependencies {
    api(project(":minecraft"))
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
}

// A mod, though it adds nothing to a game and has no `@Mod` class. It has to be one so that
// FancyModLoader loads it in the same transforming loader as the framework it drives: a runner on
// the plain application classpath is a *second* copy of every shared class, and registering a
// receiver from one world while a generated table looks for it in the other fails with a
// ClassCastException naming the same type twice.
neoForge {
    version = libs.versions.neoforge.get()

    mods {
        create("e2e_suite") { sourceSet(sourceSets.main.get()) }
    }
}
