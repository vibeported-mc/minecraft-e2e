import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

kotlin {
    explicitApi()
}

dependencies {
    // The compiler supplies this at plugin load time; it must not be bundled or leak downstream.
    compileOnly(libs.kotlin.compiler.embeddable)

    testImplementation(libs.kotlin.compiler.embeddable)
    testImplementation(libs.kctfork.core)
    // The snippets under test call the real entry points, so the module declaring them has to be
    // resolvable. No Minecraft anywhere, unlike the harness's own plugin.
    testImplementation(project(":rpc:core"))
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        optIn.add("org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi")
        optIn.add("org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI")
        optIn.add("org.jetbrains.kotlin.fir.symbols.SymbolInternals")
        // FIR checkers declare `check` with context parameters.
        freeCompilerArgs.add("-Xcontext-parameters")
    }
}

tasks.withType<Test>().configureEach {
    // kctfork drives the embeddable compiler in-process, and needs the internals JDK 17+ seals off.
    jvmArgs(
        "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
        "--add-opens=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",
        "--add-opens=jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED",
        "--add-opens=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED",
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
    )
}
