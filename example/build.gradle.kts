plugins {
    // Applied here rather than by the e2e plugin, so that exactly one copy of ModDevGradle is
    // loaded for the whole build. See the comment in E2eGradlePlugin for what two copies do to an
    // IDE import.
    alias(libs.plugins.moddev)
    id("dev.vibeported.mc.e2e")
}

mcE2E {
    neoForge {
        version = libs.versions.neoforge.get()
    }
    modId = "example"
    orchestratorMain = "dev.vibeported.mc.e2e.tests.MainKt"

    // Started before the run rather than on demand. A name nobody lists still works -- the first
    // call addressed to it launches a client and waits -- but that costs a whole game boot in the
    // middle of a test, so the ones a suite is built around are named here.
    clients = listOf("steve", "alex")

    // Names every block the loaded mods register, so a fixture is written in Kotlin the compiler
    // checks rather than in a string nobody checks. Costs one game boot when the mod set changes.
    blockDsl {
        enable()
    }
}

// A published consumer gets these from Maven. This repo points them at its own projects so the
// example exercises the same code path anyone else would take.
dependencies {
    "e2eTestImplementation"(project(":minecraft"))
    "e2eTestImplementation"(project(":dsl"))
    "e2eTestImplementation"(project(":suite"))
    e2eCompilerPlugin(project(":rpc:compiler-plugin"))
    e2eOrchestrator(project(":orchestrator"))
    e2eCodegen(project(":codegen"))
}
