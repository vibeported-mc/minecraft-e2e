// Stands in for what every node has: the common half of a game, present on client and server alike.
//
// It applies the RPC plugin only to declare a serializer for one of its own types. A module that
// hosts no procedure at all still publishes a manifest, and that is what lets the layer send an
// `Ident` with nothing in its own build script saying so.
plugins {
    alias(libs.plugins.kotlin.serialization)
    id("dev.vibeported.rpc")
}

dependencies {
    rpcCompilerPlugin(project(":rpc:compiler-plugin"))

    api(project(":rpc:core"))
}
