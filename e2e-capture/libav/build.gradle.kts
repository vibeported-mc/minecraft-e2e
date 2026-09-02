// The hand-written object-oriented layer, plus the native bundle it loads.

import java.security.MessageDigest

plugins {
    `java-library`
}

dependencies {
    // Consumers see the generated bindings too: everything not wrapped here is
    // still reachable, which is the point of generating the full surface.
    api(project(":libav-gen"))
}

@Suppress("UNCHECKED_CAST")
val nativesDir = rootProject.extra["nativesDir"] as Provider<Directory>
val buildNatives = rootProject.tasks.named("buildNatives")

val nativesPath = "dev/vibeported/capture/libav/natives/windows-x64"
val staged = layout.buildDirectory.dir("resources-natives")

// The DLLs ride inside the jar as resources, the way LWJGL and sqlite-jdbc do
// it, so a consumer needs the jar and nothing else. NativeBootstrap unpacks them
// on first use; see it for why load order matters.
val stageNatives by tasks.registering(Sync::class) {
    dependsOn(buildNatives)
    from(nativesDir) {
        // Only the libraries. ffmpeg.exe and ffprobe.exe show up here when
        // -Plibav.withPrograms=true, and have no business in the jar.
        include("*.dll")
    }
    into(staged.map { it.dir(nativesPath) })
}

// An index of what shipped. Its digest keys the extraction cache, so two
// different FFmpeg builds can never collide in the same directory.
val nativesIndex by tasks.registering {
    dependsOn(stageNatives)
    // Locals of this block, not of the script. A lambda that reads a script-level val
    // captures the script object itself, which the configuration cache cannot store.
    val indexDir = staged.map { it.dir(nativesPath) }
    val sourceDescription = nativesDir.map { it.asFile.absolutePath }
    outputs.file(indexDir.map { it.file("natives.index") })
    doLast {
        val folder = indexDir.get().asFile
        val setDigest = MessageDigest.getInstance("SHA-256")
        val lines = (folder.listFiles { f: java.io.File -> f.name.endsWith(".dll") } ?: emptyArray())
            .sortedBy { it.name }
            .map { file ->
                val hex = MessageDigest.getInstance("SHA-256")
                    .digest(file.readBytes())
                    .joinToString("") { byte: Byte -> "%02x".format(byte) }
                setDigest.update(hex.toByteArray())
                "${file.name} $hex"
            }
        require(lines.isNotEmpty()) { "No DLLs staged from ${sourceDescription.get()}" }
        val setId = setDigest.digest().joinToString("") { byte: Byte -> "%02x".format(byte) }.take(16)
        indexDir.get().file("natives.index").asFile.writeText(
            (listOf("# sha256 of each native, then the id of the set as a whole") +
                    lines + listOf("set $setId")).joinToString("\n", postfix = "\n")
        )
    }
}

sourceSets.main {
    resources.srcDir(staged)
}

tasks.processResources {
    dependsOn(nativesIndex)
    // LGPL: the licence texts and the manifest of what was built travel with
    // the binaries they describe.
    from(nativesDir.map { it.file("BUILD-MANIFEST.txt") }) { into("dev/vibeported/capture/libav") }
    from(nativesDir.map { it.dir("licenses") }) { into("dev/vibeported/capture/libav/licenses") }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
