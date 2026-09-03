// Stands in for the half of a game that only some nodes have -- the client classes a dedicated
// server is stripped of. Nothing about it is special; what matters is that one node's classpath has
// it and another's does not.
dependencies {
    implementation(project(":rpc:e2e:part-a"))
}
