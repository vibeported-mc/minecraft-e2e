plugins {
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    explicitApi()
}

dependencies {
    api(project(":rpc:core"))

    testImplementation(libs.coroutines.test)
}
