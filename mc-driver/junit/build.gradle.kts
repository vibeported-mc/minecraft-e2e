import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// JUnit 5 for the driver, kept in a module of its own.
//
// The driver's founding rule is that it knows nothing about tests -- no assertions, no reports, no
// runner -- and a dependency on `junit-jupiter-api` over there would end that. So the integration
// lives here, depends on the driver, and nothing depends on it but a test.
//
// No ModDevGradle: this names no Minecraft type, only the driver's own `ClusterScope`.
plugins {
    `java-library`
}

kotlin {
    explicitApi()
}

// The games are Java 25, and this is loaded into the same JVM as the tests that drive them.
java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25
}

dependencies {
    api(project(":mc-driver:driver"))
    api(libs.junit.jupiter.api)
    implementation(libs.coroutines.core)
}
