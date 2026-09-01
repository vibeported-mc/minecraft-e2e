pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "minecraft-e2e"

include(
    ":e2e-api",
    ":e2e-runtime",
    ":e2e-mock-world",
    ":e2e-compiler-plugin",
    ":e2e-gradle-plugin",
    ":e2e-samples",
)
