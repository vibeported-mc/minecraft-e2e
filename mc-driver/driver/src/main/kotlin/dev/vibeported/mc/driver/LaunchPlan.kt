package dev.vibeported.mc.driver

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * How to start one game process.
 *
 * Written by the driver's Gradle plugin, which harvests it from ModDevGradle's own run task rather
 * than reconstructing a Minecraft command line by hand. That reconstruction is not a thing anybody
 * should attempt: the classpath alone runs to hundreds of entries chosen by a dozen artifact
 * transforms, and it is only assembled inside the run task's `exec()`.
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
 * Everything needed to bring a cluster of games up, handed over as one file.
 *
 * Nothing about tests, and nothing about what to do with the games once they are running -- it says
 * how to start them and no more. @see cluster
 */
@Serializable
public data class LaunchPlan(
    public val server: LaunchSpec,
    /**
     * The template every client is started from.
     *
     * One harvested command is enough: a client is that same command with its own username and game
     * directory, and a name nobody knew in advance is started the moment somebody asks for it.
     */
    public val client: LaunchSpec? = null,
    /** Where a client is told to connect. The server never learns about the client any other way. */
    public val serverAddress: String = "localhost:25565",
    /** Minecraft's own default of 854x480 is too small to watch. */
    public val clientWidth: Int = 1280,
    public val clientHeight: Int = 720,
    /**
     * Whether each client moves its window so two of them do not land where the last one did.
     *
     * Off by default: somebody watching a run usually wants to arrange the windows themselves, and a
     * layout that guesses wrong is worse than no layout at all.
     */
    public val tileWindows: Boolean = false,
) {
    public companion object {
        /** `mcdriver.launch.plan` -- where the plan file is. Set by the Gradle plugin. */
        public const val PROPERTY: String = "mcdriver.launch.plan"

        private val json = Json { ignoreUnknownKeys = true }

        public fun parse(text: String): LaunchPlan = json.decodeFromString(serializer(), text)

        /** The plan this process was pointed at, or null when nobody pointed it at one. */
        public fun readOrNull(): LaunchPlan? =
            System.getProperty(PROPERTY)?.takeIf { it.isNotBlank() }?.let { parse(File(it).readText()) }

        public fun read(): LaunchPlan = readOrNull() ?: error(
            "mcdriver: -D$PROPERTY was not set, so there is nothing to start. The driver's Gradle " +
                "plugin writes the plan and points a run at it."
        )
    }
}
