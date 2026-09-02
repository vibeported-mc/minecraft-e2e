import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.moddev)
}

kotlin {
    explicitApi()
}

// Starts inside FancyModLoader, like the orchestrator, so it runs on the same Java the game needs.
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

// Deliberately depends on nothing of ours. It reads the block registry and writes text; it neither
// speaks the transport nor knows what a procedure is, and a dependency on e2e-core would only invite
// it to grow one.
dependencies {
    testImplementation(libs.coroutines.test)
}

// A mod, though it has no @Mod class and adds nothing to a game -- same reason the orchestrator is
// one. FancyModLoader resolves the class it is told to run through the game content loader, and
// being a mod is how a jar gets in there.
neoForge {
    version = libs.versions.neoforge.get()

    mods {
        create("e2e_codegen") { sourceSet(sourceSets.main.get()) }
    }
}
