plugins {
    alias(libs.plugins.kotlin.serialization)
    id("dev.vibeported.rpc")
}

// The whole point of this module is one jar, loaded by every node, holding bodies that not every
// node can run. Both halves are `compileOnly` because that is the truth being modelled: a mod jar
// is written against the game and bundles none of it, and each node supplies the half it has.
dependencies {
    rpcCompilerPlugin(project(":rpc:compiler-plugin"))

    api(project(":rpc:core"))
    compileOnly(project(":rpc:e2e:part-a"))
    compileOnly(project(":rpc:e2e:part-b"))
}
