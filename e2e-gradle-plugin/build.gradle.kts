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
    // its runtime classpath is exported onto the consuming script's plugin classpath -- a second copy
    // of ModDevGradle beside the one the consuming build applies itself. MDG applies gradle-idea-ext
    // to the *root* project, and Gradle's already-applied check is per Class object, so the second
    // copy does not recognise the first one's work and collides on the `settings` extension. The
    // failure names neither plugin and only bites during an IDE import. The consuming build applies
    // MDG itself, so these types are on the classpath at runtime regardless.
    compileOnly("net.neoforged:moddev-gradle:2.0.144")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10")
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
