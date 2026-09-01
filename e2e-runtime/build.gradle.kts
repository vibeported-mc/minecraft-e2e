plugins {
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":e2e-api"))
    implementation(project(":e2e-mock-world"))
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
    // ValueCodec resolves a serializer from the runtime class of a shared value.
    implementation(libs.kotlin.reflect)

    testImplementation(libs.coroutines.test)
}
