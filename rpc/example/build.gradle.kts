// The whole chain, applied the way anyone else would apply it: the Gradle plugin by id from the
// included build, and the compiler plugin as a dependency it resolves. Nothing here reaches inside
// either one, which is the point -- if this module compiles and its tests pass, the wiring works
// for a build that has never seen this repository.
plugins {
    // Required by the one above, which says so with a message rather than applying it: a procedure's
    // arguments cross a wire, and kotlinx.serialization is what carries them.
    alias(libs.plugins.kotlin.serialization)
    id("dev.vibeported.rpc")
}

dependencies {
    rpcCompilerPlugin(project(":rpc:compiler-plugin"))

    testImplementation(project(":rpc:core"))
    testImplementation(project(":rpc:testkit"))
    testImplementation(libs.coroutines.test)
}
