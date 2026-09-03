kotlin {
    explicitApi()
}

dependencies {
    api(project(":rpc:transport"))
    api(libs.coroutines.core)
}
