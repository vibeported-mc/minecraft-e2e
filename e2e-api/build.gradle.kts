plugins {
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    explicitApi()
}

dependencies {
    api(libs.coroutines.core)
    api(libs.serialization.json)
}
