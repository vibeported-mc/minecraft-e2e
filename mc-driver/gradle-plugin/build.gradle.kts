import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.4.10"
    `java-gradle-plugin`
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget = JvmTarget.JVM_21
}

dependencies {
    // compileOnly, and that is load-bearing. This plugin lives in an included build, so anything on
    // its runtime classpath is exported onto the consuming script's plugin classpath -- a second
    // copy of ModDevGradle beside the one the consuming build applies itself. MDG applies
    // gradle-idea-ext to the *root* project, and Gradle's already-applied check is per Class object,
    // so the second copy does not recognise the first one's work and collides on the `settings`
    // extension. The failure names neither plugin and only bites during an IDE import. The consuming
    // build applies MDG itself, so these types are on the classpath at runtime regardless.
    compileOnly("net.neoforged:moddev-gradle:2.0.144")
}

gradlePlugin {
    plugins {
        create("mcDriver") {
            id = "dev.vibeported.mc.driver"
            implementationClass = "dev.vibeported.mc.driver.gradle.McDriverGradlePlugin"
            displayName = "Minecraft driver"
            description = "Declares the game runs, records how ModDevGradle would launch them, and runs a main inside a prepared NeoForge environment."
        }
    }
}
