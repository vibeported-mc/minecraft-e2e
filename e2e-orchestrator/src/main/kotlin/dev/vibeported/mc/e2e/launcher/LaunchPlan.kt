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
    /** Wall clock for one whole test. */
    public val testTimeoutSeconds: Long = 300,
    /** How long one block invocation may take before the orchestrator stops waiting for it. */
    public val callTimeoutSeconds: Long = 120,
    /** How long a teleport or a turn may take to show up on the client that was asked. */
    public val actionTimeoutSeconds: Long = 10,
    /** Client window size. Minecraft's own default of 854x480 is too small to watch. */
    public val clientWidth: Int = 1280,
    public val clientHeight: Int = 720,
    /**
     * Whether each client moves its window so two of them do not land where the last one did.
     *
     * Off by default: a person watching a run wants to arrange the windows themselves, and a layout
     * that guesses wrong is worse than no layout at all.
     */
    public val tileWindows: Boolean = false,
)
