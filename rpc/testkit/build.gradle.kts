kotlin {
    explicitApi()
}

dependencies {
    api(project(":rpc:transport"))
    api(libs.coroutines.core)

    testImplementation(libs.coroutines.test)
}

// Explicit API is for what this module publishes, not for its tests. Gradle already scopes it that
// way, but the IDE applies the module-level setting to every source set and paints test files red
// over a rule the build does not enforce. Saying it outright is what the imported model carries.
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileTestKotlin") {
    compilerOptions.freeCompilerArgs.add("-Xexplicit-api=disable")
}
