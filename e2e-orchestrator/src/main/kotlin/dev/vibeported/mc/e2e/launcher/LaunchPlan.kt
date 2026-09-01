package dev.vibeported.mc.e2e.launcher

import kotlinx.serialization.Serializable

/**
 * How to start one game process.
 *
 * Written by the Gradle plugin, which harvests it from ModDevGradle's own run task rather than
 * trying to reconstruct a Minecraft command line by hand.
 */
@Serializable
public data class LaunchSpec(
    public val name: String,
    public val javaExecutable: String,
    public val jvmArgs: List<String> = emptyList(),
    public val mainClass: String,
    public val programArgs: List<String> = emptyList(),
    public val classpath: List<String> = emptyList(),
    public val workingDir: String,
    public val environment: Map<String, String> = emptyMap(),
)

/** Everything the orchestrator needs to run a suite, handed to it as one file. */
@Serializable
public data class LaunchPlan(
    public val server: LaunchSpec,
    public val clients: List<LaunchSpec> = emptyList(),
    /** Paths to the `index.json` files the compiler plugin wrote for the test mods. */
    public val indexFiles: List<String> = emptyList(),
    public val reportDir: String,
    /** 0 asks the operating system for a free port, which is what avoids clashing runs. */
    public val port: Int = 0,
    /** The address the client is told to join once it is up. */
    public val serverAddress: String = "localhost:25565",
    public val startupTimeoutSeconds: Long = 900,
    public val testTimeoutSeconds: Long = 300,
)
