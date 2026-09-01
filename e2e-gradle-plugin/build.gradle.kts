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
    // ModDevGradle registers the Minecraft runs this plugin harvests launch commands from.
    compileOnly("net.neoforged:moddev-gradle:2.0.144")
}

gradlePlugin {
    plugins {
        create("e2e") {
            id = "dev.vibeported.mc.e2e"
            implementationClass = "dev.vibeported.mc.e2e.gradle.E2eGradlePlugin"
            displayName = "Minecraft E2E"
            description = "Runs an orchestrated NeoForge client and server pair against a compiled e2e test mod."
        }
    }
}
