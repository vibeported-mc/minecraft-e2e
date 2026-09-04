pluginManagement {
    // Both Gradle plugins are included builds, so this very build can apply them by id rather than
    // duplicating their wiring for the sake of dogfooding it. Gradle takes an included build's name
    // from its directory, and both are called `gradle-plugin`, so both are renamed.
    includeBuild("rpc/gradle-plugin") { name = "rpc-gradle-plugin" }

    // The driver's configures the ModDevGradle a consuming build applied: it declares the game
    // runs, harvests how ModDevGradle would launch them, and hangs the test task off that.
    includeBuild("mc-driver/gradle-plugin") { name = "mc-driver-gradle-plugin" }

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
    // The driver. One mod that puts an RPC node in a game and exposes methods for driving it, a
    // launcher that starts a JVM inside a prepared NeoForge environment, and a smoke run that uses
    // both. Tools only -- none of it knows anything about tests, reports or logging.
    ":mc-driver:driver",
    ":mc-driver:launcher",
    ":mc-driver:junit",
    ":mc-driver:smoke",

    // The RPC framework the harness is built on, and which knows nothing about Minecraft. Kept in
    // this build for the shared catalog and conventions; kept free of the game by never applying
    // ModDevGradle to it, which `subprojects { }` does not do for anyone.
    ":rpc:core",
    ":rpc:transport",
    ":rpc:testkit",
    ":rpc:host",
    ":rpc:compiler-plugin",
    ":rpc:example",

    // Three processes with three different classpaths, which is the only way to test the thing the
    // roles exist for: a node that cannot load half the bodies in a jar it is holding.
    ":rpc:e2e:node",
    ":rpc:e2e:part-a",
    ":rpc:e2e:part-b",
    ":rpc:e2e:layer",
    ":rpc:e2e:driver",

    // The capture stack: FFmpeg cross-built for Windows, the Panama bindings generated from
    // the very headers it was built with, and the object-oriented layer over them. Ordinary
    // subprojects -- the Docker step is a task like any other, with the same up-to-date
    // checks. They build on 25 rather than the 21 the rest of the tree uses, because the FFM
    // API only became final in 22; capture/build.gradle.kts sets that for itself.
    ":capture:libav-gen",
    ":capture:libav",
)
