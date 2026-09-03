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

    testImplementation(libs.coroutines.test)
}
