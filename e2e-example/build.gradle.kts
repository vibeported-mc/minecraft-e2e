plugins {
    id("dev.vibeported.mc.e2e")
}

mcE2E {
    neoForge {
        version = libs.versions.neoforge.get()
    }
    modId = "example"
    orchestratorMain = "dev.vibeported.mc.e2e.tests.MainKt"
}

// A published consumer gets these from Maven. This repo points them at its own projects so the
// example exercises the same code path anyone else would take.
dependencies {
    "e2eTestImplementation"(project(":e2e-core"))
    "e2eTestImplementation"(project(":e2e-dsl"))
    "e2eTestImplementation"(project(":e2e-suite"))
    e2eCompilerPlugin(project(":e2e-compiler-plugin"))
    e2eOrchestrator(project(":e2e-orchestrator"))
}
