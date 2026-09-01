plugins {
    `java-gradle-plugin`
}

// The compiler-plugin coordinates the Gradle plugin resolves at runtime come from this build,
// so a version bump cannot leave the published plugin pointing at a stale artifact.
val coordinatesRoot = layout.buildDirectory.dir("generated/coordinates")

val pluginCoordinates by tasks.registering(WriteProperties::class) {
    // Sits beside E2eGradlePlugin so it can be read with a package-relative getResourceAsStream.
    destinationFile = coordinatesRoot.map { it.file("dev/vibeported/mc/e2e/gradle/coordinates.properties") }
    property("group", providers.provider { project.group.toString() })
    property("version", providers.provider { project.version.toString() })
}

sourceSets.main {
    resources.srcDir(pluginCoordinates.map { coordinatesRoot })
}

dependencies {
    compileOnly(libs.kotlin.gradle.plugin.api)
    implementation(libs.kotlin.gradle.plugin)
}

gradlePlugin {
    plugins {
        create("e2e") {
            id = "dev.vibeported.mc.e2e"
            implementationClass = "dev.vibeported.mc.e2e.gradle.E2eGradlePlugin"
            displayName = "Minecraft E2E"
            description = "Lifts e2e server/client blocks into a stable dispatch table and rewrites shared state into RPC."
        }
    }
}
