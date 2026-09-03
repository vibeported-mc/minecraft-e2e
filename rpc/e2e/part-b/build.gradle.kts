// Stands in for the half of a game that only some nodes have -- the client classes a dedicated
// server is stripped of. Nothing about it is special; what matters is that one node's classpath has
// it and another's does not. That now goes for its serializer as much as for its classes.
plugins {
    alias(libs.plugins.kotlin.serialization)
    id("dev.vibeported.rpc")
}

dependencies {
    rpcCompilerPlugin(project(":rpc:compiler-plugin"))

    implementation(project(":rpc:e2e:part-a"))
}
