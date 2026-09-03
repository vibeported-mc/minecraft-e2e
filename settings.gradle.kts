pluginManagement {
    // The Gradle plugin is an included build so this very build can apply it by id, rather than
    // duplicating the Minecraft run wiring for the sake of dogfooding it.
    includeBuild("gradle-plugin")

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
    ":core",
    ":dsl",
    ":suite",
    ":orchestrator",
    ":codegen",
    ":example",
    ":compiler-plugin",

    // The RPC framework the harness is built on, and which knows nothing about Minecraft. Kept in
    // this build for the shared catalog and conventions; kept free of the game by never applying
    // ModDevGradle to it, which `subprojects { }` does not do for anyone.
    ":rpc:core",

    // The capture stack: FFmpeg cross-built for Windows, the Panama bindings generated from
    // the very headers it was built with, and the object-oriented layer over them. Ordinary
    // subprojects -- the Docker step is a task like any other, with the same up-to-date
    // checks. They build on 25 rather than the 21 the rest of the tree uses, because the FFM
    // API only became final in 22; capture/build.gradle.kts sets that for itself.
    ":capture:libav-gen",
    ":capture:libav",
    ":capture:example",
)
