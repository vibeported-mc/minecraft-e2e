plugins {
    java
}

// Coordinates so the parent build can pull this in as an included build and have
// Gradle substitute the project for the module. Still buildable on its own.
allprojects {
    group = "dev.vibeported.capture"
    version = "0.1.0"
}

// The FFM API is final in 22+, so nothing here needs --enable-preview.
subprojects {
    apply(plugin = "java")

    extensions.configure<JavaPluginExtension> {
        toolchain { languageVersion = JavaLanguageVersion.of(25) }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
    }
}

// ---------------------------------------------------------------------------
// The native half of this project: FFmpeg cross-compiled for Windows, and the
// Panama bindings jextract generates from the very headers it was built with.
// Both come out of docker/Dockerfile, and both land under build/.
// ---------------------------------------------------------------------------

val ffmpegRef = providers.gradleProperty("libav.ffmpegRef").orElse("n9.0.1")
val withPrograms = providers.gradleProperty("libav.withPrograms").orElse("false")

val nativesDir: Provider<Directory> = layout.buildDirectory.dir("natives")
val generatedDir: Provider<Directory> = layout.buildDirectory.dir("generated")

/**
 * Runs the container build and drops its output into build/natives and
 * build/generated.
 *
 * Declaring docker/ as the input and those two directories as the outputs is
 * what keeps this off the critical path: Gradle skips the task entirely unless
 * a Dockerfile, a build script or a pinned ref actually changed. A normal
 * `gradlew build` after the first one never invokes docker at all.
 */
// Resolved here rather than inside the task: a task action that reaches back into the
// build script cannot be stored in the configuration cache.
val outputRoot = layout.buildDirectory.get().asFile.absolutePath
val dockerContext = layout.projectDirectory.dir("docker").asFile.absolutePath
val dockerOnPath = System.getenv("PATH").orEmpty().split(File.pathSeparator).any { dir ->
    File(dir, if (System.getProperty("os.name").startsWith("Windows")) "docker.exe" else "docker").canExecute()
}

val buildNatives by tasks.registering(Exec::class) {
    group = "build"
    description = "Cross-compiles FFmpeg for Windows and generates the Panama bindings"

    inputs.dir(layout.projectDirectory.dir("docker")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("ffmpegRef", ffmpegRef)
    inputs.property("withPrograms", withPrograms)
    outputs.dir(nativesDir)
    outputs.dir(generatedDir)

    commandLine(
        "docker", "buildx", "build", dockerContext,
        "--platform", "linux/amd64",
        "--build-arg", "FFMPEG_REF=${ffmpegRef.get()}",
        "--build-arg", "WITH_PROGRAMS=${if (withPrograms.get().toBoolean()) "1" else "0"}",
        "--target", "artifacts",
        "--output", "type=local,dest=$outputRoot",
    )
}


// A task action that reaches back into the build script cannot be stored in the
// configuration cache, so the prerequisite is checked here instead. Only when the
// output is not already there: with natives in place the task never runs, and a
// machine without docker can still compile against what a previous build produced.
if (!dockerOnPath && !file(outputRoot).resolve("natives").isDirectory) {
    throw GradleException(
        "docker was not found on PATH, and the FFmpeg DLLs and Panama bindings have to be " +
            "built before anything here compiles. Install Docker Desktop, or build " +
            "docker/Dockerfile elsewhere and unpack its output into $outputRoot."
    )
}

// Where the two halves meet. The subprojects read these rather than reaching
// into build/ by hand.
extra["nativesDir"] = nativesDir
extra["generatedDir"] = generatedDir
extra["buildNatives"] = buildNatives
