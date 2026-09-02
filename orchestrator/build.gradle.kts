import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.moddev)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    explicitApi()
}

// The orchestrator starts inside FancyModLoader now, so it runs on the same Java the game needs.
java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25
}

repositories {
    mavenCentral()
    maven("https://maven.neoforged.net/releases")
    maven("https://thedarkcolour.github.io/KotlinForForge/") {
        content { includeGroup("thedarkcolour") }
    }
}

dependencies {
    api(project(":core"))
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)

    testImplementation(libs.coroutines.test)
}

// A mod, though it has no @Mod class and adds nothing to a game. FancyModLoader resolves the class
// it is told to run through the game content loader, so the bootstrap has to be a thing that loader
// knows about -- and being a mod is how a jar gets in there.
neoForge {
    version = libs.versions.neoforge.get()

    mods {
        create("e2e_orchestrator") { sourceSet(sourceSets.main.get()) }
    }
}
