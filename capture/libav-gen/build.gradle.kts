// Nothing but the jextract output. It lives in its own module so Gradle compiles
// the several hundred generated classes once and caches them, instead of
// recompiling them every time the hand-written layer next door changes.
//
// The sources are produced by the root project's buildNatives task into
// build/generated. Never edit them; the next container build overwrites the lot.

@Suppress("UNCHECKED_CAST")
val generatedDir = project(":capture").extra["generatedDir"] as Provider<Directory>

sourceSets.main { java.srcDir(generatedDir) }

// Every compile task, not just Java: the root build applies Kotlin to all subprojects, so a
// compileKotlin exists here too and sees the same generated directory. A task that reads an
// output without declaring it is a build that works until it is run in a different order.
tasks.matching { it.name.startsWith("compile") }.configureEach {
    dependsOn(project(":capture").tasks.named("buildNatives"))
}

tasks.withType<JavaCompile>().configureEach {
    // Generated code; its warnings are not ours to fix and would bury real ones.
    options.compilerArgs.addAll(listOf("-nowarn", "-Xlint:none"))
}
