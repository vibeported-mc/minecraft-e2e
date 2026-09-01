import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

dependencies {
    // The compiler supplies these at plugin load time; they must not be bundled or leak downstream.
    compileOnly(libs.kotlin.compiler.embeddable)

    testImplementation(libs.kotlin.compiler.embeddable)
    testImplementation(libs.kctfork.core)
    testImplementation(project(":e2e-api"))
    testImplementation(project(":e2e-runtime"))
    testImplementation(project(":e2e-mock-world"))
    testImplementation(libs.coroutines.test)
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
    // kctfork drives the embeddable compiler in-process; it needs at the internals JDK 17+ seals off.
    jvmArgs(
        "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
        "--add-opens=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",
        "--add-opens=jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED",
        "--add-opens=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED",
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
    )
    // The plugin tests compile sample sources against these, so they need the paths at runtime.
    systemProperty("e2e.api.classpath", project(":e2e-api").name)
}
