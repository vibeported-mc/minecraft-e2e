kotlin {
    explicitApi()
}

dependencies {
    api(project(":rpc:transport"))
    api(libs.coroutines.core)

    testImplementation(libs.coroutines.test)
}
