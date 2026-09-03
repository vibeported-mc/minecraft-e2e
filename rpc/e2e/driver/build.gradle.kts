import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.tasks.Classpath
import org.gradle.process.CommandLineArgumentProvider

plugins {
    alias(libs.plugins.kotlin.serialization)
    id("dev.vibeported.rpc")
}

// Two classpaths, and the difference between them is the entire experiment. Both nodes get the same
// layer jar, holding bodies for both roles; only one gets the jar those `B` bodies need.
//
// Asking for JAR library elements rather than taking the default is what makes this faithful: a
// project dependency otherwise resolves to a directory of class files, and the manifest this all
// turns on is a resource read with `getResources` from inside a jar in every real deployment.
fun nodeClasspath(name: String): Configuration = configurations.create(name) {
    isCanBeResolved = true
    isCanBeConsumed = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
        attribute(
            LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
            objects.named(LibraryElements::class.java, LibraryElements.JAR),
        )
    }
}

val nodeA: Configuration = nodeClasspath("nodeA")
val nodeB: Configuration = nodeClasspath("nodeB")

dependencies {
    rpcCompilerPlugin(project(":rpc:compiler-plugin"))

    // The driver itself. It names the layer so it can write calls, and neither half of the game --
    // which is the point: after the plugin has lifted the bodies out, the layer's own class no
    // longer references `Alpha` or `Beta` at all, so a process can dispatch procedures it could
    // never run.
    testImplementation(project(":rpc:e2e:layer"))
    testImplementation(project(":rpc:e2e:host"))
    testImplementation(libs.coroutines.test)

    nodeA(project(":rpc:e2e:host"))
    nodeA(project(":rpc:e2e:layer"))
    nodeA(project(":rpc:e2e:part-a"))

    nodeB(project(":rpc:e2e:host"))
    nodeB(project(":rpc:e2e:layer"))
    nodeB(project(":rpc:e2e:part-a"))
    nodeB(project(":rpc:e2e:part-b"))
}

// Handed over as JVM arguments rather than read from the project at execution time, so the
// configuration cache can serialize them and so the test task reruns when either classpath changes.
class Classpaths(
    @get:Classpath val a: FileCollection,
    @get:Classpath val b: FileCollection,
) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> = listOf(
        "-Drpc.e2e.classpath.a=" + a.joinToString(File.pathSeparator) { it.absolutePath },
        "-Drpc.e2e.classpath.b=" + b.joinToString(File.pathSeparator) { it.absolutePath },
    )
}

tasks.test {
    jvmArgumentProviders.add(Classpaths(nodeA, nodeB))
}

// Writes the two node classpaths out, so the scenario can be reproduced by hand. A three-process
// test that only fails inside Gradle is a test nobody can debug.
val writeNodeClasspaths by tasks.registering {
    // The file collections, not the configurations. A Configuration captured by a task action is
    // something the configuration cache cannot write, and it says so naming the type, not the line.
    val a: FileCollection = nodeA
    val b: FileCollection = nodeB
    val out = layout.buildDirectory.dir("rpc-e2e")
    outputs.dir(out)
    doLast {
        out.get().asFile.mkdirs()
        out.get().file("classpath-a.txt").asFile
            .writeText(a.joinToString(File.pathSeparator) { it.absolutePath })
        out.get().file("classpath-b.txt").asFile
            .writeText(b.joinToString(File.pathSeparator) { it.absolutePath })
        println("wrote " + out.get().asFile)
    }
}
