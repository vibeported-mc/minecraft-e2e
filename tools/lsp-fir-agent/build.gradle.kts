plugins {
    java
}

java {
    // 24 is where `java.lang.classfile` became a standard API, and the language server runs on
    // JBR 25. Using it means this agent has no dependencies at all: nothing to shade, nothing to
    // relocate, and no second copy of ASM on the system classloader of somebody's IDE.
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks.jar {
    archiveFileName = "lsp-fir-agent.jar"
    manifest {
        attributes(
            "Premain-Class" to "dev.vibeported.mc.e2e.agent.OnlyBundledPluginsAgent",
            "Agent-Class" to "dev.vibeported.mc.e2e.agent.OnlyBundledPluginsAgent",
            "Can-Retransform-Classes" to "true",
            "Main-Class" to "dev.vibeported.mc.e2e.agent.OnlyBundledPluginsAgent",
        )
    }
}
