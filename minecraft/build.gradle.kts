import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.moddev)
    alias(libs.plugins.kotlin.serialization)
    id("dev.vibeported.rpc")
}

// Minecraft 26.2 resolves against a Java 25 runtime -- FancyModLoader 11 refuses to hand its
// artifacts to anything older -- so this module overrides the toolchain the rest of the build uses.
java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25
}

// ModDevGradle adds its own repositories to this project, which makes Gradle prefer project
// repositories here and ignore the ones declared in settings.
repositories {
    mavenCentral()
    maven("https://maven.neoforged.net/releases")
    maven("https://thedarkcolour.github.io/KotlinForForge/") {
        content { includeGroup("thedarkcolour") }
    }
}

// The types a body may send that this build supplies the serializer for. A `BlockPos` is not
// `@Serializable` and never will be, but it has a Mojang codec -- so the compiler is told the name
// and `MinecraftSerializers` provides the serializer to match. Anything not named here still has to
// be `@Serializable`, checked at compile time.
rpc {
    contextual.add("net.minecraft.core.BlockPos")
}

dependencies {
    rpcCompilerPlugin(project(":rpc:compiler-plugin"))

    api(project(":rpc:core"))
    api(project(":rpc:host"))
    api(libs.coroutines.core)

    // Kotlin as a loaded mod. A dev run would have the stdlib on its classpath anyway, but a mod jar
    // shipped to anyone else would not, and this is the version pairing that has to hold either way.
    implementation(libs.kotlinforforge)
}

neoForge {
    version = libs.versions.neoforge.get()

    mods {
        create("e2e") { sourceSet(sourceSets.main.get()) }
    }
}
