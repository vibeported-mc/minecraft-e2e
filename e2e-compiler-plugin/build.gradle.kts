import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.moddev)
}

// The plugin itself has nothing to do with Minecraft. Its tests do: they compile snippets that call
// the real DSL, whose block receivers are Minecraft types, so the game has to be on the test
// classpath for those snippets to resolve at all.
java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

repositories {
    mavenCentral()
    maven("https://maven.neoforged.net/releases")
    // e2e-core brings Kotlin for Forge with it, and ModDevGradle makes this project prefer its own
    // repositories over the ones in settings.
    maven("https://thedarkcolour.github.io/KotlinForForge/") {
        content { includeGroup("thedarkcolour") }
    }
}

neoForge {
    version = libs.versions.neoforge.get()
    addModdingDependenciesTo(sourceSets.test.get())
}

dependencies {
    // The compiler supplies these at plugin load time; they must not be bundled or leak downstream.
    compileOnly(libs.kotlin.compiler.embeddable)

    testImplementation(libs.kotlin.compiler.embeddable)
    testImplementation(libs.kctfork.core)
    // The snippets these tests compile call the real DSL, which is Minecraft-typed, so this source
    // set needs the game on its classpath. That is what `addModdingDependenciesTo` below is for.
    testImplementation(project(":e2e-core"))
    testImplementation(project(":e2e-core"))
    testImplementation(project(":e2e-orchestrator"))
    testImplementation(libs.coroutines.test)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25
        optIn.add("org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi")
        optIn.add("org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI")
        optIn.add("org.jetbrains.kotlin.fir.symbols.SymbolInternals")
        // FIR checkers declare `check` with context parameters.
        freeCompilerArgs.add("-Xcontext-parameters")
    }
}

tasks.withType<Test>().configureEach {
    // kctfork drives the embeddable compiler in-process; it needs at the internals JDK 17+ seals off.
    jvmArgs(
        "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
        "--add-opens=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",
        "--add-opens=jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED",
        "--add-opens=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED",
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
    )
}
