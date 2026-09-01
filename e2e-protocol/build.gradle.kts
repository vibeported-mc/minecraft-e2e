plugins {
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    explicitApi()
}

// Deliberately free of Minecraft. The orchestrator is a plain JVM with no game on its classpath, so
// anything it and a game process both need has to live here.
dependencies {
    api(libs.coroutines.core)
    api(libs.serialization.json)
    // ValueCodec resolves a serializer from the runtime class of a shared value.
    implementation(libs.kotlin.reflect)

    testImplementation(libs.coroutines.test)
}
