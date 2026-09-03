import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.moddev)
    alias(libs.plugins.kotlin.serialization)
    id("dev.vibeported.rpc")
}

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

// The verbs send positions across, so this module makes the same promise `:minecraft` does. The
// serializer itself lives there; what is declared here is only that the compiler should allow it.
rpc {
    contextual.add("net.minecraft.core.BlockPos")
}

dependencies {
    rpcCompilerPlugin(project(":rpc:compiler-plugin"))

    api(project(":minecraft"))
    implementation(libs.kotlinforforge)
    // Screen recording. Its jar carries the FFmpeg DLLs inside it and unpacks them on first use.
    implementation(project(":capture:libav"))
}

// The gameplay verbs are built out of the same `server { }` and `client { }` calls a test uses, so
// this module needs the compiler plugin every bit as much as a suite does -- and dogfooding it here
// is what proves the primitives are good enough to build on. The wiring that used to sit here, for
// the generated index and the plugin classpath, is what `dev.vibeported.rpc` does now.

neoForge {
    version = libs.versions.neoforge.get()

    mods {
        create("e2e_dsl") { sourceSet(sourceSets.main.get()) }
    }
}
