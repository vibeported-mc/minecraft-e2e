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
// in e2e-core or e2e-dsl knows this module exists, so a JUnit runner could replace it wholesale.
dependencies {
    api(project(":e2e-core"))
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
}

neoForge {
    version = libs.versions.neoforge.get()
}
