pluginManagement {
    // The Gradle plugin is an included build so this very build can apply it by id, rather than
    // duplicating the Minecraft run wiring for the sake of dogfooding it.
    includeBuild("e2e-gradle-plugin")

    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven("https://maven.neoforged.net/releases")
    }
}

plugins {
    // Lets the Minecraft module ask for a JDK 21 toolchain without one being installed by hand.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://maven.neoforged.net/releases")
        maven("https://thedarkcolour.github.io/KotlinForForge/") {
            content { includeGroup("thedarkcolour") }
        }
    }
}

rootProject.name = "minecraft-e2e"

include(
    ":e2e-core",
    ":e2e-dsl",
    ":e2e-suite",
    ":e2e-orchestrator",
    ":e2e-example",
    ":e2e-compiler-plugin",
)
