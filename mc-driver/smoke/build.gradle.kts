import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// The driver, driving a real game.
//
// A mod, though it adds nothing to one: FancyModLoader resolves the class it is told to run through
// the game content loader, and being a mod is how a jar gets in there. The rpc plugin is applied for
// the same reason any consumer applies it -- `server { }` and `client { }` bodies written here are
// lifted into tables, and the tables have to be packaged with a manifest that finds them.
plugins {
    alias(libs.plugins.moddev)
    alias(libs.plugins.kotlin.serialization)
    id("dev.vibeported.rpc")
    id("dev.vibeported.mc.driver")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25
}

// ModDevGradle adds its own repositories here, which makes Gradle prefer project repositories and
// ignore the ones declared in settings.
repositories {
    mavenCentral()
    maven("https://maven.neoforged.net/releases")
    maven("https://thedarkcolour.github.io/KotlinForForge/") {
        content { includeGroup("thedarkcolour") }
    }
}

dependencies {
    rpcCompilerPlugin(project(":rpc:compiler-plugin"))
    implementation(project(":mc-driver:driver"))
    implementation(libs.coroutines.core)
    implementation(libs.kotlinforforge)

    testImplementation(project(":mc-driver:junit"))
}

neoForge {
    version = libs.versions.neoforge.get()

    mods {
        create("mcdriver_smoke") { sourceSet(sourceSets.main.get()) }
    }

    unitTest {
        enable()
        testedMod = mods.getByName("mcdriver_smoke")
    }

    mcDriver {
        captureDir = layout.buildDirectory.dir("smoke")
    }
}
