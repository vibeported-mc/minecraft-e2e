// Deliberately standalone: this build has no relationship to the minecraft-e2e
// build above it, and none to Minecraft. Its native half -- the FFmpeg DLLs and
// the Panama bindings -- is produced by the root buildNatives task out of
// docker/, so a plain `gradlew build` is all there is to run.
rootProject.name = "e2e-capture"

dependencyResolutionManagement {
    repositories { mavenCentral() }
}

include(":libav-gen", ":libav", ":example")
