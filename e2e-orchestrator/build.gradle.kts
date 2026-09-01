plugins {
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    explicitApi()
}

dependencies {
    api(project(":e2e-protocol"))
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)

    testImplementation(libs.coroutines.test)
}
