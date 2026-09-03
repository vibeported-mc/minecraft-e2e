pluginManagement {
    // The Gradle plugin is an included build so this very build can apply it by id, rather than
    // duplicating the Minecraft run wiring for the sake of dogfooding it.
    includeBuild("gradle-plugin")

    // And the RPC one, for the same reason: a plugin cannot be applied by the build that declares
    // it. Unlike its neighbour it names nothing but the Kotlin Gradle plugin, so it cannot repeat
    // the second-ModDevGradle-copy collision that broke IDE import.
    // Renamed, because Gradle takes an included build's path from its directory name and there is
    // already a `gradle-plugin` above.
    includeBuild("rpc/gradle-plugin") { name = "rpc-gradle-plugin" }

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
    ":rpc:transport",
    ":rpc:testkit",
    ":rpc:compiler-plugin",
    ":rpc:example",

    // Three processes with three different classpaths, which is the only way to test the thing the
    // roles exist for: a node that cannot load half the bodies in a jar it is holding.
    ":rpc:e2e:host",
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
    ":capture:example",
)
