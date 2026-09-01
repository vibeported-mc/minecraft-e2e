plugins {
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":e2e-api"))
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
}
