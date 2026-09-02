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

/**
 * Everything the orchestrator needs to bring a cluster up, handed to it as one file.
 *
 * Nothing about tests. The orchestrator starts the processes, wires the transport and calls
 * [mainClass]; what that main then does with the transport is none of its business.
 */
@Serializable
public data class LaunchPlan(
    public val server: LaunchSpec,
    public val clients: List<LaunchSpec> = emptyList(),
    /** The class whose `main` is run once the cluster is up. */
    public val mainClass: String = "",
    /**
     * Clients to start before that main runs, on top of every name the compiler collected.
     *
     * A name nobody could work out ahead of time is not listed here and does not need to be: the
     * first call addressed to it starts it.
     */
    public val clientNames: List<String> = emptyList(),
    public val reportDir: String,
    /** 0 asks the operating system for a free port, which is what avoids clashing runs. */
    public val port: Int = 0,
    /** The address the client is told to join once it is up. */
    public val serverAddress: String = "localhost:25565",
    public val startupTimeoutSeconds: Long = 900,
    /** How long one procedure call may take before the transport stops waiting for it. */
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
