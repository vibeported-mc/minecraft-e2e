// Nothing but the jextract output. It lives in its own module so Gradle compiles
// the several hundred generated classes once and caches them, instead of
// recompiling them every time the hand-written layer next door changes.
//
// The sources are produced by the root project's buildNatives task into
// build/generated. Never edit them; the next container build overwrites the lot.

@Suppress("UNCHECKED_CAST")
val generatedDir = rootProject.extra["generatedDir"] as Provider<Directory>

sourceSets.main { java.srcDir(generatedDir) }

tasks.compileJava { dependsOn(rootProject.tasks.named("buildNatives")) }

tasks.withType<JavaCompile>().configureEach {
    // Generated code; its warnings are not ours to fix and would bury real ones.
    options.compilerArgs.addAll(listOf("-nowarn", "-Xlint:none"))
}
