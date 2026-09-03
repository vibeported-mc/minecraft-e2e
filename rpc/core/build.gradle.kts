plugins {
    alias(libs.plugins.kotlin.serialization)
}

// The public surface of a framework other people build on, so every visibility is written down
// rather than inferred.
kotlin {
    explicitApi()
}

// Deliberately short, and deliberately without ModDevGradle. Nothing here may name a Minecraft type,
// and the surest way to keep that true is to give it no way to resolve one.
dependencies {
    api(libs.coroutines.core)
    api(libs.serialization.json)
    api(libs.serialization.cbor)

    testImplementation(libs.coroutines.test)
}

// Explicit API is for what this module publishes, not for its tests. Gradle already scopes it that
// way, but the IDE applies the module-level setting to every source set and paints test files red
// over a rule the build does not enforce. Saying it outright is what the imported model carries.
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileTestKotlin") {
    compilerOptions.freeCompilerArgs.add("-Xexplicit-api=disable")
}
